package com.lanlink.shopping.module.aitool.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.module.aitool.dto.AiToolDto;
import com.lanlink.shopping.module.aitool.service.AiToolExecutionService;
import com.lanlink.shopping.module.aitool.service.AiToolGuard;
import com.lanlink.shopping.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AI 商城内部核心工具控制器
 * 挂载于 /internal/ai/tools/**
 * 职责：
 * 1. 响应 AI 网关与外部经授权的 Agent 调用的 6 大电商标准工具；
 * 2. 统一经过 AiToolGuard 安全防护校验（签名秘钥/API Key 鉴权、令牌桶限流）；
 * 3. 提供 /specs 元数据接口，返回符合 OpenAI Function Calling 标准的 JSON Schema。
 */
@RestController
@RequestMapping("/internal/ai/tools")
public class AiToolsInternalController {

    private final AiToolExecutionService toolExecutionService;
    private final AiToolGuard aiToolGuard;

    @Autowired(required = false)
    private AuditService auditService;

    @Autowired
    public AiToolsInternalController(AiToolExecutionService toolExecutionService, AiToolGuard aiToolGuard) {
        this.toolExecutionService = toolExecutionService;
        this.aiToolGuard = aiToolGuard;
    }

    private AiToolGuard.AuthContext checkAuthAndRate(HttpServletRequest request, String permission) {
        AiToolGuard.AuthContext auth = aiToolGuard.authenticateContext(request);
        if (!auth.allowed()) {
            auditRejection(request, permission, auth, "unauthenticated");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "缺少或错误的内部密钥/API Key（X-Internal-Secret 或 Authorization: Bearer llk_...）");
        }
        if (!auth.hasPermission(permission)) {
            auditRejection(request, permission, auth, "scope-denied");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "API Key 缺少 scope " + permission);
        }
        if (!aiToolGuard.checkRateLimit(auth.rateClientId())) {
            auditRejection(request, permission, auth, "rate-limited");
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "工具调用频次超限，请稍后重试");
        }
        return auth;
    }

    /**
     * 访问控制拒绝必须留痕（ASVS 7.2.2）。在此集中记录可覆盖所有工具端点，
     * 包括尚未生成 requestId 的鉴权阶段。
     */
    private void auditRejection(HttpServletRequest request, String permission,
                                AiToolGuard.AuthContext auth, String reason) {
        if (auditService == null) return;
        auditService.record(null, "AI_TOOL_CALL_DENIED",
                "reason=" + reason + " scope=" + permission + " keyPrefix=" + auth.keyPrefix()
                        + " tenant=" + auth.tenantId() + " remote=" + request.getRemoteAddr(),
                request);
    }

    private void auditToolCall(AiToolGuard.AuthContext auth, HttpServletRequest request,
                               String tool, String requestId, String outcome) {
        if (auditService == null) return;
        auditService.record(null, "AI_TOOL_CALL",
                "requestId=" + requestId + " keyPrefix=" + auth.keyPrefix() + " tool=" + safeAuditField(tool)
                        + " tenant=" + auth.tenantId() + " outcome=" + safeAuditField(outcome),
                request);
    }

    /** tool 名来自请求体，去掉换行与控制字符，避免伪造审计记录（detail 列定长 500）。 */
    private static String safeAuditField(String raw) {
        if (raw == null) return "null";
        String cleaned = raw.replaceAll("[\\r\\n\\p{Cntrl}]", "?");
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    /**
     * 1. 商品与现货检索
     */
    @PostMapping("/search-products")
    public R<AiToolDto.SearchProductsResponse> searchProducts(@Valid @RequestBody AiToolDto.SearchProductsRequest req,
                                                              HttpServletRequest request) {
        checkAuthAndRate(request, "products:read");
        return R.ok("检索成功", toolExecutionService.searchProducts(req));
    }

    /**
     * 2. 商品规格与阶梯价详情
     */
    @PostMapping("/get-product-detail")
    public R<AiToolDto.ProductDetailResponse> getProductDetail(@Valid @RequestBody AiToolDto.ProductDetailRequest req,
                                                               HttpServletRequest request) {
        checkAuthAndRate(request, "products:read");
        return R.ok("获取商品详情成功", toolExecutionService.getProductDetail(req));
    }

    /**
     * 3. 动态阶梯价与税费试算
     */
    @PostMapping("/calculate-quotation")
    public R<AiToolDto.CalculateQuotationResponse> calculateQuotation(@Valid @RequestBody AiToolDto.CalculateQuotationRequest req,
                                                                      HttpServletRequest request) {
        checkAuthAndRate(request, "products:read");
        return R.ok("报价试算完成", toolExecutionService.calculateQuotation(req));
    }

    /**
     * 4. 生成采购草稿与结算深链（人在回路）
     */
    @PostMapping("/create-cart-draft")
    public R<AiToolDto.CreateCartDraftResponse> createCartDraft(@Valid @RequestBody AiToolDto.CreateCartDraftRequest req,
                                                                HttpServletRequest request) {
        checkAuthAndRate(request, "cart:write");
        return R.ok("意向草稿单已生成", toolExecutionService.createCartDraft(req));
    }

    /**
     * 5. 政策与采购知识库检索
     */
    @PostMapping("/query-knowledge")
    public R<AiToolDto.QueryKnowledgeResponse> queryKnowledge(@Valid @RequestBody AiToolDto.QueryKnowledgeRequest req,
                                                              HttpServletRequest request) {
        checkAuthAndRate(request, "knowledge:read");
        return R.ok("知识检索完成", toolExecutionService.queryKnowledge(req));
    }

    /**
     * 6. 物流轨迹与订单跟踪（含 PII 掩码）
     */
    @PostMapping("/track-logistics")
    public R<AiToolDto.TrackLogisticsResponse> trackLogistics(@Valid @RequestBody AiToolDto.TrackLogisticsRequest req,
                                                              HttpServletRequest request) {
        checkAuthAndRate(request, "orders:read");
        return R.ok("物流轨迹获取成功", toolExecutionService.trackLogistics(req));
    }

    /**
     * 7. 采购意向草稿单财务审核（财务审核 Agent）
     */
    @PostMapping("/audit-procurement-draft")
    public R<AiToolDto.AuditProcurementDraftResponse> auditProcurementDraft(@Valid @RequestBody AiToolDto.AuditProcurementDraftRequest req,
                                                                            HttpServletRequest request) {
        checkAuthAndRate(request, "procurement:audit");
        return R.ok("财务合规审核完成", toolExecutionService.auditProcurementDraft(req));
    }

    /**
     * 8. 采购审批流放行/驳回（财务审核 Agent）
     */
    @PostMapping("/approve-procurement-flow")
    public R<AiToolDto.ApproveProcurementFlowResponse> approveProcurementFlow(@Valid @RequestBody AiToolDto.ApproveProcurementFlowRequest req,
                                                                              HttpServletRequest request) {
        checkAuthAndRate(request, "procurement:approve");
        return R.ok("审批操作完成", toolExecutionService.approveProcurementFlow(req));
    }

    /**
     * 9. 订单履约时效与SLA监控（履约追踪 Agent）
     */
    @PostMapping("/monitor-delivery-sla")
    public R<AiToolDto.MonitorDeliverySlaResponse> monitorDeliverySla(@Valid @RequestBody AiToolDto.MonitorDeliverySlaRequest req,
                                                                      HttpServletRequest request) {
        checkAuthAndRate(request, "logistics:monitor");
        return R.ok("履约SLA监控完成", toolExecutionService.monitorDeliverySla(req));
    }

    /**
     * 10. 统一只读知识检索（外部大模型/Agent 的推荐入口）
     */
    @PostMapping("/knowledge-search")
    public R<AiToolDto.KnowledgeSearchResponse> knowledgeSearch(@Valid @RequestBody AiToolDto.KnowledgeSearchRequest req,
                                                                HttpServletRequest request,
                                                                HttpServletResponse response) {
        AiToolGuard.AuthContext auth = checkAuthAndRate(request, "knowledge:read");
        String requestId = UUID.randomUUID().toString();
        response.setHeader("X-Request-Id", requestId);
        String outcome = "rejected";
        try {
            AiToolDto.KnowledgeSearchResponse out = toolExecutionService.searchKnowledge(
                    req, auth.tenantId(), requestId);
            outcome = "count=" + out.getCount() + " status=" + out.getStatus();
            if ("RETRIEVAL_FAILED".equals(out.getStatus())) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "本地知识库检索暂时不可用，请稍后重试");
            }
            return R.ok("知识检索完成", out);
        } finally {
            // 拒绝与失败同样留痕，否则越权探测在服务端没有痕迹
            auditToolCall(auth, request, "lanlink_search_knowledge", requestId, outcome);
        }
    }

    /**
     * 通用调度入口：接收 { "tool": "lanlink_search_products", "arguments": { ... } }
     */
    @PostMapping("/execute")
    public R<AiToolDto.GenericToolCallResponse> execute(@Valid @RequestBody AiToolDto.GenericToolCallRequest req,
                                                        HttpServletRequest request,
                                                        HttpServletResponse response) {
        AiToolGuard.AuthContext auth = checkAuthAndRate(request, permissionForTool(req.getTool()));
        String requestId = UUID.randomUUID().toString();
        response.setHeader("X-Request-Id", requestId);
        String outcome = "rejected";
        try {
            AiToolDto.GenericToolCallResponse out = toolExecutionService.executeTool(req, auth.tenantId(), requestId);
            outcome = Boolean.TRUE.equals(out.getSuccess()) ? "success" : "failed";
            return R.ok(out);
        } finally {
            // 鉴权拒绝会向上抛出，仍需在 finally 里留痕
            auditToolCall(auth, request, req.getTool(), requestId, outcome);
        }
    }

    /**
     * 工具元数据定义发现（OpenAI Function Calling Schema 格式）。
     * 返回集合按当前 API Key scope 过滤，避免向低权限调用方泄露写工具能力。
     */
    @GetMapping("/specs")
    public R<List<Map<String, Object>>> getSpecs(HttpServletRequest request) {
        AiToolGuard.AuthContext auth = checkAuthAndRate(request, "tools:read");
        return R.ok("获取工具列表成功", visibleSpecs(auth));
    }

    private static String permissionForTool(String tool) {
        if (tool == null) return "tools:call";
        return switch (tool) {
            case "lanlink_search_products", "lanlink_get_product_detail", "lanlink_calculate_quotation" -> "products:read";
            case "lanlink_query_knowledge", "lanlink_search_knowledge" -> "knowledge:read";
            case "lanlink_track_logistics" -> "orders:read";
            case "lanlink_monitor_delivery_sla" -> "logistics:monitor";
            case "lanlink_create_cart_draft" -> "cart:write";
            case "lanlink_audit_procurement_draft" -> "procurement:audit";
            case "lanlink_approve_procurement_flow" -> "procurement:approve";
            default -> "tools:call";
        };
    }

    private List<Map<String, Object>> visibleSpecs(AiToolGuard.AuthContext auth) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> tool : getToolDefinitions()) {
            Map<?, ?> fn = (Map<?, ?>) tool.get("function");
            String permission = permissionForTool(String.valueOf(fn.get("name")));
            if (auth.hasPermission(permission)) out.add(tool);
        }
        return out;
    }

    public static List<Map<String, Object>> getToolDefinitions() {
        return List.of(
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "lanlink_search_products",
                                "description", "检索 LANLink 商城在售工业品与聚合现货。支持品类、关键词、价格区间筛选及库存排序。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "keyword", Map.of("type", "string", "description", "搜索关键词（如：'304不锈钢无缝管'、'微型断路器'）"),
                                                "catId", Map.of("type", "integer", "description", "商品分类ID（可选）"),
                                                "minPrice", Map.of("type", "number", "description", "最低价格限制"),
                                                "maxPrice", Map.of("type", "number", "description", "最高价格限制"),
                                                "inStockOnly", Map.of("type", "boolean", "description", "是否仅看有货现货，默认 true")
                                        ),
                                        "required", List.of("keyword")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "lanlink_get_product_detail",
                                "description", "获取指定商品的实时库存、起订量(MOQ)、B2B 阶梯批发价与专票/物流履约承诺。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "productId", Map.of("type", "integer", "description", "商品唯一标识 ID (productId)")
                                        ),
                                        "required", List.of("productId")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "lanlink_calculate_quotation",
                                "description", "根据采购数量实时试算大宗采购阶梯折扣单价、总金额、预估税费与运费。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "productId", Map.of("type", "integer", "description", "商品唯一标识 ID"),
                                                "quantity", Map.of("type", "integer", "description", "订购数量")
                                        ),
                                        "required", List.of("productId", "quantity")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "lanlink_create_cart_draft",
                                "description", "生成采购意向草稿单并返回一键购买结算跳转深链，供买家在前端核实确认并支付（人在回路）。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "productId", Map.of("type", "integer", "description", "商品唯一标识 ID"),
                                                "quantity", Map.of("type", "integer", "description", "采购数量")
                                        ),
                                        "required", List.of("productId", "quantity")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "lanlink_query_knowledge",
                                "description", "查询 LANLink 平台采购规则知识库：涵盖增值税专用发票开具门槛与流程、企业授信账期、售后退换货政策及运损赔付规范。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "query", Map.of("type", "string", "description", "咨询的问题（如：'如何开具13%增值税专票'）"),
                                                "scope", Map.of("type", "string", "description", "范畴过滤：policy/invoice/payment/logistics/all")
                                        ),
                                        "required", List.of("query")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "lanlink_track_logistics",
                                "description", "查询指定订单的最新履约状态、快递单号与详细物流运输节点。需提供订单编号。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "orderNo", Map.of("type", "string", "description", "平台订单编号")
                                        ),
                                        "required", List.of("orderNo")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "lanlink_audit_procurement_draft",
                                "description", "财务审核采购草稿单合规性、企业可用授信额度（t_credit_account）与单价偏离度风控核验。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "draftId", Map.of("type", "string", "description", "采购意向草稿单号 (dft_xxx)"),
                                                "enterpriseId", Map.of("type", "integer", "description", "采购企业ID（可选）"),
                                                "userId", Map.of("type", "integer", "description", "经办采购人用户ID（可选）")
                                        ),
                                        "required", List.of("draftId")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "lanlink_approve_procurement_flow",
                                "description", "财务审批流放行或驳回操作。放行时签发具有时效的审批放行 Token (approvalToken)，用于下游支付下单流程校验。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "draftId", Map.of("type", "string", "description", "采购意向草稿单号 (dft_xxx)"),
                                                "approved", Map.of("type", "boolean", "description", "审批决定：true 为核准放行，false 为合规驳回"),
                                                "comment", Map.of("type", "string", "description", "财务审核意见或驳回原因说明")
                                        ),
                                        "required", List.of("draftId", "approved")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "lanlink_monitor_delivery_sla",
                                "description", "订单与物流履约时效 SLA 监控。检测订单发货、干线运输、派送等各环节是否存在超时、滞留或异常告警预警。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "orderNo", Map.of("type", "string", "description", "平台订单编号（可选）"),
                                                "waybillNo", Map.of("type", "string", "description", "物流运单编号（可选）")
                                        )
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "lanlink_search_knowledge",
                                "description", "统一只读检索 LANLink 本地商品与采购政策知识库。返回带来源引用的片段；商品命中附带实时价格/库存，外部模型不得直连底层数据库。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "query", Map.of("type", "string", "description", "自然语言问题或商品语义查询"),
                                                "topK", Map.of("type", "integer", "description", "最多返回片段数，默认 5，最大 10"),
                                                "scope", Map.of("type", "string", "description", "all、product 或 policy，默认 all"),
                                                "catId", Map.of("type", "integer", "description", "商品类目过滤（仅 product 有效）"),
                                                "indId", Map.of("type", "integer", "description", "行业过滤（仅 product 有效）"),
                                                "minScore", Map.of("type", "number", "description", "最低相关度，默认 0.30"),
                                                "includeLiveData", Map.of("type", "boolean", "description", "商品命中是否附带实时价格/库存，默认 true")
                                        ),
                                        "required", List.of("query")
                                )
                        )
                )
        );
    }
}
