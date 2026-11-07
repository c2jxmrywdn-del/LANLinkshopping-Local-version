package com.lanlink.shopping.service;

import com.lanlink.shopping.entity.*;
import com.lanlink.shopping.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 物流发货 Phase 2 (随货 CoA / e-POD 现场验签 / VAS增值服务) 专项单元测试。
 */
class LogisticsPhase2ServiceTest {

    private LogisticsWaybillMapper waybillMapper;
    private LogisticsTraceMapper traceMapper;
    private LogisticsAlertMapper alertMapper;
    private LogisticsProviderMapper providerMapper;
    private LogisticsTemplateMapper templateMapper;
    private OrderMapper orderMapper;
    private MessageService messageService;
    private AuditService auditService;
    private OrderItemMapper orderItemMapper;
    private LogisticsWaybillItemMapper waybillItemMapper;
    private LogisticsDocumentMapper documentMapper;
    private LogisticsPodRecordMapper podRecordMapper;

    private LogisticsWaybillService service;

    @BeforeEach
    void setUp() {
        waybillMapper = mock(LogisticsWaybillMapper.class);
        traceMapper = mock(LogisticsTraceMapper.class);
        alertMapper = mock(LogisticsAlertMapper.class);
        providerMapper = mock(LogisticsProviderMapper.class);
        templateMapper = mock(LogisticsTemplateMapper.class);
        orderMapper = mock(OrderMapper.class);
        messageService = mock(MessageService.class);
        auditService = mock(AuditService.class);
        orderItemMapper = mock(OrderItemMapper.class);
        waybillItemMapper = mock(LogisticsWaybillItemMapper.class);
        documentMapper = mock(LogisticsDocumentMapper.class);
        podRecordMapper = mock(LogisticsPodRecordMapper.class);

        when(orderMapper.markShippedAtomic(any())).thenReturn(1);
        when(orderMapper.markPartialShippedAtomic(any())).thenReturn(1);
        when(orderMapper.markFullyShippedAtomic(any())).thenReturn(1);

        service = new LogisticsWaybillService(
                waybillMapper, traceMapper, alertMapper, providerMapper, templateMapper,
                orderMapper, new LogisticsFreightCalculator(), new LogisticsTraceParser(),
                messageService, auditService, mock(SequenceService.class),
                orderItemMapper, waybillItemMapper
        );
        ReflectionTestUtils.setField(service, "documentMapper", documentMapper);
        ReflectionTestUtils.setField(service, "podRecordMapper", podRecordMapper);
    }

    private Order testOrder() {
        Order o = new Order();
        o.setOrderNo("ORD-PHASE2-001");
        o.setUserId(100L);
        o.setPayStatus(1);
        o.setOrderStatus(0); // 待发货
        o.setTotalAmount(new BigDecimal("8800.00"));
        o.setReceiver("张经理");
        o.setPhone("13800000000");
        o.setAddress("江苏省苏州市工业园区纳米城 88 号");
        return o;
    }

    private LogisticsProvider testProvider() {
        LogisticsProvider p = new LogisticsProvider();
        p.setProviderId(1L);
        p.setProviderName("德邦快运");
        p.setProviderCode("DEPPON");
        p.setStatus(1);
        return p;
    }

    private LogisticsTemplate testTemplate() {
        LogisticsTemplate t = new LogisticsTemplate();
        t.setTemplateId(1L);
        t.setProviderId(1L);
        t.setPricingType("weight");
        t.setTierMode("tiered");
        t.setTierRuleJson("""
            [
              {"minWeight":0, "maxWeight":300, "unitPrice":1.5, "basePrice":30.0},
              {"minWeight":300, "maxWeight":1000, "unitPrice":1.1, "basePrice":0.0}
            ]
            """);
        t.setVasRuleJson("""
            {
              "forklift": {"basePrice":80.0, "freeTons":1.0, "perTonPrice":50.0}
            }
            """);
        t.setStatus(1);
        return t;
    }

    @Test
    @DisplayName("多包裹拆单发货携带随货 CoA 质检单与叉车增值服务")
    void testShipSplitWithCoaAndVas() {
        Order order = testOrder();
        when(orderMapper.selectById("ORD-PHASE2-001")).thenReturn(order);
        when(providerMapper.selectById(1L)).thenReturn(testProvider());
        when(templateMapper.selectById(1L)).thenReturn(testTemplate());

        OrderItem item1 = new OrderItem();
        item1.setItemId(101L);
        item1.setOrderNo(order.getOrderNo());
        item1.setProdId(2001L);
        item1.setProdName("高压工业球阀 DN100");
        item1.setQuantity(5);
        item1.setShippedQuantity(0);

        when(orderItemMapper.selectById(101L)).thenReturn(item1);
        when(orderItemMapper.increaseShippedQuantityAtomic(101L, 2)).thenReturn(1);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item1));

        var coaItem = new LogisticsWaybillService.CoaDocItem(
                "出厂检验合格单.pdf", "/attachment/file/ATT-COA-01", "BATCH-202611-01", "coa", 102400L, "hash123"
        );
        var vasReq = new LogisticsFreightCalculator.VasRequest(true, false, 1, false, false);

        var splitReq = new LogisticsWaybillService.SplitShipRequest(
                order.getOrderNo(), 1L, 1L, "DP987654321",
                new BigDecimal("500"), null, false,
                "苏州仓", "13900000000", "苏州市高新区科技路1号",
                "ltl", "PKG-01", 1, 2,
                List.of(new LogisticsWaybillService.SplitItem(101L, 2)),
                List.of(coaItem), vasReq
        );

        LogisticsWaybill wb = service.shipSplit(splitReq, 88L, null);

        assertNotNull(wb);
        assertEquals("PKG-01", wb.getPackageNo());
        assertTrue(wb.getCoaDocsJson().contains("出厂检验合格单.pdf"));
        verify(documentMapper, times(1)).insert(any(LogisticsDocument.class));
    }

    @Test
    @DisplayName("现场 e-POD 签收回单提交与商户验签核准闭环")
    void testEpodUploadAndApprove() {
        LogisticsWaybill wb = new LogisticsWaybill();
        wb.setWaybillId(999L);
        wb.setWaybillNo("LL202611059999");
        wb.setOrderNo("ORD-PHASE2-001");
        wb.setStatus(LogisticsStateMachine.IN_TRANSIT);
        wb.setReceiverLng(new BigDecimal("120.62"));
        wb.setReceiverLat(new BigDecimal("31.32"));

        Order order = testOrder();
        when(waybillMapper.selectById(999L)).thenReturn(wb);
        when(orderMapper.selectById("ORD-PHASE2-001")).thenReturn(order);

        // 1. 上传 e-POD
        var uploadReq = new LogisticsWaybillService.PodUploadRequest(
                999L, "李质检员", "13712345678", LocalDateTime.now(),
                "/attachment/file/ATT-STAMP-01", List.of("/attachment/file/ATT-CARGO-01"),
                new BigDecimal("120.62"), new BigDecimal("31.32"), "外观无损，盖章验收"
        );

        LogisticsPodRecord pod = service.uploadPod(uploadReq, 100L, null);
        assertNotNull(pod);
        assertEquals("pending", pod.getAuditStatus());
        assertEquals("李质检员", wb.getPodSignedBy());
        assertEquals("pending", wb.getPodAuditStatus());
        verify(podRecordMapper, times(1)).insert(any(LogisticsPodRecord.class));

        // 2. 验签通过
        when(podRecordMapper.selectList(any())).thenReturn(List.of(pod));
        var auditReq = new LogisticsWaybillService.PodAuditRequest("approve", "公章清晰完整，核实通过");
        LogisticsWaybill approvedWb = service.auditPod(999L, auditReq, 88L, null);

        assertEquals("approved", approvedWb.getPodAuditStatus());
        assertEquals(LogisticsStateMachine.DELIVERED, approvedWb.getStatus());
        assertNotNull(approvedWb.getDeliveredTime());
    }

    @Test
    @DisplayName("现场 e-POD 验签驳回处理")
    void testEpodAuditReject() {
        LogisticsWaybill wb = new LogisticsWaybill();
        wb.setWaybillId(888L);
        wb.setWaybillNo("LL202611058888");
        wb.setOrderNo("ORD-PHASE2-001");
        wb.setStatus(LogisticsStateMachine.IN_TRANSIT);
        wb.setPodAuditStatus("pending");

        when(waybillMapper.selectById(888L)).thenReturn(wb);
        when(orderMapper.selectById("ORD-PHASE2-001")).thenReturn(testOrder());

        var auditReq = new LogisticsWaybillService.PodAuditRequest("reject", "印章模糊无法辨认收货单位");
        LogisticsWaybill rejectedWb = service.auditPod(888L, auditReq, 88L, null);

        assertEquals("rejected", rejectedWb.getPodAuditStatus());
        assertEquals(LogisticsStateMachine.IN_TRANSIT, rejectedWb.getStatus());
        assertEquals("印章模糊无法辨认收货单位", rejectedWb.getPodAuditNote());
    }
}
