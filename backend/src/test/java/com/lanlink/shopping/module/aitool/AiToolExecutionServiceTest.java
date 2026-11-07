package com.lanlink.shopping.module.aitool;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Category;
import com.lanlink.shopping.entity.Enterprise;
import com.lanlink.shopping.entity.LogisticsTrace;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CategoryMapper;
import com.lanlink.shopping.mapper.EnterpriseMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.aikb.KnowledgeRagService;
import com.lanlink.shopping.module.aikb.RedisVectorStore;
import com.lanlink.shopping.module.aitool.dto.AiToolDto;
import com.lanlink.shopping.module.aitool.service.AiToolExecutionService;
import com.lanlink.shopping.module.aitool.service.AiToolGuard;
import com.lanlink.shopping.service.LogisticsWaybillService;
import com.lanlink.shopping.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

public class AiToolExecutionServiceTest {

    private ProductService productService;
    private ProductMapper productMapper;
    private CategoryMapper categoryMapper;
    private MerchantMapper merchantMapper;
    private EnterpriseMapper enterpriseMapper;
    private KnowledgeRagService knowledgeRagService;
    private OrderMapper orderMapper;
    private LogisticsWaybillService waybillService;
    private LogisticsWaybillMapper waybillMapper;
    private StringRedisTemplate redis;
    private ObjectMapper objectMapper;

    private AiToolExecutionService toolExecutionService;

    @BeforeEach
    void setUp() {
        productService = Mockito.mock(ProductService.class);
        productMapper = Mockito.mock(ProductMapper.class);
        categoryMapper = Mockito.mock(CategoryMapper.class);
        merchantMapper = Mockito.mock(MerchantMapper.class);
        enterpriseMapper = Mockito.mock(EnterpriseMapper.class);
        knowledgeRagService = Mockito.mock(KnowledgeRagService.class);
        orderMapper = Mockito.mock(OrderMapper.class);
        waybillService = Mockito.mock(LogisticsWaybillService.class);
        waybillMapper = Mockito.mock(LogisticsWaybillMapper.class);
        redis = null;
        objectMapper = new ObjectMapper();

        toolExecutionService = new AiToolExecutionService(
                productService, productMapper, categoryMapper, merchantMapper, enterpriseMapper,
                knowledgeRagService, orderMapper, waybillService, waybillMapper, redis, objectMapper);
    }

    private Product createSampleProduct() {
        Product p = new Product();
        p.setProdId(101L);
        p.setMerId(1L);
        p.setCatId(10L);
        p.setTitle("304不锈钢无缝工业钢管 Φ25*3");
        p.setBrand("太钢");
        p.setSpec("Φ25mm*3mm*6000mm");
        p.setPrice(new BigDecimal("48.50"));
        p.setStock(1500);
        p.setStatus(1);
        p.setReviewStatus(1);
        p.setSales(240);
        p.setCoverUrl("https://img.lanlink.com/prod101.jpg");
        p.setTierPriceJson("[{\"minQty\":10,\"price\":48.50},{\"minQty\":100,\"price\":45.00},{\"minQty\":500,\"price\":41.20}]");
        return p;
    }

    @Test
    void testSearchProducts_Success() {
        Product p = createSampleProduct();
        Page<Product> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(p));

        when(productService.page(anyLong(), anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(page);

        Category cat = new Category();
        cat.setName("工业管材");
        when(categoryMapper.selectById(10L)).thenReturn(cat);

        AiToolDto.SearchProductsRequest req = AiToolDto.SearchProductsRequest.builder()
                .keyword("不锈钢管")
                .minPrice(new BigDecimal("10.00"))
                .maxPrice(new BigDecimal("100.00"))
                .inStockOnly(true)
                .build();

        AiToolDto.SearchProductsResponse resp = toolExecutionService.searchProducts(req);

        assertNotNull(resp);
        assertEquals(1, resp.getItems().size());
        AiToolDto.ProductSummaryVo vo = resp.getItems().get(0);
        assertEquals(101L, vo.getProdId());
        assertEquals("IN_STOCK", vo.getStockStatus());
        assertTrue(vo.getHasTierPricing());
        assertEquals("工业管材", vo.getCatName());
    }

    @Test
    void testGetProductDetail_ParsesTierPricingAndMoq() {
        Product p = createSampleProduct();
        when(productService.detailForPublic(101L)).thenReturn(p);

        Merchant m = new Merchant();
        m.setMerId(1L);
        m.setEntId(200L);
        when(merchantMapper.selectById(1L)).thenReturn(m);

        Enterprise ent = new Enterprise();
        ent.setName("太钢集团特钢直营中心");
        ent.setMemberLevel(2);
        ent.setCreditCode("91140000123456789X");
        when(enterpriseMapper.selectById(200L)).thenReturn(ent);

        AiToolDto.ProductDetailRequest req = AiToolDto.ProductDetailRequest.builder()
                .productId(101L)
                .build();

        AiToolDto.ProductDetailResponse resp = toolExecutionService.getProductDetail(req);

        assertNotNull(resp);
        assertEquals(101L, resp.getProdId());
        assertEquals(10, resp.getMoq());
        assertEquals(3, resp.getTierPricing().size());
        assertEquals(new BigDecimal("41.20"), resp.getTierPricing().get(2).getUnitPrice());
        assertEquals("太钢集团特钢直营中心", resp.getSupplier().getEnterpriseName());
        assertNotNull(resp.getPolicy().getVatInvoice());
    }

    @Test
    void testCalculateQuotation_TierPricingAndTax() {
        Product p = createSampleProduct();
        when(productService.detailForPublic(101L)).thenReturn(p);

        // 订购 300 件：命中 minQty=100 档位单价 45.00
        AiToolDto.CalculateQuotationRequest req = AiToolDto.CalculateQuotationRequest.builder()
                .productId(101L)
                .quantity(300)
                .build();

        AiToolDto.CalculateQuotationResponse resp = toolExecutionService.calculateQuotation(req);

        assertNotNull(resp);
        assertEquals(300, resp.getQuantity());
        assertEquals(new BigDecimal("45.00"), resp.getEffectiveUnitPrice());
        assertEquals(new BigDecimal("13500.00"), resp.getGoodsTotalAmount()); // 300 * 45 = 13500
        assertEquals(BigDecimal.ZERO, resp.getEstimatedFreight()); // 满 300 件包邮
        assertEquals(new BigDecimal("13500.00"), resp.getFinalTotalAmount());
        assertEquals(new BigDecimal("1050.00"), resp.getTotalSavings()); // 原价 48.5 * 300 = 14550，优惠 1050
        assertTrue(resp.getQuotationSummary().contains("已享包邮"));
    }

    @Test
    void testCreateCartDraft_GeneratesDeepLinkAndDraft() {
        Product p = createSampleProduct();
        when(productService.detailForPublic(101L)).thenReturn(p);

        AiToolDto.CreateCartDraftRequest req = AiToolDto.CreateCartDraftRequest.builder()
                .productId(101L)
                .quantity(50)
                .userId(999L)
                .remark("急需48小时发货")
                .build();

        AiToolDto.CreateCartDraftResponse resp = toolExecutionService.createCartDraft(req);

        assertNotNull(resp);
        assertTrue(resp.getDraftId().startsWith("dft_"));
        assertEquals("PENDING_USER_CONFIRMATION", resp.getStatus());
        assertTrue(resp.getConfirmationRequired());
        assertTrue(resp.getCheckoutDeepLink().contains("/checkout?draftId=dft_"));
    }

    @Test
    void testQueryKnowledge_HybridRecall() {
        RedisVectorStore.Hit hit = new RedisVectorStore.Hit(
                "doc_1", "policy", "ref_1", "增值税专用发票政策", "满100元可开具13%专票", 0.92);
        when(knowledgeRagService.searchHybrid(anyString(), anyInt(), any()))
                .thenReturn(List.of(hit));

        AiToolDto.QueryKnowledgeRequest req = AiToolDto.QueryKnowledgeRequest.builder()
                .query("怎么开专票")
                .scope("policy")
                .topK(2)
                .build();

        AiToolDto.QueryKnowledgeResponse resp = toolExecutionService.queryKnowledge(req);

        assertNotNull(resp);
        assertEquals(1, resp.getCount());
        assertEquals("增值税专用发票政策", resp.getHits().get(0).getTitle());
        assertEquals(0.92, resp.getHits().get(0).getRelevanceScore());
    }

    @Test
    void testTrackLogistics_AntiIDORAndPiiMasking() {
        Order order = new Order();
        order.setOrderNo("ORD202611010001");
        order.setUserId(888L);
        order.setReceiver("张三丰");
        order.setPhone("13912345678");
        order.setAddress("江苏省苏州市工业园区生物医药产业园6栋302室");
        order.setOrderStatus(2); // 已发货

        when(orderMapper.selectById("ORD202611010001")).thenReturn(order);

        LogisticsWaybill waybill = new LogisticsWaybill();
        waybill.setWaybillId(5001L);
        waybill.setWaybillNo("SF148293019");
        waybill.setLogisticsNo("SF148293019");
        waybill.setStatus("in_transit");
        when(waybillMapper.selectOne(any())).thenReturn(waybill);

        LogisticsTrace trace = new LogisticsTrace();
        trace.setTraceTime(LocalDateTime.now());
        trace.setLocation("苏州分拨中心");
        trace.setStatus("派送中");
        trace.setDescription("快递员正在派送中");
        when(waybillService.traces(5001L, 888L, false)).thenReturn(List.of(trace));

        // 1. 模拟越权查询（请求者用户ID为 999，订单归属于 888） -> 必须抛出异常
        AiToolDto.TrackLogisticsRequest idorReq = AiToolDto.TrackLogisticsRequest.builder()
                .orderNo("ORD202611010001")
                .userId(999L)
                .build();

        assertThrows(BusinessException.class, () -> toolExecutionService.trackLogistics(idorReq));

        // 2. 正常查询（请求者用户ID为 888） -> 验证 PII 掩码
        AiToolDto.TrackLogisticsRequest normalReq = AiToolDto.TrackLogisticsRequest.builder()
                .orderNo("ORD202611010001")
                .userId(888L)
                .build();

        AiToolDto.TrackLogisticsResponse resp = toolExecutionService.trackLogistics(normalReq);

        assertNotNull(resp);
        assertEquals("ORD202611010001", resp.getOrderNo());
        assertEquals("张*丰", resp.getReceiverName()); // PII 掩码
        assertEquals("139****5678", resp.getReceiverPhone()); // PII 掩码
        assertTrue(resp.getDestination().contains("***")); // PII 掩码
        assertEquals("in_transit", resp.getCurrentStatus());
        assertEquals(1, resp.getTraces().size());
    }

    @Test
    void testExecuteTool_GenericDispatcher() {
        Product p = createSampleProduct();
        when(productService.detailForPublic(101L)).thenReturn(p);

        AiToolDto.GenericToolCallRequest req = AiToolDto.GenericToolCallRequest.builder()
                .tool("lanlink_calculate_quotation")
                .arguments(Map.of("productId", 101, "quantity", 20))
                .build();

        AiToolDto.GenericToolCallResponse resp = toolExecutionService.executeTool(req);

        assertNotNull(resp);
        assertTrue(resp.getSuccess());
        assertEquals("lanlink_calculate_quotation", resp.getTool());
        assertNotNull(resp.getData());
        assertNotNull(resp.getLatencyMs());
    }

    @Test
    void executeToolRethrowsPermissionDenialsSoAdviceCanMapThemTo403() {
        Order order = new Order();
        order.setOrderNo("ORD202611010001");
        order.setUserId(888L);
        order.setReceiver("张三丰");
        order.setPhone("13912345678");
        order.setAddress("江苏省苏州市工业园区生物医药产业园6栋302室");
        order.setOrderStatus(2);
        when(orderMapper.selectById("ORD202611010001")).thenReturn(order);

        AiToolDto.GenericToolCallRequest req = AiToolDto.GenericToolCallRequest.builder()
                .tool("lanlink_track_logistics")
                .arguments(Map.of("orderNo", "ORD202611010001", "userId", 999))
                .build();

        // 越权不能被降级成 success=false 的 HTTP 200，必须原样抛出交给异常边界
        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolExecutionService.executeTool(req, null, "req-test-403"));
        assertTrue(ex.getMessage().startsWith("FORBIDDEN:"),
                "鉴权失败需保留 FORBIDDEN 前缀供 AiToolExceptionHandler 映射为 403，实际为: " + ex.getMessage());
    }

    @Test
    void executeToolKeepsOrdinaryBusinessFailureAsSuccessFalseEnvelope() {
        when(productService.detailForPublic(999999L)).thenReturn(null);

        AiToolDto.GenericToolCallRequest req = AiToolDto.GenericToolCallRequest.builder()
                .tool("lanlink_get_product_detail")
                .arguments(Map.of("productId", 999999))
                .build();

        AiToolDto.GenericToolCallResponse resp = toolExecutionService.executeTool(req, null, "req-test-biz");

        assertFalse(resp.getSuccess());
        assertEquals("商品不存在或已下架", resp.getError());
    }

    @Test
    void testAuditAndApproveProcurementFlow_EndToEnd() {
        Product p = createSampleProduct();
        when(productService.detailForPublic(101L)).thenReturn(p);

        // 1. 创建采购草稿
        AiToolDto.CreateCartDraftRequest draftReq = AiToolDto.CreateCartDraftRequest.builder()
                .productId(101L)
                .quantity(50)
                .userId(888L)
                .build();
        AiToolDto.CreateCartDraftResponse draftResp = toolExecutionService.createCartDraft(draftReq);
        assertNotNull(draftResp);
        String draftId = draftResp.getDraftId();
        assertTrue(draftId.startsWith("dft_"));

        // 2. 财务审核 Agent 调用 auditProcurementDraft
        AiToolDto.AuditProcurementDraftRequest auditReq = AiToolDto.AuditProcurementDraftRequest.builder()
                .draftId(draftId)
                .userId(888L)
                .build();
        AiToolDto.AuditProcurementDraftResponse auditResp = toolExecutionService.auditProcurementDraft(auditReq);
        assertNotNull(auditResp);
        assertEquals(draftId, auditResp.getDraftId());
        assertEquals("PASSED", auditResp.getAuditStatus());
        assertFalse(auditResp.getRequiresHumanApproval());
        assertTrue(auditResp.getCreditSufficient());

        // 3. 财务放行 approveProcurementFlow
        AiToolDto.ApproveProcurementFlowRequest approveReq = AiToolDto.ApproveProcurementFlowRequest.builder()
                .draftId(draftId)
                .approved(true)
                .comment("额度充足，阶梯价合规，核准放行")
                .build();
        AiToolDto.ApproveProcurementFlowResponse approveResp = toolExecutionService.approveProcurementFlow(approveReq);
        assertNotNull(approveResp);
        assertEquals("APPROVED", approveResp.getStatus());
        assertNotNull(approveResp.getApprovalToken());
        assertTrue(approveResp.getApprovalToken().startsWith("appr_"));

        // 4. 验证 executeTool 分发新工具
        AiToolDto.GenericToolCallRequest auditGenericReq = AiToolDto.GenericToolCallRequest.builder()
                .tool("lanlink_audit_procurement_draft")
                .arguments(Map.of("draftId", draftId))
                .build();
        AiToolDto.GenericToolCallResponse genericResp = toolExecutionService.executeTool(auditGenericReq);
        assertTrue(genericResp.getSuccess());
    }

    @Test
    void testMonitorDeliverySla_NormalAndWarning() {
        Order o = new Order();
        o.setOrderNo("ORD_SLA_001");
        o.setUserId(888L);
        o.setOrderStatus(2);
        o.setPayTime(LocalDateTime.now().minusHours(10));
        when(orderMapper.selectById("ORD_SLA_001")).thenReturn(o);

        LogisticsWaybill w = new LogisticsWaybill();
        w.setWaybillId(99L);
        w.setWaybillNo("LL202611019999");
        w.setOrderNo("ORD_SLA_001");
        w.setStatus("in_transit");
        w.setShipTime(LocalDateTime.now().minusHours(10));
        when(waybillMapper.selectOne(any())).thenReturn(w);

        AiToolDto.MonitorDeliverySlaRequest slaReq = AiToolDto.MonitorDeliverySlaRequest.builder()
                .orderNo("ORD_SLA_001")
                .userId(888L)
                .build();

        AiToolDto.MonitorDeliverySlaResponse slaResp = toolExecutionService.monitorDeliverySla(slaReq);
        assertNotNull(slaResp);
        assertEquals("ORD_SLA_001", slaResp.getOrderNo());
        assertEquals("NORMAL", slaResp.getSlaStatus());
        assertFalse(slaResp.getHasAlert());
        assertEquals(10L, slaResp.getElapsedHours());
    }
}
