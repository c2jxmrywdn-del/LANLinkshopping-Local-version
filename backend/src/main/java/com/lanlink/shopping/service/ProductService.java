package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.storage.StorageUrls;
import com.lanlink.shopping.entity.AuditLog;
import com.lanlink.shopping.entity.Category;
import com.lanlink.shopping.entity.Enterprise;
import com.lanlink.shopping.entity.Industry;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.integration.event.ProductLifecycleEvent;
import com.lanlink.shopping.mapper.AuditLogMapper;
import com.lanlink.shopping.mapper.CategoryMapper;
import com.lanlink.shopping.mapper.EnterpriseMapper;
import com.lanlink.shopping.mapper.IndustryMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.pricemonitor.service.AnalysisSafetyFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商城与商品服务
 */
@Service
public class ProductService {

    private final ProductMapper productMapper;
    private final MerchantMapper merchantMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final CategoryMapper categoryMapper;
    private final IndustryMapper industryMapper;
    private final AuditLogMapper auditLogMapper;
    private final AuditService auditService;
    private final com.lanlink.shopping.module.pricemonitor.service.PriceMonitorService priceMonitorService;
    /** 语义搜索（可空：单参构造/单测兼容；null 时关键词走原 LIKE） */
    @Autowired(required = false)
    private com.lanlink.shopping.module.search.SearchService searchService;
    /** 事件发布器：用于触发商品生命周期向量同步事件 */
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;
    /** 商品知识 outbox（可空：单测兼容）；写操作同事务落任务，worker 事务外幂等嵌入 */
    @Autowired(required = false)
    private com.lanlink.shopping.module.aikb.KbOutboxService kbOutboxService;

    private void publishLifecycleEvent(Long prodId, ProductLifecycleEvent.Action action) {
        if (eventPublisher != null && prodId != null && action != null) {
            try {
                eventPublisher.publishEvent(new ProductLifecycleEvent(this, prodId, action));
            } catch (Exception ignored) {}
        }
    }

    private void enqueueKbSync(Long prodId) {
        if (kbOutboxService == null || prodId == null) return;
        try {
            kbOutboxService.enqueue(prodId, "SYNC");
        } catch (Exception e) {
            // outbox 表缺失/迁移异常不能反噬商品主事务；定时全量 reconcile 仍是最终兜底。
            org.slf4j.LoggerFactory.getLogger(ProductService.class)
                    .warn("[product-kb-outbox] enqueue prodId={} failed: {}", prodId, e.getMessage());
        }
    }

    /** 通知自动审核监听器（事务提交后异步消费；事件器缺失时静默跳过，不影响主流程）。 */
    private void publishReviewRequested(Long prodId,
                                        com.lanlink.shopping.module.productreview.event.ProductReviewRequestedEvent.Reason reason) {
        if (eventPublisher == null || prodId == null) return;
        try {
            eventPublisher.publishEvent(new com.lanlink.shopping.module.productreview.event.ProductReviewRequestedEvent(
                    this, prodId, reason));
        } catch (Exception ignored) {
            // 自动审核是旁路增强，事件发布失败不得回滚商品发布
        }
    }

    @Autowired
    public ProductService(ProductMapper productMapper,
                          @Autowired(required = false) MerchantMapper merchantMapper,
                          @Autowired(required = false) EnterpriseMapper enterpriseMapper,
                          @Autowired(required = false) CategoryMapper categoryMapper,
                          @Autowired(required = false) IndustryMapper industryMapper,
                          @Autowired(required = false) AuditLogMapper auditLogMapper,
                          @Autowired(required = false) AuditService auditService,
                          @Autowired(required = false) com.lanlink.shopping.module.pricemonitor.service.PriceMonitorService priceMonitorService) {
        this.productMapper = productMapper;
        this.merchantMapper = merchantMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.categoryMapper = categoryMapper;
        this.industryMapper = industryMapper;
        this.auditLogMapper = auditLogMapper;
        this.auditService = auditService;
        this.priceMonitorService = priceMonitorService;
    }

    public ProductService(ProductMapper productMapper) {
        this(productMapper, null, null, null, null, null, null, null);
    }

    /** 分页查询(前台): 关键词/分类/行业/排序。所有分支追加 prod_id 决胜键——非唯一排序键下的
     * LIMIT/OFFSET 跨页顺序不保证，曾导致分页丢项/重复项（批量跑批与商城翻页同受影响）。
     * 口径与五维因子分析链（ProductFactorAutoService）/个性化推流一致：仅「上架且过审」，
     * 待审核商品不再出现在商城与价格监控列表（deleted 由 @TableLogic 自动追加）。 */
    public IPage<Product> page(long current, long size, String keyword, Long catId, Long indId, String sort) {
        LambdaQueryWrapper<Product> qw = Wrappers.<Product>lambdaQuery()
                .eq(Product::getStatus, 1)
                .eq(Product::getReviewStatus, 1)
                .like(StringUtils.hasText(keyword), Product::getTitle, keyword)
                .eq(catId != null, Product::getCatId, catId)
                .eq(indId != null, Product::getIndId, indId);
        if ("sales".equals(sort)) {
            qw.orderByDesc(Product::getSales);
        } else if ("priceAsc".equals(sort)) {
            qw.orderByAsc(Product::getPrice);
        } else if ("priceDesc".equals(sort)) {
            qw.orderByDesc(Product::getPrice);
        } else {
            qw.orderByDesc(Product::getCreateTime);
        }
        qw.orderByDesc(Product::getProdId);
        return productMapper.selectPage(new Page<>(current, size), qw);
    }

    /**
     * 商品搜索（P1-2 语义搜索）：jieba 分词多词 LIKE + bge-m3 向量 KNN 双路 RRF 融合。
     * searchService 未注入（单测兼容）时走原 LIKE；SearchService 内部含向量开关与降级链，
     * catId/indId 结构化筛选存在时仍走原精确过滤链（语义路不带结构化筛选）。
     */
    public IPage<Product> searchPage(long current, long size, String keyword, Long catId, Long indId, String sort) {
        return searchPage(current, size, keyword, catId, indId, sort, null, null);
    }

    /**
     * 商品搜索（多目标融合版）：userId/ll_analytics Cookie 透传给语义搜索作 Per 分量
     * （双信号门控在 SearchService 内闭环，任一缺失 Per=0）。分支条件与回退链同上版：
     * catId/indId 结构化筛选存在时仍走原 LIKE 精确链，响应结构不变。
     */
    public IPage<Product> searchPage(long current, long size, String keyword, Long catId, Long indId, String sort,
                                     Long userId, String analyticsCookie) {
        if (StringUtils.hasText(keyword) && catId == null && indId == null && searchService != null) {
            try {
                return searchService.search(keyword, current, size, userId, analyticsCookie);
            } catch (Exception e) {
                // 语义搜索任何异常回退原 LIKE（可回滚承诺）
            }
        }
        return page(current, size, keyword, catId, indId, sort);
    }

    public Product detail(Long id) {
        Product p = productMapper.selectById(id);
        if (p == null) throw new BusinessException("商品不存在或已下架");
        return p;
    }

    /**
     * 前台商品详情：仅可售(审核通过且上架)商品对用户可见
     */
    public Product detailForPublic(Long id) {
        Product p = detail(id);
        if (p.getStatus() == null || p.getStatus() != 1) throw new BusinessException("商品不存在或已下架");
        // 回填类目名称：前端习惯画像的类目维度依赖此字段
        if (p.getCatId() != null) {
            Category cat = categoryMapper.selectById(p.getCatId());
            if (cat != null) p.setCatName(cat.getName());
        }
        return p;
    }

    /**
     * 商户发布商品：进入平台审核流程。
     * 发布后自动置为「待审核」(review_status=0)且不可售(status=0)；
     * 审核通过后由 review() 置为可售(status=1)并开放购买。
     */
    @Transactional
    public Product publish(Long merId, Product p) {
        validatePublish(p);
        p.setMerId(merId);
        p.setStatus(0);            // 不可售：待审核
        p.setReviewStatus(0);      // 待审核
        p.setRejectReason(null);
        p.setSales(0);
        p.setCreateTime(LocalDateTime.now());
        p.setUpdateTime(LocalDateTime.now());
        productMapper.insert(p);
        // 价格智能监控：记录首发价（走势图起点）
        if (priceMonitorService != null && p.getPrice() != null) {
            priceMonitorService.recordPriceChange(p, null, p.getPrice(), "MERCHANT", null);
        }
        enqueueKbSync(p.getProdId());
        // 新发布商品进入自动审核队列（事务提交后异步执行，不阻塞发布响应）
        publishReviewRequested(p.getProdId(),
                com.lanlink.shopping.module.productreview.event.ProductReviewRequestedEvent.Reason.NEW_PUBLISH);
        return p;
    }

    /** 品牌/规格长度上限（LLM01 注入面收窄）：与 t_product.brand VARCHAR(64) / spec VARCHAR(128) 对齐并留余量 */
    private static final int BRAND_MAX_LEN = 50;
    private static final int SPEC_MAX_LEN = 128;
    /** 商品描述长度上限（与前端表单 maxlength 同口径） */
    private static final int DETAIL_MAX_LEN = 2000;
    /** 主图 URL 长度上限（与 t_product.cover_url VARCHAR(255) 对齐） */
    private static final int COVER_URL_MAX_LEN = 255;

    /** 发布信息合规校验（与前端表单规则一致，服务端兜底） */
    private void validatePublish(Product p) {
        // 商户可控文本（标题/品牌/规格）入库前清洗指令式标记（LLM01）：
        // 这些字段经每日快照进入向量知识库与 RAG/客服/价格分析上下文，须先去注入载体再校验长度
        if (p.getTitle() != null) p.setTitle(AnalysisSafetyFilter.sanitize(p.getTitle().trim()));
        if (p.getBrand() != null) p.setBrand(AnalysisSafetyFilter.sanitize(p.getBrand().trim()));
        if (p.getSpec() != null) p.setSpec(AnalysisSafetyFilter.sanitize(p.getSpec().trim()));
        if (p.getTitle() == null || p.getTitle().isBlank()
                || p.getTitle().length() < 2 || p.getTitle().length() > 100) {
            throw new BusinessException("商品名称需 2-100 个字符");
        }
        if (p.getBrand() != null && p.getBrand().length() > BRAND_MAX_LEN) {
            throw new BusinessException("品牌需不超过 " + BRAND_MAX_LEN + " 个字符");
        }
        if (p.getSpec() != null && p.getSpec().length() > SPEC_MAX_LEN) {
            throw new BusinessException("规格需不超过 " + SPEC_MAX_LEN + " 个字符");
        }
        if (p.getDetail() != null && p.getDetail().length() > DETAIL_MAX_LEN) {
            throw new BusinessException("商品描述需不超过 " + DETAIL_MAX_LEN + " 个字符");
        }
        // A2「库内一律存 key」：前端把上传响应的含 base 段 URL 直接入库（/products/products/prod_x.webp），
        // 这里统一截回裸 key（products/prod_x.webp）；存量裸 key 与外域 URL 原样放行（StorageUrls.toKey 语义）
        if (p.getCoverUrl() != null) {
            p.setCoverUrl(StorageUrls.toKey(p.getCoverUrl().trim()));
        }
        if (p.getCoverUrl() != null && p.getCoverUrl().length() > COVER_URL_MAX_LEN) {
            throw new BusinessException("商品主图 URL 过长（最多 " + COVER_URL_MAX_LEN + " 个字符）");
        }
        if (p.getPrice() == null || p.getPrice().compareTo(BigDecimal.ZERO) <= 0
                || p.getPrice().compareTo(new BigDecimal("9999999")) > 0) {
            throw new BusinessException("价格需为 0.01 ~ 9999999 之间的数值");
        }
        if (p.getStock() == null || p.getStock() < 1 || p.getStock() > 999999) {
            throw new BusinessException("库存需为 1 ~ 999999 之间的整数");
        }
        if (p.getCatId() == null || p.getIndId() == null) {
            throw new BusinessException("请选择行业与商品分类");
        }
        if (p.getCoverUrl() == null || p.getCoverUrl().isBlank()) {
            throw new BusinessException("请上传商品主图");
        }
    }

    /** 商户自己的商品列表（含审核状态，供商户端展示） */
    public IPage<Product> myPage(Long merId, long current, long size) {
        return myPage(merId, current, size, null, null, null);
    }

    /** 商户自己的商品列表（可按标题关键词/审核状态/可售状态过滤） */
    public IPage<Product> myPage(Long merId, long current, long size, String keyword,
                                 Integer reviewStatus, Integer status) {
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return productMapper.selectPage(new Page<>(current, size),
                Wrappers.<Product>lambdaQuery()
                        .eq(Product::getMerId, merId)
                        .like(kw != null, Product::getTitle, kw)
                        .eq(reviewStatus != null, Product::getReviewStatus, reviewStatus)
                        .eq(status != null, Product::getStatus, status)
                        .orderByDesc(Product::getCreateTime));
    }

    /**
     * 平台审核：通过 → 审核状态置 1 且自动转为可售(status=1)，开放购买权限；
     * 驳回 → 审核状态置 2，保持不可售并记录驳回原因。
     * 状态机：已通过(review_status=1)的商品不再允许重复审核（需商户改稿重新送审）；
     * 条件更新仅 SET 审核相关列，影响行数=0 即并发状态已变化，禁止整实体写回覆盖价格/库存等列。
     */
    @Transactional
    public Product review(Long prodId, Integer reviewStatus, String reason) {
        Product p = productMapper.selectById(prodId);
        if (p == null) throw new BusinessException("商品不存在");
        if (reviewStatus == null || (reviewStatus != 1 && reviewStatus != 2)) {
            throw new BusinessException("审核状态不合法");
        }
        if (reviewStatus == 2 && !StringUtils.hasText(reason)) {
            throw new BusinessException("驳回时必须填写原因");
        }
        int affected = productMapper.update(null, Wrappers.<Product>lambdaUpdate()
                .eq(Product::getProdId, prodId)
                .and(w -> w.isNull(Product::getReviewStatus).or().ne(Product::getReviewStatus, 1))
                .set(Product::getReviewStatus, reviewStatus)
                .set(Product::getRejectReason, reviewStatus == 2 ? reason : null)
                .set(Product::getStatus, reviewStatus == 1 ? 1 : 0)
                .set(Product::getUpdateTime, LocalDateTime.now()));
        if (affected == 0) {
            throw new BusinessException("商品已审核通过或状态已变更，请刷新后重试");
        }
        p.setReviewStatus(reviewStatus);
        p.setRejectReason(reviewStatus == 2 ? reason : null);
        p.setStatus(reviewStatus == 1 ? 1 : 0);
        p.setUpdateTime(LocalDateTime.now());
        if (reviewStatus == 1) {
            publishLifecycleEvent(prodId, ProductLifecycleEvent.Action.APPROVED);
        } else if (reviewStatus == 2) {
            publishLifecycleEvent(prodId, ProductLifecycleEvent.Action.REJECTED);
        }
        enqueueKbSync(prodId);
        return p;
    }

    /** 管理后台: 全量商品分页(含已下架), 可按状态/关键词筛选 */
    public IPage<Product> adminPage(long current, long size, Integer status, String keyword) {
        return adminPage(current, size, status, null, null, null, keyword);
    }

    /** 管理后台: 多维组合筛选商品分页（状态/审核状态/行业/分类/关键词） */
    public IPage<Product> adminPage(long current, long size, Integer status, Integer reviewStatus, Long indId, Long catId, String keyword) {
        return productMapper.selectPage(new Page<>(current, size),
                Wrappers.<Product>lambdaQuery()
                        .eq(status != null, Product::getStatus, status)
                        .eq(reviewStatus != null, Product::getReviewStatus, reviewStatus)
                        .eq(indId != null, Product::getIndId, indId)
                        .eq(catId != null, Product::getCatId, catId)
                        .like(StringUtils.hasText(keyword), Product::getTitle, keyword)
                        .orderByDesc(Product::getCreateTime));
    }

    /**
     * 自动审核决策落库（仅处理仍处于待审核且不可售的商品，条件原子更新）：
     * 通过→review_status=1/status=1；驳回→review_status=2/status=0 + 驳回原因。
     * 影响行数=0 表示已被人工/并发处理，直接放弃本次自动结论。
     */
    @Transactional
    public boolean applyAutoReviewDecision(Long prodId, boolean approved, String reason, int score) {
        if (prodId == null) return false;
        String safeReason = approved ? null : truncateReason(reason);
        int affected = productMapper.update(null, Wrappers.<Product>lambdaUpdate()
                .eq(Product::getProdId, prodId)
                .eq(Product::getReviewStatus, 0)
                .eq(Product::getStatus, 0)
                .set(Product::getReviewStatus, approved ? 1 : 2)
                .set(Product::getStatus, approved ? 1 : 0)
                .set(Product::getRejectReason, safeReason)
                .set(Product::getReviewSource, "AUTO")
                .set(Product::getAutoScore, score)
                .set(Product::getAutoReviewedAt, LocalDateTime.now())
                .set(Product::getUpdateTime, LocalDateTime.now()));
        if (affected == 0) return false;
        publishLifecycleEvent(prodId, approved
                ? ProductLifecycleEvent.Action.APPROVED : ProductLifecycleEvent.Action.REJECTED);
        enqueueKbSync(prodId);
        return true;
    }

    private static String truncateReason(String reason) {
        if (reason == null || reason.isBlank()) return "自动审核未通过";
        String s = reason.trim();
        return s.length() > 255 ? s.substring(0, 255) : s;
    }

    /** 管理后台: 商品审核统计看板 */
    public Map<String, Object> adminStats() {
        List<Product> all = productMapper.selectList(null);
        long total = all.size();
        long pending = all.stream().filter(p -> p.getReviewStatus() != null && p.getReviewStatus() == 0).count();
        long approved = all.stream().filter(p -> p.getReviewStatus() != null && p.getReviewStatus() == 1).count();
        long rejected = all.stream().filter(p -> p.getReviewStatus() != null && p.getReviewStatus() == 2).count();
        long onSale = all.stream().filter(p -> p.getStatus() != null && p.getStatus() == 1).count();
        long offShelf = all.stream().filter(p -> p.getStatus() != null && p.getStatus() == 0).count();
        double passRate = total > 0 ? (double) approved / total * 100.0 : 0.0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("pending", pending);
        out.put("approved", approved);
        out.put("rejected", rejected);
        out.put("onSale", onSale);
        out.put("offShelf", offShelf);
        out.put("passRate", Math.round(passRate * 10.0) / 10.0);
        return out;
    }

    /** 管理后台: 商品审核全景详情与合规初筛校验报告 */
    public Map<String, Object> adminDetail(Long prodId) {
        Product p = productMapper.selectById(prodId);
        if (p == null) throw new BusinessException("商品不存在");

        Merchant m = merchantMapper != null && p.getMerId() != null ? merchantMapper.selectById(p.getMerId()) : null;
        Enterprise ent = enterpriseMapper != null && m != null && m.getEntId() != null ? enterpriseMapper.selectById(m.getEntId()) : null;
        Category cat = categoryMapper != null && p.getCatId() != null ? categoryMapper.selectById(p.getCatId()) : null;
        Industry ind = industryMapper != null && p.getIndId() != null ? industryMapper.selectById(p.getIndId()) : null;

        // 广告法违禁词合规初筛扫描
        List<String> bannedHits = new ArrayList<>();
        String[] bannedWords = {"最优质", "全网第一", "顶级", "国家级", "垄断", "绝无仅有", "包治百病", "万能", "绝对", "首家", "极品"};
        String textToScan = (p.getTitle() != null ? p.getTitle() : "") + " " + (p.getDetail() != null ? p.getDetail() : "");
        for (String w : bannedWords) {
            if (textToScan.contains(w)) bannedHits.add(w);
        }

        Map<String, Object> compliance = new LinkedHashMap<>();
        compliance.put("hasBannedWords", !bannedHits.isEmpty());
        compliance.put("bannedWordsHit", bannedHits);
        // 前端契约字段：管理端抽屉按 violations/riskLevel/merchantQualified 渲染
        compliance.put("violations", bannedHits);
        compliance.put("riskLevel", bannedHits.isEmpty() ? "LOW" : "HIGH");
        compliance.put("merchantQualified", m != null && m.getReviewStatus() != null && m.getReviewStatus() == 1);
        compliance.put("priceValid", p.getPrice() != null && p.getPrice().compareTo(BigDecimal.ZERO) > 0);
        compliance.put("stockValid", p.getStock() != null && p.getStock() > 0);
        compliance.put("hasCover", StringUtils.hasText(p.getCoverUrl()));
        compliance.put("merchantApproved", m != null && m.getReviewStatus() != null && m.getReviewStatus() == 1);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("product", p);
        out.put("merchant", m);
        out.put("enterpriseName", ent != null ? ent.getName() : "华晨建材");
        out.put("categoryName", cat != null ? cat.getName() : "通用类目");
        out.put("industryName", ind != null ? ind.getName() : "工业制造");
        out.put("compliance", compliance);
        return out;
    }

    /** 管理后台: 批量审核商品 */
    @Transactional
    public Map<String, Object> batchReview(List<Long> prodIds, Integer reviewStatus, String reason, Long adminUserId, HttpServletRequest request) {
        if (prodIds == null || prodIds.isEmpty()) throw new BusinessException("请选择要批量审核的商品");
        int success = 0;
        for (Long id : prodIds) {
            try {
                review(id, reviewStatus, reason);
                success++;
            } catch (Exception ignored) {}
        }
        if (auditService != null) {
            auditService.record(adminUserId, "PRODUCT_BATCH_REVIEW",
                    "批量审核商品共 " + success + "/" + prodIds.size() + " 条，状态=" + reviewStatus + "，原因=" + (reason != null ? reason : "无"), request);
        }
        return Map.of("total", prodIds.size(), "success", success);
    }

    /** 管理后台: 获取商品审核流转审计历史 */
    public List<AuditLog> auditLogs(Long prodId) {
        if (auditLogMapper == null) return List.of();
        return auditLogMapper.selectList(Wrappers.<AuditLog>lambdaQuery()
                .like(AuditLog::getDetail, "商品 #" + prodId)
                .or().like(AuditLog::getDetail, "「" + prodId + "」")
                .orderByDesc(AuditLog::getCreateTime));
    }

    /** 管理后台: 上架/下架（条件更新：上架仅允许审核通过商品，避免未审核商品绕过审核直接可售） */
    @Transactional
    public Product setStatus(Long prodId, Integer status) {
        if (status == null || (status != 0 && status != 1)) throw new BusinessException("商品状态不合法");
        Product p = productMapper.selectById(prodId);
        if (p == null) throw new BusinessException("商品不存在");
        int affected = productMapper.update(null, Wrappers.<Product>lambdaUpdate()
                .eq(Product::getProdId, prodId)
                .eq(status == 1, Product::getReviewStatus, 1)
                .set(Product::getStatus, status)
                .set(Product::getUpdateTime, LocalDateTime.now()));
        if (affected == 0) {
            throw new BusinessException(status == 1 ? "商品未通过审核，不能上架" : "商品状态已变更，请刷新后重试");
        }
        p.setStatus(status);
        p.setUpdateTime(LocalDateTime.now());
        if (status == 1) {
            publishLifecycleEvent(prodId, ProductLifecycleEvent.Action.ON_SHELF);
        } else {
            publishLifecycleEvent(prodId, ProductLifecycleEvent.Action.OFF_SHELF);
        }
        enqueueKbSync(prodId);
        return p;
    }

    // ===== 商户端：商品编辑与自主上下架（归属校验 + 内容变更重新送审） =====

    /** 商户自己的商品详情（归属校验，不含已逻辑删除商品） */
    public Product myDetail(Long merId, Long prodId) {
        Product p = productMapper.selectById(prodId);
        if (p == null || !merId.equals(p.getMerId())) throw new BusinessException("商品不存在或无权操作");
        return p;
    }

    /**
     * 商户编辑自己的商品：
     * - 仅调整库存：即时生效，不影响审核状态与可售状态；
     * - 修改标题/价格/分类/主图/详情等内容字段：重置为「待审核」且不可售，平台复审通过后自动恢复可售。
     * 价格变动同步写入价格监控留痕（同事务，触发订阅预警）。
     */
    @Transactional
    public Product myUpdate(Long merId, Long prodId, Product patch) {
        Product p = myDetail(merId, prodId);
        boolean contentChanged = false;
        BigDecimal priceBefore = p.getPrice();
        if (patch.getTitle() != null && !patch.getTitle().equals(p.getTitle())) {
            // 与发布链一致：标题入库前清洗指令式标记（LLM01，标题随快照进入向量知识库）
            p.setTitle(AnalysisSafetyFilter.sanitize(patch.getTitle().trim()));
            contentChanged = true;
        }
        if (patch.getPrice() != null && (p.getPrice() == null || patch.getPrice().compareTo(p.getPrice()) != 0)) {
            p.setPrice(patch.getPrice());
            contentChanged = true;
        }
        if (patch.getBrand() != null && !patch.getBrand().equals(p.getBrand())) {
            String brand = AnalysisSafetyFilter.sanitize(patch.getBrand().trim());
            if (brand.length() > BRAND_MAX_LEN) {
                throw new BusinessException("品牌需不超过 " + BRAND_MAX_LEN + " 个字符");
            }
            p.setBrand(brand);
            contentChanged = true;
        }
        if (patch.getSpec() != null && !patch.getSpec().equals(p.getSpec())) {
            String spec = AnalysisSafetyFilter.sanitize(patch.getSpec().trim());
            if (spec.length() > SPEC_MAX_LEN) {
                throw new BusinessException("规格需不超过 " + SPEC_MAX_LEN + " 个字符");
            }
            p.setSpec(spec);
            contentChanged = true;
        }
        if (patch.getIndId() != null && !patch.getIndId().equals(p.getIndId())) {
            p.setIndId(patch.getIndId());
            contentChanged = true;
        }
        if (patch.getCatId() != null && !patch.getCatId().equals(p.getCatId())) {
            p.setCatId(patch.getCatId());
            contentChanged = true;
        }
        if (patch.getCoverUrl() != null && !patch.getCoverUrl().equals(p.getCoverUrl())) {
            // 与发布链同口径（A2「库内一律存 key」）：含 base 段的 URL 先截回裸 key 再入库
            String coverKey = StorageUrls.toKey(patch.getCoverUrl().trim());
            if (coverKey.length() > COVER_URL_MAX_LEN) {
                throw new BusinessException("商品主图 URL 过长（最多 " + COVER_URL_MAX_LEN + " 个字符）");
            }
            p.setCoverUrl(coverKey);
            contentChanged = true;
        }
        if (patch.getDetail() != null && !patch.getDetail().equals(p.getDetail())) {
            String detail = patch.getDetail().trim();
            if (detail.length() > DETAIL_MAX_LEN) {
                throw new BusinessException("商品描述需不超过 " + DETAIL_MAX_LEN + " 个字符");
            }
            p.setDetail(detail);
            contentChanged = true;
        }
        if (patch.getStock() != null) {
            if (patch.getStock() < 1 || patch.getStock() > 999999) {
                throw new BusinessException("库存需为 1 ~ 999999 之间的整数");
            }
            p.setStock(patch.getStock());
        }
        if (contentChanged) {
            if (p.getTitle() == null || p.getTitle().length() < 2 || p.getTitle().length() > 100) {
                throw new BusinessException("商品名称需 2-100 个字符");
            }
            if (p.getPrice() == null || p.getPrice().compareTo(BigDecimal.ZERO) <= 0
                    || p.getPrice().compareTo(new BigDecimal("9999999")) > 0) {
                throw new BusinessException("价格需为 0.01 ~ 9999999 之间的数值");
            }
            if (p.getCatId() == null || p.getIndId() == null) {
                throw new BusinessException("请选择行业与商品分类");
            }
            if (p.getCoverUrl() == null || p.getCoverUrl().isBlank()) {
                throw new BusinessException("请上传商品主图");
            }
            // 内容变更重新送审：下架 + 待审核，复审通过自动恢复可售
            p.setStatus(0);
            p.setReviewStatus(0);
            p.setRejectReason(null);
        }
        p.setUpdateTime(LocalDateTime.now());
        productMapper.updateById(p);
        if (contentChanged) {
            publishLifecycleEvent(prodId, ProductLifecycleEvent.Action.CONTENT_UPDATED);
        } else if (p.getStatus() != null && p.getStatus() == 1 && p.getReviewStatus() != null && p.getReviewStatus() == 1) {
            publishLifecycleEvent(prodId, ProductLifecycleEvent.Action.PRICE_UPDATED);
        }
        // 价格智能监控：调价留痕 + 预警评估（同事务）
        if (priceMonitorService != null && priceBefore != null && p.getPrice() != null && priceBefore.compareTo(p.getPrice()) != 0) {
            priceMonitorService.recordPriceChange(p, priceBefore, p.getPrice(), "MERCHANT", null);
        }
        enqueueKbSync(prodId);
        if (contentChanged) {
            // 内容变更重新送审：事务提交后重新触发自动审核
            publishReviewRequested(prodId,
                    com.lanlink.shopping.module.productreview.event.ProductReviewRequestedEvent.Reason.CONTENT_UPDATED);
        }
        return p;
    }

    /** 商户自主上下架：仅审核通过的商品可操作，可反复上下架（条件更新避免与平台审核并发互相覆盖） */
    @Transactional
    public Product mySetStatus(Long merId, Long prodId, Integer status) {
        if (status == null || (status != 0 && status != 1)) throw new BusinessException("商品状态不合法");
        Product p = myDetail(merId, prodId);
        int affected = productMapper.update(null, Wrappers.<Product>lambdaUpdate()
                .eq(Product::getProdId, prodId)
                .eq(Product::getMerId, merId)
                .eq(Product::getReviewStatus, 1)
                .set(Product::getStatus, status)
                .set(Product::getUpdateTime, LocalDateTime.now()));
        if (affected == 0) {
            throw new BusinessException("仅审核通过的商品可上架/下架");
        }
        p.setStatus(status);
        p.setUpdateTime(LocalDateTime.now());
        if (status == 1) {
            publishLifecycleEvent(prodId, ProductLifecycleEvent.Action.ON_SHELF);
        } else {
            publishLifecycleEvent(prodId, ProductLifecycleEvent.Action.OFF_SHELF);
        }
        enqueueKbSync(prodId);
        return p;
    }
}
