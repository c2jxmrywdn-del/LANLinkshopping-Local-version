package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 */
@Service
public class LogisticsCarrierRecommenderService {

    private static final Logger log = LoggerFactory.getLogger(LogisticsCarrierRecommenderService.class);

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
        private String transportMode;          // express / ltl / ftload
        private BigDecimal totalFreight;       // 综合运费（元）
        private BigDecimal baseFreight;        // 基础阶梯运费
        private BigDecimal vasFreight;         // 工业增值服务费合计
        private Integer etaHours;              // 预计干线时效（小时）
        private String etaDesc;                // 时效文案，如 "次日 12:00 达"
        private Integer serviceScore;          // 服务综合分（0-100）
        private Double slaRate;                // 准时交付率（%）
        private List<String> highlights;       // 优势标签
        private Double matchScore;             // AI 综合匹配度得分（0-100）
        private Boolean vasFullySupported;     // 是否完整支持所选增值服务
        private List<String> unsupportedVas;   // 不支持的增值服务项
    }

    /**
     * 核心推荐算费方法
     */
    public RecommendResult recommend(RecommendRequest req) {
        BigDecimal weight = req.getWeight() != null && req.getWeight().compareTo(BigDecimal.ZERO) > 0 ? req.getWeight() : BigDecimal.valueOf(1.0);
        BigDecimal volume = req.getVolume() != null && req.getVolume().compareTo(BigDecimal.ZERO) > 0 ? req.getVolume() : BigDecimal.valueOf(0.01);
        String province = req.getReceiverProvince() != null ? req.getReceiverProvince() : "";

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

        List<CarrierQuote> quotes = new ArrayList<>();

        for (LogisticsProvider p : providers) {
            List<LogisticsTemplate> templates = templateMapper.selectList(
                    new LambdaQueryWrapper<LogisticsTemplate>()
                            .eq(LogisticsTemplate::getProviderId, p.getProviderId())
                            .eq(LogisticsTemplate::getStatus, 1)
            );
            if (templates.isEmpty()) continue;

            for (LogisticsTemplate t : templates) {
                try {
                    // 调用运费核算引擎
                    LogisticsFreightCalculator.CalcResult calc = freightCalculator.calculate(
                            t, weight, volume, province, false, BigDecimal.valueOf(1000), 1, vas
                    );

                    CarrierQuote q = new CarrierQuote();
                    q.setProviderId(p.getProviderId());
                    q.setProviderName(p.getProviderName());
                    q.setProviderCode(p.getProviderCode());
                    q.setTemplateId(t.getTemplateId());
                    q.setTemplateName(t.getTemplateName());
                    q.setTotalFreight(calc.freight().setScale(2, RoundingMode.HALF_UP));

                    // 解析时效
                    int eta = p.getAvgEtaHours() != null && p.getAvgEtaHours() > 0 ? p.getAvgEtaHours() : 48;
                    // 若目的地同城/省内可适度快 12h
                    q.setEtaHours(eta);
                    q.setEtaDesc(formatEta(eta));

                    int score = p.getServiceScore() != null ? p.getServiceScore() : 90;
                    q.setServiceScore(score);
                    q.setSlaRate(p.getSlaRate() != null ? p.getSlaRate() : 98.0);

                    // 运输模式推断
                    String mode = "ltl";
                    if (weight.compareTo(BigDecimal.valueOf(50)) <= 0 && volume.compareTo(BigDecimal.valueOf(0.3)) <= 0) {
                        mode = "express";
                    } else if (weight.compareTo(BigDecimal.valueOf(3000)) >= 0) {
                        mode = "ftload";
                    }
                    q.setTransportMode(mode);

                    // 增值服务支持校验
                    checkVasSupport(t, vas, q);

                    quotes.add(q);
                } catch (Exception e) {
                    log.warn("[carrier-recommender] 服务商 {} 模板 {} 试算异常: {}", p.getProviderName(), t.getTemplateName(), e.getMessage());
                }
            }
        }

        if (quotes.isEmpty()) {
            return fallbackResult(providers);
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
            q.setMatchScore(Math.round(finalScore * 10.0) / 10.0);

            // 注入亮点标签
            q.setHighlights(buildHighlights(q, minCost, maxCost, minEta));
        }

        // 排序按匹配分从高到低
        quotes.sort((a, b) -> Double.compare(b.getMatchScore(), a.getMatchScore()));

        // 遴选三张专属卡片
        CarrierQuote costOptimal = quotes.stream()
                .min(Comparator.comparing(CarrierQuote::getTotalFreight))
                .orElse(quotes.get(0));

        CarrierQuote speedOptimal = quotes.stream()
                .min(Comparator.comparingInt(CarrierQuote::getEtaHours).thenComparing(CarrierQuote::getTotalFreight))
                .orElse(quotes.get(0));

        CarrierQuote serviceOptimal = quotes.stream()
                .filter(q -> Boolean.TRUE.equals(q.getVasFullySupported()))
                .max(Comparator.comparingDouble(q -> q.getServiceScore() * 0.5 + q.getSlaRate() * 0.5))
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

    private void checkVasSupport(LogisticsTemplate t, LogisticsFreightCalculator.VasRequest vas, CarrierQuote q) {
        List<String> unsupported = new ArrayList<>();
        boolean fully = true;
        String vasRule = t.getVasRuleJson();

        if (Boolean.TRUE.equals(vas.forklift()) && (vasRule == null || !vasRule.contains("forklift"))) {
            unsupported.add("叉车装卸");
            fully = false;
        }
        if (Boolean.TRUE.equals(vas.woodenCrate()) && (vasRule == null || !vasRule.contains("woodenCrate"))) {
            unsupported.add("定制木架");
            fully = false;
        }
        if (Boolean.TRUE.equals(vas.upstairs()) && (vasRule == null || !vasRule.contains("upstairs"))) {
            unsupported.add("送货上楼");
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
