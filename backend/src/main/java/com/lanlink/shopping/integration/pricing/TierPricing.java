package com.lanlink.shopping.integration.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶梯价（B2B 批量价）的唯一解析入口。
 *
 * <p>背景：`t_product.tier_price_json` 早就在表里，但下单、购物车、AI 报价三处各写各的：
 * 种子数据用 `qty` 作为档位键，AI 侧只认 `minQty`/`min_qty`，于是"有阶梯价"的商品在
 * AI 报价里静默变成空、在结算里被完全忽略——同一个商品出现两个价格口径。
 *
 * <p>这里统一成一条规则：**取满足数量条件的最大档位单价**，解析失败一律回落到基准价并且不阻断交易
 * （脏 JSON 是数据问题，不该让买家下不了单）。匹配档位比基准价还贵时也回落基准价，
 * 防止运营把档位填反造成"批得越多越贵"。
 */
public final class TierPricing {

    private static final Logger log = LoggerFactory.getLogger(TierPricing.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一个档位：达到 minQty 件时单价为 unitPrice */
    public record Tier(int minQty, BigDecimal unitPrice) {
    }

    /**
     * 计价结果。
     *
     * @param appliedTierQty 命中的档位门槛；0 表示未命中任何档位、用的是基准价
     * @param unitPrice      实际成交单价
     */
    public record Resolved(int appliedTierQty, BigDecimal unitPrice) {
    }

    private TierPricing() {
    }

    /** 解析阶梯价 JSON；任何异常都返回空列表（调用方回落基准价） */
    public static List<Tier> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            log.warn("[tier-price] 阶梯价 JSON 解析失败，按基准价处理: {}", abbreviate(json));
            return List.of();
        }
        if (root == null || !root.isArray()) {
            log.warn("[tier-price] 阶梯价不是数组，按基准价处理: {}", abbreviate(json));
            return List.of();
        }
        // 同一门槛出现多次时保留最低价：宁可少赚也不能因重复配置多收
        Map<Integer, BigDecimal> byQty = new LinkedHashMap<>();
        for (JsonNode node : root) {
            int minQty = readInt(node, "qty", "minQty", "min_qty");
            BigDecimal price = readDecimal(node, "price", "unitPrice", "unit_price");
            if (minQty <= 0 || price == null || price.signum() <= 0) {
                continue;
            }
            byQty.merge(minQty, price, (a, b) -> a.min(b));
        }
        List<Tier> tiers = new ArrayList<>(byQty.size());
        byQty.forEach((qty, price) -> tiers.add(new Tier(qty, price.setScale(2, RoundingMode.HALF_UP))));
        tiers.sort(Comparator.comparingInt(Tier::minQty));
        return tiers;
    }

    /** 按购买数量取成交单价；无阶梯价、解析失败或未达最低档时回落基准价 */
    public static Resolved resolve(BigDecimal basePrice, String tierJson, int qty) {
        BigDecimal base = basePrice == null ? BigDecimal.ZERO : basePrice;
        if (qty <= 0) {
            return new Resolved(0, base);
        }
        Tier hit = null;
        for (Tier t : parse(tierJson)) {
            if (qty >= t.minQty()) {
                hit = t;
            }
        }
        if (hit == null) {
            return new Resolved(0, base);
        }
        if (base.signum() > 0 && hit.unitPrice().compareTo(base) > 0) {
            log.warn("[tier-price] 档位价高于基准价，疑似配置颠倒，按基准价: qty={} tier={} base={}",
                    hit.minQty(), hit.unitPrice(), base);
            return new Resolved(0, base);
        }
        return new Resolved(hit.minQty(), hit.unitPrice());
    }

    /** 下一档（用于"再买 N 件可低至 ¥X"提示）；没有更高档位时返回 null */
    public static Tier next(String tierJson, int qty) {
        for (Tier t : parse(tierJson)) {
            if (qty < t.minQty()) {
                return t;
            }
        }
        return null;
    }

    /**
     * 起订量（MOQ）：阶梯配置里最小档的 minQty；无阶梯配置返回 0（不限）。
     * 写侧（购物车/下单）用它做前置拦截，读侧解析失败一律回落 0，不阻断交易。
     */
    public static int minOrderQty(String tierJson) {
        List<Tier> tiers = parse(tierJson);
        return tiers.isEmpty() ? 0 : tiers.get(0).minQty();
    }

    /**
     * 对外契约（P1-17）：详情/商户编辑回显出站形态，统一键名 minQty/price，
     * 按 minQty 升序返回（买家侧展示时自行倒序渲染）。解析走 {@link #parse} 的容错口径。
     */
    public static List<com.lanlink.shopping.entity.Product.TierPrice> toContract(String tierJson) {
        return parse(tierJson).stream()
                .map(t -> new com.lanlink.shopping.entity.Product.TierPrice(t.minQty(), t.unitPrice()))
                .toList();
    }

    /**
     * 写侧契约校验 + 归一化落库（商户发布/编辑）：
     * ≥1 条、minQty 为正整数且（归一后）严格递增、price 递减或相等且 ≤ 基准价，
     * 返回规范 JSON（minQty 升序、两位小数）；无档位返回 null（清空阶梯）。
     * 任何非法输入抛 {@link BusinessException}，文案直接面向商户。
     */
    public static String encodeContract(List<com.lanlink.shopping.entity.Product.TierPrice> tiers,
                                        BigDecimal basePrice) {
        if (tiers == null || tiers.isEmpty()) {
            return null;
        }
        for (com.lanlink.shopping.entity.Product.TierPrice t : tiers) {
            if (t == null || t.minQty() < 1) {
                throw new BusinessException("阶梯价数量需为正整数（起订数量 ≥1）");
            }
            if (t.price() == null || t.price().signum() <= 0) {
                throw new BusinessException("阶梯价单价需为大于 0 的数值（第 " + t.minQty() + " 件档）");
            }
        }
        List<com.lanlink.shopping.entity.Product.TierPrice> sorted = tiers.stream()
                .sorted(Comparator.comparingInt(com.lanlink.shopping.entity.Product.TierPrice::minQty))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            int prevQty = sorted.get(i - 1).minQty();
            int curQty = sorted.get(i).minQty();
            if (curQty == prevQty) {
                throw new BusinessException("阶梯档位数量重复：" + curQty + " 件出现了多次");
            }
            if (sorted.get(i).price().compareTo(sorted.get(i - 1).price()) > 0) {
                throw new BusinessException("阶梯价单价需随数量递减或持平：" + curQty + " 件档（"
                        + sorted.get(i).price().setScale(2, RoundingMode.HALF_UP) + "）不能高于 "
                        + prevQty + " 件档（" + sorted.get(i - 1).price().setScale(2, RoundingMode.HALF_UP) + "）");
            }
        }
        if (basePrice != null && basePrice.signum() > 0) {
            BigDecimal top = sorted.get(sorted.size() - 1).price();
            if (top.compareTo(basePrice) > 0) {
                throw new BusinessException("阶梯价单价（" + top.setScale(2, RoundingMode.HALF_UP)
                        + "）不能高于商品单价（" + basePrice.setScale(2, RoundingMode.HALF_UP) + "）");
            }
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sorted.size(); i++) {
            com.lanlink.shopping.entity.Product.TierPrice t = sorted.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"minQty\":").append(t.minQty())
              .append(",\"price\":").append(t.price().setScale(2, RoundingMode.HALF_UP).toPlainString()).append('}');
        }
        sb.append(']');
        if (sb.length() > MAX_JSON_LEN) {
            throw new BusinessException("阶梯价档位过多（存储上限 " + MAX_JSON_LEN + " 字符），请减少档位");
        }
        return sb.toString();
    }

    /** t_product.tier_price_json VARCHAR(500)（schema.sql:205），写侧同口径封顶 */
    private static final int MAX_JSON_LEN = 500;

    private static int readInt(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) {
                if (v.isNumber()) {
                    return v.asInt();
                }
                if (v.isTextual()) {
                    try {
                        return Integer.parseInt(v.asText().trim());
                    } catch (NumberFormatException ignore) {
                        // 换下一个候选键
                    }
                }
            }
        }
        return 0;
    }

    private static BigDecimal readDecimal(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v == null || v.isNull()) {
                continue;
            }
            if (v.isNumber()) {
                return v.decimalValue();
            }
            if (v.isTextual()) {
                try {
                    return new BigDecimal(v.asText().trim());
                } catch (NumberFormatException ignore) {
                    // 换下一个候选键
                }
            }
        }
        return null;
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}
