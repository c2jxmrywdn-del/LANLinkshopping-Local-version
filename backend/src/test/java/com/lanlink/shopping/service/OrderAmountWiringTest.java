package com.lanlink.shopping.service;

import com.lanlink.shopping.dto.CheckoutDTO;
import com.lanlink.shopping.entity.Cart;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.OrderItem;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.integration.pricing.OrderAmounts;
import com.lanlink.shopping.integration.pricing.PricingFacade;
import com.lanlink.shopping.mapper.CartMapper;
import com.lanlink.shopping.mapper.OrderItemMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.membership.service.MembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-6/P1-5 接线：下单固化 goods/freight/行实付 + 退款按行实付 + 可退上限含运费规则。
 *
 * <p>「改前失败」依据（本类首跑于接线前）：
 * <ul>
 *   <li>接线前 {@code OrderService} 无 freightQuoteService 字段 → 反射注入失败 / 运费恒 0；</li>
 *   <li>接线前 {@code t_order.goods_amount/freight_amount} 不写 → null 断言失败；</li>
 *   <li>接线前行 {@code paid_amount} 不写、{@code fullRefundSettle} 按 subtotal 退 → 断言失败。</li>
 * </ul>
 */
class OrderAmountWiringTest {

    private CartMapper cartMapper;
    private ProductMapper productMapper;
    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private UserMapper userMapper;
    private MessageService messageService;
    private MembershipService membershipService;
    private CreditTermService creditTermService;
    private IdempotentService idempotentService;
    private TamperGuardService tamperGuardService;
    private com.lanlink.shopping.module.campaign.service.CampaignService campaignService;
    private com.lanlink.shopping.module.risk.service.RiskEventService riskEventService;
    private PricingFacade pricingFacade;
    private FreightQuoteService freightQuoteService;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        cartMapper = mock(CartMapper.class);
        productMapper = mock(ProductMapper.class);
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        userMapper = mock(UserMapper.class);
        messageService = mock(MessageService.class);
        membershipService = mock(MembershipService.class);
        creditTermService = mock(CreditTermService.class);
        idempotentService = mock(IdempotentService.class);
        tamperGuardService = mock(TamperGuardService.class);
        campaignService = mock(com.lanlink.shopping.module.campaign.service.CampaignService.class);
        riskEventService = mock(com.lanlink.shopping.module.risk.service.RiskEventService.class);
        pricingFacade = mock(PricingFacade.class);
        freightQuoteService = mock(FreightQuoteService.class);

        orderService = new OrderService(
                cartMapper, productMapper, orderMapper, orderItemMapper, userMapper, messageService,
                mock(IdentityService.class), mock(AuditService.class), pricingFacade,
                mock(ApplicationEventPublisher.class), membershipService, creditTermService,
                idempotentService, tamperGuardService, campaignService, riskEventService);
        // 接线前的分支：无该字段时 ReflectionTestUtils 抛 IllegalStateException → 本类首跑即红
        ReflectionTestUtils.setField(orderService, "freightQuoteService", freightQuoteService);
        // P1-3：库存回补统一走 StockService（幂等）；本类只验证金额口径，注入 mock 即可
        orderService.setStockService(mock(StockService.class));

        when(idempotentService.reserve(any(), any(), any(), any())).thenReturn(1L);
        when(orderMapper.insert(any(Order.class))).thenReturn(1);
        when(productMapper.deductStockAtomic(any(), anyInt())).thenReturn(1);
        when(membershipService.refundPoints(any(), any())).thenReturn(0);
        when(membershipService.reverseEarn(any(), any())).thenReturn(0);
        when(freightQuoteService.quote(any(Long.class), any(BigDecimal.class), any(), anyInt()))
                .thenReturn(new BigDecimal("7.00"));
    }

    private static User user() {
        User u = new User();
        u.setUserId(7L);
        u.setRoleId(2L);
        return u;
    }

    private static CheckoutDTO dto() {
        CheckoutDTO d = new CheckoutDTO();
        d.setPayType("corporate");
        d.setReceiver("张三");
        d.setPhone("13900000001");
        d.setAddress("上海市浦东新区某路 1 号");
        d.setIdempotentKey("idem-amount-test");
        return d;
    }

    private static Cart cart(long cartId, long prodId, int qty) {
        Cart c = new Cart();
        c.setCartId(cartId);
        c.setUserId(7L);
        c.setProdId(prodId);
        c.setQuantity(qty);
        c.setChecked(1);
        return c;
    }

    private static Product product(long prodId, String price) {
        Product p = new Product();
        p.setProdId(prodId);
        p.setMerId(9L);
        p.setIndId(1L);
        p.setTitle("P-" + prodId);
        p.setPrice(new BigDecimal(price));
        p.setStock(100);
        p.setStatus(1);
        return p;
    }

    private List<OrderItem> insertedItems(int expectedCount) {
        ArgumentCaptor<OrderItem> cap = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper, times(expectedCount)).insert(cap.capture());
        return cap.getAllValues();
    }

    /** 无优惠：goods=Σsubtotal，freight=7 → total = goods + 7；行 paid 和 == goods。 */
    @Test
    void checkoutPersistsGoodsFreightAndLinePaidAmounts() {
        when(userMapper.selectById(7L)).thenReturn(user());
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(11L, 101L, 2)));
        when(productMapper.selectById(101L)).thenReturn(product(101L, "100.00"));
        when(pricingFacade.quote(any(), any(), any(), anyBoolean(), anyList())).thenAnswer(inv ->
                new PricingFacade.Quote(inv.getArgument(1), BigDecimal.ZERO, BigDecimal.ZERO,
                        PricingFacade.SOURCE_PROMOTION, null));

        Order order = orderService.checkout(7L, dto());

        assertEquals(0, new BigDecimal("200.00").compareTo(order.getGoodsAmount()));
        assertEquals(0, new BigDecimal("7.00").compareTo(order.getFreightAmount()));
        assertEquals(0, new BigDecimal("207.00").compareTo(order.getTotalAmount()), "total = goods + freight");

        List<OrderItem> items = insertedItems(1);
        assertEquals(0, new BigDecimal("200.00").compareTo(items.get(0).getPaidAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(items.get(0).getAllocatedDiscount()));
        assertEquals(0, new BigDecimal("7.00").compareTo(items.get(0).getFreightAmount()));
        OrderAmounts.checkInvariants(List.of(new BigDecimal("200.00")),
                order.getGoodsAmount(), order.getFreightAmount(),
                List.of(new OrderAmounts.LineAmount(items.get(0).getAllocatedDiscount(),
                        items.get(0).getPaidAmount(), items.get(0).getFreightAmount())));
        verify(creditTermService, never()).createBillForOrder(any(), any(), any());
    }

    /** 尾差订单：3 行 33.33+33.33+33.34，促销 0.01 → goods=99.99；行实付和分毫不差、末行吸尾差。 */
    @Test
    void tailDifferenceOrderKeepsInvariantsOnPersistedItems() {
        when(userMapper.selectById(7L)).thenReturn(user());
        when(cartMapper.selectList(any())).thenReturn(List.of(
                cart(11L, 101L, 1), cart(12L, 102L, 1), cart(13L, 103L, 1)));
        when(productMapper.selectById(101L)).thenReturn(product(101L, "33.33"));
        when(productMapper.selectById(102L)).thenReturn(product(102L, "33.33"));
        when(productMapper.selectById(103L)).thenReturn(product(103L, "33.34"));
        when(pricingFacade.quote(any(), any(), any(), anyBoolean(), anyList())).thenAnswer(inv ->
                new PricingFacade.Quote(inv.getArgument(1), new BigDecimal("0.01"), BigDecimal.ZERO,
                        PricingFacade.SOURCE_PROMOTION, null));

        Order order = orderService.checkout(7L, dto());

        assertEquals(0, new BigDecimal("99.99").compareTo(order.getGoodsAmount()));
        assertEquals(0, new BigDecimal("106.99").compareTo(order.getTotalAmount()));
        List<OrderItem> items = insertedItems(3);
        List<BigDecimal> subtotals = new ArrayList<>();
        List<OrderAmounts.LineAmount> lines = new ArrayList<>();
        BigDecimal sumPaid = BigDecimal.ZERO;
        for (OrderItem it : items) {
            subtotals.add(it.getSubtotal());
            lines.add(new OrderAmounts.LineAmount(it.getAllocatedDiscount(), it.getPaidAmount(),
                    it.getFreightAmount()));
            sumPaid = sumPaid.add(it.getPaidAmount());
            assertNotNull(it.getPaidAmount(), "行实付必须落库（接线前为 null）");
        }
        assertEquals(0, new BigDecimal("99.99").compareTo(sumPaid), "Σ行实付 == goods_amount");
        assertEquals(0, new BigDecimal("0.01").compareTo(
                items.stream().map(OrderItem::getAllocatedDiscount).reduce(BigDecimal.ZERO, BigDecimal::add)));
        assertEquals(0, new BigDecimal("7.00").compareTo(
                items.stream().map(OrderItem::getFreightAmount).reduce(BigDecimal.ZERO, BigDecimal::add)));
        assertDoesNotThrow(() -> OrderAmounts.checkInvariants(
                subtotals, order.getGoodsAmount(), order.getFreightAmount(), lines));
    }

    /** 已发货订单：退款全额判定按“可退总额=goods”，100 元退完即 success（旧逻辑按 total=110 判 partial）。 */
    @Test
    void applyRefundResultUsesRefundableTotalForShippedOrder() {
        Order o = new Order();
        o.setOrderNo("ORD-R");
        o.setUserId(7L);
        o.setPayStatus(1);
        o.setOrderStatus(1); // 已发货：运费不退
        o.setTotalAmount(new BigDecimal("110.00"));
        o.setGoodsAmount(new BigDecimal("100.00"));
        o.setFreightAmount(new BigDecimal("10.00"));
        o.setRefundStatus("merchant_approved");
        when(orderMapper.selectById("ORD-R")).thenReturn(o);

        orderService.applyRefundResult("ORD-R", "RID-1", new BigDecimal("100.00"), true);

        assertEquals("success", o.getRefundStatus(), "已发货订单可退上限=goods_amount(100)，退满即 success");
        assertEquals(0, new BigDecimal("100.00").compareTo(o.getRefundAmount()));
    }

    /** 全额退款：行退款额 == paid_amount（实付）而非 subtotal（原价）。 */
    @Test
    void fullRefundSettleWritesPaidAmountNotSubtotal() {
        Order o = new Order();
        o.setOrderNo("ORD-F");
        o.setUserId(7L);
        o.setPayType("corporate");
        o.setPayStatus(1);
        o.setOrderStatus(0);
        o.setTotalAmount(new BigDecimal("80.00"));
        o.setGoodsAmount(new BigDecimal("80.00"));
        o.setFreightAmount(BigDecimal.ZERO);

        OrderItem item = new OrderItem();
        item.setItemId(501L);
        item.setOrderNo("ORD-F");
        item.setProdId(101L);
        item.setQuantity(1);
        item.setSubtotal(new BigDecimal("100.00"));
        item.setPaidAmount(new BigDecimal("80.00"));
        item.setAllocatedDiscount(new BigDecimal("20.00"));
        item.setFreightAmount(BigDecimal.ZERO);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item));

        orderService.fullRefundSettle(o);

        ArgumentCaptor<OrderItem> cap = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper).updateById(cap.capture());
        assertEquals(0, new BigDecimal("80.00").compareTo(cap.getValue().getRefundAmount()),
                "行退款额必须按 paid_amount 实付，不得按 subtotal 原价");
        assertEquals("refunded", cap.getValue().getRefundStatus());
        assertEquals(Integer.valueOf(5), o.getOrderStatus());
        assertEquals(Integer.valueOf(2), o.getPayStatus());
    }

    /** 存量单（goods_amount 未回填）全额退款仍按 total_amount 兜底，不因 null 崩。 */
    @Test
    void fullRefundSettleFallsBackToSubtotalWhenPaidAmountMissing() {
        Order o = new Order();
        o.setOrderNo("ORD-L");
        o.setUserId(7L);
        o.setPayType("corporate");
        o.setPayStatus(1);
        o.setOrderStatus(0);
        o.setTotalAmount(new BigDecimal("100.00"));

        OrderItem legacy = new OrderItem();
        legacy.setOrderNo("ORD-L");
        legacy.setProdId(101L);
        legacy.setQuantity(1);
        legacy.setSubtotal(new BigDecimal("100.00")); // paid_amount null（未接线前/未回填存量）
        when(orderItemMapper.selectList(any())).thenReturn(List.of(legacy));

        assertDoesNotThrow(() -> orderService.fullRefundSettle(o));
        assertEquals(0, new BigDecimal("100.00").compareTo(legacy.getRefundAmount()));
    }

    /** P1-3 上线阻断修复：expectedTotal 只比对货款 goods_amount，不含服务端计算的运费。 */
    @Test
    void expectedTotalComparesGoodsAmountAndExcludesServerFreight() {
        when(userMapper.selectById(7L)).thenReturn(user());
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(11L, 101L, 2)));
        when(productMapper.selectById(101L)).thenReturn(product(101L, "100.00"));
        when(pricingFacade.quote(any(), any(), any(), anyBoolean(), anyList())).thenAnswer(inv ->
                new PricingFacade.Quote(inv.getArgument(1), BigDecimal.ZERO, BigDecimal.ZERO,
                        PricingFacade.SOURCE_PROMOTION, null));
        when(tamperGuardService.checkNumeric(any(), any(), any(), any(), any(), any())).thenReturn(true);

        CheckoutDTO d = dto();
        d.setExpectedTotal(new BigDecimal("200.00")); // 货款；若误比 total(200+运费7=207) 本用例会失败
        assertDoesNotThrow(() -> orderService.checkout(7L, d));

        ArgumentCaptor<Number> serverValue = ArgumentCaptor.forClass(Number.class);
        verify(tamperGuardService).checkNumeric(eq(7L), eq("order"), eq("goodsAmount"),
                eq(new BigDecimal("200.00")), serverValue.capture(), isNull());
        assertEquals(0, new BigDecimal("200.00").compareTo(new BigDecimal(serverValue.getValue().toString())),
                "防篡改比对基准必须是 goods_amount(200)，不得含服务端运费(7)");
    }
}
