package com.lanlink.shopping.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.entity.LogisticsTemplate;
import com.lanlink.shopping.mapper.LogisticsTemplateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 运费报价唯一入口（P1-5 批次 1，MVP）。
 *
 * <p>为什么需要它：现状三套运费口径并存——结算链路运费恒 0、AI 报价硬编码 ¥35
 * （{@code AiToolExecutionService:320-324}）、商户发货才按模板真算（{@code LogisticsWaybillService}）。
 * 本类收敛为「一处算运费」，后续结算页/下单/AI 报价只允许调它。
 *
 * <p><b>MVP 三模式（不依赖商品重量）</b>——现库 {@code t_product} 无 weight/volume 列，
 * 按重量计费需先补商品重量字段（下一批次），因此本版只做：
 * <ol>
 *   <li>{@code fixed}：按单固定价 = {@code first_price}；</li>
 *   <li>{@code piece}：按件计价 = {@code first_price + continued_price × (件数−1)}；</li>
 *   <li>{@code region}：其余类型（weight/volume 无重量数据时的退化）= 基础价 + 地区加价
 *       （{@code region_rule_json}，与 {@link LogisticsFreightCalculator#regionSurcharge} 同匹配语义）。</li>
 * </ol>
 * 三模式统一叠加：满额包邮（基数必须用 {@code goods_amount}，见下）→ 地区加价 → 最低起运价。
 *
 * <p><b>包邮基数用 goods_amount 的理由</b>：满额包邮是“买够多少商品”促销，若把运费计入基数，
 * 会形成“为免运费而付运费”的循环/套利；且与佣金、行分摊基数统一为同一个货款口径。
 *
 * <p>模板归属：{@code t_logistics_template} 目前挂 {@code provider_id}（平台运营维护），
 * 无商户/商品绑定列，故本版统一取平台默认模板（配置 {@code app.freight.default-template-id}，缺省 1）。
 * {@code merId} 入参预留给下一批次的商户/商品模板绑定，当前不参与计算。
 *
 * <p><b>总开关 {@code app.freight.enabled}（默认 false，上线防资损）</b>：
 * 现库有 11 条历史物流模板，若默认模板 id=1 直接生效，会给全站每单凭空加运费（典型历史模板首价 ¥12）。
 * 因此生产入口 {@link #quote(Long, BigDecimal, String, int)} 最前面先判开关：关闭时直接返回 0，
 * <b>不查模板、不打包邮、不加价</b>，行为与“运费恒 0”的上线前状态完全一致。
 * 启用顺序：先配置可用的默认模板 → 验证 quote 金额 → 再置 {@code app.freight.enabled=true} → 灰度。
 *
 * <p>失败策略：<b>fail-open</b>——模板缺失/停用/脏 JSON 一律回落 0 或基础价，绝不阻断下单。
 */
@Service
public class FreightQuoteService {

    private static final Logger log = LoggerFactory.getLogger(FreightQuoteService.class);

    /** 金额统一保留 2 位小数（分） */
    public static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUNDING);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LogisticsTemplateMapper templateMapper;
    private final long defaultTemplateId;
    /** 运费总开关：默认 false（关闭=全站运费 0，不查模板），配置好默认模板并验证后再开启 */
    private final boolean enabled;

    public FreightQuoteService(LogisticsTemplateMapper templateMapper,
                               @Value("${app.freight.default-template-id:1}") long defaultTemplateId,
                               @Value("${app.freight.enabled:false}") boolean enabled) {
        this.templateMapper = templateMapper;
        this.defaultTemplateId = defaultTemplateId;
        this.enabled = enabled;
    }

    /**
     * 唯一对外入口：按平台默认模板报价。
     *
     * <p>总开关关闭（默认）时直接返回 0，且<b>不会触达模板 Mapper</b>（首屏/下单零额外查询）。
     * 开启后默认模板不存在或已停用时同样返回 0 并 WARN（fail-open，不按 id 猜、不阻断下单）。
     *
     * @param merId       商户ID（预留给模板绑定，当前未使用）
     * @param goodsAmount 订单货款实付（包邮门槛基数；null 按 0）
     * @param region      收货地区（省名，用于 region_rule_json 匹配；null/空 = 无地区加价）
     * @param pieces      件数（piece 模式用；&lt;=0 按 1 件）
     * @return 运费（元，2 位小数）；总开关关闭/模板不可用时 0
     */
    public BigDecimal quote(Long merId, BigDecimal goodsAmount, String region, int pieces) {
        // ① 总开关（默认 false）：关闭即 0，不查模板、不打包邮、不加价——上线前行为不变，防“默认模板给全站加运费”
        if (!enabled) {
            return ZERO;
        }
        // ② 模板归属校验：enabled=true 但默认模板缺失/停用 → fail-open=0 + WARN，绝不静默按 id 猜
        LogisticsTemplate template = resolveDefaultTemplate();
        if (template == null) {
            log.warn("[freight-quote] 运费总开关已开启，但默认运费模板不可用（不存在或查询失败），运费按 0 处理: templateId={}",
                    defaultTemplateId);
            return ZERO;
        }
        if (template.getStatus() != null && template.getStatus() != 1) {
            log.warn("[freight-quote] 运费总开关已开启，但默认运费模板已停用（status={}），运费按 0 处理: templateId={}",
                    template.getStatus(), defaultTemplateId);
            return ZERO;
        }
        return quote(template, goodsAmount, region, pieces);
    }

    /**
     * 纯计算（无 IO，可单测）：三模式 + 满额包邮 + 地区加价 + 最低起运价。
     *
     * <p>注意：本重载<b>不读取总开关</b>（纯计算函数，供已拿到模板的调用方/单测使用）；
     * 生产入口是 {@link #quote(Long, BigDecimal, String, int)}，总开关与模板归属校验都在那里。
     *
     * @param template 运费模板（null 或非启用 → 0）
     */
    public BigDecimal quote(LogisticsTemplate template, BigDecimal goodsAmount, String region, int pieces) {
        if (template == null) {
            return ZERO;
        }
        if (template.getStatus() != null && template.getStatus() != 1) {
            return ZERO; // 停用模板不计费（与 LogisticsWaybillService 校验语义一致）
        }
        BigDecimal goods = nz(goodsAmount);

        // 1) 满额包邮：基数=货款实付（不含运费），先短路
        BigDecimal threshold = nz(template.getFreeThreshold());
        if (threshold.signum() > 0 && goods.compareTo(threshold) >= 0) {
            return ZERO;
        }

        int qty = Math.max(1, pieces);
        String pricingType = template.getPricingType() == null ? "weight" : template.getPricingType();
        BigDecimal firstPrice = nz(template.getFirstPrice());

        // 2) 三模式基础运费
        BigDecimal freight;
        switch (pricingType) {
            case "fixed" -> freight = firstPrice;
            case "piece" -> freight = firstPrice.add(
                    nz(template.getContinuedPrice()).multiply(BigDecimal.valueOf(Math.max(0, qty - 1))));
            default -> freight = firstPrice; // region 模式（weight/volume 无重量数据退化）：基础价 + 地区加价
        }

        // 3) 地区加价（三模式统一叠加，与既有引擎 regionSurcharge 语义一致）
        freight = freight.add(regionSurcharge(template.getRegionRuleJson(), region));

        // 4) 最低起运价保底
        BigDecimal minCharge = nz(template.getMinCharge());
        if (minCharge.signum() > 0 && freight.compareTo(minCharge) < 0) {
            freight = minCharge;
        }

        if (freight.signum() < 0) {
            freight = ZERO; // 配置脏（负价）兜底
        }
        return freight.setScale(SCALE, ROUNDING);
    }

    /** 平台默认模板；查询失败/不存在一律返回 null（fail-open → 0 运费），只告警不抛 */
    LogisticsTemplate resolveDefaultTemplate() {
        try {
            return templateMapper == null ? null : templateMapper.selectById(defaultTemplateId);
        } catch (Exception e) {
            log.warn("[freight-quote] 默认运费模板查询失败，运费按 0 处理: templateId={}, err={}",
                    defaultTemplateId, e.getMessage());
            return null;
        }
    }

    /**
     * 地区加价：解析 {@code region_rule_json = [{"regions":["内蒙古","新疆"],"surcharge":15}]}，
     * 命中省份累加加价。匹配语义与 {@link LogisticsFreightCalculator#regionSurcharge} 一致
     * （相等 / 前缀互含），脏 JSON 一律返回 0 不阻断。
     */
    BigDecimal regionSurcharge(String regionRuleJson, String region) {
        if (regionRuleJson == null || regionRuleJson.isBlank() || region == null || region.isBlank()) {
            return ZERO;
        }
        try {
            JsonNode root = MAPPER.readTree(regionRuleJson);
            if (root == null || !root.isArray()) {
                return ZERO;
            }
            BigDecimal total = ZERO;
            for (JsonNode rule : root) {
                JsonNode regions = rule.get("regions");
                if (regions == null || !regions.isArray()) {
                    continue;
                }
                boolean matched = false;
                for (JsonNode regionNode : regions) {
                    if (regionNode == null || regionNode.isNull()) {
                        continue;
                    }
                    if (regionMatches(regionNode.asText(), region)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    continue;
                }
                JsonNode surcharge = rule.get("surcharge");
                if (surcharge != null && !surcharge.isNull()) {
                    total = total.add(surcharge.isNumber()
                            ? surcharge.decimalValue()
                            : new BigDecimal(surcharge.asText().trim()));
                }
            }
            return total;
        } catch (Exception e) {
            log.warn("[freight-quote] region_rule_json 解析失败，按无地区加价处理: {}", e.getMessage());
            return ZERO;
        }
    }

    private static boolean regionMatches(String configured, String region) {
        if (configured == null || configured.isBlank()) {
            return false;
        }
        String cfg = configured.trim();
        String target = region.trim();
        return cfg.equals(target) || target.startsWith(cfg)
                || (cfg.length() >= 2 && cfg.startsWith(target));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? ZERO : value;
    }
}
