package com.lanlink.shopping.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.service.ProductService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * 商城商品: 前台浏览(公开) + 商户发布(需登录且为商户, 进入平台审核)
 */
@RestController
@RequestMapping("/product")
public class ProductController {

    private final ProductService productService;
    private final com.lanlink.shopping.service.MerchantService merchantService;
    private final com.lanlink.shopping.module.recommend.service.PersonalizeService personalizeService;
    private final com.lanlink.shopping.module.search.ProductIndexJob productIndexJob;
    private final com.lanlink.shopping.module.productreview.service.ProductAutoReviewService productAutoReviewService;
    private final com.lanlink.shopping.module.productreview.service.ProductReviewConfigService productReviewConfigService;
    private final StorageService storageService;
    private final com.lanlink.shopping.service.AuditService auditService;
    private final com.lanlink.shopping.service.ProductTitleAiService productTitleAiService;

    public ProductController(ProductService productService,
                             com.lanlink.shopping.service.MerchantService merchantService,
                             com.lanlink.shopping.module.recommend.service.PersonalizeService personalizeService,
                             com.lanlink.shopping.module.search.ProductIndexJob productIndexJob,
                             com.lanlink.shopping.module.productreview.service.ProductAutoReviewService productAutoReviewService,
                             com.lanlink.shopping.module.productreview.service.ProductReviewConfigService productReviewConfigService,
                             StorageService storageService,
                             com.lanlink.shopping.service.AuditService auditService,
                             com.lanlink.shopping.service.ProductTitleAiService productTitleAiService) {
        this.productService = productService;
        this.merchantService = merchantService;
        this.personalizeService = personalizeService;
        this.productIndexJob = productIndexJob;
        this.productAutoReviewService = productAutoReviewService;
        this.productReviewConfigService = productReviewConfigService;
        this.storageService = storageService;
        this.auditService = auditService;
        this.productTitleAiService = productTitleAiService;
    }

    private static final long MAX_IMG_SIZE = 5 * 1024 * 1024;

    /**
     * 商城商品分页（公开）。personalization=true 且登录且 analytics 已授权时走个性化推流
     * （习惯类目打分 + 多样性打散）；其余情况原样自然排序——个性化降级在服务端闭环。
     */
    @GetMapping("/page")
    public R<Object> page(@RequestParam(defaultValue = "1") long current,
                          @RequestParam(defaultValue = "12") long size,
                          @RequestParam(required = false) String keyword,
                          @RequestParam(required = false) Long catId,
                          @RequestParam(required = false) Long indId,
                          @RequestParam(required = false) String sort,
                          @RequestParam(defaultValue = "false") boolean personalization,
                          @RequestHeader(value = "Cookie", required = false) String cookie,
                          HttpServletRequest request) {
        // 语义搜索：有关键词且无结构化筛选时走 jieba+向量双路 RRF + 多目标融合重排
        //（userId/ll_analytics Cookie 透传作 Per 分量双信号，SearchService 内部门控降级）
        IPage<Product> page = hasText(keyword)
                ? productService.searchPage(current, size, keyword, catId, indId, sort,
                        UserContext.currentUserId(request), analyticsCookie(cookie))
                : productService.page(current, size, keyword, catId, indId, sort);
        // 仅综合态（无搜索/筛选/显式排序）走个性化推流
        boolean eligible = personalization
                && !hasText(keyword) && catId == null && indId == null && !hasText(sort);
        IPage<Product> personalized = eligible
                ? personalizeService.page(current, size, UserContext.currentUserId(request), analyticsCookie(cookie),
                        keyword, catId, indId, sort)
                : null;
        if (personalized != null) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("records", personalized.getRecords());
            out.put("total", personalized.getTotal());
            out.put("current", personalized.getCurrent());
            out.put("size", personalized.getSize());
            out.put("pages", personalized.getPages());
            out.put("personalized", true);
            return R.ok(out);
        }
        return R.ok((Object) page);
    }

    /** 管理端：手动触发商品向量索引重建（商品发布/批量导入后可立即生效） */
    @PostMapping("/admin/search/reindex")
    public R<Map<String, Object>> reindex() {
        return R.ok(new java.util.LinkedHashMap<>(productIndexJob.reindex()));
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String analyticsCookie(String cookieHeader) {
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split("; ")) {
            if (part.startsWith("ll_analytics=")) return part.substring("ll_analytics=".length());
        }
        return null;
    }

    /** 前台详情：仅可售(审核通过)商品可见 */
    @GetMapping("/detail/{id}")
    public R<Product> detail(@PathVariable Long id) {
        return R.ok(productService.detailForPublic(id));
    }

    /** 商户发布商品：发布后自动进入「待审核」，审核通过才可售（需 product:publish 权限） */
    @PostMapping("/publish")
    @com.lanlink.shopping.integration.security.RequirePerm("product:publish")
    public R<Product> publish(@Valid @RequestBody Product product, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        var merchant = merchantService.getByUser(userId);
        if (merchant == null) return R.fail("请先完成商户入驻");
        if (merchant.getReviewStatus() == null || merchant.getReviewStatus() != 1) {
            return R.fail("商户审核未通过，暂不能上架商品");
        }
        return R.ok("已提交，系统正在自动审核", productService.publish(merchant.getMerId(), product));
    }

    /** 商户自己的商品列表（含审核状态，实时同步；可按关键词/审核状态/可售状态过滤） */
    @GetMapping("/my/list")
    @com.lanlink.shopping.integration.security.RequirePerm("product:publish")
    public R<IPage<Product>> myList(@RequestParam(defaultValue = "1") long current,
                                    @RequestParam(defaultValue = "10") long size,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Integer reviewStatus,
                                    @RequestParam(required = false) Integer status,
                                    HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        var merchant = merchantService.getByUser(userId);
        if (merchant == null) return R.fail("请先完成商户入驻");
        return R.ok(productService.myPage(merchant.getMerId(), current, size, keyword, reviewStatus, status));
    }

    /** 商户自己的商品详情（编辑回显；归属校验） */
    @GetMapping("/my/detail/{prodId}")
    @com.lanlink.shopping.integration.security.RequirePerm("product:publish")
    public R<Product> myDetail(@PathVariable Long prodId, HttpServletRequest request) {
        var merchant = mustApprovedMerchant(UserContext.currentUserId(request));
        return R.ok(productService.myDetail(merchant.getMerId(), prodId));
    }

    /**
     * 商户编辑自己的商品：仅调库存即时生效；
     * 修改内容字段（标题/价格/分类/主图/详情等）会重新送审（待审核且不可售）。
     */
    @PostMapping("/my/update/{prodId}")
    @com.lanlink.shopping.integration.security.RequirePerm("product:publish")
    public R<Product> myUpdate(@PathVariable Long prodId, @RequestBody Product patch,
                               HttpServletRequest request) {
        var merchant = mustApprovedMerchant(UserContext.currentUserId(request));
        return R.ok("保存成功", productService.myUpdate(merchant.getMerId(), prodId, patch));
    }

    /** 商户自主上架/下架（status 1上架 0下架；仅审核通过的商品可操作） */
    @PostMapping("/my/status/{prodId}")
    @com.lanlink.shopping.integration.security.RequirePerm("product:publish")
    public R<Product> myStatus(@PathVariable Long prodId, @RequestParam Integer status,
                               HttpServletRequest request) {
        var merchant = mustApprovedMerchant(UserContext.currentUserId(request));
        return R.ok(status == 1 ? "商品已上架" : "商品已下架",
                productService.mySetStatus(merchant.getMerId(), prodId, status));
    }

    /** 已通过审核的入驻商户（商品管理操作前置条件） */
    private com.lanlink.shopping.entity.Merchant mustApprovedMerchant(Long userId) {
        var merchant = merchantService.getByUser(userId);
        if (merchant == null) throw new BusinessException("请先完成商户入驻");
        if (merchant.getReviewStatus() == null || merchant.getReviewStatus() != 1) {
            throw new BusinessException("商户审核未通过，暂不能管理商品");
        }
        return merchant;
    }

    /**
     * 商品主图上传：JPG/PNG/WebP，魔数校验防伪造，返回 URL 供 publish 引用（key 供前端入库用）。
     * 落对象存储（out/storage-mq-plan.md 任务 A3）：库内只存 key（如 products/prod_1730....webp），
     * 出站由 StorageUrlSerializer 拼公开绝对 URL；enabled=false 时 StorageService 自动回退本地盘，
     * URL 形态与历史 /api/uploads/products/** 完全一致。
     *
     * <p>三重收口（security-hardening-plan.md P1-5）：① @RequirePerm 与 /my/* 兄弟端点对齐
     * （BUYER/VIP 403、匿名 401，由 PermInterceptor 既有链路执行）+ 方法内 mustApprovedMerchant
     * 前置——上传从此是「已过审商户的商品管理动作」，任意登录用户不可写公读桶；
     * ② IP/入口限流（AuthRateLimitInterceptor POLICIES /product/image + WebConfig 注册）；
     * ③ CSRF（WebConfig + frontend/src/api/csrfPolicy.js 双端登记）；成功写审计 PRODUCT_IMAGE_UPLOAD，
     * 批量换图/占桶事后可查。
     */
    @PostMapping("/image")
    @com.lanlink.shopping.integration.security.RequirePerm("product:publish")
    public R<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file,
                                              HttpServletRequest request) throws IOException {
        Long userId = UserContext.currentUserId(request); // 需登录（PermInterceptor 另行校验权限）
        mustApprovedMerchant(userId); // 未入驻/未过审商户直接拒绝（与 /my/* 兄弟端点同前置）
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择图片文件");
        if (file.getSize() > MAX_IMG_SIZE) throw new IllegalArgumentException("图片大小不能超过 5MB");
        String kind;
        try (InputStream in = file.getInputStream()) {
            byte[] h = in.readNBytes(12);
            if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) kind = "jpg";
            else if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47) kind = "png";
            else if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') kind = "webp";
            else throw new IllegalArgumentException("仅支持 JPG/PNG/WebP 图片");
        }
        BufferedImage img = null;
        try {
            img = ImageIO.read(file.getInputStream());
        } catch (IOException e) {
            throw new IllegalArgumentException("图片解析失败，请重新上传");
        }
        if (img != null) {
            if (img.getWidth() < 100 || img.getHeight() < 100) {
                throw new IllegalArgumentException("图片尺寸过小（需≥100×100）");
            }
        } else if (!"webp".equals(kind)) {
            throw new IllegalArgumentException("图片解析失败，请重新上传");
        }
        String key = "products/prod_" + System.currentTimeMillis() + "." + kind;
        String url = storageService.put(key, file.getBytes(), contentTypeOf(kind));
        // 旁路审计（AuditService 写失败不反噬主流程）：key/类型/大小留痕
        auditService.record(userId,
                "PRODUCT_IMAGE_UPLOAD", key + "/" + contentTypeOf(kind) + "/" + file.getSize(), request);
        return R.ok(Map.of("url", url, "key", storageService.s3Enabled() ? key : ""));
    }

    private static String contentTypeOf(String kind) {
        return switch (kind) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }

    /**
     * AI 标题润色（out/ai-polish-design.md）：商户标题草稿 → 2-3 条 B2B 风格候选，点选后回填表单
     * 走既有发布/编辑流程。纯文本进出（无 productId，无跨商户数据面）；indId/catId 仅进 prompt 用词。
     *
     * <p>三重收口对齐 /product/image 先例：① @RequirePerm("product:publish") + 方法内
     * mustApprovedMerchant（BUYER/VIP 403、匿名 401、未过审商户拒）；② IP/入口限流
     * （AuthRateLimitInterceptor POLICIES /product/ai/* 前缀匹配 + WebConfig 注册 /product/ai/**）；
     * ③ CSRF 双端登记（WebConfig + frontend/src/api/csrfPolicy.js）。双层配额（商户级+全局 backstop）、
     * 注入净化拒绝、候选硬 token 校验与全失败熔断在 ProductTitleAiService 内闭环；
     * 全渠道失败只返回失败提示，绝不虚构候选。
     */
    @PostMapping("/ai/polish-title")
    @com.lanlink.shopping.integration.security.RequirePerm("product:publish")
    public R<Map<String, Object>> polishTitle(@RequestBody com.lanlink.shopping.service.ProductTitleAiService.PolishRequest body,
                                              HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        mustApprovedMerchant(userId);
        String title = body == null || body.title() == null ? "" : body.title().trim();
        if (title.length() < 2 || title.length() > 100) {
            throw new BusinessException("商品名称需 2-100 个字符");
        }
        var result = productTitleAiService.polish(userId, body, request);
        if (result.failed()) return R.fail(result.failureMessage());
        return R.ok(Map.of("candidates", result.candidates()));
    }

    /** 管理后台: 全量商品列表(含已下架), 支持多维筛选 */
    @GetMapping("/admin/list")
    public R<IPage<Product>> adminList(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size,
                                       @RequestParam(required = false) Integer status,
                                       @RequestParam(required = false) Integer reviewStatus,
                                       @RequestParam(required = false) Long indId,
                                       @RequestParam(required = false) Long catId,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(productService.adminPage(current, size, status, reviewStatus, indId, catId, keyword));
    }

    /** 管理后台: 商品审核统计看板 (总数/待审核/已通过/已驳回/已下架/通过率) */
    @GetMapping("/admin/stats")
    public R<Map<String, Object>> adminStats() {
        return R.ok(productService.adminStats());
    }

    /** 管理后台: 商品审核全景详情与合规初筛校验报告 */
    @GetMapping("/admin/detail/{prodId}")
    public R<Map<String, Object>> adminDetail(@PathVariable Long prodId) {
        return R.ok(productService.adminDetail(prodId));
    }

    /** 管理后台: 批量审核商品（批量通过/批量驳回） */
    @PostMapping("/admin/batch-review")
    public R<Map<String, Object>> adminBatchReview(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long adminUserId = UserContext.currentUserId(request);
        @SuppressWarnings("unchecked")
        java.util.List<Integer> rawIds = (java.util.List<Integer>) body.get("prodIds");
        if (rawIds == null) throw new IllegalArgumentException("请选择商品");
        java.util.List<Long> prodIds = rawIds.stream().map(Integer::longValue).toList();
        Integer reviewStatus = (Integer) body.get("reviewStatus");
        String reason = (String) body.get("reason");
        return R.ok(productService.batchReview(prodIds, reviewStatus, reason, adminUserId, request));
    }

    /** 管理后台: 获取商品审核流转审计历史 */
    @GetMapping("/admin/audit-logs/{prodId}")
    public R<java.util.List<com.lanlink.shopping.entity.AuditLog>> adminAuditLogs(@PathVariable Long prodId) {
        return R.ok(productService.auditLogs(prodId));
    }

    /** 管理后台: 上架/下架 (status 1上架 0下架) */
    @PostMapping("/admin/status/{prodId}")
    public R<Product> adminStatus(@PathVariable Long prodId, @RequestParam Integer status) {
        return R.ok(productService.setStatus(prodId, status));
    }

    /** 管理后台: 商品审核（通过→自动可售；驳回→记录原因并保持不可售） */
    @PostMapping("/admin/review/{prodId}")
    public R<Product> adminReview(@PathVariable Long prodId,
                                  @RequestParam Integer reviewStatus,
                                  @RequestParam(required = false) String reason) {
        return R.ok(productService.review(prodId, reviewStatus, reason));
    }

    // ==================== 商品自动审核（规则 + 文本AI + 主图视觉AI） ====================

    /** 管理端：自动审核开关/阈值配置 */
    @GetMapping("/admin/review/config")
    public R<Map<String, Object>> autoReviewConfig() {
        return R.ok(productReviewConfigService.view());
    }

    /** 管理端：更新自动审核配置（白名单键，边界收敛） */
    @PutMapping("/admin/review/config")
    public R<Map<String, Object>> updateAutoReviewConfig(@RequestBody Map<String, Object> body,
                                                         HttpServletRequest request) {
        Map<String, Object> out = productReviewConfigService.update(body);
        com.lanlink.shopping.config.UserContext.currentUserId(request); // 需登录且 /admin/** 已做运营身份校验
        return R.ok(out);
    }

    /** 管理端：手动重跑一轮自动审核（不受每日预算限制，结果留痕） */
    @PostMapping("/admin/review/{prodId}/auto")
    public R<com.lanlink.shopping.module.productreview.entity.ProductReviewRecord> rerunAutoReview(
            @PathVariable Long prodId) {
        return R.ok(productAutoReviewService.reviewNow(prodId, true));
    }

    /** 管理端：查看商品最近一轮自动审核结论 */
    @GetMapping("/admin/review/{prodId}/auto")
    public R<Map<String, Object>> latestAutoReview(@PathVariable Long prodId) {
        return R.ok(productAutoReviewService.latest(prodId));
    }
}
