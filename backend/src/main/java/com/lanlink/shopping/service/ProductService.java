package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.ProductMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商城与商品服务
 */
@Service
public class ProductService {

    private final ProductMapper productMapper;

    public ProductService(ProductMapper productMapper) {
        this.productMapper = productMapper;
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
        return productMapper.selectPage(new Page<>(current, size),
                Wrappers.<Product>lambdaQuery().eq(Product::getMerId, merId).orderByDesc(Product::getCreateTime));
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
        return productMapper.selectPage(new Page<>(current, size),
                Wrappers.<Product>lambdaQuery()
                        .eq(status != null, Product::getStatus, status)
                        .like(StringUtils.hasText(keyword), Product::getTitle, keyword)
                        .orderByDesc(Product::getCreateTime));
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
}
