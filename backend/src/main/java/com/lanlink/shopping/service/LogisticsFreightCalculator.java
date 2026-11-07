package com.lanlink.shopping.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.entity.LogisticsTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工业大宗与多包裹运费计算引擎。
 *
 * 计费能力矩阵：
 *   1. 计费重换算：chargeableWeight = max(实际重量, 体积重量)；快运重抛比 1:3000 / 快递 1:6000
 *   2. 阶梯模式（tierMode）：
 *      - linear: 传统首续重 / 按件 / 固定
 *      - tiered: 大宗分段非线性区间定价（依据 tierRuleJson）
 *      - ton: 大宗工业纯吨位定价
 *   3. 工业增值服务池（VAS）：
 *      - forklift: 叉车装卸费 (起步价 + 超吨加价)
 *      - upstairs: 送货上楼 / 进厂就位费 (起步价 + 楼层加价)
 *      - woodenCrate: 工业木箱 / 打木架定制包装 (按体积 m³)
 *      - oversize: 超长超重附加费 (基准运费比例)
 *   4. 最低起运价保底（minCharge）与偏远/区域附加费叠加
 *   5. 审计留痕：detailJson 完整输出分步算费上下文
 */
@Component
public class LogisticsFreightCalculator {

    /** 运费计算结果（含明细快照） */
    public record CalcResult(BigDecimal freight, String detailJson) {}

    /** 工业增值服务费计算入参 */
    public record VasRequest(
            Boolean forklift,       // 是否需要叉车装卸
            Boolean upstairs,       // 是否送货上楼/进厂就位
            Integer floorCount,     // 楼层数 (默认 1)
            Boolean woodenCrate,    // 是否定制木箱木架
            Boolean oversize        // 是否超长超重
    ) {}

    /**
     * 计算运费（兼容历史调用接口，无 VAS 增值服务）。
     */
    public CalcResult calculate(LogisticsTemplate template, BigDecimal weightKg, BigDecimal volumeM3,
                                String province, boolean remote, BigDecimal orderAmount, Integer quantity) {
        return calculate(template, weightKg, volumeM3, province, remote, orderAmount, quantity, null);
    }

    /**
     * 计算运费（全功能支持：阶梯运费 + 工业增值服务费）。
     */
    public CalcResult calculate(LogisticsTemplate template, BigDecimal weightKg, BigDecimal volumeM3,
                                String province, boolean remote, BigDecimal orderAmount, Integer quantity,
                                VasRequest vas) {
        if (template == null) throw new IllegalArgumentException("运费模板不能为空");
        BigDecimal firstPrice = nvl(template.getFirstPrice());
        BigDecimal firstWeight = nvl(template.getFirstWeight());
        BigDecimal contWeight = nvl(template.getContinuedWeight());
        BigDecimal contPrice = nvl(template.getContinuedPrice());
        BigDecimal volFactor = nvl(template.getVolumeFactor());
        BigDecimal remoteSurcharge = remote ? nvl(template.getRemoteSurcharge()) : BigDecimal.ZERO;

        // 满额包邮
        BigDecimal threshold = nvl(template.getFreeThreshold());
        if (threshold.compareTo(BigDecimal.ZERO) > 0 && nvl(orderAmount).compareTo(threshold) >= 0) {
            return new CalcResult(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    detail("free_threshold", template, null, null, province, remoteSurcharge, BigDecimal.ZERO, threshold, BigDecimal.ZERO, null));
        }

        String pricingType = template.getPricingType() == null ? "weight" : template.getPricingType();
        String tierMode = template.getTierMode() == null ? "linear" : template.getTierMode();
        BigDecimal freight;
        BigDecimal chargeable = null;
        BigDecimal volumeWeight = null;

        switch (pricingType) {
            case "fixed" -> freight = firstPrice;
            case "piece" -> {
                int qty = quantity == null || quantity < 1 ? 1 : quantity;
                freight = firstPrice.add(contPrice.multiply(BigDecimal.valueOf(Math.max(0, qty - 1))));
            }
            default -> { // weight / volume
                BigDecimal actual = nvl(weightKg);
                volumeWeight = volumeWeight(volumeM3, volFactor);
                chargeable = actual.max(volumeWeight);

                if ("tiered".equals(tierMode) && template.getTierRuleJson() != null && !template.getTierRuleJson().isBlank()) {
                    freight = calculateTiered(template.getTierRuleJson(), chargeable);
                } else if ("ton".equals(tierMode)) {
                    BigDecimal weightTon = chargeable.divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
                    freight = weightTon.multiply(firstPrice);
                } else {
                    // 传统 linear 线性计算
                    BigDecimal extra = chargeable.subtract(firstWeight).max(BigDecimal.ZERO);
                    int steps = contWeight.compareTo(BigDecimal.ZERO) > 0
                            ? extra.divide(contWeight, 0, RoundingMode.CEILING).intValue() : 0;
                    freight = firstPrice.add(contPrice.multiply(BigDecimal.valueOf(steps)));
                }
            }
        }

        // 增值服务费计算
        Map<String, BigDecimal> vasFees = calculateVas(template.getVasRuleJson(), vas, chargeable, volumeM3, freight);
        BigDecimal vasTotal = vasFees.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        freight = freight.add(vasTotal);

        // 区域加价与偏远附加
        BigDecimal regionSurcharge = regionSurcharge(template.getRegionRuleJson(), province);
        freight = freight.add(remoteSurcharge).add(regionSurcharge);

        // 最低起运价保底
        BigDecimal minCharge = nvl(template.getMinCharge());
        if (minCharge.compareTo(BigDecimal.ZERO) > 0) {
            freight = freight.max(minCharge);
        }

        if (freight.compareTo(BigDecimal.ZERO) < 0) freight = BigDecimal.ZERO;
        freight = freight.setScale(2, RoundingMode.HALF_UP);

        return new CalcResult(freight, detail(pricingType, template, chargeable, volumeWeight,
                province, remoteSurcharge, regionSurcharge, threshold, vasTotal, vasFees));
    }

    /**
     * 大宗非线性阶梯计费解析：
     * JSON: [{"minWeight":0,"maxWeight":300,"unitPrice":1.5,"basePrice":30.0}, ...]
     */
    BigDecimal calculateTiered(String tierRuleJson, BigDecimal chargeableWeight) {
        try {
            List<Map<String, Object>> rules = JSON.readValue(tierRuleJson,
                    new TypeReference<List<Map<String, Object>>>() {});
            double weight = chargeableWeight.doubleValue();
            for (Map<String, Object> rule : rules) {
                double min = toDouble(rule.get("minWeight"), 0.0);
                double max = toDouble(rule.get("maxWeight"), Double.MAX_VALUE);
                if (weight >= min && weight < max) {
                    double unitPrice = toDouble(rule.get("unitPrice"), 0.0);
                    double basePrice = toDouble(rule.get("basePrice"), 0.0);
                    return BigDecimal.valueOf(basePrice + weight * unitPrice).setScale(2, RoundingMode.HALF_UP);
                }
            }
        } catch (Exception ignored) {}
        return BigDecimal.ZERO;
    }

    /**
     * 工业增值服务费核算：
     * JSON: {
     *   "forklift": { "basePrice": 80.0, "freeTons": 1.0, "perTonPrice": 50.0 },
     *   "upstairs": { "basePrice": 50.0, "perFloorPrice": 20.0 },
     *   "woodenCrate": { "pricePerM3": 120.0 },
     *   "oversizeOverweight": { "surchargeRate": 0.20 }
     * }
     */
    Map<String, BigDecimal> calculateVas(String vasRuleJson, VasRequest vas, BigDecimal chargeableWeight, BigDecimal volumeM3, BigDecimal baseFreight) {
        Map<String, BigDecimal> result = new HashMap<>();
        if (vas == null || vasRuleJson == null || vasRuleJson.isBlank()) {
            return result;
        }
        try {
            Map<String, Map<String, Object>> rules = JSON.readValue(vasRuleJson,
                    new TypeReference<Map<String, Map<String, Object>>>() {});

            if (Boolean.TRUE.equals(vas.forklift()) && rules.containsKey("forklift")) {
                Map<String, Object> cfg = rules.get("forklift");
                double base = toDouble(cfg.get("basePrice"), 80.0);
                double freeTons = toDouble(cfg.get("freeTons"), 1.0);
                double perTon = toDouble(cfg.get("perTonPrice"), 50.0);
                double tons = chargeableWeight != null ? chargeableWeight.doubleValue() / 1000.0 : 0.0;
                double extraTons = Math.max(0.0, tons - freeTons);
                result.put("forklift", BigDecimal.valueOf(base + extraTons * perTon).setScale(2, RoundingMode.HALF_UP));
            }

            if (Boolean.TRUE.equals(vas.upstairs()) && rules.containsKey("upstairs")) {
                Map<String, Object> cfg = rules.get("upstairs");
                double base = toDouble(cfg.get("basePrice"), 50.0);
                double perFloor = toDouble(cfg.get("perFloorPrice"), 20.0);
                int floors = vas.floorCount() != null ? vas.floorCount() : 1;
                double extraFloors = Math.max(0, floors - 1);
                result.put("upstairs", BigDecimal.valueOf(base + extraFloors * perFloor).setScale(2, RoundingMode.HALF_UP));
            }

            if (Boolean.TRUE.equals(vas.woodenCrate()) && rules.containsKey("woodenCrate")) {
                Map<String, Object> cfg = rules.get("woodenCrate");
                double pricePerM3 = toDouble(cfg.get("pricePerM3"), 120.0);
                double vol = volumeM3 != null ? volumeM3.doubleValue() : 0.0;
                result.put("woodenCrate", BigDecimal.valueOf(vol * pricePerM3).setScale(2, RoundingMode.HALF_UP));
            }

            if (Boolean.TRUE.equals(vas.oversize()) && rules.containsKey("oversizeOverweight")) {
                Map<String, Object> cfg = rules.get("oversizeOverweight");
                double rate = toDouble(cfg.get("surchargeRate"), 0.20);
                result.put("oversize", baseFreight.multiply(BigDecimal.valueOf(rate)).setScale(2, RoundingMode.HALF_UP));
            }
        } catch (Exception ignored) {}
        return result;
    }

    /** 体积重量(kg) = 长×宽×高(cm) / volumeFactor；volumeM3 → cm³ ×1e6 */
    BigDecimal volumeWeight(BigDecimal volumeM3, BigDecimal volumeFactor) {
        if (volumeM3 == null || volumeM3.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        if (volumeFactor == null || volumeFactor.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        return volumeM3.multiply(BigDecimal.valueOf(1_000_000)).divide(volumeFactor, 3, RoundingMode.HALF_UP);
    }

    BigDecimal regionSurcharge(String regionRuleJson, String province) {
        if (regionRuleJson == null || regionRuleJson.isBlank() || province == null || province.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            List<Map<String, Object>> rules = JSON.readValue(regionRuleJson,
                    new TypeReference<List<Map<String, Object>>>() {});
            BigDecimal total = BigDecimal.ZERO;
            for (Map<String, Object> rule : rules) {
                Object regionsObj = rule.get("regions");
                if (!(regionsObj instanceof List<?> regions)) continue;
                boolean matched = false;
                for (Object region : regions) {
                    if (region == null) continue;
                    String regionName = String.valueOf(region);
                    if (regionName.equals(province) || province.startsWith(regionName)
                            || (province.length() >= 2 && regionName.startsWith(province))) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) continue;
                Object surcharge = rule.get("surcharge");
                if (surcharge instanceof Number n) total = total.add(new BigDecimal(n.toString()));
                else if (surcharge != null) total = total.add(new BigDecimal(surcharge.toString()));
            }
            return total;
        } catch (Exception ignored) {
        }
        return BigDecimal.ZERO;
    }

    public static String extractProvince(String address) {
        if (address == null || address.isBlank()) return "";
        String trimmed = address.trim();
        for (String full : PROVINCES_FULL) {
            if (trimmed.contains(full)) return full;
        }
        for (String shortName : PROVINCES_SHORT) {
            if (trimmed.startsWith(shortName) || trimmed.contains(shortName)) return shortName;
        }
        return trimmed.length() > 2 ? trimmed.substring(0, 2) : trimmed;
    }

    private static final String[] PROVINCES_FULL = {
            "内蒙古自治区", "广西壮族自治区", "西藏自治区", "宁夏回族自治区", "新疆维吾尔自治区",
            "香港特别行政区", "澳门特别行政区",
            "北京市", "天津市", "上海市", "重庆市",
            "河北省", "山西省", "辽宁省", "吉林省", "黑龙江省", "江苏省", "浙江省", "安徽省",
            "福建省", "江西省", "山东省", "河南省", "湖北省", "湖南省", "广东省", "海南省",
            "四川省", "贵州省", "云南省", "陕西省", "甘肃省", "青海省", "台湾省"
    };
    private static final String[] PROVINCES_SHORT = {
            "内蒙古", "广西", "西藏", "宁夏", "新疆", "香港", "澳门",
            "北京", "天津", "上海", "重庆",
            "河北", "山西", "辽宁", "吉林", "黑龙江", "江苏", "浙江", "安徽",
            "福建", "江西", "山东", "河南", "湖北", "湖南", "广东", "海南",
            "四川", "贵州", "云南", "陕西", "甘肃", "青海", "台湾"
    };

    private static final ObjectMapper JSON = new ObjectMapper();

    private String detail(String pricingType, LogisticsTemplate t, BigDecimal chargeable,
                          BigDecimal volumeWeight, String province, BigDecimal remoteFee,
                          BigDecimal regionFee, BigDecimal threshold, BigDecimal vasTotal,
                          Map<String, BigDecimal> vasFees) {
        List<String> parts = new ArrayList<>();
        parts.add("\"pricingType\":\"" + pricingType + "\"");
        parts.add("\"tierMode\":\"" + (t.getTierMode() != null ? t.getTierMode() : "linear") + "\"");
        parts.add("\"firstPrice\":" + nvl(t.getFirstPrice()));
        if (chargeable != null) parts.add("\"chargeableWeight\":" + chargeable);
        if (volumeWeight != null) parts.add("\"volumeWeight\":" + volumeWeight);
        parts.add("\"continuedPrice\":" + nvl(t.getContinuedPrice()));
        parts.add("\"remoteSurcharge\":" + remoteFee);
        parts.add("\"regionSurcharge\":" + regionFee);
        if (vasTotal != null && vasTotal.compareTo(BigDecimal.ZERO) > 0) {
            parts.add("\"vasTotal\":" + vasTotal);
        }
        if (vasFees != null && !vasFees.isEmpty()) {
            List<String> vasParts = new ArrayList<>();
            vasFees.forEach((k, v) -> vasParts.add("\"" + k + "\":" + v));
            parts.add("\"vasFees\":{" + String.join(",", vasParts) + "}");
        }
        if (t.getMinCharge() != null && t.getMinCharge().compareTo(BigDecimal.ZERO) > 0) {
            parts.add("\"minCharge\":" + t.getMinCharge());
        }
        if (province != null) parts.add("\"province\":\"" + province + "\"");
        if (threshold != null) parts.add("\"freeThreshold\":" + threshold);
        return "{" + String.join(",", parts) + "}";
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static double toDouble(Object obj, double defaultVal) {
        if (obj instanceof Number n) return n.doubleValue();
        if (obj != null) {
            try {
                return Double.parseDouble(obj.toString());
            } catch (Exception ignored) {}
        }
        return defaultVal;
    }
}
