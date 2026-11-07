package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.integration.pricing.PricingFacade;
import com.lanlink.shopping.mapper.CartMapper;
import com.lanlink.shopping.mapper.OrderItemMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.membership.service.MembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 退款申请闭环单测：用户申请（归属/支付状态/重复申请/原因必填）与平台驳回。
 */
class OrderRefundApplyTest {

    private OrderMapper orderMapper;
    private MessageService messageService;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        messageService = mock(MessageService.class);
        orderService = new OrderService(
                mock(CartMapper.class), mock(ProductMapper.class), orderMapper, mock(OrderItemMapper.class),
                mock(UserMapper.class), messageService, mock(IdentityService.class), mock(AuditService.class),
                mock(PricingFacade.class), mock(ApplicationEventPublisher.class),
                mock(MembershipService.class), mock(CreditTermService.class),
                mock(IdempotentService.class), mock(TamperGuardService.class),
                mock(com.lanlink.shopping.module.campaign.service.CampaignService.class),
                mock(com.lanlink.shopping.module.risk.service.RiskEventService.class));
    }

    private Order paidOrder() {
        Order o = new Order();
        o.setOrderNo("ORD1");
        o.setUserId(7L);
        o.setTotalAmount(new BigDecimal("2000.00"));
        o.setPayStatus(1);
        o.setOrderStatus(0);
        o.setPayType("corporate");
        o.setPayChannel("mock");
        o.setRefundStatus("none");
        return o;
    }

    /** 在真实上传根落盘证据文件并返回合法证据 JSON；用例结束统一清理 */
    private String evidenceJson() throws Exception {
        return com.lanlink.shopping.support.RefundEvidenceTestFiles.writeImageEvidence(7L);
    }

    @Test
    void applyRefundMarksAppliedAndNotifies() throws Exception {
        String evidences = evidenceJson();
        try {
            when(orderMapper.selectById("ORD1")).thenReturn(paidOrder());
            when(orderMapper.applyRefundAtomic(eq("ORD1"), eq("商品缺货"), any())).thenReturn(1);
            orderService.applyRefund(7L, "ORD1", "商品缺货", evidences);
            verify(orderMapper).applyRefundAtomic(eq("ORD1"), eq("商品缺货"), any());
            verify(messageService).send(eq(7L), eq("order"), any(), any(), eq("ORD1"));
        } finally {
            com.lanlink.shopping.support.RefundEvidenceTestFiles.deleteByUrlJson(evidences);
        }
    }

    @Test
    void applyRefundConcurrentDuplicateRejected() throws Exception {
        String evidences = evidenceJson();
        try {
            // 需求 2 幂等/并发：原子流转返回 0（并发抢先提交/状态已被并发变更）→ 拒绝并报错
            when(orderMapper.selectById("ORD1")).thenReturn(paidOrder());
            when(orderMapper.applyRefundAtomic(eq("ORD1"), eq("并发请求"), any())).thenReturn(0);
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.applyRefund(7L, "ORD1", "并发请求", evidences));
            assertTrue(ex.getMessage().contains("重复"));
        } finally {
            com.lanlink.shopping.support.RefundEvidenceTestFiles.deleteByUrlJson(evidences);
        }
    }

    @Test
    void applyRefundRejectedForNonOwner() {
        when(orderMapper.selectById("ORD1")).thenReturn(paidOrder());
        assertThrows(BusinessException.class, () -> orderService.applyRefund(99L, "ORD1", "不想要了", null));
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    void applyRefundRejectedWhenUnpaid() {
        Order o = paidOrder();
        o.setPayStatus(0);
        when(orderMapper.selectById("ORD1")).thenReturn(o);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.applyRefund(7L, "ORD1", "不想要了", null));
        assertTrue(ex.getMessage().contains("已支付"));
    }

    @Test
    void applyRefundRejectedWhenAlreadyApplied() throws Exception {
        String evidences = evidenceJson();
        try {
            Order o = paidOrder();
            o.setRefundStatus("applied");
            when(orderMapper.selectById("ORD1")).thenReturn(o);
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.applyRefund(7L, "ORD1", "重复申请", evidences));
            assertTrue(ex.getMessage().contains("等待平台处理"));
        } finally {
            com.lanlink.shopping.support.RefundEvidenceTestFiles.deleteByUrlJson(evidences);
        }
    }

    @Test
    void applyRefundRejectedWhenReasonBlank() {
        when(orderMapper.selectById("ORD1")).thenReturn(paidOrder());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.applyRefund(7L, "ORD1", "   ", null));
        assertTrue(ex.getMessage().contains("退款原因"));
    }

    @Test
    void applyRefundRejectedWhenEvidenceMissing() {
        // 证据必填 ≥1：未提交凭证在原因校验之后、CAS 之前被拒
        when(orderMapper.selectById("ORD1")).thenReturn(paidOrder());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.applyRefund(7L, "ORD1", "想退款", null));
        assertTrue(ex.getMessage().contains("请至少上传"));
        verify(orderMapper, never()).applyRefundAtomic(any(), any(), any());
    }

    @Test
    void applyRefundRejectedWhenEvidenceNotOwned() throws Exception {
        // 凭证归属即安全：URL 落盘名首段 userId 非当前用户 → 拒绝
        String url = com.lanlink.shopping.support.RefundEvidenceTestFiles.writeEvidenceUrl(999L, "jpg");
        try {
            String evidences = "[{\"type\":\"image\",\"url\":\"" + url + "\",\"name\":\"x.png\"}]";
            when(orderMapper.selectById("ORD1")).thenReturn(paidOrder());
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.applyRefund(7L, "ORD1", "想退款", evidences));
            assertTrue(ex.getMessage().contains("不属于当前用户"));
        } finally {
            com.lanlink.shopping.support.RefundEvidenceTestFiles.delete(url);
        }
    }

    @Test
    void applyRefundWritesNormalizedEvidenceColumn() throws Exception {
        // 合法证据经归一化后随 CAS 落库列值（多余字段被剔除）
        String url = com.lanlink.shopping.support.RefundEvidenceTestFiles.writeEvidenceUrl(7L, "png");
        try {
            String evidences = "[{\"type\":\"image\",\"url\":\"" + url + "\",\"name\":\"聊天记录截图.png\",\"junk\":\"inject\"}]";
            when(orderMapper.selectById("ORD1")).thenReturn(paidOrder());
            when(orderMapper.applyRefundAtomic(eq("ORD1"), anyString(), any())).thenReturn(1);
            orderService.applyRefund(7L, "ORD1", "协商退款", evidences);
            org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(orderMapper).applyRefundAtomic(eq("ORD1"), eq("协商退款"), captor.capture());
            String saved = captor.getValue();
            assertTrue(saved.contains("\"type\":\"image\""));
            assertTrue(saved.contains(url));
            assertFalse(saved.contains("junk"), "归一化必须剔除多余字段");
        } finally {
            com.lanlink.shopping.support.RefundEvidenceTestFiles.delete(url);
        }
    }

    @Test
    void applyRefundAllowedForTermOrder() throws Exception {
        String evidences = evidenceJson();
        try {
            Order o = paidOrder();
            o.setPayType("term");
            when(orderMapper.selectById("ORD1")).thenReturn(o);
            when(orderMapper.applyRefundAtomic(eq("ORD1"), eq("账期订单申请退款"), any())).thenReturn(1);
            assertDoesNotThrow(() -> orderService.applyRefund(7L, "ORD1", "账期订单申请退款", evidences));
            verify(orderMapper).applyRefundAtomic(eq("ORD1"), eq("账期订单申请退款"), any());
        } finally {
            com.lanlink.shopping.support.RefundEvidenceTestFiles.deleteByUrlJson(evidences);
        }
    }

    @Test
    void merchantApproveRefundSuccessAndPermissionCheck() {
        com.lanlink.shopping.mapper.MerchantMapper merchantMapper = mock(com.lanlink.shopping.mapper.MerchantMapper.class);
        orderService.setMerchantMapper(merchantMapper);

        com.lanlink.shopping.entity.Merchant m = new com.lanlink.shopping.entity.Merchant();
        m.setMerId(100L);
        m.setUserId(88L);
        when(merchantMapper.selectOne(any())).thenReturn(m);

        Order o = paidOrder();
        o.setRefundStatus("applied");
        when(orderMapper.selectById("ORD1")).thenReturn(o);

        // 场景 A: 该商户不包含订单中的商品 -> 越权抛异常
        when(orderMapper.merchantItems(eq(100L), anyList())).thenReturn(java.util.List.of());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.merchantApproveRefund(88L, "ORD1"));
        assertTrue(ex.getMessage().contains("无权操作"));

        // 场景 B: 拥有该商品 -> 初审通过
        com.lanlink.shopping.entity.OrderItem item = new com.lanlink.shopping.entity.OrderItem();
        item.setItemId(1L);
        when(orderMapper.merchantItems(eq(100L), anyList())).thenReturn(java.util.List.of(item));
        when(orderMapper.merchantApproveRefundAtomic("ORD1")).thenReturn(1);

        assertDoesNotThrow(() -> orderService.merchantApproveRefund(88L, "ORD1"));
        verify(orderMapper).merchantApproveRefundAtomic("ORD1");
    }

    @Test
    void merchantRejectRefundSuccess() {
        com.lanlink.shopping.mapper.MerchantMapper merchantMapper = mock(com.lanlink.shopping.mapper.MerchantMapper.class);
        orderService.setMerchantMapper(merchantMapper);

        com.lanlink.shopping.entity.Merchant m = new com.lanlink.shopping.entity.Merchant();
        m.setMerId(100L);
        m.setUserId(88L);
        when(merchantMapper.selectOne(any())).thenReturn(m);

        Order o = paidOrder();
        o.setRefundStatus("applied");
        when(orderMapper.selectById("ORD1")).thenReturn(o);

        com.lanlink.shopping.entity.OrderItem item = new com.lanlink.shopping.entity.OrderItem();
        item.setItemId(1L);
        when(orderMapper.merchantItems(eq(100L), anyList())).thenReturn(java.util.List.of(item));
        when(orderMapper.merchantRejectRefundAtomic("ORD1", "商品无质量问题")).thenReturn(1);

        assertDoesNotThrow(() -> orderService.merchantRejectRefund(88L, "ORD1", "商品无质量问题"));
        verify(orderMapper).merchantRejectRefundAtomic("ORD1", "商品无质量问题");
    }

    @Test
    void applyRefundAllowedForPartialShippedOrder() throws Exception {
        String evidences = evidenceJson();
        try {
            Order o = paidOrder();
            o.setOrderStatus(4); // 部分发货状态
            when(orderMapper.selectById("ORD1")).thenReturn(o);
            when(orderMapper.applyRefundAtomic(eq("ORD1"), eq("部分商品缺货退款"), any())).thenReturn(1);
            assertDoesNotThrow(() -> orderService.applyRefund(7L, "ORD1", "部分商品缺货退款", evidences));
            verify(orderMapper).applyRefundAtomic(eq("ORD1"), eq("部分商品缺货退款"), any());
        } finally {
            com.lanlink.shopping.support.RefundEvidenceTestFiles.deleteByUrlJson(evidences);
        }
    }

    @Test
    void applyRefundRejectedWhenCancelledOrClosed() {
        Order o3 = paidOrder();
        o3.setOrderStatus(3); // 已取消
        when(orderMapper.selectById("ORD1")).thenReturn(o3);
        BusinessException ex3 = assertThrows(BusinessException.class,
                () -> orderService.applyRefund(7L, "ORD1", "想退款", null));
        assertTrue(ex3.getMessage().contains("已取消或已关闭"));

        Order o5 = paidOrder();
        o5.setOrderStatus(5); // 已关闭
        when(orderMapper.selectById("ORD1")).thenReturn(o5);
        BusinessException ex5 = assertThrows(BusinessException.class,
                () -> orderService.applyRefund(7L, "ORD1", "想退款", null));
        assertTrue(ex5.getMessage().contains("已取消或已关闭"));
    }

    @Test
    void rejectRefundMarksRejectedWithReason() {
        Order o = paidOrder();
        o.setRefundStatus("applied");
        when(orderMapper.selectById("ORD1")).thenReturn(o);
        when(orderMapper.rejectRefundAtomic("ORD1", "已发货不支持退款")).thenReturn(1);
        orderService.rejectRefund("ORD1", "已发货不支持退款");
        // 需求 2：驳回走原子条件更新（仅 applied 可置 rejected），不再读-改-写覆盖
        verify(orderMapper).rejectRefundAtomic("ORD1", "已发货不支持退款");
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    void rejectRefundConcurrentRaceRejected() {
        // 并发竞态：另一个并行操作已抢先变更状态（原子更新返回 0）→ 拒绝且不覆盖其结果
        Order o = paidOrder();
        o.setRefundStatus("applied");
        when(orderMapper.selectById("ORD1")).thenReturn(o);
        when(orderMapper.rejectRefundAtomic("ORD1", "并发驳回")).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.rejectRefund("ORD1", "并发驳回"));
        assertTrue(ex.getMessage().contains("状态已变更"));
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    void rejectRefundRequiresPendingApplication() {
        when(orderMapper.selectById("ORD1")).thenReturn(paidOrder()); // refundStatus=none
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.rejectRefund("ORD1", null));
        assertTrue(ex.getMessage().contains("没有待处理"));
    }

    @Test
    void payAtomicSuccessAndConcurrentRaceRejected() {
        Order o = paidOrder();
        o.setPayStatus(0);
        when(orderMapper.selectById("ORD1")).thenReturn(o);
        when(orderMapper.markPaidAtomic("ORD1")).thenReturn(1);
        Order paid = orderService.pay(7L, "ORD1");
        assertNotNull(paid);
        verify(orderMapper).markPaidAtomic("ORD1");

        // 并发竞态：已被抢先支付或状态不允许（原子返回 0）
        when(orderMapper.markPaidAtomic("ORD1")).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.pay(7L, "ORD1"));
        assertTrue(ex.getMessage().contains("已支付或当前状态不允许支付"));
    }
}
