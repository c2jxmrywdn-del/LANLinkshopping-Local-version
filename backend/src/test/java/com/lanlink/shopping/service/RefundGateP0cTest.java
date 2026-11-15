package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.dto.refund.RefundReviewRequest;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.OrderItem;
import com.lanlink.shopping.entity.OrderRma;
import com.lanlink.shopping.entity.RefundApproval;
import com.lanlink.shopping.mapper.LogisticsTraceMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.OrderItemMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.mapper.OrderRmaMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.mapper.RefundApprovalMapper;
import com.lanlink.shopping.mapper.SupportTicketMapper;
import com.lanlink.shopping.payment.PaymentProperties;
import com.lanlink.shopping.payment.alipay.AlipayPayClient;
import com.lanlink.shopping.payment.dto.RefundRequest;
import com.lanlink.shopping.payment.service.PaymentLogService;
import com.lanlink.shopping.payment.service.PaymentService;
import com.lanlink.shopping.payment.stripe.StripePayClient;
import com.lanlink.shopping.payment.wallet.entity.WalletLog;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import com.lanlink.shopping.payment.wechat.WechatPayClient;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * P0-C 退款出款收口回归测试。
 *
 * <p>每个用例都是「改前失败 / 改后通过」的守卫性断言：
 * <ol>
 *   <li>{@code rejected} 状态的订单即使有平台复核凭证也必须被拦在渠道之前（改前会出款）；</li>
 *   <li>商户验货通过、仅退款初审通过都不再直接出款（改前会直接 {@code paymentService.refund}）；</li>
 *   <li>平台复核通过后才出款且只出一次（重复复核幂等、凭证原子消费）；</li>
 *   <li>出款 SQL 契约：rejected/none/applied 不在白名单（改前在）。</li>
 * </ol>
 */
class RefundGateP0cTest {

    // ---- 出款侧（PaymentService）----
    private PaymentService paymentService;
    private OrderMapper orderMapper;
    private OrderService orderService;
    private WalletService walletService;
    private PaymentLogService payLog;
    private RefundApprovalMapper refundApprovalMapper;

    // ---- 售后侧（OrderRmaService）----
    private OrderRmaService rmaService;
    private OrderRmaMapper rmaMapper;
    private OrderItemMapper orderItemMapper;
    private ProductMapper productMapper;
    private MerchantMapper merchantMapper;
    private MessageService messageService;
    private SupportTicketMapper supportTicketMapper;
    private PaymentService payoutSpy;
    private StockService stockService;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderService = mock(OrderService.class);
        walletService = mock(WalletService.class);
        payLog = mock(PaymentLogService.class);
        refundApprovalMapper = mock(RefundApprovalMapper.class);
        PaymentProperties props = new PaymentProperties();
        props.setMock(true);
        paymentService = new PaymentService(props, mock(WechatPayClient.class), mock(AlipayPayClient.class),
                mock(StripePayClient.class), orderService, orderMapper, payLog, walletService);
        paymentService.setRefundApprovalMapper(refundApprovalMapper);

        rmaMapper = mock(OrderRmaMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        productMapper = mock(ProductMapper.class);
        merchantMapper = mock(MerchantMapper.class);
        messageService = mock(MessageService.class);
        supportTicketMapper = mock(SupportTicketMapper.class);
        stockService = mock(StockService.class);
        payoutSpy = mock(PaymentService.class);
        rmaService = new OrderRmaService(rmaMapper, orderMapper, orderItemMapper, productMapper,
                merchantMapper, mock(LogisticsWaybillMapper.class), mock(LogisticsTraceMapper.class),
                payoutSpy, messageService);
        rmaService.setRefundApprovalMapper(refundApprovalMapper);
        rmaService.setAuditService(mock(AuditService.class));
        rmaService.setSupportTicketMapper(supportTicketMapper);
        // P1-3：库存回补统一走 StockService（幂等）
        rmaService.setStockService(stockService);
    }

    // ==================== 1. 出款 SQL 契约 ====================

    private static String sqlOf(String method, Class<?>... params) throws Exception {
        Method m = OrderMapper.class.getMethod(method, params);
        Update update = m.getAnnotation(Update.class);
        assertNotNull(update, method + " 必须带 @Update");
        return String.join(" ", update.value()).replaceAll("\\s+", " ").trim();
    }

    @Test
    void guardSql_neverAllowsRejectedOrUnreviewedStates() throws Exception {
        String sql = sqlOf("markRefundProcessingAtomic", String.class);
        assertTrue(sql.contains("refund_status = 'processing'"), "必须原子置 processing: " + sql);
        // 改前白名单含 applied/partial/none/rejected/merchant_approved —— 这里逐个钉死被摘除的终态
        assertFalse(sql.contains("'rejected'"), "rejected 是终态，绝不能再进入出款白名单: " + sql);
        assertFalse(sql.contains("'none'"), "none（无退款依据）不得出款: " + sql);
        assertFalse(sql.contains("'applied'"), "applied（商户未初审）不得出款: " + sql);
        assertTrue(sql.contains(
                        "refund_status IN ('merchant_approved', 'shipping_back', 'received', 'partial')"),
                "出款白名单必须精确匹配审批链完成态: " + sql);
    }

    // ==================== 2. 平台出款入口守卫 ====================

    private Order order(String refundStatus, String payStatus, BigDecimal total) {
        Order o = new Order();
        o.setOrderNo("ORD-P0C");
        o.setUserId(9L);
        o.setPayStatus(1);
        o.setOrderStatus(0);
        o.setPayType("corporate");
        o.setPayChannel("wallet");
        o.setTotalAmount(total);
        o.setRefundStatus(refundStatus);
        return o;
    }

    private RefundApproval approval(long id, String action, String amount, int consumed) {
        RefundApproval a = new RefundApproval();
        a.setId(id);
        a.setOrderNo("ORD-P0C");
        a.setStage("PLATFORM");
        a.setAction(action);
        a.setAmount(amount == null ? null : new BigDecimal(amount));
        a.setConsumed(consumed);
        return a;
    }

    @Test
    void rejectedRefund_isBlockedBeforeAnyChannelCall_evenWithApprovalToken() {
        // 改前：markRefundProcessingAtomic 白名单含 rejected → 直接进入渠道退款（出款推翻驳回）
        when(orderMapper.selectById("ORD-P0C")).thenReturn(order("rejected", "paid", new BigDecimal("100.00")));
        when(refundApprovalMapper.findLatestPlatform("ORD-P0C"))
                .thenReturn(approval(1L, "APPROVE", "100.00", 0));

        RefundRequest req = new RefundRequest();
        req.setOrderNo("ORD-P0C");
        req.setReason("试图推翻驳回");

        BusinessException ex = assertThrows(BusinessException.class, () -> paymentService.refund(req, 3L));
        assertEquals(403, ex.getCode(), "驳回状态必须以 403 拒绝出款");
        verify(orderMapper, never()).markRefundProcessingAtomic(anyString());
        verify(refundApprovalMapper, never()).consume(anyLong());
        verify(walletService, never()).refundToWallet(any(), any(), anyString(), anyString());
    }

    @Test
    void merchantApprovedOrder_withoutPlatformApproval_isBlocked() {
        when(orderMapper.selectById("ORD-P0C")).thenReturn(order("merchant_approved", "paid", new BigDecimal("100.00")));
        when(refundApprovalMapper.findLatestPlatform("ORD-P0C")).thenReturn(null);

        RefundRequest req = new RefundRequest();
        req.setOrderNo("ORD-P0C");

        BusinessException ex = assertThrows(BusinessException.class, () -> paymentService.refund(req, 3L));
        assertEquals(403, ex.getCode(), "缺平台复核记录必须 403");
        verify(orderMapper, never()).markRefundProcessingAtomic(anyString());
        verify(walletService, never()).refundToWallet(any(), any(), anyString(), anyString());
    }

    @Test
    void consumedApproval_blocksSecondPayout() {
        when(orderMapper.selectById("ORD-P0C")).thenReturn(order("merchant_approved", "paid", new BigDecimal("100.00")));
        when(refundApprovalMapper.findLatestPlatform("ORD-P0C"))
                .thenReturn(approval(1L, "APPROVE", "100.00", 1)); // 已被上一次出款消费

        RefundRequest req = new RefundRequest();
        req.setOrderNo("ORD-P0C");

        BusinessException ex = assertThrows(BusinessException.class, () -> paymentService.refund(req, 3L));
        assertEquals(409, ex.getCode(), "已消费的复核凭证必须 409 拒绝重复出款");
        verify(walletService, never()).refundToWallet(any(), any(), anyString(), anyString());
    }

    @Test
    void approvalAmountMismatch_blocksPayout() {
        when(orderMapper.selectById("ORD-P0C")).thenReturn(order("merchant_approved", "paid", new BigDecimal("100.00")));
        when(refundApprovalMapper.findLatestPlatform("ORD-P0C"))
                .thenReturn(approval(1L, "APPROVE", "50.00", 0));

        RefundRequest req = new RefundRequest();
        req.setOrderNo("ORD-P0C");
        req.setAmount(new BigDecimal("80.00")); // 复核只批了 50，出款 80 → 必须拒绝

        BusinessException ex = assertThrows(BusinessException.class, () -> paymentService.refund(req, 3L));
        assertEquals(403, ex.getCode(), "出款金额必须与复核锁定金额一致");
        verify(refundApprovalMapper, never()).consume(anyLong());
        verify(walletService, never()).refundToWallet(any(), any(), anyString(), anyString());
    }

    @Test
    void approvedRefund_paysOnceAndConsumesApprovalToken() {
        when(orderMapper.selectById("ORD-P0C")).thenReturn(order("merchant_approved", "paid", new BigDecimal("100.00")));
        when(orderMapper.markRefundProcessingAtomic("ORD-P0C")).thenReturn(1);
        when(refundApprovalMapper.findLatestPlatform("ORD-P0C"))
                .thenReturn(approval(1L, "APPROVE", "100.00", 0));
        when(refundApprovalMapper.consume(1L)).thenReturn(1);
        WalletLog wl = new WalletLog();
        wl.setId(5L);
        when(walletService.refundToWallet(eq(9L), any(), eq("ORD-P0C"), anyString())).thenReturn(wl);

        RefundRequest req = new RefundRequest();
        req.setOrderNo("ORD-P0C");
        req.setReason("平台复核通过");

        assertTrue(paymentService.refund(req, 3L));
        verify(refundApprovalMapper, times(1)).consume(1L); // 一次复核只消费一次
        verify(walletService, times(1)).refundToWallet(eq(9L), any(), eq("ORD-P0C"), anyString());
        verify(orderService).applyRefundResult(eq("ORD-P0C"), anyString(), any(), eq(true));
    }

    // ==================== 3. 商户侧不再直连出款 ====================

    @Test
    void merchantInspectPassed_onlyRestoresStock_andNeverCallsChannelRefund() {
        OrderRma rma = new OrderRma();
        rma.setRmaNo("RMA-P0C");
        rma.setOrderNo("ORD-P0C");
        rma.setUserId(7L);
        rma.setMerId(10L);
        rma.setStatus("shipping_back");
        rma.setApplyAmount(new BigDecimal("120.00"));
        when(rmaMapper.selectOne(any())).thenReturn(rma);

        Merchant merchant = new Merchant();
        merchant.setMerId(10L);
        merchant.setUserId(88L);
        when(merchantMapper.selectOne(any())).thenReturn(merchant);

        Order order = order("shipping_back", "paid", new BigDecimal("120.00"));
        when(orderMapper.selectById("ORD-P0C")).thenReturn(order);
        when(rmaMapper.update(any(), any())).thenReturn(1);

        OrderItem item = new OrderItem();
        item.setProdId(101L);
        item.setQuantity(3);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item));

        com.lanlink.shopping.dto.rma.MerchantInspectDTO dto =
                new com.lanlink.shopping.dto.rma.MerchantInspectDTO();
        dto.setInspectStatus("passed");
        dto.setResult("验货合格");
        rmaService.merchantInspect(88L, "RMA-P0C", dto);

        // P1-3：库存回补改为委托 StockService（幂等），不再直调 productMapper
        verify(stockService).restoreByOrder(
                eq(StockService.BIZ_RMA_RESTOCK), eq("RMA-P0C"), eq("ORD-P0C"), eq(88L), any());
        verify(productMapper, never()).restoreStockAtomic(anyLong(), anyInt());
        // 改前：这里会调用 paymentService.refund(...) 直接打款；改后必须 0 次
        verify(payoutSpy, never()).refund(any(), any());
        verify(rmaMapper, never()).settleSuccessAtomic(anyString(), any(), any(), any());
        verify(rmaMapper).update(any(), any()); // inspected → platform_pending
    }

    // ==================== 4. 平台复核：出款一次且幂等 ====================

    @Test
    void platformReview_paysExactlyOnce_andRepeatedReviewIsIdempotent() {
        OrderRma pending = new OrderRma();
        pending.setRmaNo("RMA-P0C");
        pending.setOrderNo("ORD-P0C");
        pending.setUserId(7L);
        pending.setMerId(10L);
        pending.setStatus(OrderRmaService.RMA_STATUS_PLATFORM_PENDING);
        pending.setApplyAmount(new BigDecimal("120.00"));
        OrderRma settled = new OrderRma();
        settled.setRmaNo("RMA-P0C");
        settled.setOrderNo("ORD-P0C");
        settled.setUserId(7L);
        settled.setStatus("success");
        when(rmaMapper.selectOne(any())).thenReturn(pending, settled, settled);
        when(rmaMapper.update(any(), any())).thenReturn(1);
        when(payoutSpy.refund(any(RefundRequest.class), eq(3L))).thenReturn(true);
        Order order = order("shipping_back", "paid", new BigDecimal("120.00"));
        when(orderMapper.selectById("ORD-P0C")).thenReturn(order);

        RefundReviewRequest req = new RefundReviewRequest();
        req.setRemark("平台复核通过");
        OrderRma first = rmaService.platformApproveRma(3L, "RMA-P0C", req);
        assertEquals("success", first.getStatus());
        // 重复复核：已结案直接返回，不再出款
        OrderRma second = rmaService.platformApproveRma(3L, "RMA-P0C", req);
        assertEquals("success", second.getStatus());

        verify(payoutSpy, times(1)).refund(any(RefundRequest.class), eq(3L)); // 只出款一次
        verify(rmaMapper, times(1)).settleSuccessAtomic(eq("RMA-P0C"), any(), isNull(), isNull());
        verify(refundApprovalMapper, times(1)).insert(argThat((RefundApproval a) ->
                "PLATFORM".equals(a.getStage()) && "APPROVE".equals(a.getAction())));
    }

    @Test
    void platformReject_trailsApproval_andNeverPays() {
        OrderRma pending = new OrderRma();
        pending.setRmaNo("RMA-P0C");
        pending.setOrderNo("ORD-P0C");
        pending.setUserId(7L);
        pending.setMerId(10L);
        pending.setStatus(OrderRmaService.RMA_STATUS_PLATFORM_PENDING);
        when(rmaMapper.selectOne(any())).thenReturn(pending, pending);
        when(rmaMapper.update(any(), any())).thenReturn(1);
        when(orderMapper.update(isNull(), any())).thenReturn(1);
        when(orderMapper.selectById("ORD-P0C")).thenReturn(order("merchant_approved", "paid", new BigDecimal("120.00")));

        rmaService.platformRejectRma(3L, "RMA-P0C", "凭证不足");

        verify(payoutSpy, never()).refund(any(), any());
        verify(refundApprovalMapper).insert(argThat((RefundApproval a) ->
                "PLATFORM".equals(a.getStage()) && "REJECT".equals(a.getAction())));
        verify(messageService).send(eq(7L), eq("order"), contains("平台复核未通过"), anyString(), eq("ORD-P0C"));
    }
}
