package com.lanlink.shopping.module.aitool.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Category;
import com.lanlink.shopping.entity.CreditAccount;
import com.lanlink.shopping.entity.Enterprise;
import com.lanlink.shopping.entity.LogisticsAlert;
import com.lanlink.shopping.entity.LogisticsTrace;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.integration.pricing.TierPricing;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CategoryMapper;
import com.lanlink.shopping.mapper.CreditAccountMapper;
import com.lanlink.shopping.mapper.EnterpriseMapper;
import com.lanlink.shopping.mapper.LogisticsAlertMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.aikb.KnowledgeRagService;
import com.lanlink.shopping.module.aitool.dto.AiToolDto;
import com.lanlink.shopping.module.pricemonitor.service.AnalysisSafetyFilter;
import com.lanlink.shopping.service.LogisticsWaybillService;
import com.lanlink.shopping.service.OrderService;
import com.lanlink.shopping.service.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * AI 商城核心工具执行服务
 * 封装 6 大标准电商原子能力：
 * 1. lanlink_search_products (商品现货检索)
 * 2. lanlink_get_product_detail (规格与阶梯价)
 * 3. lanlink_calculate_quotation (实时动态大宗询价试算)
 * 4. lanlink_create_cart_draft (加购意向单与结算深链，人在回路)
 * 5. lanlink_query_knowledge (政企采购与政策问答检索)
 * 6. lanlink_track_logistics (物流轨迹跟踪与订单履约，含 PII 脱敏与防 IDOR)
 */
@Service
public class AiToolExecutionService {

    private static final Logger log = LoggerFactory.getLogger(AiToolExecutionService.class);
    private static final String CART_DRAFT_PREFIX = "aikb:cart_draft:";
    private static final int DRAFT_EXPIRE_MINUTES = 30;

    private final ProductService productService;
    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final MerchantMapper merchantMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final KnowledgeRagService knowledgeRagService;
    private final OrderMapper orderMapper;
    private final LogisticsWaybillService waybillService;
    private final LogisticsWaybillMapper waybillMapper;
    private final CreditAccountMapper creditAccountMapper;
    private final LogisticsAlertMapper logisticsAlertMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private static final int LOCAL_DRAFT_MAX = 1000;
    // 内存降级缓存（Redis 不可用时保障草稿单功能）；有界 LRU + TTL，避免长期运行泄漏。
    private final LinkedHashMap<String, LocalDraft> localDraftCache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, LocalDraft> eldest) {
                    return size() > LOCAL_DRAFT_MAX;
                }
            };

    private record LocalDraft(String json, long expiresAt) {}

    @Autowired
    public AiToolExecutionService(ProductService productService,
                                 @Autowired(required = false) ProductMapper productMapper,
                                 @Autowired(required = false) CategoryMapper categoryMapper,
                                 @Autowired(required = false) MerchantMapper merchantMapper,
                                 @Autowired(required = false) EnterpriseMapper enterpriseMapper,
                                 @Autowired(required = false) KnowledgeRagService knowledgeRagService,
                                 @Autowired(required = false) OrderMapper orderMapper,
                                 @Autowired(required = false) LogisticsWaybillService waybillService,
                                 @Autowired(required = false) LogisticsWaybillMapper waybillMapper,
                                 @Autowired(required = false) CreditAccountMapper creditAccountMapper,
                                 @Autowired(required = false) LogisticsAlertMapper logisticsAlertMapper,
                                 @Autowired(required = false) StringRedisTemplate redis,
                                 @Autowired(required = false) ObjectMapper objectMapper) {
        this.productService = productService;
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.merchantMapper = merchantMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.knowledgeRagService = knowledgeRagService;
        this.orderMapper = orderMapper;
        this.waybillService = waybillService;
        this.waybillMapper = waybillMapper;
        this.creditAccountMapper = creditAccountMapper;
        this.logisticsAlertMapper = logisticsAlertMapper;
        this.redis = redis;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public AiToolExecutionService(ProductService productService,
                                 ProductMapper productMapper,
                                 CategoryMapper categoryMapper,
                                 MerchantMapper merchantMapper,
                                 EnterpriseMapper enterpriseMapper,
                                 KnowledgeRagService knowledgeRagService,
                                 OrderMapper orderMapper,
                                 LogisticsWaybillService waybillService,
                                 LogisticsWaybillMapper waybillMapper,
                                 StringRedisTemplate redis,
                                 ObjectMapper objectMapper) {
        this(productService, productMapper, categoryMapper, merchantMapper, enterpriseMapper,
             knowledgeRagService, orderMapper, waybillService, waybillMapper, null, null, redis, objectMapper);
    }

    // ==================== 1. 商品与现货检索 ====================

    public AiToolDto.SearchProductsResponse searchProducts(AiToolDto.SearchProductsRequest req) {
        if (req == null || !StringUtils.hasText(req.getKeyword())) {
            throw new BusinessException("搜索关键词不能为空");
        }
        String cleanKeyword = AnalysisSafetyFilter.sanitize(req.getKeyword().trim());
        int limit = (req.getLimit() != null && req.getLimit() > 0) ? Math.min(req.getLimit(), 20) : 5;
        String sort = StringUtils.hasText(req.getSort()) ? req.getSort() : "sales";

        IPage<Product> page = productService.page(1, limit, cleanKeyword, req.getCatId(), req.getIndId(), sort);
        List<Product> records = page != null && page.getRecords() != null ? page.getRecords() : Collections.emptyList();

        List<AiToolDto.ProductSummaryVo> items = new ArrayList<>();
        for (Product p : records) {
            // 严格过滤仅审核通过(reviewStatus=1)且在售(status=1)商品
            if (p.getStatus() == null || p.getStatus() != 1) continue;
            if (p.getReviewStatus() != null && p.getReviewStatus() != 1) continue;

            // 价格区间过滤
            if (req.getMinPrice() != null && p.getPrice() != null && p.getPrice().compareTo(req.getMinPrice()) < 0) continue;
            if (req.getMaxPrice() != null && p.getPrice() != null && p.getPrice().compareTo(req.getMaxPrice()) > 0) continue;

            // 现货库存过滤
            int stock = p.getStock() != null ? p.getStock() : 0;
            if (Boolean.TRUE.equals(req.getInStockOnly()) && stock <= 0) continue;

            String stockStatus = stock > 10 ? "IN_STOCK" : (stock > 0 ? "LOW_STOCK" : "OUT_OF_STOCK");
            boolean hasTierPricing = StringUtils.hasText(p.getTierPriceJson()) && !p.getTierPriceJson().equals("[]");

            // 回填分类名与商户企业名
            String catName = p.getCatName();
            if (!StringUtils.hasText(catName) && p.getCatId() != null && categoryMapper != null) {
                try {
                    Category cat = categoryMapper.selectById(p.getCatId());
                    if (cat != null) catName = cat.getName();
                } catch (Exception ignored) {}
            }

            String merchantName = "平台自营";
            boolean verifiedSupplier = false;
            if (p.getMerId() != null && merchantMapper != null) {
                try {
                    Merchant m = merchantMapper.selectById(p.getMerId());
                    if (m != null && m.getEntId() != null && enterpriseMapper != null) {
                        Enterprise ent = enterpriseMapper.selectById(m.getEntId());
                        if (ent != null && StringUtils.hasText(ent.getName())) {
                            merchantName = ent.getName();
                            verifiedSupplier = StringUtils.hasText(ent.getCreditCode());
                        }
                    }
                } catch (Exception ignored) {}
            }

            items.add(AiToolDto.ProductSummaryVo.builder()
                    .prodId(p.getProdId())
                    .title(p.getTitle())
                    .brand(p.getBrand() != null ? p.getBrand() : "通用")
                    .spec(p.getSpec() != null ? p.getSpec() : "标准规格")
                    .price(p.getPrice())
                    .stock(stock)
                    .stockStatus(stockStatus)
                    .sales(p.getSales() != null ? p.getSales() : 0)
                    .coverUrl(p.getCoverUrl())
                    .catName(catName)
                    .merchantName(merchantName)
                    .verifiedSupplier(verifiedSupplier)
                    .hasTierPricing(hasTierPricing)
                    .moq(1)
                    .build());
        }

        return AiToolDto.SearchProductsResponse.builder()
                .total(page != null ? page.getTotal() : (long) items.size())
                .items(items)
                .keyword(cleanKeyword)
                .build();
    }

    // ==================== 2. 商品规格与阶梯价详情 ====================

    public AiToolDto.ProductDetailResponse getProductDetail(AiToolDto.ProductDetailRequest req) {
        if (req == null || req.getProductId() == null) {
            throw new BusinessException("商品ID不能为空");
        }
        Product p = productService.detailForPublic(req.getProductId());
        if (p == null) {
            throw new BusinessException("商品不存在或已下架");
        }

        int stock = p.getStock() != null ? p.getStock() : 0;
        String stockStatus = stock > 10 ? "IN_STOCK" : (stock > 0 ? "LOW_STOCK" : "OUT_OF_STOCK");

        // 解析阶梯价数组
        List<AiToolDto.TierPriceItemVo> tierList = parseTierPricing(p.getTierPriceJson(), p.getPrice());

        int moq = 1;
        if (!tierList.isEmpty() && tierList.get(0).getMinQty() != null && tierList.get(0).getMinQty() > 1) {
            moq = tierList.get(0).getMinQty();
        }

        // 查找供应商企业认证
        String enterpriseName = "平台优质供应商";
        String certification = "已通过平台企业资质核验";
        boolean isDirect = true;
        if (p.getMerId() != null && merchantMapper != null) {
            try {
                Merchant m = merchantMapper.selectById(p.getMerId());
                if (m != null && m.getEntId() != null && enterpriseMapper != null) {
                    Enterprise ent = enterpriseMapper.selectById(m.getEntId());
                    if (ent != null) {
                        enterpriseName = ent.getName();
                        certification = (ent.getMemberLevel() != null && ent.getMemberLevel() > 0)
                                ? "ISO9001认证 / 平台官方认证企业供应商" : "企业实名认证";
                    }
                }
            } catch (Exception ignored) {}
        }

        return AiToolDto.ProductDetailResponse.builder()
                .prodId(p.getProdId())
                .title(p.getTitle())
                .brand(p.getBrand() != null ? p.getBrand() : "通用品牌")
                .spec(p.getSpec() != null ? p.getSpec() : "标准件")
                .basePrice(p.getPrice())
                .stock(stock)
                .stockStatus(stockStatus)
                .moq(moq)
                .unit("件/套")
                .coverUrl(p.getCoverUrl())
                .detail(p.getDetail())
                .tierPricing(tierList)
                .supplier(AiToolDto.SupplierInfoVo.builder()
                        .merchantId(p.getMerId())
                        .enterpriseName(enterpriseName)
                        .certification(certification)
                        .isDirectManufacturer(isDirect)
                        .build())
                .policy(AiToolDto.ProcurementPolicyVo.builder()
                        .vatInvoice("支持开具13%增值税专用发票（订单确认收货后3个工作日内开具寄送）")
                        .warranty("365天原厂品质保证，随单附带产品出厂合格证与检测报告")
                        .shippingPolicy("现货订单48小时内发货，支持顺丰/德邦专线直达")
                        .returnPolicy("质量不符假一赔十，入库7天内未拆包支持无损换退")
                        .build())
                .build();
    }

    // ==================== 3. 动态阶梯价与税费试算 ====================

    public AiToolDto.CalculateQuotationResponse calculateQuotation(AiToolDto.CalculateQuotationRequest req) {
        if (req == null || req.getProductId() == null || req.getQuantity() == null || req.getQuantity() <= 0) {
            throw new BusinessException("商品ID和采购数量为必填项且数量必须大于0");
        }
        Product p = productService.detailForPublic(req.getProductId());
        if (p == null) {
            throw new BusinessException("商品不存在或已下架");
        }

        BigDecimal basePrice = p.getPrice() != null ? p.getPrice() : BigDecimal.ZERO;
        int qty = req.getQuantity();

        List<AiToolDto.TierPriceItemVo> tierList = parseTierPricing(p.getTierPriceJson(), basePrice);
        int moq = 1;
        if (!tierList.isEmpty() && tierList.get(0).getMinQty() != null && tierList.get(0).getMinQty() > 1) {
            moq = tierList.get(0).getMinQty();
        }
        boolean meetsMoq = qty >= moq;

        // 与下单同一口径：取「满足条件的最大档位」，不再遍历取最低价
        TierPricing.Resolved resolved = TierPricing.resolve(basePrice, p.getTierPriceJson(), qty);
        BigDecimal effectiveUnitPrice = resolved.unitPrice();
        String appliedTierDesc = resolved.appliedTierQty() > 0
                ? String.format("≥%d件阶梯价", resolved.appliedTierQty())
                : "基准现货零售单价";

        BigDecimal goodsTotal = effectiveUnitPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal originalTotal = basePrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal savings = originalTotal.subtract(goodsTotal).max(BigDecimal.ZERO);

        // 运费测算：采购金额满 5000 元或数量满 300 件包邮；否则收取标准工业快件运费 35 元
        BigDecimal freight = BigDecimal.ZERO;
        if (goodsTotal.compareTo(BigDecimal.valueOf(5000)) < 0 && qty < 300) {
            freight = new BigDecimal("35.00");
        }

        // 13% 增值税额测算 = goodsTotal / 1.13 * 0.13
        BigDecimal estimatedVatTax = goodsTotal.multiply(new BigDecimal("0.13"))
                .divide(new BigDecimal("1.13"), 2, RoundingMode.HALF_UP);

        BigDecimal finalTotal = goodsTotal.add(freight);

        String summary = String.format("采购 %d 件【%s】，单价 ¥%.2f（%s），货款共计 ¥%.2f，运费 ¥%.2f（%s），总计 ¥%.2f（含 13%% 专票税额约 ¥%.2f）。",
                qty, p.getTitle(), effectiveUnitPrice, appliedTierDesc, goodsTotal, freight,
                freight.compareTo(BigDecimal.ZERO) == 0 ? "已享包邮" : "标准专线运费", finalTotal, estimatedVatTax);

        return AiToolDto.CalculateQuotationResponse.builder()
                .prodId(p.getProdId())
                .title(p.getTitle())
                .quantity(qty)
                .moq(moq)
                .meetsMoq(meetsMoq)
                .originalUnitPrice(basePrice)
                .effectiveUnitPrice(effectiveUnitPrice)
                .appliedTierDesc(appliedTierDesc)
                .goodsTotalAmount(goodsTotal)
                .estimatedFreight(freight)
                .estimatedVatTax(estimatedVatTax)
                .finalTotalAmount(finalTotal)
                .totalSavings(savings)
                .quotationSummary(summary)
                .build();
    }

    // ==================== 4. 生成采购草稿与结算深链（人在回路） ====================

    public AiToolDto.CreateCartDraftResponse createCartDraft(AiToolDto.CreateCartDraftRequest req) {
        if (req == null || req.getProductId() == null || req.getQuantity() == null || req.getQuantity() <= 0) {
            throw new BusinessException("商品ID与订购数量必填");
        }
        AiToolDto.CalculateQuotationResponse quote = calculateQuotation(
                AiToolDto.CalculateQuotationRequest.builder()
                        .productId(req.getProductId())
                        .quantity(req.getQuantity())
                        .build());

        String draftId = "dft_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        Map<String, Object> draftData = new LinkedHashMap<>();
        draftData.put("draftId", draftId);
        draftData.put("prodId", req.getProductId());
        draftData.put("title", quote.getTitle());
        draftData.put("quantity", req.getQuantity());
        draftData.put("effectiveUnitPrice", quote.getEffectiveUnitPrice());
        draftData.put("finalTotalAmount", quote.getFinalTotalAmount());
        draftData.put("userId", req.getUserId());
        draftData.put("remark", req.getRemark());
        draftData.put("createTime", LocalDateTime.now().toString());

        try {
            String json = objectMapper.writeValueAsString(draftData);
            if (redis != null) {
                redis.opsForValue().set(CART_DRAFT_PREFIX + draftId, json, DRAFT_EXPIRE_MINUTES, TimeUnit.MINUTES);
            }
            putLocalDraft(draftId, json);
        } catch (Exception e) {
            log.error("[ai-tool] failed to persist cart draft to redis/cache", e);
            // 暂存失败时不能返回一个无法核实的 draftId，否则外部 Agent 会把死链交给用户
            throw new BusinessException("购物车草稿暂存失败，请稍后重试");
        }

        // 构造面向商城前端的一键核对支付深链
        String checkoutUrl = "/checkout?draftId=" + draftId + "&prodId=" + req.getProductId() + "&qty=" + req.getQuantity();

        return AiToolDto.CreateCartDraftResponse.builder()
                .draftId(draftId)
                .prodId(req.getProductId())
                .title(quote.getTitle())
                .quantity(req.getQuantity())
                .effectiveUnitPrice(quote.getEffectiveUnitPrice())
                .finalTotalAmount(quote.getFinalTotalAmount())
                .checkoutDeepLink(checkoutUrl)
                .expireMinutes(DRAFT_EXPIRE_MINUTES)
                .status("PENDING_USER_CONFIRMATION")
                .confirmationRequired(true)
                .instruction("已为您锁定当前阶梯折扣，请点击一键购买链接在商城前端核对地址及开票信息并完成支付。")
                .build();
    }

    // ==================== 5. 政策与采购知识库检索 ====================

    public AiToolDto.QueryKnowledgeResponse queryKnowledge(AiToolDto.QueryKnowledgeRequest req) {
        if (req == null || !StringUtils.hasText(req.getQuery())) {
            throw new BusinessException("查询问题不能为空");
        }
        String cleanQuery = AnalysisSafetyFilter.sanitize(req.getQuery().trim());
        int topK = (req.getTopK() != null && req.getTopK() > 0) ? Math.min(req.getTopK(), 5) : 3;

        List<AiToolDto.KnowledgeHitVo> hits = new ArrayList<>();
        if (knowledgeRagService != null) {
            try {
                String sourceFilter = (StringUtils.hasText(req.getScope()) && !"all".equalsIgnoreCase(req.getScope()))
                        ? req.getScope().trim() : null;
                var recalls = knowledgeRagService.searchHybrid(cleanQuery, topK, sourceFilter);
                if (recalls != null) {
                    for (var r : recalls) {
                        hits.add(AiToolDto.KnowledgeHitVo.builder()
                                .title(r.title() != null ? r.title() : "平台采购政策条款")
                                .category(r.source() != null ? r.source() : "policy")
                                .content(r.text())
                                .relevanceScore(r.score())
                                .build());
                    }
                }
            } catch (Exception e) {
                log.warn("[ai-tool] knowledge hybrid recall failed, fallback empty", e);
            }
        }

        return AiToolDto.QueryKnowledgeResponse.builder()
                .query(cleanQuery)
                .count(hits.size())
                .hits(hits)
                .noAnswer(hits.isEmpty())
                .build();
    }

    private static final Set<String> POLICY_SOURCES = Set.of(
            "policy", "kb", "membership", "subscription", "logistics", "manual", "learned");

    /**
     * 统一只读知识检索：外部 LLM/Agent 通过该入口读取本地知识，不直连 Redis 或 MySQL。
     * 商品命中批量回源当前价格/库存；政策 scope 只返回平台稳定资料，排除新闻。
     */
    public AiToolDto.KnowledgeSearchResponse searchKnowledge(AiToolDto.KnowledgeSearchRequest req,
                                                              Long tenantMerId,
                                                              String requestId) {
        if (req == null || !StringUtils.hasText(req.getQuery())) {
            throw new BusinessException("查询问题不能为空");
        }
        long t0 = System.currentTimeMillis();
        String cleanQuery = AnalysisSafetyFilter.sanitize(req.getQuery().trim());
        int topK = Math.max(1, Math.min(req.getTopK() == null ? 5 : req.getTopK(), 10));
        double minScore = Math.max(0.0, Math.min(req.getMinScore() == null ? 0.30 : req.getMinScore(), 1.0));
        String scope = req.getScope() == null || req.getScope().isBlank() ? "all" : req.getScope().trim().toLowerCase();
        Long effectiveMerId = tenantMerId != null ? tenantMerId : req.getMerId();
        if (tenantMerId != null && req.getMerId() != null && !tenantMerId.equals(req.getMerId())) {
            throw new BusinessException("FORBIDDEN: API Key 无权查询该商户知识");
        }

        boolean retrievalFailed = knowledgeRagService == null;
        List<com.lanlink.shopping.module.aikb.RedisVectorStore.Hit> candidates = List.of();
        if (!retrievalFailed) {
            try {
                String sourceFilter = "product".equals(scope) ? "product" : null;
                candidates = knowledgeRagService.searchHybrid(cleanQuery, Math.min(topK * 4, 20), sourceFilter);
            } catch (Exception e) {
                retrievalFailed = true;
                log.warn("[ai-tool] knowledge search recall failed: {}", e.getMessage());
            }
        }

        List<AiToolDto.KnowledgeSearchHitVo> hits = new ArrayList<>();
        if (!retrievalFailed) {
            List<com.lanlink.shopping.module.aikb.RedisVectorStore.Hit> eligible = new ArrayList<>();
            for (var hit : candidates) {
                if (hit != null && hit.score() >= minScore && sourceAllowed(hit.source(), scope)) eligible.add(hit);
            }
            Map<Long, Product> products = loadProductMap(eligible);
            java.util.Set<Long> seenProductIds = new java.util.HashSet<>();
            for (var hit : eligible) {
                if (hits.size() >= topK) break;
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("trust", "product".equals(hit.source()) ? "merchant" : "platform");
                metadata.put("docType", hit.source());
                Map<String, Object> liveData = null;

                if ("product".equals(hit.source())) {
                    Long productId = productIdFromRef(hit.refId());
                    Product p = productId == null ? null : products.get(productId);
                    if (p == null) continue;
                    if (effectiveMerId != null && !effectiveMerId.equals(p.getMerId())) continue;
                    if (req.getCatId() != null && !req.getCatId().equals(p.getCatId())) continue;
                    if (req.getIndId() != null && !req.getIndId().equals(p.getIndId())) continue;
                    if (!seenProductIds.add(p.getProdId())) continue;
                    metadata.put("merId", p.getMerId());
                    metadata.put("catId", p.getCatId());
                    metadata.put("indId", p.getIndId());
                    metadata.put("status", p.getStatus());
                    metadata.put("reviewStatus", p.getReviewStatus());
                    if (p.getUpdateTime() != null) metadata.put("sourceUpdatedAt", p.getUpdateTime().toString());
                    if (Boolean.TRUE.equals(req.getIncludeLiveData())) liveData = liveProductData(p);
                } else {
                    if (effectiveMerId != null) continue; // 平台政策不属于单一商户
                    if (req.getCatId() != null || req.getIndId() != null) continue;
                }

                hits.add(AiToolDto.KnowledgeSearchHitVo.builder()
                        .docId(hit.id())
                        .refId(hit.refId())
                        .source(hit.source())
                        .title(hit.title() == null ? "平台知识" : hit.title())
                        .snippet(truncate(hit.text(), 500))
                        .score(round4(hit.score()))
                        .updatedAt(String.valueOf(metadata.getOrDefault("sourceUpdatedAt", "")))
                        .metadata(metadata)
                        .liveData(liveData)
                        .build());
            }
        }

        String status;
        if (retrievalFailed) status = "RETRIEVAL_FAILED";
        else if (!hits.isEmpty()) status = hits.size() >= topK ? "PARTIAL" : "ANSWERED";
        else if (eligibleCount(candidates, minScore, scope) > 0) status = "FILTERED_EMPTY";
        else status = "NO_ANSWER";

        return AiToolDto.KnowledgeSearchResponse.builder()
                .requestId(requestId)
                .query(cleanQuery)
                .count(hits.size())
                .noAnswer("NO_ANSWER".equals(status) || "FILTERED_EMPTY".equals(status))
                .status(status)
                .hits(hits)
                .latencyMs(System.currentTimeMillis() - t0)
                .build();
    }

    private static boolean sourceAllowed(String source, String scope) {
        if (source == null || "intent".equals(source)) return false;
        if ("product".equals(source)) return !"policy".equals(scope);
        if ("news".equals(source)) return false;
        if ("policy".equals(scope)) return POLICY_SOURCES.contains(source);
        return true;
    }

    private static int eligibleCount(List<com.lanlink.shopping.module.aikb.RedisVectorStore.Hit> hits,
                                     double minScore, String scope) {
        int count = 0;
        for (var hit : hits) {
            if (hit != null && hit.score() >= minScore && sourceAllowed(hit.source(), scope)) count++;
        }
        return count;
    }

    private Map<Long, Product> loadProductMap(List<com.lanlink.shopping.module.aikb.RedisVectorStore.Hit> hits) {
        if (productMapper == null) return Map.of();
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
        for (var hit : hits) {
            Long id = productIdFromRef(hit.refId());
            if (id != null) ids.add(id);
        }
        if (ids.isEmpty()) return Map.of();
        Map<Long, Product> out = new LinkedHashMap<>();
        try {
            List<Product> products = productMapper.selectBatchIds(ids);
            if (products != null) {
                for (Product p : products) {
                    if (p == null || p.getProdId() == null) continue;
                    if (p.getDeleted() != null && p.getDeleted() != 0) continue;
                    if (p.getStatus() == null || p.getStatus() != 1) continue;
                    if (p.getReviewStatus() == null || p.getReviewStatus() != 1) continue;
                    out.put(p.getProdId(), p);
                }
            }
        } catch (Exception e) {
            log.warn("[ai-tool] batch product lookup failed: {}", e.getMessage());
        }
        return out;
    }

    private static Long productIdFromRef(String refId) {
        if (refId == null || refId.isBlank()) return null;
        int dash = refId.indexOf("-en");
        String idPart = dash > 0 ? refId.substring(0, dash) : refId;
        try {
            return Long.valueOf(idPart.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> liveProductData(Product p) {
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("prodId", p.getProdId());
        live.put("price", p.getPrice());
        live.put("stock", p.getStock() == null ? 0 : p.getStock());
        live.put("status", p.getStatus());
        live.put("reviewStatus", p.getReviewStatus());
        return live;
    }

    private static String truncate(String value, int max) {
        String v = value == null ? "" : value;
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    // ==================== 6. 物流轨迹与订单跟踪（含 PII 掩码） ====================

    public AiToolDto.TrackLogisticsResponse trackLogistics(AiToolDto.TrackLogisticsRequest req) {
        if (req == null || !StringUtils.hasText(req.getOrderNo())) {
            throw new BusinessException("订单编号不能为空");
        }
        String orderNo = req.getOrderNo().trim();

        if (orderMapper == null) {
            throw new BusinessException("订单系统未就绪");
        }
        Order order = orderMapper.selectById(orderNo);
        if (order == null) {
            throw new BusinessException("未找到对应的订单记录，请核对订单号是否正确");
        }

        // 防 BOLA / IDOR 越权拦截：如果指定了 userId，必须严格归属于当前用户
        if (req.getUserId() != null && !req.getUserId().equals(order.getUserId())) {
            log.warn("[ai-tool] IDOR blocked: requested order {} belonging to user {}, but requested by user {}",
                    orderNo, order.getUserId(), req.getUserId());
            throw new BusinessException("FORBIDDEN: 无权查询该订单的物流与收件信息");
        }

        // PII 脱敏处理
        String maskedReceiver = AiToolGuard.maskName(order.getReceiver());
        String maskedPhone = AiToolGuard.maskPhone(order.getPhone());
        String maskedAddress = AiToolGuard.maskAddress(order.getAddress());

        String waybillNo = "未生成运单";
        String carrierName = "顺丰速运 / 德邦大件专线";
        String expressNo = "待分配物流单号";
        String currentStatus = "PREPARING";
        List<AiToolDto.LogisticsTraceNodeVo> traceNodes = new ArrayList<>();

        if (waybillMapper != null) {
            try {
                LogisticsWaybill waybill = waybillMapper.selectOne(
                        Wrappers.<LogisticsWaybill>lambdaQuery().eq(LogisticsWaybill::getOrderNo, orderNo));
                if (waybill != null) {
                    waybillNo = waybill.getWaybillNo();
                    expressNo = waybill.getLogisticsNo() != null ? waybill.getLogisticsNo() : waybillNo;
                    currentStatus = waybill.getStatus();

                    if (waybillService != null && waybill.getWaybillId() != null) {
                        // 归属校验与 HTTP 端点同源：IDOR 守卫已保证 attributed userId 为订单属主；
                        // 未归属（userId==null）时 fail-closed 拒绝读取，降级为通用兜底节点
                        List<LogisticsTrace> traces = waybillService.traces(waybill.getWaybillId(), req.getUserId(), false);
                        if (traces != null) {
                            for (LogisticsTrace t : traces) {
                                traceNodes.add(AiToolDto.LogisticsTraceNodeVo.builder()
                                        .time(t.getTraceTime() != null ? t.getTraceTime().toString() : "")
                                        .location(t.getLocation() != null ? t.getLocation() : "枢纽转运中心")
                                        .status(t.getStatus() != null ? t.getStatus() : "运输中")
                                        .description(t.getDescription())
                                        .build());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[ai-tool] query waybill trace failed", e);
            }
        }

        if (traceNodes.isEmpty()) {
            traceNodes.add(AiToolDto.LogisticsTraceNodeVo.builder()
                    .time(LocalDateTime.now().toString())
                    .location("商家发货仓")
                    .status("已打包待揽收")
                    .description("商品已完成打包质检，等待承运专车揽件发往转运中心")
                    .build());
        }

        Integer oStatus = order.getOrderStatus();
        String orderStatusStr = oStatus != null ? String.valueOf(oStatus) : "1";
        String statusDesc = "已付款待发货";
        if (Integer.valueOf(2).equals(oStatus) || "in_transit".equalsIgnoreCase(currentStatus) || "shipped".equalsIgnoreCase(currentStatus)) {
            statusDesc = "运输途中，预计次日送达";
        } else if (Integer.valueOf(3).equals(oStatus) || "delivered".equalsIgnoreCase(currentStatus) || "signed".equalsIgnoreCase(currentStatus)) {
            statusDesc = "已成功签收";
        } else if (Integer.valueOf(0).equals(oStatus)) {
            statusDesc = "待付款";
        } else if (Integer.valueOf(4).equals(oStatus)) {
            statusDesc = "已取消";
        }

        return AiToolDto.TrackLogisticsResponse.builder()
                .orderNo(orderNo)
                .orderStatus(orderStatusStr)
                .orderStatusDesc(statusDesc)
                .receiverName(maskedReceiver)
                .receiverPhone(maskedPhone)
                .destination(maskedAddress)
                .waybillNo(waybillNo)
                .carrierName(carrierName)
                .expressNo(expressNo)
                .currentStatus(currentStatus)
                .traces(traceNodes)
                .build();
    }

    // ==================== 7. 采购意向草稿单财务审核（财务审核 Agent） ====================

    public AiToolDto.AuditProcurementDraftResponse auditProcurementDraft(AiToolDto.AuditProcurementDraftRequest req) {
        if (req == null || !StringUtils.hasText(req.getDraftId())) {
            throw new BusinessException("草稿单ID不能为空");
        }
        String draftId = req.getDraftId().trim();
        Map<String, Object> draftData = getDraftData(draftId);
        if (draftData == null) {
            throw new BusinessException("草稿单不存在或已失效: " + draftId);
        }

        Long prodId = parseLong(draftData.get("prodId"));
        Integer qty = parseInt(draftData.get("quantity"));
        String title = (String) draftData.getOrDefault("title", "未命名商品");
        BigDecimal finalAmount = parseBigDecimal(draftData.get("finalTotalAmount"));
        BigDecimal effectiveUnitPrice = parseBigDecimal(draftData.get("effectiveUnitPrice"));

        // 获取原商品基准价
        BigDecimal baseUnitPrice = effectiveUnitPrice;
        if (prodId != null) {
            Product p = productService.detailForPublic(prodId);
            if (p != null && p.getPrice() != null) {
                baseUnitPrice = p.getPrice();
            }
        }

        // 计算单价偏离度: (effectiveUnitPrice - baseUnitPrice) / baseUnitPrice
        BigDecimal deviationRate = BigDecimal.ZERO;
        if (baseUnitPrice != null && baseUnitPrice.compareTo(BigDecimal.ZERO) > 0 && effectiveUnitPrice != null) {
            deviationRate = effectiveUnitPrice.subtract(baseUnitPrice).divide(baseUnitPrice, 4, RoundingMode.HALF_UP);
        }

        // 获取企业授信账户信息
        Long effectiveUserId = req.getUserId() != null ? req.getUserId() : parseLong(draftData.get("userId"));
        CreditAccount account = null;
        if (req.getEnterpriseId() != null && creditAccountMapper != null) {
            try {
                account = creditAccountMapper.selectOne(Wrappers.<CreditAccount>lambdaQuery()
                        .eq(CreditAccount::getEntId, req.getEnterpriseId())
                        .last("LIMIT 1"));
            } catch (Exception ignored) {}
        }
        if (account == null && effectiveUserId != null && creditAccountMapper != null) {
            try {
                account = creditAccountMapper.selectOne(Wrappers.<CreditAccount>lambdaQuery()
                        .eq(CreditAccount::getUserId, effectiveUserId)
                        .last("LIMIT 1"));
            } catch (Exception ignored) {}
        }

        BigDecimal availableCredit = (account != null && account.getAvailableLimit() != null)
                ? account.getAvailableLimit()
                : new BigDecimal("100000.00"); // 默认基准授信，若企业有记录则以真实为准
        if (account != null && account.getAvailableLimit() != null) {
            availableCredit = account.getAvailableLimit();
        }

        boolean creditSufficient = finalAmount != null && availableCredit.compareTo(finalAmount) >= 0;
        String riskLevel = (account != null && StringUtils.hasText(account.getRiskLevel())) ? account.getRiskLevel() : "LOW";

        BigDecimal maxAutoApproval = new BigDecimal("50000.00");
        String auditStatus = "PASSED";
        boolean requiresHuman = false;
        String remark = "财务合规风控初审通过：订单总额在授信额度内，阶梯采购单价在合规区间内。";

        if (finalAmount != null && finalAmount.compareTo(maxAutoApproval) > 0) {
            auditStatus = "REQUIRES_HUMAN_APPROVAL";
            requiresHuman = true;
            remark = "采购总额(¥" + finalAmount + ")超过系统自动免审上限(¥" + maxAutoApproval + ")，需财务总监人工核准。";
        } else if (!creditSufficient) {
            auditStatus = "REQUIRES_HUMAN_APPROVAL";
            requiresHuman = true;
            remark = "企业可用授信额度(¥" + availableCredit + ")不足以全额覆盖采购金额(¥" + finalAmount + ")，需申请临时增额或前台现付。";
        } else if (deviationRate.compareTo(new BigDecimal("-0.40")) < 0) {
            auditStatus = "REQUIRES_HUMAN_APPROVAL";
            requiresHuman = true;
            remark = "单价优惠偏离度超过40%，触发反舞弊超低价风控拦截，需人工复核。";
        }

        return AiToolDto.AuditProcurementDraftResponse.builder()
                .draftId(draftId)
                .prodId(prodId)
                .title(title)
                .quantity(qty)
                .finalTotalAmount(finalAmount)
                .effectiveUnitPrice(effectiveUnitPrice)
                .baseUnitPrice(baseUnitPrice)
                .priceDeviationRate(deviationRate)
                .availableCredit(availableCredit)
                .creditSufficient(creditSufficient)
                .riskLevel(riskLevel)
                .auditStatus(auditStatus)
                .requiresHumanApproval(requiresHuman)
                .maxAutoApprovalLimit(maxAutoApproval)
                .auditRemark(remark)
                .evaluatedAt(LocalDateTime.now().toString())
                .build();
    }

    // ==================== 8. 采购审批流放行/驳回（财务审核 Agent） ====================

    public AiToolDto.ApproveProcurementFlowResponse approveProcurementFlow(AiToolDto.ApproveProcurementFlowRequest req) {
        if (req == null || !StringUtils.hasText(req.getDraftId())) {
            throw new BusinessException("草稿单ID不能为空");
        }
        if (req.getApproved() == null) {
            throw new BusinessException("审批结论不能为空");
        }
        String draftId = req.getDraftId().trim();
        Map<String, Object> draftData = getDraftData(draftId);
        if (draftData == null) {
            throw new BusinessException("草稿单不存在或已失效: " + draftId);
        }

        String status = Boolean.TRUE.equals(req.getApproved()) ? "APPROVED" : "REJECTED";
        String token = null;
        String msg;

        if (Boolean.TRUE.equals(req.getApproved())) {
            token = "appr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            draftData.put("auditStatus", "APPROVED");
            draftData.put("approvalToken", token);
            draftData.put("approvedAt", LocalDateTime.now().toString());
            draftData.put("auditorComment", req.getComment());
            msg = "财务审批通过，已签发数字放行凭证，有效期30分钟，可安全推进后续下单支付。";
        } else {
            draftData.put("auditStatus", "REJECTED");
            draftData.put("rejectionReason", req.getComment());
            msg = "财务审批驳回: " + (StringUtils.hasText(req.getComment()) ? req.getComment() : "未通过财务合规审核");
        }

        saveDraftData(draftId, draftData);

        return AiToolDto.ApproveProcurementFlowResponse.builder()
                .draftId(draftId)
                .status(status)
                .approvalToken(token)
                .approvedAt(LocalDateTime.now().toString())
                .expireMinutes(DRAFT_EXPIRE_MINUTES)
                .message(msg)
                .build();
    }

    // ==================== 9. 订单履约时效与SLA监控（履约追踪 Agent） ====================

    public AiToolDto.MonitorDeliverySlaResponse monitorDeliverySla(AiToolDto.MonitorDeliverySlaRequest req) {
        if (req == null || (!StringUtils.hasText(req.getOrderNo()) && !StringUtils.hasText(req.getWaybillNo()))) {
            throw new BusinessException("订单编号与运单编号至少需提供一项");
        }

        Order order = null;
        if (StringUtils.hasText(req.getOrderNo()) && orderMapper != null) {
            try {
                order = orderMapper.selectById(req.getOrderNo().trim());
            } catch (Exception ignored) {}
        }

        // 垂直越权检查（IDOR）
        if (order != null && req.getUserId() != null && order.getUserId() != null) {
            if (!req.getUserId().equals(order.getUserId())) {
                log.warn("[ai-tool] IDOR blocked: order {} belongs to user {}, requested by user {}",
                        req.getOrderNo(), order.getUserId(), req.getUserId());
                throw new BusinessException("FORBIDDEN: 无权访问该订单履约信息");
            }
        }

        LogisticsWaybill waybill = null;
        if (waybillMapper != null) {
            try {
                if (StringUtils.hasText(req.getWaybillNo())) {
                    waybill = waybillMapper.selectOne(Wrappers.<LogisticsWaybill>lambdaQuery()
                            .eq(LogisticsWaybill::getWaybillNo, req.getWaybillNo().trim())
                            .last("LIMIT 1"));
                } else if (order != null) {
                    waybill = waybillMapper.selectOne(Wrappers.<LogisticsWaybill>lambdaQuery()
                            .eq(LogisticsWaybill::getOrderNo, order.getOrderNo())
                            .last("LIMIT 1"));
                }
            } catch (Exception ignored) {}
        }

        // 查询异常告警
        List<AiToolDto.DeliveryAlertVo> alertVos = new ArrayList<>();
        if (waybill != null && waybill.getWaybillId() != null && logisticsAlertMapper != null) {
            try {
                List<LogisticsAlert> alerts = logisticsAlertMapper.selectList(Wrappers.<LogisticsAlert>lambdaQuery()
                        .eq(LogisticsAlert::getWaybillId, waybill.getWaybillId()));
                if (alerts != null) {
                    for (LogisticsAlert a : alerts) {
                        alertVos.add(AiToolDto.DeliveryAlertVo.builder()
                                .alertId(a.getAlertId())
                                .alertType(a.getAlertType())
                                .alertLevel(a.getAlertLevel())
                                .content(a.getContent())
                                .createTime(a.getCreateTime() != null ? a.getCreateTime().toString() : null)
                                .build());
                    }
                }
            } catch (Exception ignored) {}
        }

        // 计算耗时与 SLA 状态
        LocalDateTime startTime = null;
        if (waybill != null && waybill.getShipTime() != null) {
            startTime = waybill.getShipTime();
        } else if (order != null && order.getPayTime() != null) {
            startTime = order.getPayTime();
        } else if (order != null && order.getCreateTime() != null) {
            startTime = order.getCreateTime();
        }

        long elapsedHours = 12; // 默认平稳
        if (startTime != null) {
            elapsedHours = Math.max(0, java.time.Duration.between(startTime, LocalDateTime.now()).toHours());
        }

        String slaStatus = "NORMAL";
        String warningMsg = null;
        String suggestedAction = "货物运输履约推进平稳，暂无需人工跟进。";

        boolean hasAlert = !alertVos.isEmpty();
        if (hasAlert) {
            boolean isCritical = alertVos.stream().anyMatch(a -> "high".equalsIgnoreCase(a.getAlertLevel()) || "damage".equalsIgnoreCase(a.getAlertType()) || "lost".equalsIgnoreCase(a.getAlertType()));
            slaStatus = isCritical ? "CRITICAL_DELAY" : "DELAY_WARNING";
            warningMsg = "检测到 " + alertVos.size() + " 条运输异常告警: " + alertVos.get(0).getContent();
            suggestedAction = "建议履约追踪 Agent 立即向承运商专线客服下发工单核实，并向买家推送主动关怀与时效补偿预案。";
        } else if (elapsedHours > 72 && (waybill == null || !"delivered".equalsIgnoreCase(waybill.getStatus()))) {
            slaStatus = "DELAY_WARNING";
            warningMsg = "订单运输累计耗时已达 " + elapsedHours + " 小时，超出标准 48 小时交付基准。";
            suggestedAction = "建议触发物流催揽预警机制，核对干线中转站包裹状态。";
        }

        String carrier = (waybill != null && waybill.getProviderId() != null) ? "顺丰速运/德邦专线" : "平台认证专线";
        String currentStatus = (waybill != null && waybill.getStatus() != null) ? waybill.getStatus() : (order != null ? "STATUS_" + order.getOrderStatus() : "in_transit");

        return AiToolDto.MonitorDeliverySlaResponse.builder()
                .orderNo(order != null ? order.getOrderNo() : req.getOrderNo())
                .waybillNo(waybill != null ? waybill.getWaybillNo() : req.getWaybillNo())
                .slaStatus(slaStatus)
                .elapsedHours(elapsedHours)
                .estimatedDeliveryTime(LocalDateTime.now().plusDays(1).toLocalDate().toString() + " 18:00")
                .carrierName(carrier)
                .currentStatus(currentStatus)
                .hasAlert(hasAlert)
                .alerts(alertVos)
                .warningMessage(warningMsg)
                .suggestedAction(suggestedAction)
                .build();
    }

    // ==================== 通用工具统一调度入口 ====================

    public AiToolDto.GenericToolCallResponse executeTool(AiToolDto.GenericToolCallRequest req) {
        return executeTool(req, null, UUID.randomUUID().toString());
    }

    public AiToolDto.GenericToolCallResponse executeTool(AiToolDto.GenericToolCallRequest req,
                                                          Long tenantMerId,
                                                          String requestId) {
        if (req == null || !StringUtils.hasText(req.getTool())) {
            return AiToolDto.GenericToolCallResponse.builder()
                    .tool(req != null ? req.getTool() : "unknown")
                    .success(false)
                    .error("工具名称不能为空")
                    .build();
        }

        long start = System.currentTimeMillis();
        String toolName = req.getTool().trim();
        Map<String, Object> args = req.getArguments() != null ? req.getArguments() : Collections.emptyMap();

        try {
            Object result;
            switch (toolName) {
                case "lanlink_search_products": {
                    AiToolDto.SearchProductsRequest pReq = objectMapper.convertValue(args, AiToolDto.SearchProductsRequest.class);
                    result = searchProducts(pReq);
                    break;
                }
                case "lanlink_get_product_detail": {
                    AiToolDto.ProductDetailRequest dReq = objectMapper.convertValue(args, AiToolDto.ProductDetailRequest.class);
                    result = getProductDetail(dReq);
                    break;
                }
                case "lanlink_calculate_quotation": {
                    AiToolDto.CalculateQuotationRequest cReq = objectMapper.convertValue(args, AiToolDto.CalculateQuotationRequest.class);
                    result = calculateQuotation(cReq);
                    break;
                }
                case "lanlink_create_cart_draft": {
                    AiToolDto.CreateCartDraftRequest crReq = objectMapper.convertValue(args, AiToolDto.CreateCartDraftRequest.class);
                    if (crReq.getUserId() == null && req.getUserId() != null) {
                        crReq.setUserId(req.getUserId());
                    }
                    result = createCartDraft(crReq);
                    break;
                }
                case "lanlink_query_knowledge": {
                    AiToolDto.QueryKnowledgeRequest kReq = objectMapper.convertValue(args, AiToolDto.QueryKnowledgeRequest.class);
                    result = queryKnowledge(kReq);
                    break;
                }
                case "lanlink_search_knowledge": {
                    AiToolDto.KnowledgeSearchRequest kReq = objectMapper.convertValue(args, AiToolDto.KnowledgeSearchRequest.class);
                    result = searchKnowledge(kReq, tenantMerId, requestId);
                    break;
                }
                case "lanlink_track_logistics": {
                    AiToolDto.TrackLogisticsRequest tReq = objectMapper.convertValue(args, AiToolDto.TrackLogisticsRequest.class);
                    if (tReq.getUserId() == null && req.getUserId() != null) {
                        tReq.setUserId(req.getUserId());
                    }
                    result = trackLogistics(tReq);
                    break;
                }
                case "lanlink_audit_procurement_draft": {
                    AiToolDto.AuditProcurementDraftRequest aReq = objectMapper.convertValue(args, AiToolDto.AuditProcurementDraftRequest.class);
                    if (aReq.getUserId() == null && req.getUserId() != null) {
                        aReq.setUserId(req.getUserId());
                    }
                    result = auditProcurementDraft(aReq);
                    break;
                }
                case "lanlink_approve_procurement_flow": {
                    AiToolDto.ApproveProcurementFlowRequest apReq = objectMapper.convertValue(args, AiToolDto.ApproveProcurementFlowRequest.class);
                    result = approveProcurementFlow(apReq);
                    break;
                }
                case "lanlink_monitor_delivery_sla": {
                    AiToolDto.MonitorDeliverySlaRequest mReq = objectMapper.convertValue(args, AiToolDto.MonitorDeliverySlaRequest.class);
                    if (mReq.getUserId() == null && req.getUserId() != null) {
                        mReq.setUserId(req.getUserId());
                    }
                    result = monitorDeliverySla(mReq);
                    break;
                }
                default:
                    return AiToolDto.GenericToolCallResponse.builder()
                            .tool(toolName)
                            .success(false)
                            .error("未知工具: " + toolName + "。请先获取工具清单，确认当前 Key 可用的工具名")
                            .latencyMs(System.currentTimeMillis() - start)
                            .build();
            }

            return AiToolDto.GenericToolCallResponse.builder()
                    .tool(toolName)
                    .success(true)
                    .data(result)
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();

        } catch (BusinessException be) {
            String message = be.getMessage() == null ? "" : be.getMessage();
            // 鉴权/限流类失败必须透出真实 HTTP 状态，不能被降级成 HTTP 200 的业务失败
            if (message.startsWith("FORBIDDEN:") || message.startsWith("401 ")
                    || message.startsWith("429 ") || message.startsWith("503 ")) {
                throw be;
            }
            return AiToolDto.GenericToolCallResponse.builder()
                    .tool(toolName)
                    .success(false)
                    .error(message)
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            log.error("[ai-tool] execute tool failed: tool={}", toolName, e);
            return AiToolDto.GenericToolCallResponse.builder()
                    .tool(toolName)
                    .success(false)
                    .error("工具执行失败，请检查参数或稍后重试")
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    // ==================== 内部阶梯价解析辅助方法 ====================

    private List<AiToolDto.TierPriceItemVo> parseTierPricing(String tierJson, BigDecimal basePrice) {
        List<AiToolDto.TierPriceItemVo> list = new ArrayList<>();
        if (!StringUtils.hasText(tierJson)) {
            return list;
        }
        // 与下单共用同一个解析器：以前这里只认 minQty/min_qty，而商品侧存的是 qty，
        // 结果"有阶梯价的商品"在 AI 报价里静默变成无阶梯价、与实付不一致。
        for (TierPricing.Tier tier : TierPricing.parse(tierJson)) {
            BigDecimal tierPrice = tier.unitPrice();
            BigDecimal rate = BigDecimal.ONE;
            if (basePrice != null && basePrice.compareTo(BigDecimal.ZERO) > 0) {
                rate = tierPrice.divide(basePrice, 2, RoundingMode.HALF_UP);
            }
            String desc = String.format("≥%d件: ¥%.2f (约%.1f折)", tier.minQty(), tierPrice, rate.multiply(BigDecimal.TEN));
            list.add(AiToolDto.TierPriceItemVo.builder()
                    .minQty(tier.minQty())
                    .unitPrice(tierPrice)
                    .discountRate(rate)
                    .desc(desc)
                    .build());
        }
        return list;
    }

    // ==================== 内部草稿与类型转换辅助方法 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> getDraftData(String draftId) {
        if (!StringUtils.hasText(draftId)) return null;
        String json = null;
        if (redis != null) {
            try {
                json = redis.opsForValue().get(CART_DRAFT_PREFIX + draftId);
            } catch (Exception ignored) {}
        }
        if (!StringUtils.hasText(json)) {
            json = getLocalDraft(draftId);
        }
        if (!StringUtils.hasText(json)) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void saveDraftData(String draftId, Map<String, Object> draftData) {
        if (!StringUtils.hasText(draftId) || draftData == null) return;
        try {
            String json = objectMapper.writeValueAsString(draftData);
            if (redis != null) {
                redis.opsForValue().set(CART_DRAFT_PREFIX + draftId, json, DRAFT_EXPIRE_MINUTES, TimeUnit.MINUTES);
            }
            putLocalDraft(draftId, json);
        } catch (Exception e) {
            log.error("[ai-tool] save draft failed", e);
        }
    }

    private synchronized void putLocalDraft(String draftId, String json) {
        localDraftCache.put(draftId, new LocalDraft(json,
                System.currentTimeMillis() + DRAFT_EXPIRE_MINUTES * 60_000L));
    }

    private synchronized String getLocalDraft(String draftId) {
        LocalDraft draft = localDraftCache.get(draftId);
        if (draft == null) return null;
        if (draft.expiresAt() < System.currentTimeMillis()) {
            localDraftCache.remove(draftId);
            return null;
        }
        return draft.json();
    }

    private Long parseLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try { return Long.parseLong(obj.toString()); } catch (Exception e) { return null; }
    }

    private Integer parseInt(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try { return Integer.parseInt(obj.toString()); } catch (Exception e) { return null; }
    }

    private BigDecimal parseBigDecimal(Object obj) {
        if (obj == null) return BigDecimal.ZERO;
        if (obj instanceof BigDecimal) return (BigDecimal) obj;
        if (obj instanceof Number) return BigDecimal.valueOf(((Number) obj).doubleValue());
        try { return new BigDecimal(obj.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
    }
}
