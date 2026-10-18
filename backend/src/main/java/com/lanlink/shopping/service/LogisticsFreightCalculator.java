package com.lanlink.shopping.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.entity.LogisticsTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 运费计算引擎（纯逻辑组件，无状态，便于单元测试）。
 *
 * 计费公式（weight 计费）：
 *   chargeableWeight = max(实际重量, 体积重量)；体积重量 = 长×宽×高(cm)/volumeFactor
 *   freight = firstPrice + ceil(max(0, chargeableWeight - firstWeight) / continuedWeight) × continuedPrice
 *           + remoteSurcharge + regionSurcharge
 * 规则：
 *   - orderAmount ≥ freeThreshold(>0 时) → freight = 0（满额包邮）
 *   - pricingType=fixed → freight = firstPrice（固定运费）
 *   - pricingType=piece → 按件：firstPrice + 超出件数 × continuedPrice
 *   - 计费明细快照 {@link CalcResult#detailJson()} 落库留痕保证可审计
 */
@Component
public class LogisticsFreightCalculator {

    /** 运费计算结果（含明细快照） */
    public record CalcResult(BigDecimal freight, String detailJson) {}

    /**
     * 计算运费。
     * @param template 运费模板
     * @param weightKg 实际重量(kg)，可空（fixed 计费忽略）
     * @param volumeM3 体积(m³)，可空（无体积重时传 0）
     * @param province 收件省份（区域加价匹配）
     * @param remote   是否偏远地区
     * @param orderAmount 订单金额（满额包邮判定）
     * @param quantity 件数（piece 计费用，默认 1）
     */
    public CalcResult calculate(LogisticsTemplate template, BigDecimal weightKg, BigDecimal volumeM3,
                                String province, boolean remote, BigDecimal orderAmount, Integer quantity) {
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
                    detail("free_threshold", template, null, null, province, remoteSurcharge, BigDecimal.ZERO, threshold));
        }

        String pricingType = template.getPricingType() == null ? "weight" : template.getPricingType();
        BigDecimal freight;
        BigDecimal chargeable = null;
        BigDecimal volumeWeight = null;

        switch (pricingType) {
            case "fixed" -> freight = firstPrice;
            case "piece" -> {
                int qty = quantity == null || quantity < 1 ? 1 : quantity;
                freight = firstPrice.add(contPrice.multiply(BigDecimal.valueOf(Math.max(0, qty - 1))));
            }
            default -> { // weight / volume 统一按计费重处理
                BigDecimal actual = nvl(weightKg);
                volumeWeight = volumeWeight(volumeM3, volFactor);
                chargeable = actual.max(volumeWeight);
                BigDecimal extra = chargeable.subtract(firstWeight).max(BigDecimal.ZERO);
                int steps = contWeight.compareTo(BigDecimal.ZERO) > 0
                        ? extra.divide(contWeight, 0, RoundingMode.CEILING).intValue() : 0;
                freight = firstPrice.add(contPrice.multiply(BigDecimal.valueOf(steps)));
            }
        }
        BigDecimal regionSurcharge = regionSurcharge(template.getRegionRuleJson(), province);
        freight = freight.add(remoteSurcharge).add(regionSurcharge);
        if (freight.compareTo(BigDecimal.ZERO) < 0) freight = BigDecimal.ZERO;
        freight = freight.setScale(2, RoundingMode.HALF_UP);
        return new CalcResult(freight, detail(pricingType, template, chargeable, volumeWeight,
                province, remoteSurcharge, regionSurcharge, threshold));
    }

    /** 体积重量(kg) = 长×宽×高(cm) / volumeFactor；volumeM3 → cm³ ×1e6 */
    BigDecimal volumeWeight(BigDecimal volumeM3, BigDecimal volumeFactor) {
        if (volumeM3 == null || volumeM3.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        if (volumeFactor == null || volumeFactor.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        return volumeM3.multiply(BigDecimal.valueOf(1_000_000)).divide(volumeFactor, 3, RoundingMode.HALF_UP);
    }

    /**
     * 区域加价：regionRuleJson = [{"regions":["新疆","西藏"],"surcharge":10.0}, ...]。
     * 用 Jackson 正规解析（Issue 修复：正则分段匹配在省份含数字/特殊字符时会误匹配）。
     * 匹配规则：regions 列表中任一项与 province 精确相等或 province 以该项开头（如"内蒙古"匹配"内蒙古自治区"）。
     * 多规则同时命中时累加（Issue 修复：如规则「新疆」+10 与「新疆维吾尔自治区」+5 同时命中，
     * 应合计 15 而非只取第一条）；同一规则内多个 region 命中只计一次 surcharge。
     * JSON 解析失败不影响主流程（返回 0）。
     */
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
                        break; // 同一规则内多个 region 命中只计一次
                    }
                }
                if (!matched) continue;
                Object surcharge = rule.get("surcharge");
                if (surcharge instanceof Number n) total = total.add(new BigDecimal(n.toString()));
                else if (surcharge != null) total = total.add(new BigDecimal(surcharge.toString()));
            }
            return total;
        } catch (Exception ignored) {
            // 解析失败按 0 处理，运费计算不被规则格式错误中断
        }
        return BigDecimal.ZERO;
    }

    /**
     * 从完整收货地址中提取省级行政区名（Issue 修复：order.getAddress() 是完整地址，
     * 直接传给 regionSurcharge 会因包含详细地址而匹配失败）。
     *
     * 匹配策略：先匹配带后缀的完整省级名（省/市/自治区/特别行政区），
     * 再匹配简称（如"广东"、"新疆"）；均未命中时兜底取地址前 2 字。
     */
    static String extractProvince(String address) {
        if (address == null || address.isBlank()) return "";
        String trimmed = address.trim();
        // 带后缀的完整省级名（优先，避免"黑龙江省"误判为"黑龙"）
        for (String full : PROVINCES_FULL) {
            if (trimmed.contains(full)) return full;
        }
        // 简称匹配（含 Issue 提到的省份含数字场景，如"内蒙古1区"）
        for (String shortName : PROVINCES_SHORT) {
            if (trimmed.startsWith(shortName) || trimmed.contains(shortName)) return shortName;
        }
        return trimmed.length() > 2 ? trimmed.substring(0, 2) : trimmed;
    }

    /** 省级行政区全称（带后缀），按长度降序保证"内蒙古自治区"优先于"内蒙古" */
    private static final String[] PROVINCES_FULL = {
            "内蒙古自治区", "广西壮族自治区", "西藏自治区", "宁夏回族自治区", "新疆维吾尔自治区",
            "香港特别行政区", "澳门特别行政区",
            "北京市", "天津市", "上海市", "重庆市",
            "河北省", "山西省", "辽宁省", "吉林省", "黑龙江省", "江苏省", "浙江省", "安徽省",
            "福建省", "江西省", "山东省", "河南省", "湖北省", "湖南省", "广东省", "海南省",
            "四川省", "贵州省", "云南省", "陕西省", "甘肃省", "青海省", "台湾省"
    };
    /** 省级行政区简称 */
    private static final String[] PROVINCES_SHORT = {
            "内蒙古", "广西", "西藏", "宁夏", "新疆", "香港", "澳门",
            "北京", "天津", "上海", "重庆",
            "河北", "山西", "辽宁", "吉林", "黑龙江", "江苏", "浙江", "安徽",
            "福建", "江西", "山东", "河南", "湖北", "湖南", "广东", "海南",
            "四川", "贵州", "云南", "陕西", "甘肃", "青海", "台湾"
    };

    /** Jackson 复用实例（线程安全） */
    private static final ObjectMapper JSON = new ObjectMapper();

    private String detail(String pricingType, LogisticsTemplate t, BigDecimal chargeable,
                          BigDecimal volumeWeight, String province, BigDecimal remoteFee,
                          BigDecimal regionFee, BigDecimal threshold) {
        List<String> parts = new ArrayList<>();
        parts.add("\"pricingType\":\"" + pricingType + "\"");
        parts.add("\"firstPrice\":" + nvl(t.getFirstPrice()));
        if (chargeable != null) parts.add("\"chargeableWeight\":" + chargeable);
        if (volumeWeight != null) parts.add("\"volumeWeight\":" + volumeWeight);
        parts.add("\"continuedPrice\":" + nvl(t.getContinuedPrice()));
        parts.add("\"remoteSurcharge\":" + remoteFee);
        parts.add("\"regionSurcharge\":" + regionFee);
        if (province != null) parts.add("\"province\":\"" + province + "\"");
        if (threshold != null) parts.add("\"freeThreshold\":" + threshold);
        return "{" + String.join(",", parts) + "}";
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
