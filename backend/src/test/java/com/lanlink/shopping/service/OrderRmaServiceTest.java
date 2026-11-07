package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.dto.rma.BuyerShipBackDTO;
import com.lanlink.shopping.dto.rma.MerchantInspectDTO;
import com.lanlink.shopping.dto.rma.MerchantRmaApproveDTO;
import com.lanlink.shopping.dto.rma.RmaApplyDTO;
import com.lanlink.shopping.entity.*;
import com.lanlink.shopping.mapper.*;
import com.lanlink.shopping.payment.dto.RefundRequest;
import com.lanlink.shopping.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RMA 逆向物流退货退款闭环核心逻辑单测。
 */
class OrderRmaServiceTest {

    private OrderRmaMapper rmaMapper;
    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private ProductMapper productMapper;
    private MerchantMapper merchantMapper;
    private LogisticsWaybillMapper waybillMapper;
    private LogisticsTraceMapper traceMapper;
    private PaymentService paymentService;
    private MessageService messageService;
    private OrderRmaService rmaService;

    @BeforeEach
    void setUp() {
        rmaMapper = mock(OrderRmaMapper.class);
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        productMapper = mock(ProductMapper.class);
        merchantMapper = mock(MerchantMapper.class);
        waybillMapper = mock(LogisticsWaybillMapper.class);
        traceMapper = mock(LogisticsTraceMapper.class);
        paymentService = mock(PaymentService.class);
        messageService = mock(MessageService.class);

        rmaService = new OrderRmaService(
                rmaMapper, orderMapper, orderItemMapper, productMapper,
                merchantMapper, waybillMapper, traceMapper, paymentService, messageService);
    }

    private Order mockPaidOrder() {
        Order o = new Order();
        o.setOrderNo("ORD100");
        o.setUserId(99L);
        o.setPayStatus(1);
        o.setOrderStatus(1); // 已发货
        o.setTotalAmount(new BigDecimal("1200.00"));
        o.setRefundAmount(BigDecimal.ZERO);
        o.setRefundStatus("none");
        return o;
    }

    private Merchant mockMerchant() {
        Merchant m = new Merchant();
        m.setMerId(10L);
        m.setUserId(88L);
        m.setLegalPerson("张经理");
        m.setContactPhone("13800000000");
        m.setBusinessAddress("上海市青浦区工业园区5号库");
        return m;
    }

    @Test
    void applyRmaCreatesRmaRecordAndLocksOrder() throws Exception {
        String evidences = com.lanlink.shopping.support.RefundEvidenceTestFiles.writeImageEvidence(99L);
        try {
            when(orderMapper.selectById("ORD100")).thenReturn(mockPaidOrder());
            when(orderMapper.applyRefundAtomic(eq("ORD100"), anyString(), isNull())).thenReturn(1);

            OrderItem it = new OrderItem();
            it.setProdId(1L);
            when(orderItemMapper.selectList(any())).thenReturn(List.of(it));
            Product p = new Product();
            p.setMerId(10L);
            when(productMapper.selectById(1L)).thenReturn(p);

            RmaApplyDTO dto = new RmaApplyDTO();
            dto.setOrderNo("ORD100");
            dto.setRmaType("return_goods");
            dto.setApplyReason("货物尺寸不符");
            dto.setApplyAmount(new BigDecimal("1200.00"));
            dto.setProofImages(evidences);

            OrderRma rma = rmaService.applyRma(99L, dto);

            assertNotNull(rma);
            assertEquals("return_goods", rma.getRmaType());
            assertEquals("buyer_applied", rma.getStatus());
            assertEquals(evidences, rma.getProofImages()); // 链B证据经 validator 归一后落 RMA 行
            verify(rmaMapper).insert(any(OrderRma.class));
            // 链A证据列独占：RMA 的 CAS 调用点传 null 不双写
            verify(orderMapper).applyRefundAtomic(eq("ORD100"), eq("货物尺寸不符"), isNull());
            verify(messageService).send(eq(99L), eq("order"), anyString(), anyString(), eq("ORD100"));
        } finally {
            com.lanlink.shopping.support.RefundEvidenceTestFiles.deleteByUrlJson(evidences);
        }
    }

    @Test
    void applyRmaRejectedWhenProofImagesMissingOrBadCategory() throws Exception {
        when(orderMapper.selectById("ORD100")).thenReturn(mockPaidOrder());

        RmaApplyDTO noProof = new RmaApplyDTO();
        noProof.setOrderNo("ORD100");
        noProof.setApplyReason("缺凭证");
        BusinessException exNoProof = assertThrows(BusinessException.class,
                () -> rmaService.applyRma(99L, noProof));
        assertTrue(exNoProof.getMessage().contains("请至少上传"));

        // reasonCategory 枚举校验：非白名单分类拒绝（证据本身合法，拦截点在 CAS 之前）
        String evidences = com.lanlink.shopping.support.RefundEvidenceTestFiles.writeImageEvidence(99L);
        try {
            RmaApplyDTO badCat = new RmaApplyDTO();
            badCat.setOrderNo("ORD100");
            badCat.setApplyReason("分类非法");
            badCat.setProofImages(evidences);
            badCat.setReasonCategory("hacker");
            BusinessException exCat = assertThrows(BusinessException.class,
                    () -> rmaService.applyRma(99L, badCat));
            assertTrue(exCat.getMessage().contains("原因分类"));
            verify(orderMapper, never()).applyRefundAtomic(any(), any(), any());
        } finally {
            com.lanlink.shopping.support.RefundEvidenceTestFiles.deleteByUrlJson(evidences);
        }
    }

    @Test
    void merchantApproveRmaForReturnGoodsSetsAddress() {
        when(merchantMapper.selectOne(any())).thenReturn(mockMerchant());

        OrderRma rma = new OrderRma();
        rma.setRmaNo("RMA001");
        rma.setOrderNo("ORD100");
        rma.setUserId(99L);
        rma.setMerId(10L);
        rma.setRmaType("return_goods");
        rma.setStatus("buyer_applied");
        when(rmaMapper.selectOne(any())).thenReturn(rma);
        when(rmaMapper.merchantApproveAtomic(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(orderMapper.selectById("ORD100")).thenReturn(mockPaidOrder());

        MerchantRmaApproveDTO dto = new MerchantRmaApproveDTO();
        dto.setReceiverName("退货收件人");
        dto.setReceiverPhone("13911112222");
        dto.setWarehouseAddress("苏州市工业园区6号仓库");
        dto.setAuditNote("同意退货");

        rmaService.merchantApproveRma(88L, "RMA001", dto);

        verify(rmaMapper).merchantApproveAtomic(eq("RMA001"), eq("退货收件人"), eq("13911112222"), eq("苏州市工业园区6号仓库"), eq("同意退货"));
        verify(orderMapper).merchantApproveRefundAtomic("ORD100");
        verify(messageService).send(eq(99L), eq("order"), contains("退货"), contains("苏州市工业园区6号仓库"), eq("ORD100"));
        verify(paymentService, never()).refund(any(), any());
    }

    @Test
    void merchantApproveRmaForRefundOnlyDirectlyTriggersRefund() {
        when(merchantMapper.selectOne(any())).thenReturn(mockMerchant());

        OrderRma rma = new OrderRma();
        rma.setRmaNo("RMA002");
        rma.setOrderNo("ORD100");
        rma.setUserId(99L);
        rma.setMerId(10L);
        rma.setRmaType("refund_only");
        rma.setApplyAmount(new BigDecimal("1200.00"));
        rma.setApplyReason("未发货协商退款");
        rma.setStatus("buyer_applied");
        when(rmaMapper.selectOne(any())).thenReturn(rma);
        when(rmaMapper.merchantApproveAtomic(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(orderMapper.selectById("ORD100")).thenReturn(mockPaidOrder());

        rmaService.merchantApproveRma(88L, "RMA002", null);

        verify(paymentService).refund(any(RefundRequest.class), eq(88L));
        verify(rmaMapper).settleSuccessAtomic(eq("RMA002"), eq(new BigDecimal("1200.00")), any(), any());
    }

    @Test
    void buyerShipBackGeneratesReverseWaybillAndStartsTrace() {
        OrderRma rma = new OrderRma();
        rma.setRmaNo("RMA001");
        rma.setOrderNo("ORD100");
        rma.setUserId(99L);
        rma.setMerId(10L);
        rma.setStatus("merchant_approved");
        rma.setReturnReceiverName("退货组");
        rma.setReturnReceiverPhone("13800000000");
        rma.setReturnWarehouseAddr("仓库地址A");
        when(rmaMapper.selectOne(any())).thenReturn(rma);
        when(orderMapper.selectById("ORD100")).thenReturn(mockPaidOrder());

        BuyerShipBackDTO dto = new BuyerShipBackDTO();
        dto.setCarrierName("顺丰速运");
        dto.setWaybillNo("SF123456789");
        dto.setRemark("包装完好");

        rmaService.buyerShipBack(99L, "RMA001", dto);

        verify(waybillMapper).insert((LogisticsWaybill) argThat(wb -> wb instanceof LogisticsWaybill && "reverse".equals(((LogisticsWaybill) wb).getWaybillDirection()) && "SF123456789".equals(((LogisticsWaybill) wb).getLogisticsNo())));
        verify(traceMapper).insert((LogisticsTrace) argThat(tr -> tr instanceof LogisticsTrace && ((LogisticsTrace) tr).getDescription().contains("SF123456789")));
        verify(rmaMapper).shipBackAtomic(eq("RMA001"), eq("顺丰速运"), eq("SF123456789"), any(), eq("包装完好"));
        verify(orderMapper).shipBackAtomic("ORD100", "顺丰速运", "SF123456789");
    }

    @Test
    void merchantInspectPassedRestoresStockAndSettlesRefund() {
        when(merchantMapper.selectOne(any())).thenReturn(mockMerchant());

        OrderRma rma = new OrderRma();
        rma.setRmaNo("RMA001");
        rma.setOrderNo("ORD100");
        rma.setUserId(99L);
        rma.setMerId(10L);
        rma.setStatus("shipping_back");
        rma.setApplyAmount(new BigDecimal("1200.00"));
        when(rmaMapper.selectOne(any())).thenReturn(rma);

        OrderItem it = new OrderItem();
        it.setProdId(200L);
        it.setQuantity(5);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(it));
        when(orderMapper.selectById("ORD100")).thenReturn(mockPaidOrder());

        MerchantInspectDTO dto = new MerchantInspectDTO();
        dto.setInspectStatus("passed");
        dto.setResult("开箱查验完好，符合退货标准");

        rmaService.merchantInspect(88L, "RMA001", dto);

        verify(rmaMapper).inspectAtomic(eq("RMA001"), eq("inspected"), eq(88L), eq("passed"), eq("开箱查验完好，符合退货标准"), eq(1));
        verify(orderMapper).updateInspectResult(eq("ORD100"), eq("passed"), eq("开箱查验完好，符合退货标准"));
        verify(productMapper).restoreStockAtomic(200L, 5); // 原子回滚恢复库存
        verify(paymentService).refund(any(RefundRequest.class), eq(88L)); // 自动出账退款
    }

    @Test
    void merchantInspectDamagedMarksDispute() {
        when(merchantMapper.selectOne(any())).thenReturn(mockMerchant());

        OrderRma rma = new OrderRma();
        rma.setRmaNo("RMA001");
        rma.setOrderNo("ORD100");
        rma.setUserId(99L);
        rma.setMerId(10L);
        rma.setStatus("shipping_back");
        when(rmaMapper.selectOne(any())).thenReturn(rma);

        MerchantInspectDTO dto = new MerchantInspectDTO();
        dto.setInspectStatus("damaged");
        dto.setResult("内部线圈烧毁货损");

        rmaService.merchantInspect(88L, "RMA001", dto);

        verify(rmaMapper).inspectAtomic(eq("RMA001"), eq("dispute"), eq(88L), eq("damaged"), eq("内部线圈烧毁货损"), eq(0));
        verify(productMapper, never()).restoreStockAtomic(anyLong(), anyInt()); // 不入库
        verify(paymentService, never()).refund(any(), any()); // 不放款
        verify(messageService).send(eq(99L), eq("order"), contains("异常"), contains("内部线圈烧毁货损"), eq("ORD100"));
    }
}
