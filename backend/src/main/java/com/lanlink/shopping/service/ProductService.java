package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.AuditLog;
import com.lanlink.shopping.entity.Category;
import com.lanlink.shopping.entity.Enterprise;
import com.lanlink.shopping.entity.Industry;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.AuditLogMapper;
import com.lanlink.shopping.mapper.CategoryMapper;
import com.lanlink.shopping.mapper.EnterpriseMapper;
import com.lanlink.shopping.mapper.IndustryMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    public ProductService(ProductMapper productMapper,
                          @Autowired(required = false) MerchantMapper merchantMapper,
                          @Autowired(required = false) EnterpriseMapper enterpriseMapper,
                          @Autowired(required = false) CategoryMapper categoryMapper,
                          @Autowired(required = false) IndustryMapper industryMapper,
                          @Autowired(required = false) AuditLogMapper auditLogMapper,
                          @Autowired(required = false) AuditService auditService) {
        this.productMapper = productMapper;
        this.merchantMapper = merchantMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.categoryMapper = categoryMapper;
        this.industryMapper = industryMapper;
        this.auditLogMapper = auditLogMapper;
        this.auditService = auditService;
    }

    public ProductService(ProductMapper productMapper) {
        this(productMapper, null, null, null, null, null, null);
    }

    /** 分页查询(前台): 关键词/分类/行业/排序 */
    public IPage<Product> page(long current, long size, String keyword, Long catId, Long indId, String sort) {
        LambdaQueryWrapper<Product> qw = Wrappers.<Product>lambdaQuery()
                .eq(Product::getStatus, 1)
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
        return productMapper.selectPage(new Page<>(current, size), qw);
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
        return p;
    }

    /**
     * 商户发布商品：进入平台审核流程。
     * 发布后自动置为「待审核」(review_status=0)且不可售(status=0)；
     * 审核通过后由 review() 置为可售(status=1)并开放购买。
     */
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
        return p;
    }

    /** 发布信息合规校验（与前端表单规则一致，服务端兜底） */
    private void validatePublish(Product p) {
        if (p.getTitle() == null || p.getTitle().isBlank()
                || p.getTitle().trim().length() < 2 || p.getTitle().trim().length() > 100) {
            throw new BusinessException("商品名称需 2-100 个字符");
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
     */
    public Product review(Long prodId, Integer reviewStatus, String reason) {
        Product p = productMapper.selectById(prodId);
        if (p == null) throw new BusinessException("商品不存在");
        if (reviewStatus == null || (reviewStatus != 1 && reviewStatus != 2)) {
            throw new BusinessException("审核状态不合法");
        }
        if (reviewStatus == 2 && !StringUtils.hasText(reason)) {
            throw new BusinessException("驳回时必须填写原因");
        }
        p.setReviewStatus(reviewStatus);
        p.setRejectReason(reviewStatus == 2 ? reason : null);
        p.setStatus(reviewStatus == 1 ? 1 : 0);
        p.setUpdateTime(LocalDateTime.now());
        productMapper.updateById(p);
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

    /** 管理后台: 上架/下架 */
    public Product setStatus(Long prodId, Integer status) {
        Product p = productMapper.selectById(prodId);
        if (p == null) throw new BusinessException("商品不存在");
        p.setStatus(status);
        p.setUpdateTime(LocalDateTime.now());
        productMapper.updateById(p);
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
     */
    public Product myUpdate(Long merId, Long prodId, Product patch) {
        Product p = myDetail(merId, prodId);
        boolean contentChanged = false;
        if (patch.getTitle() != null && !patch.getTitle().equals(p.getTitle())) {
            p.setTitle(patch.getTitle().trim());
            contentChanged = true;
        }
        if (patch.getPrice() != null && (p.getPrice() == null || patch.getPrice().compareTo(p.getPrice()) != 0)) {
            p.setPrice(patch.getPrice());
            contentChanged = true;
        }
        if (patch.getBrand() != null && !patch.getBrand().equals(p.getBrand())) {
            p.setBrand(patch.getBrand().trim());
            contentChanged = true;
        }
        if (patch.getSpec() != null && !patch.getSpec().equals(p.getSpec())) {
            p.setSpec(patch.getSpec().trim());
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
            p.setCoverUrl(patch.getCoverUrl());
            contentChanged = true;
        }
        if (patch.getDetail() != null && !patch.getDetail().equals(p.getDetail())) {
            p.setDetail(patch.getDetail().trim());
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
        return p;
    }

    /** 商户自主上下架：仅审核通过的商品可操作，可反复上下架 */
    public Product mySetStatus(Long merId, Long prodId, Integer status) {
        if (status == null || (status != 0 && status != 1)) throw new BusinessException("商品状态不合法");
        Product p = myDetail(merId, prodId);
        if (p.getReviewStatus() == null || p.getReviewStatus() != 1) {
            throw new BusinessException("仅审核通过的商品可上架/下架");
        }
        p.setStatus(status);
        p.setUpdateTime(LocalDateTime.now());
        productMapper.updateById(p);
        return p;
    }
}
