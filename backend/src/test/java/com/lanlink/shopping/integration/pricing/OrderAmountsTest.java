package com.lanlink.shopping.integration.pricing;

import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.OrderItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单金额拆分（P1-6）：分摊到分/末行吸尾差/三不变式/可退总额。
 *
 * <p>钉住的事：① 3 行不整除时 Σ行实付必须分毫不差等于订单货款；② 尾差只由最后一行吸收；
 * ③ 不变式被篡改必须抛（不允许静默吞）；④ 可退总额“未发货含运费、已发货仅货款”；
 * ⑤ 存量未回填（goodsAmount=null）时回落 totalAmount。
 */
class OrderAmountsTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static final BigDecimal ZERO = bd("0.00");

    @Test
    void threeLinesWithRemainderLastLineAbsorbsTail() {
        // Σsubtotal=60，货款 50 → 10/60、20/60 各四舍五入，末行 = 50 − 已分
        List<BigDecimal> subs = List.of(bd("10.00"), bd("20.00"), bd("30.00"));
        List<OrderAmounts.LineAmount> lines = OrderAmounts.allocate(subs, bd("50.00"), bd("7.00"));

        assertEquals(bd("8.33"), lines.get(0).paidAmount());   // 10×50/60=8.3333 → 8.33
        assertEquals(bd("16.67"), lines.get(1).paidAmount());  // 20×50/60=16.6667 → 16.67
        // 末行吸收尾差：50 − (8.33+16.67) = 25.00（不是四舍五入的 25.00 巧合 → 用不整除例子再断言）
        assertEquals(bd("25.00"), lines.get(2).paidAmount());

        assertEquals(bd("1.17"), lines.get(0).freightAmount());  // 10×7/60=1.1667 → 1.17
        assertEquals(bd("2.33"), lines.get(1).freightAmount());  // 20×7/60=2.3333 → 2.33
        assertEquals(bd("3.50"), lines.get(2).freightAmount());  // 7 − 3.50

        // 优惠 = subtotal − paid
        assertEquals(bd("1.67"), lines.get(0).allocatedDiscount());
        assertEquals(bd("3.33"), lines.get(1).allocatedDiscount());
        assertEquals(bd("5.00"), lines.get(2).allocatedDiscount());

        OrderAmounts.checkInvariants(subs, bd("50.00"), bd("7.00"), lines);
    }

    @Test
    void tailDifferenceIsReallyAbsorbedByLastLine() {
        // 3 行各 33.33（Σ=99.99），货款 100.00？不允许 goods>Σsub 的“正常单”，这里测尾差用 99.98：
        // 每行 33.33×99.98/99.99 = 33.3266… → 33.33 / 33.33 / 末行 99.98−66.66 = 33.32
        List<BigDecimal> subs = List.of(bd("33.33"), bd("33.33"), bd("33.33"));
        List<OrderAmounts.LineAmount> lines = OrderAmounts.allocate(subs, bd("99.98"), ZERO);

        assertEquals(bd("33.33"), lines.get(0).paidAmount());
        assertEquals(bd("33.33"), lines.get(1).paidAmount());
        assertEquals(bd("33.32"), lines.get(2).paidAmount());
        BigDecimal sum = lines.stream().map(OrderAmounts.LineAmount::paidAmount)
                .reduce(ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(bd("99.98")), "Σ行实付必须等于订单货款（分毫不差）");
        OrderAmounts.checkInvariants(subs, bd("99.98"), ZERO, lines);
    }

    @Test
    void sumOfDiscountsEqualsSubtotalMinusGoods() {
        List<BigDecimal> subs = List.of(bd("10.00"), bd("20.00"), bd("30.00"));
        List<OrderAmounts.LineAmount> lines = OrderAmounts.allocate(subs, bd("42.00"), bd("6.00"));

        BigDecimal sumDiscount = lines.stream().map(OrderAmounts.LineAmount::allocatedDiscount)
                .reduce(ZERO, BigDecimal::add);
        assertEquals(0, sumDiscount.compareTo(bd("60.00").subtract(bd("42.00"))));
        OrderAmounts.checkInvariants(subs, bd("42.00"), bd("6.00"), lines);
    }

    @Test
    void invariantsThrowWhenTampered() {
        List<BigDecimal> subs = List.of(bd("10.00"), bd("20.00"), bd("30.00"));
        List<OrderAmounts.LineAmount> lines = OrderAmounts.allocate(subs, bd("50.00"), bd("7.00"));

        List<OrderAmounts.LineAmount> tampered = new ArrayList<>(lines);
        tampered.set(0, new OrderAmounts.LineAmount(
                lines.get(0).allocatedDiscount(),
                lines.get(0).paidAmount().add(bd("0.01")), // 多 1 分
                lines.get(0).freightAmount()));
        IllegalStateException paidErr = assertThrows(IllegalStateException.class,
                () -> OrderAmounts.checkInvariants(subs, bd("50.00"), bd("7.00"), tampered));
        assertTrue(paidErr.getMessage().contains("paid_amount"), paidErr.getMessage());

        List<OrderAmounts.LineAmount> tamperedFreight = new ArrayList<>(lines);
        tamperedFreight.set(1, new OrderAmounts.LineAmount(
                lines.get(1).allocatedDiscount(),
                lines.get(1).paidAmount(),
                lines.get(1).freightAmount().add(bd("0.02"))));
        assertThrows(IllegalStateException.class,
                () -> OrderAmounts.checkInvariants(subs, bd("50.00"), bd("7.00"), tamperedFreight));

        // 行数不一致也必须抛
        assertThrows(IllegalStateException.class,
                () -> OrderAmounts.checkInvariants(subs, bd("50.00"), bd("7.00"), List.of(lines.get(0))));
    }

    @Test
    void zeroSubtotalsSpreadEvenlyAcrossLines() {
        // 脏数据：行小计全 0，但订单有金额 → 按行均摊，末行吸尾差
        List<BigDecimal> subs = List.of(ZERO, ZERO, ZERO);
        List<OrderAmounts.LineAmount> lines = OrderAmounts.allocate(subs, bd("10.00"), bd("1.00"));

        assertEquals(bd("3.33"), lines.get(0).paidAmount());
        assertEquals(bd("3.33"), lines.get(1).paidAmount());
        assertEquals(bd("3.34"), lines.get(2).paidAmount());
        assertEquals(bd("0.33"), lines.get(0).freightAmount());
        assertEquals(bd("0.33"), lines.get(1).freightAmount());
        assertEquals(bd("0.34"), lines.get(2).freightAmount());
        OrderAmounts.checkInvariants(subs, bd("10.00"), bd("1.00"), lines);
    }

    @Test
    void fullDiscountMakesPaidZero() {
        List<BigDecimal> subs = List.of(bd("10.00"), bd("20.00"));
        List<OrderAmounts.LineAmount> lines = OrderAmounts.allocate(subs, ZERO, ZERO);

        assertEquals(0, lines.get(0).paidAmount().signum());
        assertEquals(0, lines.get(1).paidAmount().signum());
        assertEquals(bd("10.00"), lines.get(0).allocatedDiscount());
        assertEquals(bd("20.00"), lines.get(1).allocatedDiscount());
        OrderAmounts.checkInvariants(subs, ZERO, ZERO, lines);
    }

    @Test
    void dirtyGoodsGreaterThanSubtotalStillKeepsInvariants() {
        // 防御：脏数据 goods(15) > Σsubtotal(10) → 不抛，末行可能负优惠，但三条不变式仍成立
        List<BigDecimal> subs = List.of(bd("5.00"), bd("5.00"));
        List<OrderAmounts.LineAmount> lines = OrderAmounts.allocate(subs, bd("15.00"), ZERO);

        assertEquals(0, lines.stream().map(OrderAmounts.LineAmount::paidAmount)
                .reduce(ZERO, BigDecimal::add).compareTo(bd("15.00")));
        OrderAmounts.checkInvariants(subs, bd("15.00"), ZERO, lines);
    }

    @Test
    void negativeGoodsOrFreightFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> OrderAmounts.allocate(List.of(bd("10.00")), bd("-1.00"), ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> OrderAmounts.allocate(List.of(bd("10.00")), ZERO, bd("-0.01")));
    }

    @Test
    void emptyLinesOnlyAllowZeroAmounts() {
        assertEquals(List.of(), OrderAmounts.allocate(List.of(), ZERO, ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> OrderAmounts.allocate(List.of(), bd("1.00"), ZERO));
    }

    @Test
    void refundableTotalIncludesFreightOnlyBeforeShipment() {
        BigDecimal goods = bd("100.00");
        BigDecimal freight = bd("12.00");

        assertEquals(0, OrderAmounts.refundableTotal(goods, freight, false).compareTo(bd("112.00")));
        assertEquals(0, OrderAmounts.refundableTotal(goods, freight, true).compareTo(bd("100.00")));
        // null 金额按 0
        assertEquals(0, OrderAmounts.refundableTotal(null, null, false).compareTo(ZERO));
        assertEquals(0, OrderAmounts.refundableTotal(null, freight, true).compareTo(ZERO));
    }

    @Test
    void refundableTotalEntityFallsBackToTotalAmountBeforeBackfill() {
        Order order = new Order();
        order.setTotalAmount(bd("88.00"));
        // 存量未回填：goodsAmount=null → 回落 totalAmount（旧单无运费，等价）
        assertEquals(0, OrderAmounts.refundableTotal(order, false).compareTo(bd("88.00")));
        assertEquals(0, OrderAmounts.refundableTotal(order, true).compareTo(bd("88.00")));

        order.setGoodsAmount(bd("88.00"));
        order.setFreightAmount(bd("10.00"));
        assertEquals(0, OrderAmounts.refundableTotal(order, false).compareTo(bd("98.00")));
        assertEquals(0, OrderAmounts.refundableTotal(order, true).compareTo(bd("88.00")));
        assertEquals(0, OrderAmounts.refundableTotal((Order) null, false).compareTo(ZERO));
    }

    @Test
    void applyToItemsWritesAllThreeColumns() {
        OrderItem a = new OrderItem();
        a.setSubtotal(bd("10.00"));
        OrderItem b = new OrderItem();
        b.setSubtotal(bd("20.00"));
        OrderItem nullSub = new OrderItem(); // subtotal NULL 也允许（DB 列可空）

        List<OrderItem> items = List.of(a, b, nullSub);
        List<OrderAmounts.LineAmount> lines = OrderAmounts.applyToItems(items, bd("24.00"), bd("3.00"));

        assertEquals(3, lines.size());
        assertEquals(lines.get(0).paidAmount(), a.getPaidAmount());
        assertEquals(lines.get(1).allocatedDiscount(), b.getAllocatedDiscount());
        assertEquals(lines.get(2).freightAmount(), nullSub.getFreightAmount());
        OrderAmounts.checkInvariants(List.of(bd("10.00"), bd("20.00"), ZERO),
                bd("24.00"), bd("3.00"), lines);
    }
}
