package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.dto.CheckoutDTO;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.OrderItem;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.integration.pricing.PricingFacade;
import com.lanlink.shopping.mapper.CartMapper;
import com.lanlink.shopping.mapper.OrderItemMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.membership.service.MembershipService;
import com.lanlink.shopping.module.rfq.entity.Rfq;
import com.lanlink.shopping.module.rfq.entity.RfqQuote;
import com.lanlink.shopping.module.rfq.mapper.RfqEventMapper;
import com.lanlink.shopping.module.rfq.mapper.RfqMapper;
import com.lanlink.shopping.module.rfq.mapper.RfqQuoteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RFQ 接受转订单（S5，最大风险接缝）。
 *
 * <p>口径：成交价=报价单价（不叠阶梯/会员/促销/券/积分）、报价 CAS SUBMITTED→ACCEPTED 抢闸、
 * 兄弟报价同事务 REJECTED、RFQ 置 ORDERED、幂等键 rfq-accept:{quoteId} 重放返回同一订单、
 * 预期总额与报价不一致按防篡改拒绝并留痕。
 */
class OrderRfqAcceptTest {

    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private ProductMapper productMapper;
    private UserMapper userMapper;
    private MessageService messageService;
    private IdempotentService idempotentService;
    private TamperGuardService tamperGuardService;
    private CreditTermService creditTermService;
    private RfqMapper rfqMapper;
    private RfqQuoteMapper rfqQuoteMapper;
    private RfqEventMapper rfqEventMapper;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        productMapper = mock(ProductMapper.class);
        userMapper = mock(UserMapper.class);
        messageService = mock(MessageService.class);
        idempotentService = mock(IdempotentService.class);
        tamperGuardService = mock(TamperGuardService.class);
        creditTermService = mock(CreditTermService.class);
        rfqMapper = mock(RfqMapper.class);
        rfqQuoteMapper = mock(RfqQuoteMapper.class);
        rfqEventMapper = mock(RfqEventMapper.class);
        orderService = new OrderService(
                mock(CartMapper.class), productMapper, orderMapper, orderItemMapper, userMapper, messageService,
                mock(IdentityService.class), mock(AuditService.class), mock(PricingFacade.class),
                mock(ApplicationEventPublisher.class), mock(MembershipService.class), creditTermService,
                idempotentService, tamperGuardService,
                mock(com.lanlink.shopping.module.campaign.service.CampaignService.class),
                mock(com.lanlink.shopping.module.risk.service.RiskEventService.class));
        ReflectionTestUtils.setField(orderService, "rfqMapper", rfqMapper);
        ReflectionTestUtils.setField(orderService, "rfqQuoteMapper", rfqQuoteMapper);
        ReflectionTestUtils.setField(orderService, "rfqEventMapper", rfqEventMapper);

        when(idempotentService.findResultRef("rfq-accept", "55")).thenReturn(null);
        when(idempotentService.reserve(any(), any(), any(), any())).thenReturn(1L);
        when(orderMapper.insert(any(Order.class))).thenReturn(1);
        when(productMapper.deductStockAtomic(any(), anyInt())).thenReturn(1);
        when(rfqMapper.markOrdered(anyLong())).thenReturn(1);
    }

    private static Rfq rfq(String status) {
        Rfq r = new Rfq();
        r.setRfqId(100L);
        r.setRfqNo("RFQ20261109000001");
        r.setUserId(7L);
        r.setTitle("采购螺纹钢");
        r.setQuantity(new BigDecimal("20"));
        r.setStatus(status);
        return r;
    }

    private static RfqQuote quote(String status) {
        RfqQuote q = new RfqQuote();
        q.setQuoteId(55L);
        q.setRfqId(100L);
        q.setMerId(8L);
        q.setProdId(101L);
        q.setUnitPrice(new BigDecimal("98.50"));
        q.setQuantity(new BigDecimal("20"));
        q.setTotalAmount(new BigDecimal("1970.00"));
        q.setStatus(status);
        q.setValidUntil(LocalDateTime.now().plusDays(2));
        return q;
    }

    private static Product product() {
        Product p = new Product();
        p.setProdId(101L);
        p.setMerId(8L);
        p.setIndId(1L);
        p.setTitle("HRB400 Φ20");
        p.setPrice(new BigDecimal("100.00"));
        p.setStock(100);
        p.setStatus(1);
        return p;
    }

    private static CheckoutDTO dto() {
        CheckoutDTO d = new CheckoutDTO();
        d.setPayType("corporate");
        d.setReceiver("张三");
        d.setPhone("13900000001");
        d.setAddress("上海市浦东新区某路 1 号");
        return d;
    }

    @Test
    void acceptCreatesOrderAtQuotePriceAndClaimsQuote() {
        when(rfqQuoteMapper.selectById(55L)).thenReturn(quote("SUBMITTED"));
        when(rfqMapper.selectByIdForUpdate(100L)).thenReturn(rfq("QUOTED"));
        when(rfqQuoteMapper.acceptAtomic(55L)).thenReturn(1);
        when(userMapper.selectById(7L)).thenReturn(new User());
        when(productMapper.selectById(101L)).thenReturn(product());

        Order order = orderService.checkoutFromQuote(7L, 55L, dto());

        // 成交价 = 报价总额，促销/券/积分全部为 0
        assertEquals(0, new BigDecimal("1970.00").compareTo(order.getTotalAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(order.getPromoDiscount()));
        assertNull(order.getCouponId());
        assertEquals(0, BigDecimal.ZERO.compareTo(order.getPointDeductAmount()));
        assertEquals("RFQ20261109000001", order.getRfqNo());
        assertEquals(Long.valueOf(55L), order.getRfqQuoteId());
        // 明细与总额同源
        ArgumentCaptor<OrderItem> item = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper).insert(item.capture());
        assertEquals(0, new BigDecimal("98.50").compareTo(item.getValue().getPrice()));
        assertEquals(Integer.valueOf(20), item.getValue().getQuantity());
        assertEquals(0, new BigDecimal("1970.00").compareTo(item.getValue().getSubtotal()));
        verify(productMapper).deductStockAtomic(101L, 20);
        // 抢闸 + 兄弟报价拒绝 + RFQ 置成交
        verify(rfqQuoteMapper).acceptAtomic(55L);
        verify(rfqQuoteMapper).rejectSiblings(100L, 55L);
        verify(rfqMapper).markOrdered(100L);
        verify(idempotentService).bound(1L, order.getOrderNo());
    }

    @Test
    void foreignQuoteIsInvisibleToThisBuyer() {
        when(rfqQuoteMapper.selectById(55L)).thenReturn(quote("SUBMITTED"));
        when(rfqMapper.selectByIdForUpdate(100L)).thenReturn(rfq("QUOTED"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkoutFromQuote(99L, 55L, dto()));

        assertEquals(Integer.valueOf(404), ex.getCode());
        verify(rfqQuoteMapper, never()).acceptAtomic(anyLong());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void alreadyAcceptedQuoteIsRejected() {
        when(rfqQuoteMapper.selectById(55L)).thenReturn(quote("ACCEPTED"));
        when(rfqMapper.selectByIdForUpdate(100L)).thenReturn(rfq("ORDERED"));

        assertEquals(Integer.valueOf(400), assertThrows(BusinessException.class,
                () -> orderService.checkoutFromQuote(7L, 55L, dto())).getCode());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void expiredQuoteCannotBeAccepted() {
        RfqQuote expired = quote("SUBMITTED");
        expired.setValidUntil(LocalDateTime.now().minusMinutes(1));
        when(rfqQuoteMapper.selectById(55L)).thenReturn(expired);
        when(rfqMapper.selectByIdForUpdate(100L)).thenReturn(rfq("QUOTED"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkoutFromQuote(7L, 55L, dto()));
        assertEquals(Integer.valueOf(400), ex.getCode());
        assertTrue(ex.getMessage().contains("有效期"));
        verify(rfqQuoteMapper, never()).acceptAtomic(anyLong());
    }

    @Test
    void expectedTotalMismatchIsTamperAndLeavesTrace() {
        when(rfqQuoteMapper.selectById(55L)).thenReturn(quote("SUBMITTED"));
        when(rfqMapper.selectByIdForUpdate(100L)).thenReturn(rfq("QUOTED"));
        CheckoutDTO d = dto();
        d.setExpectedTotal(new BigDecimal("1.00"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkoutFromQuote(7L, 55L, d));

        assertTrue(ex.getMessage().contains("报价总额不一致"));
        verify(tamperGuardService).report(eq(7L), eq("rfq"), eq("expectedTotal"),
                eq("1.00"), eq("1970.00"), anyString(), any());
        verify(rfqQuoteMapper, never()).acceptAtomic(anyLong());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void couponAndPointsAreRejectedOnQuoteCheckout() {
        when(rfqQuoteMapper.selectById(55L)).thenReturn(quote("SUBMITTED"));
        when(rfqMapper.selectByIdForUpdate(100L)).thenReturn(rfq("QUOTED"));
        CheckoutDTO coupon = dto();
        coupon.setCouponId(9L);
        assertEquals(Integer.valueOf(400), assertThrows(BusinessException.class,
                () -> orderService.checkoutFromQuote(7L, 55L, coupon)).getCode());

        CheckoutDTO points = dto();
        points.setUsePoints(1000);
        assertEquals(Integer.valueOf(400), assertThrows(BusinessException.class,
                () -> orderService.checkoutFromQuote(7L, 55L, points)).getCode());
        verify(rfqQuoteMapper, never()).acceptAtomic(anyLong());
    }

    @Test
    void fractionalQuantityIsRejected() {
        RfqQuote fractional = quote("SUBMITTED");
        fractional.setQuantity(new BigDecimal("20.5"));
        when(rfqQuoteMapper.selectById(55L)).thenReturn(fractional);
        when(rfqMapper.selectByIdForUpdate(100L)).thenReturn(rfq("QUOTED"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkoutFromQuote(7L, 55L, dto()));
        assertEquals(Integer.valueOf(400), ex.getCode());
        assertTrue(ex.getMessage().contains("整数"));
        verify(rfqQuoteMapper, never()).acceptAtomic(anyLong());
    }

    @Test
    void replayWithSameIdempotentKeyReturnsFirstOrder() {
        when(idempotentService.findResultRef("rfq-accept", "55")).thenReturn("ORD-EXIST");
        Order prior = new Order();
        prior.setOrderNo("ORD-EXIST");
        prior.setUserId(7L);
        prior.setTotalAmount(new BigDecimal("1970.00"));
        when(orderMapper.selectById("ORD-EXIST")).thenReturn(prior);

        Order again = orderService.checkoutFromQuote(7L, 55L, dto());

        assertEquals("ORD-EXIST", again.getOrderNo());
        verify(idempotentService, never()).reserve(any(), any(), any(), any());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(rfqQuoteMapper, never()).acceptAtomic(anyLong());
    }

    @Test
    void terminalRfqStatusBlocksAccept() {
        when(rfqQuoteMapper.selectById(55L)).thenReturn(quote("SUBMITTED"));
        when(rfqMapper.selectByIdForUpdate(100L)).thenReturn(rfq("ORDERED"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkoutFromQuote(7L, 55L, dto()));
        assertEquals(Integer.valueOf(400), ex.getCode());
        verify(rfqQuoteMapper, never()).acceptAtomic(anyLong());
    }

    @Test
    void stockRaceLosingZeroRowsFailsWholeAccept() {
        when(rfqQuoteMapper.selectById(55L)).thenReturn(quote("SUBMITTED"));
        when(rfqMapper.selectByIdForUpdate(100L)).thenReturn(rfq("QUOTED"));
        when(rfqQuoteMapper.acceptAtomic(55L)).thenReturn(1);
        when(userMapper.selectById(7L)).thenReturn(new User());
        when(productMapper.selectById(101L)).thenReturn(product());
        when(productMapper.deductStockAtomic(101L, 20)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkoutFromQuote(7L, 55L, dto()));
        assertTrue(ex.getMessage().contains("并发扣减冲突"));
        // 事务回滚语义：幂等令牌释放，允许重试
        verify(idempotentService).release(1L);
    }

    @Test
    void termPayTypeMarksPrePaidAndCreatesBill() {
        when(rfqQuoteMapper.selectById(55L)).thenReturn(quote("SUBMITTED"));
        when(rfqMapper.selectByIdForUpdate(100L)).thenReturn(rfq("QUOTED"));
        when(rfqQuoteMapper.acceptAtomic(55L)).thenReturn(1);
        when(userMapper.selectById(7L)).thenReturn(new User());
        when(productMapper.selectById(101L)).thenReturn(product());
        CheckoutDTO term = dto();
        term.setPayType("term");

        Order order = orderService.checkoutFromQuote(7L, 55L, term);

        assertEquals(Integer.valueOf(1), order.getPayStatus());
        verify(creditTermService).createBillForOrder(7L, order.getOrderNo(), new BigDecimal("1970.00"));
    }

    @Test
    void losingCasReturns409WithoutOrder() {
        when(rfqQuoteMapper.selectById(55L)).thenReturn(quote("SUBMITTED"));
        when(rfqMapper.selectByIdForUpdate(100L)).thenReturn(rfq("QUOTED"));
        when(userMapper.selectById(7L)).thenReturn(new User());
        when(productMapper.selectById(101L)).thenReturn(product());
        when(rfqQuoteMapper.acceptAtomic(55L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkoutFromQuote(7L, 55L, dto()));

        assertEquals(Integer.valueOf(409), ex.getCode());
        assertTrue(ex.getMessage().contains("已被接受"));
        verify(rfqQuoteMapper, never()).rejectSiblings(anyLong(), anyLong());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void replayByAnotherUserIsRejected() {
        when(idempotentService.findResultRef("rfq-accept", "55")).thenReturn("ORD-EXIST");
        Order prior = new Order();
        prior.setOrderNo("ORD-EXIST");
        prior.setUserId(7L);
        when(orderMapper.selectById("ORD-EXIST")).thenReturn(prior);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.checkoutFromQuote(99L, 55L, dto()));

        assertEquals(Integer.valueOf(409), ex.getCode());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void acceptWritesAuditEvent() {
        when(rfqQuoteMapper.selectById(55L)).thenReturn(quote("SUBMITTED"));
        when(rfqMapper.selectByIdForUpdate(100L)).thenReturn(rfq("QUOTED"));
        when(rfqQuoteMapper.acceptAtomic(55L)).thenReturn(1);
        when(userMapper.selectById(7L)).thenReturn(new User());
        when(productMapper.selectById(101L)).thenReturn(product());

        orderService.checkoutFromQuote(7L, 55L, dto());

        ArgumentCaptor<com.lanlink.shopping.module.rfq.entity.RfqEvent> event =
                ArgumentCaptor.forClass(com.lanlink.shopping.module.rfq.entity.RfqEvent.class);
        verify(rfqEventMapper).insert(event.capture());
        assertEquals("ACCEPT", event.getValue().getEventType());
        assertEquals(Long.valueOf(55L), event.getValue().getQuoteId());
        assertEquals(Long.valueOf(100L), event.getValue().getRfqId());
    }
}