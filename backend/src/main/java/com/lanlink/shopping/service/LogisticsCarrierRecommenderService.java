package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.entity.LogisticsProvider;
import com.lanlink.shopping.entity.LogisticsTemplate;
import com.lanlink.shopping.mapper.LogisticsProviderMapper;
import com.lanlink.shopping.mapper.LogisticsTemplateMapper;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * AI 最优运力智能比价与多目标决策推荐引擎：
 *  1. 并行试算全网可用物流承运商与运费模板
 *  2. 智能输出三维决策卡片：
 *     - 🟢 性价比最优（Cost Effective）：全网最低综合运费（含阶梯计费与增值服务）
 *     - 🔵 极速时效（Time Critical）：干线运输时效最短
 *     - 🟣 服务首选（Service Preferred）：重货大宗专线、原厂品质保障与全套增值服务覆盖
 *  3. 输出加权决策分（Cost 35% + Speed 30% + Service 35%）与优势标签
 *
 * 承运商扩展（out/carrier-expansion-design.md §2）：
 *  - 比价恒传 orderAmount=0（包邮短路天然失效，比价口径=不含包邮的门市运费；
 *    真实运单链路仍按订单实额计算，两条链路口径分离）；
 *  - capability_json 统一 Jackson 解析：国际线路（scope=international）整批剔除；
 *    大件需求（>50kg 或 >0.3m³ 或 oversize）下非 heavyCargo 承运商软惩罚 ×0.85；
 *  - checkVasSupport 以 JSON key 集合判断（含 oversizeOverweight），不再字符串 contains；
 *  - SELF（商家自送）仅入 allQuotes，不参与 speed/cost 卡遴选。
 */
@Service
public class LogisticsCarrierRecommenderService {

    private static final Logger log = LoggerFactory.getLogger(LogisticsCarrierRecommenderService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 大件判定边界沿用 express/零担分界（Recommender 既有代码边界） */
    private static final BigDecimal HEAVY_WEIGHT_KG = BigDecimal.valueOf(50);
    private static final BigDecimal HEAVY_VOLUME_M3 = BigDecimal.valueOf(0.3);
    private static final BigDecimal HEAVY_FTLOAD_KG = BigDecimal.valueOf(3000);
    /** 大件需求下非 heavyCargo 承运商的软惩罚系数（不硬过滤，保留对照与立省叙事） */
    private static final double NON_HEAVY_PENALTY = 0.85;

    private final LogisticsProviderMapper providerMapper;
    private final LogisticsTemplateMapper templateMapper;
    private final LogisticsFreightCalculator freightCalculator;

    public LogisticsCarrierRecommenderService(LogisticsProviderMapper providerMapper,
                                             LogisticsTemplateMapper templateMapper,
                                             LogisticsFreightCalculator freightCalculator) {
        this.providerMapper = providerMapper;
        this.templateMapper = templateMapper;
        this.freightCalculator = freightCalculator;
    }

    /** 试算请求入参 */
    @Data
    public static class RecommendRequest {
        private BigDecimal weight;
        private BigDecimal volume;
        private String receiverProvince;
        private String receiverCity;
        private Boolean forklift;
        private Boolean upstairs;
        private Integer floorCount;
        private Boolean woodenCrate;
        private Boolean oversize;
        /** 偏好: balanced / cost / speed / service */
        private String priority;
    }

    /** 推荐决策响应结果 */
    @Data
    public static class RecommendResult {
        private CarrierQuote costOptimal;     // 🟢 性价比最优卡片
        private CarrierQuote speedOptimal;    // 🔵 极速时效卡片
        private CarrierQuote serviceOptimal;  // 🟣 原厂服务首选卡片
        private List<CarrierQuote> allQuotes; // 全量排序候选池
        private Integer totalCarriersChecked; // 参评承运商数量
        private BigDecimal maxSavedAmount;    // 相对最高报价最多节省金额
    }

    /** 承运商报价项明细 */
    @Data
    public static class CarrierQuote {
        private Long providerId;
        private String providerName;
        private String providerCode;
        private Long templateId;
        private String templateName;
        private String transportMode;          // express / ltl / ftload / self
        private BigDecimal totalFreight;       // 综合运费（元）
        private BigDecimal baseFreight;        // 基础阶梯运费
        private BigDecimal vasFreight;         // 工业增值服务费合计
        private BigDecimal freeThreshold;      // 满额包邮门槛（>0 时前端渲染「满 ¥x 包邮」角标）
        private Integer etaHours;              // 预计干线时效（小时）
        private String etaDesc;                // 时效文案，如 "次日 12:00 达"
        private Integer serviceScore;          // 服务综合分（0-100）
        private Double slaRate;                // 准时交付率（%）
        private List<String> highlights;       // 优势标签
        private List<String> capabilityTags;   // 能力标签（HEAVY/PALLET/GUARANTEED/INSTALL/LOW_COST/SELF）
        private Double matchScore;             // AI 综合匹配度得分（0-100）
        private Boolean vasFullySupported;     // 是否完整支持所选增值服务
        private List<String> unsupportedVas;   // 不支持的增值服务项
    }

    /** capability_json 解析快照（循环外一次性构建） */
    private record Capability(boolean international, boolean heavyCargo, boolean pallet,
                              boolean install, boolean guaranteed, boolean selfPickup,
                              String costLevel) {
        static final Capability EMPTY = new Capability(false, false, false, false, false, false, null);
    }

    private Capability parseCapability(LogisticsProvider p) {
        String json = p.getCapabilityJson();
        if (json == null || json.isBlank()) return Capability.EMPTY;
        try {
            Map<String, Object> m = JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
            return new Capability(
                    "international".equalsIgnoreCase(str(m.get("scope"))),
                    Boolean.TRUE.equals(m.get("heavyCargo")),
                    Boolean.TRUE.equals(m.get("pallet")),
                    Boolean.TRUE.equals(m.get("install")),
                    Boolean.TRUE.equals(m.get("guaranteed")),
                    Boolean.TRUE.equals(m.get("selfPickup")),
                    str(m.get("costLevel"))
            );
        } catch (Exception e) {
            log.warn("[carrier-recommender] capability_json 解析失败: {} {}", p.getProviderCode(), e.getMessage());
            return Capability.EMPTY;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /**
     * 核心推荐算费方法
     */
    public RecommendResult recommend(RecommendRequest req) {
        BigDecimal weight = req.getWeight() != null && req.getWeight().compareTo(BigDecimal.ZERO) > 0 ? req.getWeight() : BigDecimal.valueOf(1.0);
        BigDecimal volume = req.getVolume() != null && req.getVolume().compareTo(BigDecimal.ZERO) > 0 ? req.getVolume() : BigDecimal.valueOf(0.01);
        String province = req.getReceiverProvince() != null ? req.getReceiverProvince() : "";
        // receiverCity 此前声明未用：省为空时取市前 2 字近似省份，区域加价仍走 Calculator 既有匹配
        if ((province == null || province.isBlank()) && req.getReceiverCity() != null && req.getReceiverCity().length() >= 2) {
            province = req.getReceiverCity().substring(0, 2);
        }

        LogisticsFreightCalculator.VasRequest vas = new LogisticsFreightCalculator.VasRequest(
                Boolean.TRUE.equals(req.getForklift()),
                Boolean.TRUE.equals(req.getUpstairs()),
                req.getFloorCount() != null ? req.getFloorCount() : 1,
                Boolean.TRUE.equals(req.getWoodenCrate()),
                Boolean.TRUE.equals(req.getOversize())
        );

        List<LogisticsProvider> providers = providerMapper.selectList(
                new LambdaQueryWrapper<LogisticsProvider>()
                        .eq(LogisticsProvider::getStatus, 1)
                        .orderByAsc(LogisticsProvider::getSort)
        );

        // 国际线路整批剔除：不试算不出卡（双保险——国际种子 status=0 本就被上方
        // status=1 过滤，此处防的是后续误置 status=1 + 误配启用模板的暴露）
        Map<Long, Capability> caps = new HashMap<>();
        List<LogisticsProvider> domesticProviders = new ArrayList<>();
        for (LogisticsProvider p : providers) {
            Capability c = parseCapability(p);
            caps.put(p.getProviderId(), c);
            if (!c.international()) domesticProviders.add(p);
        }

        // 大件判定：>50kg 或 >0.3m³ 或勾选超长超重（express 边界的补集）
        boolean heavyDemand = weight.compareTo(HEAVY_WEIGHT_KG) > 0
                || volume.compareTo(HEAVY_VOLUME_M3) > 0
                || Boolean.TRUE.equals(req.getOversize());

        List<CarrierQuote> quotes = new ArrayList<>();

        for (LogisticsProvider p : domesticProviders) {
            Capability cap = caps.get(p.getProviderId());
            List<LogisticsTemplate> templates = templateMapper.selectList(
                    new LambdaQueryWrapper<LogisticsTemplate>()
                            .eq(LogisticsTemplate::getProviderId, p.getProviderId())
                            .eq(LogisticsTemplate::getStatus, 1)
            );
            if (templates.isEmpty()) continue;

            for (LogisticsTemplate t : templates) {
                try {
                    // 比价恒传 orderAmount=0：包邮条件 threshold>0 && orderAmount>=threshold 恒 false，
                    // 包邮短路天然失效，比价口径=不含包邮的门市运费（Calculator 零改动）
                    LogisticsFreightCalculator.CalcResult calc = freightCalculator.calculate(
                            t, weight, volume, province, false, BigDecimal.ZERO, 1, vas
                    );

                    CarrierQuote q = new CarrierQuote();
                    q.setProviderId(p.getProviderId());
                    q.setProviderName(p.getProviderName());
                    q.setProviderCode(p.getProviderCode());
                    q.setTemplateId(t.getTemplateId());
                    q.setTemplateName(t.getTemplateName());
                    q.setTotalFreight(calc.freight().setScale(2, RoundingMode.HALF_UP));
                    q.setFreeThreshold(t.getFreeThreshold() != null ? t.getFreeThreshold() : BigDecimal.ZERO);

                    // 解析时效
                    int eta = p.getAvgEtaHours() != null && p.getAvgEtaHours() > 0 ? p.getAvgEtaHours() : 48;
                    // 若目的地同城/省内可适度快 12h
                    q.setEtaHours(eta);
                    q.setEtaDesc(formatEta(eta));

                    int score = p.getServiceScore() != null ? p.getServiceScore() : 90;
                    q.setServiceScore(score);
                    q.setSlaRate(p.getSlaRate() != null ? p.getSlaRate() : 98.0);

                    // 运输模式推断（SELF 商家自送单列，不参与 speed/cost 遴选）
                    String mode = "ltl";
                    if ("SELF".equalsIgnoreCase(p.getProviderCode()) || cap.selfPickup()) {
                        mode = "self";
                    } else if (weight.compareTo(HEAVY_WEIGHT_KG) <= 0 && volume.compareTo(HEAVY_VOLUME_M3) <= 0) {
                        mode = "express";
                    } else if (weight.compareTo(HEAVY_FTLOAD_KG) >= 0) {
                        mode = "ftload";
                    }
                    q.setTransportMode(mode);

                    // 增值服务支持校验（Jackson key 集合，含 oversizeOverweight）
                    checkVasSupport(t, vas, q);

                    // 能力标签（枚举键，前端经 i18n 渲染）
                    q.setCapabilityTags(buildCapabilityTags(cap, mode));

                    quotes.add(q);
                } catch (Exception e) {
                    log.warn("[carrier-recommender] 服务商 {} 模板 {} 试算异常: {}", p.getProviderName(), t.getTemplateName(), e.getMessage());
                }
            }
        }

        if (quotes.isEmpty()) {
            return fallbackResult(domesticProviders);
        }

        // 计算全网最高最低以进行归一化打分
        BigDecimal minCost = quotes.stream().map(CarrierQuote::getTotalFreight).min(BigDecimal::compareTo).orElse(BigDecimal.TEN);
        BigDecimal maxCost = quotes.stream().map(CarrierQuote::getTotalFreight).max(BigDecimal::compareTo).orElse(BigDecimal.TEN);
        int minEta = quotes.stream().mapToInt(CarrierQuote::getEtaHours).min().orElse(24);

        for (CarrierQuote q : quotes) {
            double costRatio = q.getTotalFreight().compareTo(BigDecimal.ZERO) > 0
                    ? minCost.doubleValue() / q.getTotalFreight().doubleValue()
                    : 1.0;
            double costScore = Math.min(100.0, costRatio * 100.0);

            double timeRatio = q.getEtaHours() > 0 ? (double) minEta / q.getEtaHours() : 1.0;
            double timeScore = Math.min(100.0, timeRatio * 100.0);

            double serviceScore = q.getServiceScore() * (Boolean.TRUE.equals(q.getVasFullySupported()) ? 1.0 : 0.7);

            // 依据偏好动态调整权重
            double wCost = 0.35, wTime = 0.30, wService = 0.35;
            if ("cost".equalsIgnoreCase(req.getPriority())) {
                wCost = 0.60; wTime = 0.20; wService = 0.20;
            } else if ("speed".equalsIgnoreCase(req.getPriority())) {
                wCost = 0.20; wTime = 0.60; wService = 0.20;
            } else if ("service".equalsIgnoreCase(req.getPriority())) {
                wCost = 0.20; wTime = 0.20; wService = 0.60;
            }

            double finalScore = wCost * costScore + wTime * timeScore + wService * serviceScore;

            Capability cap = caps.getOrDefault(q.getProviderId(), Capability.EMPTY);
            // 大件需求下非 heavyCargo 承运商软惩罚（不硬过滤：quotes 清空触发三卡全空兜底）
            if (heavyDemand && !cap.heavyCargo()) {
                finalScore *= NON_HEAVY_PENALTY;
            }
            // costLevel=low 在 cost 偏好加 3 分；guaranteed 在 speed 偏好加 3 分
            if ("cost".equalsIgnoreCase(req.getPriority()) && "low".equalsIgnoreCase(cap.costLevel())) {
                finalScore += 3.0;
            }
            if ("speed".equalsIgnoreCase(req.getPriority()) && cap.guaranteed()) {
                finalScore += 3.0;
            }
            q.setMatchScore(Math.round(finalScore * 10.0) / 10.0);

            // 注入亮点标签
            q.setHighlights(buildHighlights(q, minCost, maxCost, minEta));
        }

        // 排序按匹配分从高到低
        quotes.sort((a, b) -> Double.compare(b.getMatchScore(), a.getMatchScore()));

        // SELF 商家自送：仅入 allQuotes，不参与三卡遴选（48h 干线口径让其恒输极速卡、
        // 取小值又恒占，两向失真都避免）
        List<CarrierQuote> cardPool = quotes.stream()
                .filter(q -> !"self".equals(q.getTransportMode()))
                .toList();

        // 遴选三张专属卡片
        CarrierQuote costOptimal = cardPool.stream()
                .min(Comparator.comparing(CarrierQuote::getTotalFreight))
                .orElse(quotes.get(0));

        CarrierQuote speedOptimal = cardPool.stream()
                .min(Comparator.comparingInt(CarrierQuote::getEtaHours).thenComparing(CarrierQuote::getTotalFreight))
                .orElse(quotes.get(0));

        // 大件需求下 serviceOptimal 在 heavyCargo ∩ vasFullySupported 内遴选（有合格集时），
        // 呼应前端「特种重货专线」卡
        Comparator<CarrierQuote> serviceRank = Comparator
                .comparingDouble((CarrierQuote q) -> q.getServiceScore() * 0.5 + q.getSlaRate() * 0.5);
        List<CarrierQuote> serviceCandidates = quotes;
        if (heavyDemand) {
            List<CarrierQuote> qualified = quotes.stream()
                    .filter(q -> Boolean.TRUE.equals(q.getVasFullySupported()))
                    .filter(q -> {
                        Capability cap = caps.getOrDefault(q.getProviderId(), Capability.EMPTY);
                        return cap.heavyCargo();
                    })
                    .toList();
            if (!qualified.isEmpty()) serviceCandidates = qualified;
        }
        CarrierQuote serviceOptimal = serviceCandidates.stream()
                .filter(q -> Boolean.TRUE.equals(q.getVasFullySupported()))
                .max(serviceRank)
                .orElse(quotes.get(0));

        RecommendResult result = new RecommendResult();
        result.setCostOptimal(costOptimal);
        result.setSpeedOptimal(speedOptimal);
        result.setServiceOptimal(serviceOptimal);
        result.setAllQuotes(quotes);
        result.setTotalCarriersChecked(quotes.size());
        result.setMaxSavedAmount(maxCost.subtract(costOptimal.getTotalFreight()).setScale(2, RoundingMode.HALF_UP));

        return result;
    }

    private List<String> buildCapabilityTags(Capability cap, String mode) {
        List<String> tags = new ArrayList<>();
        if ("self".equals(mode)) tags.add("SELF");
        if (cap.heavyCargo()) tags.add("HEAVY");
        if (cap.pallet()) tags.add("PALLET");
        if (cap.install()) tags.add("INSTALL");
        if (cap.guaranteed()) tags.add("GUARANTEED");
        if ("low".equalsIgnoreCase(cap.costLevel())) tags.add("LOW_COST");
        return tags;
    }

    /**
     * VAS 支持校验：Jackson 解析 vas_rule_json 取 key 集合（含 oversizeOverweight——
     * Calculator 支持其计费但旧实现字符串 contains 漏判），坏 JSON 视为不支持。
     */
    private void checkVasSupport(LogisticsTemplate t, LogisticsFreightCalculator.VasRequest vas, CarrierQuote q) {
        Set<String> keys = Set.of();
        String rule = t.getVasRuleJson();
        if (rule != null && !rule.isBlank()) {
            try {
                keys = JSON.readValue(rule, new TypeReference<Map<String, Map<String, Object>>>() {}).keySet();
            } catch (Exception e) {
                log.warn("[carrier-recommender] vas_rule_json 解析失败: 模板 {} {}", t.getTemplateId(), e.getMessage());
            }
        }
        List<String> unsupported = new ArrayList<>();
        boolean fully = true;
        if (Boolean.TRUE.equals(vas.forklift()) && !keys.contains("forklift")) {
            unsupported.add("叉车装卸");
            fully = false;
        }
        if (Boolean.TRUE.equals(vas.woodenCrate()) && !keys.contains("woodenCrate")) {
            unsupported.add("定制木架");
            fully = false;
        }
        if (Boolean.TRUE.equals(vas.upstairs()) && !keys.contains("upstairs")) {
            unsupported.add("送货上楼");
            fully = false;
        }
        if (Boolean.TRUE.equals(vas.oversize()) && !keys.contains("oversizeOverweight")) {
            unsupported.add("超长超重附加");
            fully = false;
        }
        q.setVasFullySupported(fully);
        q.setUnsupportedVas(unsupported);
    }

    private List<String> buildHighlights(CarrierQuote q, BigDecimal minCost, BigDecimal maxCost, int minEta) {
        List<String> tags = new ArrayList<>();
        if (q.getTotalFreight().compareTo(minCost) == 0) {
            tags.add("全网最低运费");
        } else if (maxCost.compareTo(q.getTotalFreight()) > 0) {
            BigDecimal saved = maxCost.subtract(q.getTotalFreight());
            if (saved.compareTo(BigDecimal.valueOf(10)) > 0) {
                tags.add("立省 ¥" + saved.intValue());
            }
        }

        if (q.getEtaHours() == minEta) {
            tags.add("极速履约");
        }
        if (q.getSlaRate() != null && q.getSlaRate() >= 99.0) {
            tags.add("SLA 99%+ 准时达");
        }
        if (Boolean.TRUE.equals(q.getVasFullySupported())) {
            tags.add("原厂增值服务就位");
        }
        return tags;
    }

    private String formatEta(int hours) {
        if (hours <= 24) return "次日 12:00 前达";
        if (hours <= 48) return "隔日送达 (48h)";
        int days = (int) Math.ceil((double) hours / 24);
        return days + " 日内送达";
    }

    private RecommendResult fallbackResult(List<LogisticsProvider> providers) {
        RecommendResult res = new RecommendResult();
        res.setAllQuotes(Collections.emptyList());
        res.setTotalCarriersChecked(providers.size());
        res.setMaxSavedAmount(BigDecimal.ZERO);
        return res;
    }
}
