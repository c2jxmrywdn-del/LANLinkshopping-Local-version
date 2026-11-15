package com.lanlink.shopping.module.marketingimage;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.marketingimage.MarketingImageTaskRepository.TaskRow;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 营销图任务业务层：提交校验、预算闸、任务查询。
 * 真正的上游外呼与落库在 {@link MarketingImageGenerator}（@Async）中执行。
 */
@Service
public class MarketingImageService {

    /** 只开放两个尺寸：1024x1024 适配商品主图，1280x720 适配横版海报 */
    private static final List<String> ALLOWED_SIZES = List.of("1024x1024", "1280x720");

    /**
     * 最小敏感词黑名单（简单包含匹配）。
     * 不追求穷尽，只挡住最明显的涉政/色情/暴力/他人品牌名，降低公开图像生成被滥用的风险。
     */
    private static final List<String> SENSITIVE_WORDS = List.of(
            // 涉政
            "领导人", "主席", "总统", "游行", "示威", "颠覆国家", "分裂国家", "台独", "藏独", "疆独",
            // 色情
            "色情", "裸体", "裸照", "性行为", "做爱", "情色", "成人视频", "porn", "nude", "nsfw",
            // 暴力
            "暴力", "血腥", "恐怖主义", "恐怖袭击", "枪杀", "杀人", "炸弹", "斩首",
            // 他人品牌名
            "lv", "路易威登", "gucci", "古驰", "nike", "耐克", "adidas", "阿迪达斯",
            "chanel", "香奈儿", "hermes", "爱马仕", "coca-cola", "可口可乐", "disney", "迪士尼");

    private static final DateTimeFormatter TASK_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final MarketingImageTaskRepository repository;
    private final MarketingImageGenerator generator;
    private final MarketingImageProperties props;

    public MarketingImageService(MarketingImageTaskRepository repository,
                                 MarketingImageGenerator generator,
                                 MarketingImageProperties props) {
        this.repository = repository;
        this.generator = generator;
        this.props = props;
    }

    /** 提交任务：校验通过后立即返回 PENDING，生成在后台线程池执行。 */
    public Map<String, Object> submit(String ownerType, Long ownerId, String prompt,
                                      String negativePrompt, String size, Boolean pro) {
        return submit(ownerType, ownerId, prompt, negativePrompt, size, pro, null);
    }

    /**
     * @param mode 可选模型：turbo / pro / mt；为空时回退旧的 pro 布尔口径
     */
    public Map<String, Object> submit(String ownerType, Long ownerId, String prompt,
                                      String negativePrompt, String size, Boolean pro, String mode) {
        if (!props.isEnabled()) {
            throw new BusinessException(503, "营销图生成未开启");
        }
        if (ownerId == null) {
            throw new BusinessException(401, "请先登录");
        }
        String cleanPrompt = prompt == null ? "" : prompt.trim();
        if (cleanPrompt.isEmpty() || cleanPrompt.length() > 800) {
            throw new BusinessException(400, "提示词长度需在 1-800 字之间");
        }
        String cleanNegative = negativePrompt == null ? "" : negativePrompt.trim();
        if (cleanNegative.length() > 500) {
            throw new BusinessException(400, "反向提示词不能超过 500 字");
        }
        String cleanSize = size == null || size.isBlank() ? "1024x1024" : size.trim();
        if (!ALLOWED_SIZES.contains(cleanSize)) {
            throw new BusinessException(400, "不支持的图片尺寸");
        }
        if (containsSensitive(cleanPrompt) || containsSensitive(cleanNegative)) {
            throw new BusinessException(400, "提示词包含敏感内容，请调整后重试");
        }
        // 预算闸放在提交时而不是生成前：预算耗尽应立即告知用户，而不是先排队再失败
        long used = repository.sumCostMicrosThisMonth();
        if (used >= props.monthlyBudgetMicros()) {
            throw new BusinessException(400, "本月营销图生成预算已用尽，请联系平台调整预算");
        }

        String model = resolveModel(pro, mode);
        String taskNo = nextTaskNo();
        repository.insert(taskNo, ownerType, ownerId, cleanPrompt,
                cleanNegative.isEmpty() ? null : cleanNegative, model, cleanSize);

        try {
            generator.generate(taskNo);
        } catch (RuntimeException e) {
            // 队列满/线程池关闭：任务已入库，必须落 FAILED，避免永远停在 PENDING
            repository.markFailed(taskNo, "生成队列繁忙，请稍后重试");
            throw new BusinessException(503, "生成队列繁忙，请稍后重试");
        }
        return taskMap(new TaskRow(0L, taskNo, ownerType, ownerId, cleanPrompt,
                cleanNegative.isEmpty() ? null : cleanNegative, model, cleanSize, "PENDING",
                null, null, null, 0L, LocalDateTime.now(), LocalDateTime.now()));
    }

    /** 分页查询；ownerType=null 表示管理员查看全部。 */
    public Map<String, Object> list(String ownerType, Long ownerId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(50, Math.max(1, size));
        long total = repository.count(ownerType, ownerId);
        List<Map<String, Object>> items = repository
                .page(ownerType, ownerId, (safePage - 1) * safeSize, safeSize)
                .stream().map(this::taskMap).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", items);
        out.put("total", total);
        out.put("page", safePage);
        out.put("size", safeSize);
        out.put("budget", budgetMap());
        out.put("stats30d", statsMap(repository.statsLast30Days(ownerType, ownerId)));
        return out;
    }

    /** 详情：ownerType=null 为管理员可看全部；否则必须是自己的任务（按不存在处理，避免任务号枚举）。 */
    public Map<String, Object> detail(String ownerType, Long ownerId, String taskNo) {
        Optional<TaskRow> found = repository.findByTaskNo(taskNo);
        if (found.isEmpty()) {
            throw new BusinessException(404, "任务不存在");
        }
        TaskRow task = found.get();
        if (ownerType != null
                && (!ownerType.equals(task.ownerType()) || !ownerId.equals(task.ownerId()))) {
            throw new BusinessException(404, "任务不存在");
        }
        return taskMap(task);
    }

    private Map<String, Object> budgetMap() {
        long used = repository.sumCostMicrosThisMonth();
        long budget = props.monthlyBudgetMicros();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unitCostMicros", props.getUnitCostMicros());
        m.put("monthlyBudgetMicros", budget);
        m.put("monthCostMicros", used);
        m.put("remainingMicros", Math.max(0L, budget - used));
        return m;
    }

    private Map<String, Object> statsMap(MarketingImageTaskRepository.WindowStats stats) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", stats.total());
        m.put("success", stats.success());
        m.put("costMicros", stats.costMicros());
        return m;
    }

    private Map<String, Object> taskMap(TaskRow t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskNo", t.taskNo());
        m.put("ownerType", t.ownerType());
        m.put("ownerId", t.ownerId());
        m.put("prompt", t.prompt());
        m.put("negativePrompt", t.negativePrompt());
        m.put("model", t.model());
        m.put("size", t.size());
        m.put("status", t.status());
        m.put("imageUrl", t.imageUrl());
        m.put("errorMessage", t.errorMessage());
        m.put("costMicros", t.costMicros());
        m.put("createdAt", t.createdAt() == null ? null : t.createdAt().toString());
        m.put("updatedAt", t.updatedAt() == null ? null : t.updatedAt().toString());
        return m;
    }

    private String resolveModel(Boolean pro, String mode) {
        String m = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (m) {
            case "mt" -> props.getImageModelMt();
            case "pro" -> props.getImageModelPro();
            case "turbo" -> props.getImageModel();
            // 兼容旧前端：只传 pro 布尔时仍按 turbo/pro 两档处理
            default -> Boolean.TRUE.equals(pro) ? props.getImageModelPro() : props.getImageModel();
        };
    }

    private static boolean containsSensitive(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : SENSITIVE_WORDS) {
            if (lower.contains(word)) return true;
        }
        return false;
    }

    private static String nextTaskNo() {
        return "MI" + LocalDateTime.now().format(TASK_NO_FMT)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }
}
