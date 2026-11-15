package com.lanlink.shopping.integration.pricing;

import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.OrderItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单金额拆分（P1-6）唯一算法入口：订单级优惠/运费 → 行级 {@code allocated_discount / paid_amount / freight_amount}。
 *
 * <p>设计约束（见 reports/specs/p1-amount-split.md）：
 * <ul>
 *   <li><b>口径</b>：{@code t_order.total_amount} = 买家实付总额（含运费）；
 *       {@code t_order.goods_amount} = 货款实付（= total − freight，存量=total）。
 *       佣金/包邮门槛/退款货款一律以 goods_amount 为基数，禁止用 total_amount 代替。</li>
 *   <li><b>分摊</b>：按行 {@code subtotal} 比例、HALF_UP 到分、<b>余数追加到最后一行</b>（与
 *       {@code SettlementSyncService.allocateOrderGross} 同口径，但本类独立无 IO、可单测）。</li>
 *   <li><b>固化</b>：下单时一次写入行级列，读时不再重算——退款/售后/佣金都以它为准，可审计。</li>
 *   <li><b>守则</b>：异常脏数据不阻断交易。{@code Σsubtotal=0} 时按行数均摊；goods 可大于 Σsubtotal
 *       （脏数据）时末行可能出现“负优惠”，但三条不变式仍成立，由调用方告警/人工核。</li>
 * </ul>
 *
 * <p>本类为纯静态工具（无 Spring 依赖、无数据库访问），供下单链路、回填 initializer 与单测共用。
 */
public final class OrderAmounts {

    /** 金额统一保留 2 位小数（分） */
    public static final int SCALE = 2;

    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUNDING);

    private OrderAmounts() {
    }

    /**
     * 单行分摊结果（三列均已 {@code setScale(2)}）。
     *
     * @param allocatedDiscount 本行分摊优惠 = subtotal − paidAmount
     * @param paidAmount        本行实付货款
     * @param freightAmount     本行分摊运费
     */
    public record LineAmount(BigDecimal allocatedDiscount, BigDecimal paidAmount, BigDecimal freightAmount) {
    }

    /**
     * 主算法：把订单级 {@code goodsAmount}（货款实付）与 {@code freightAmount}（运费）分摊到各行。
     *
     * <p>不变量（由 {@link #checkInvariants} 强校验）：
     * <ol>
     *   <li>Σ paidAmount == goodsAmount</li>
     *   <li>Σ allocatedDiscount == Σ subtotal − goodsAmount</li>
     *   <li>Σ freightAmount == freightAmount</li>
     * </ol>
     *
     * @param subtotals     各行小计（成交单价×数量；允许 null 元素，按 0 处理；空列表表示无行）
     * @param goodsAmount   订单货款实付（null 按 0；负数抛 IllegalArgumentException）
     * @param freightAmount 订单运费（null 按 0；负数抛 IllegalArgumentException）
     */
    public static List<LineAmount> allocate(List<BigDecimal> subtotals, BigDecimal goodsAmount,
                                            BigDecimal freightAmount) {
        List<BigDecimal> subs = normalizeSubtotals(subtotals);
        BigDecimal goods = normalizeMoney(goodsAmount, "goodsAmount");
        BigDecimal freight = normalizeMoney(freightAmount, "freightAmount");
        int n = subs.size();
        if (n == 0) {
            if (goods.signum() != 0 || freight.signum() != 0) {
                throw new IllegalArgumentException(
                        "无订单行时不允许分摊非零金额: goodsAmount=" + goods + ", freightAmount=" + freight);
            }
            return List.of();
        }
        BigDecimal sumSub = sum(subs);
        BigDecimal[] paid = sumSub.signum() == 0
                ? spreadEvenly(goods, n)
                : allocateProportionally(subs, sumSub, goods);
        BigDecimal[] freightPerLine = sumSub.signum() == 0
                ? spreadEvenly(freight, n)
                : allocateProportionally(subs, sumSub, freight);

        List<LineAmount> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BigDecimal discount = subs.get(i).subtract(paid[i]).setScale(SCALE, ROUNDING);
            out.add(new LineAmount(discount,
                    paid[i].setScale(SCALE, ROUNDING),
                    freightPerLine[i].setScale(SCALE, ROUNDING)));
        }
        return out;
    }

    /**
     * 校验三条不变式；不成立抛 {@link IllegalStateException}（带逐项差额，便于定位坏行）。
     * 不做任何“自动修正”——金额不一致必须暴露，不允许静默吞掉。
     */
    public static void checkInvariants(List<BigDecimal> subtotals, BigDecimal goodsAmount,
                                       BigDecimal freightAmount, List<LineAmount> lines) {
        List<BigDecimal> subs = normalizeSubtotals(subtotals);
        if (subs.size() != (lines == null ? 0 : lines.size())) {
            throw new IllegalStateException("行数不一致: subtotals=" + subs.size()
                    + ", lines=" + (lines == null ? 0 : lines.size()));
        }
        if (lines == null || lines.isEmpty()) {
            if (normalizeMoney(goodsAmount, "goodsAmount").signum() != 0
                    || normalizeMoney(freightAmount, "freightAmount").signum() != 0) {
                throw new IllegalStateException("无订单行但订单金额非零");
            }
            return;
        }
        BigDecimal goods = normalizeMoney(goodsAmount, "goodsAmount");
        BigDecimal freight = normalizeMoney(freightAmount, "freightAmount");
        BigDecimal sumSub = sum(subs);
        BigDecimal sumPaid = ZERO;
        BigDecimal sumFreight = ZERO;
        BigDecimal sumDiscount = ZERO;
        for (LineAmount line : lines) {
            if (line == null) {
                throw new IllegalStateException("分摊结果含 null 行");
            }
            sumPaid = sumPaid.add(nz(line.paidAmount()));
            sumFreight = sumFreight.add(nz(line.freightAmount()));
            sumDiscount = sumDiscount.add(nz(line.allocatedDiscount()));
        }
        assertEqual("Σ行实付 paid_amount", sumPaid, "goods_amount", goods);
        BigDecimal expectedDiscount = sumSub.subtract(goods);
        assertEqual("Σ行优惠 allocated_discount", sumDiscount, "Σsubtotal − goods_amount", expectedDiscount);
        assertEqual("Σ行运费 freight_amount", sumFreight, "订单 freight_amount", freight);
    }

    /**
     * 可退总额（退款上限的唯一口径）：
     * 未发货 → 货款 + 运费；已发货/部分发货 → 仅货款（运费不退）。
     *
     * @param shipped 是否已产生运单/发货
     */
    public static BigDecimal refundableTotal(BigDecimal goodsAmount, BigDecimal freightAmount, boolean shipped) {
        BigDecimal goods = nz(goodsAmount);
        BigDecimal freight = shipped ? ZERO : nz(freightAmount);
        return goods.add(freight).setScale(SCALE, ROUNDING);
    }

    /**
     * 可退总额（实体重载）：存量未回填（goodsAmount=null）时回落到 totalAmount 兜底
     * ——存量单无运费，两者等价；回填后一律读 goodsAmount。
     */
    public static BigDecimal refundableTotal(Order order, boolean shipped) {
        if (order == null) {
            return ZERO;
        }
        BigDecimal goods = order.getGoodsAmount() != null
                ? order.getGoodsAmount()
                : nz(order.getTotalAmount());
        return refundableTotal(goods, order.getFreightAmount(), shipped);
    }

    /**
     * 便捷方法：把分摊结果写回订单行（下单链路 / 回填 initializer 复用），并返回分摊结果。
     * 空列表直接返回空；行元素不应为 null。
     */
    public static List<LineAmount> applyToItems(List<OrderItem> items, BigDecimal goodsAmount,
                                                BigDecimal freightAmount) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<BigDecimal> subs = new ArrayList<>(items.size());
        for (OrderItem item : items) {
            subs.add(item == null || item.getSubtotal() == null
                    ? BigDecimal.ZERO.setScale(SCALE, ROUNDING)
                    : item.getSubtotal());
        }
        List<LineAmount> lines = allocate(subs, goodsAmount, freightAmount);
        for (int i = 0; i < items.size(); i++) {
            OrderItem item = items.get(i);
            if (item == null) {
                continue;
            }
            LineAmount line = lines.get(i);
            item.setAllocatedDiscount(line.allocatedDiscount());
            item.setPaidAmount(line.paidAmount());
            item.setFreightAmount(line.freightAmount());
        }
        return lines;
    }

    // ==================== 内部：分摊 ====================

    private static BigDecimal[] allocateProportionally(List<BigDecimal> subs, BigDecimal sumSub,
                                                       BigDecimal total) {
        int n = subs.size();
        BigDecimal[] out = new BigDecimal[n];
        BigDecimal allocated = ZERO;
        for (int i = 0; i < n - 1; i++) {
            // sub × total / sumSub，直接按“分”四舍五入（等价 round(HALF_UP, 2)）
            BigDecimal share = subs.get(i).multiply(total)
                    .divide(sumSub, SCALE, ROUNDING);
            out[i] = share;
            allocated = allocated.add(share);
        }
        // 末行吸收尾差：保证 Σ == total（分毫不差），这是对账可复现的关键
        out[n - 1] = total.subtract(allocated).setScale(SCALE, ROUNDING);
        return out;
    }

    private static BigDecimal[] spreadEvenly(BigDecimal total, int n) {
        BigDecimal[] out = new BigDecimal[n];
        if (total.signum() == 0) {
            for (int i = 0; i < n; i++) {
                out[i] = ZERO;
            }
            return out;
        }
        BigDecimal base = total.divide(BigDecimal.valueOf(n), SCALE, ROUNDING);
        BigDecimal allocated = ZERO;
        for (int i = 0; i < n - 1; i++) {
            out[i] = base;
            allocated = allocated.add(base);
        }
        out[n - 1] = total.subtract(allocated).setScale(SCALE, ROUNDING);
        return out;
    }

    // ==================== 内部：归一化 / 断言 ====================

    private static List<BigDecimal> normalizeSubtotals(List<BigDecimal> subtotals) {
        if (subtotals == null || subtotals.isEmpty()) {
            return List.of();
        }
        List<BigDecimal> out = new ArrayList<>(subtotals.size());
        for (BigDecimal sub : subtotals) {
            out.add(nz(sub));
        }
        return out;
    }

    /** null → 0；负数 → IllegalArgumentException（负数金额是无意义输入，必须 fail-fast） */
    private static BigDecimal normalizeMoney(BigDecimal value, String name) {
        if (value == null) {
            return ZERO;
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " 不能为负数: " + value);
        }
        return value.setScale(SCALE, ROUNDING);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? ZERO : value.setScale(SCALE, ROUNDING);
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        BigDecimal total = ZERO;
        for (BigDecimal v : values) {
            total = total.add(nz(v));
        }
        return total;
    }

    private static void assertEqual(String leftName, BigDecimal left, String rightName, BigDecimal right) {
        if (left.compareTo(right) != 0) {
            throw new IllegalStateException("金额不变式不成立: " + leftName + "=" + left
                    + " 应等于 " + rightName + "=" + right + "（差额 " + left.subtract(right) + "）");
        }
    }
}
