package com.lanlink.shopping.module.rfq.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.merchantexit.service.MerchantExitGuard;
import com.lanlink.shopping.module.rfq.dto.RfqQuoteCreateRequest;
import com.lanlink.shopping.module.rfq.entity.Rfq;
import com.lanlink.shopping.module.rfq.entity.RfqQuote;
import com.lanlink.shopping.module.rfq.mapper.RfqMapper;
import com.lanlink.shopping.module.rfq.mapper.RfqQuoteMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 商户报价：查看开放询价、报价（新建或修改自己的 SUBMITTED 报价）、查看自己的报价。
 *
 * <p>金额口径：总额 = 报价单价 × 询价数量（scale 2），**不接受客户端传数量/总额**。
 * 货物锚定：报价必须指向属于本商户的真实可售目录商品，否则 S5 接受后商户侧订单 JOIN 不到。
 * 一户一报价由 UNIQUE(rfq_id, mer_id) 兜底，并发首报冲突映射 409。
 * 竞对报价无任何接口暴露；询价单视图不包含买家身份与联系方式。
 */
@Service
public class RfqQuoteService {

    private static final Set<String> QUOTABLE_STATUSES = Set.of("PUBLISHED", "QUOTED");
    /** DECIMAL(14,2) 可表示的最大金额；超过则拒绝，避免落库溢出变 500 */
    private static final BigDecimal MAX_TOTAL = new BigDecimal("999999999999.99");

    private final RfqMapper rfqMapper;
    private final RfqQuoteMapper quoteMapper;
    private final ProductMapper productMapper;
    private final MerchantMapper merchantMapper;

    public RfqQuoteService(RfqMapper rfqMapper, RfqQuoteMapper quoteMapper,
                           ProductMapper productMapper, MerchantMapper merchantMapper) {
        this.rfqMapper = rfqMapper;
        this.quoteMapper = quoteMapper;
        this.productMapper = productMapper;
        this.merchantMapper = merchantMapper;
    }

    private Merchant resolveMerchant(Long userId) {
        if (userId == null) throw new BusinessException(401, "请先登录");
        Merchant merchant = merchantMapper.selectOne(
                Wrappers.<Merchant>lambdaQuery().eq(Merchant::getUserId, userId));
        if (merchant == null) throw new BusinessException(403, "当前账号尚未入驻商户");
        // 待审/驳回的商户记录同样存在，不能只凭记录存在放行（演练账号里就有 review_status=2 的挂靠记录）
        if (merchant.getReviewStatus() == null || merchant.getReviewStatus() != 1) {
            throw new BusinessException(403, "商户资质未通过审核，暂不可报价");
        }
        return merchant;
    }

    public Long requireMerchant(Long userId) {
        return resolveMerchant(userId).getMerId();
    }

    /** 报价/改价等写入口：退店流程中的商户一律拒绝。 */
    public Long requireWritableMerchant(Long userId) {
        Merchant m = resolveMerchant(userId);
        MerchantExitGuard.requireWritable(m);
        return m.getMerId();
    }

    /** 可报价的开放询价（未截止且未成交），不含买家身份 */
    public List<Rfq> openRfqs() {
        return rfqMapper.selectList(Wrappers.<Rfq>lambdaQuery()
                .in(Rfq::getStatus, "PUBLISHED", "QUOTED")
                .gt(Rfq::getQuoteDeadline, LocalDateTime.now())
                .orderByDesc(Rfq::getCreateTime));
    }

    public Rfq merchantView(String rfqNo) {
        Rfq rfq = rfqMapper.selectOne(Wrappers.<Rfq>lambdaQuery().eq(Rfq::getRfqNo, rfqNo));
        if (rfq == null) throw new BusinessException(404, "询价单不存在");
        return rfq;
    }

    @Transactional
    public RfqQuote submitQuote(Long userId, String rfqNo, RfqQuoteCreateRequest req) {
        Long merId = requireWritableMerchant(userId);
        // 行锁串行化报价与接受：名额计数、状态判断与插入在同一把 RFQ 行锁内完成
        Rfq rfq = rfqMapper.selectByNoForUpdate(rfqNo);
        if (rfq == null) throw new BusinessException(404, "询价单不存在");
        if (!QUOTABLE_STATUSES.contains(rfq.getStatus())) {
            throw new BusinessException(400, "询价单已截止或已成交，无法报价");
        }
        if (!rfq.getQuoteDeadline().isAfter(LocalDateTime.now())) {
            throw new BusinessException(400, "报价已截止");
        }
        if (req.getValidUntil() != null && !req.getValidUntil().isAfter(LocalDateTime.now())) {
            throw new BusinessException(400, "报价有效期必须晚于当前时间");
        }
        Product product = productMapper.selectById(req.getProdId());
        if (product == null) throw new BusinessException(404, "商品不存在");
        if (!merId.equals(product.getMerId())) {
            throw new BusinessException(403, "只能对自有商品报价");
        }
        if (product.getStatus() == null || product.getStatus() != 1) {
            throw new BusinessException(400, "商品不可售，无法报价");
        }
        BigDecimal total = req.getUnitPrice().multiply(rfq.getQuantity())
                .setScale(2, RoundingMode.HALF_UP);
        if (total.compareTo(MAX_TOTAL) > 0) {
            throw new BusinessException(400, "报价总额超出可表示范围");
        }

        RfqQuote existing = quoteMapper.selectByRfqAndMer(rfq.getRfqId(), merId);
        if (existing != null) {
            if (!"SUBMITTED".equals(existing.getStatus())) {
                throw new BusinessException(409, "报价已不可修改");
            }
            existing.setProdId(product.getProdId());
            existing.setUnitPrice(req.getUnitPrice());
            existing.setQuantity(rfq.getQuantity());
            existing.setTotalAmount(total);
            existing.setRemark(req.getRemark());
            existing.setValidUntil(req.getValidUntil());
            existing.setUpdateTime(LocalDateTime.now());
            quoteMapper.updateById(existing);
            return existing;
        }

        if (quoteMapper.countByRfq(rfq.getRfqId()) >= rfq.getMaxQuotes()) {
            throw new BusinessException(400, "报价户数已满（上限 " + rfq.getMaxQuotes() + " 户）");
        }
        RfqQuote quote = new RfqQuote();
        quote.setRfqId(rfq.getRfqId());
        quote.setMerId(merId);
        quote.setProdId(product.getProdId());
        quote.setUnitPrice(req.getUnitPrice());
        quote.setQuantity(rfq.getQuantity());
        quote.setTotalAmount(total);
        quote.setRemark(req.getRemark());
        quote.setValidUntil(req.getValidUntil());
        quote.setStatus("SUBMITTED");
        quote.setCreateTime(LocalDateTime.now());
        quote.setUpdateTime(quote.getCreateTime());
        try {
            quoteMapper.insert(quote);
        } catch (DuplicateKeyException e) {
            // uk_quote_rfq_mer：并发首报只有一条能落库
            throw new BusinessException(409, "已有报价，请刷新后重试");
        }
        // 兜底：行锁下状态不应变化，若条件更新未命中仍复核一次，避免为已成交单落报价
        if (quoteMapper.markQuoted(rfq.getRfqId()) == 0) {
            Rfq fresh = rfqMapper.selectByNoForUpdate(rfqNo);
            if (fresh == null || !QUOTABLE_STATUSES.contains(fresh.getStatus())) {
                throw new BusinessException(400, "询价单已截止或已成交，无法报价");
            }
        }
        return quote;
    }

    public RfqQuote myQuote(Long userId, String rfqNo) {
        Long merId = requireMerchant(userId);
        Rfq rfq = merchantView(rfqNo);
        RfqQuote quote = quoteMapper.selectByRfqAndMer(rfq.getRfqId(), merId);
        if (quote == null) throw new BusinessException(404, "尚未报价");
        return quote;
    }

    /** 买家查看自己询价收到的报价（按 user_id 收窄，读不到他人询价） */
    public List<RfqQuote> buyerQuotes(Long userId, String rfqNo) {
        if (userId == null) throw new BusinessException(401, "请先登录");
        Rfq rfq = rfqMapper.selectOwned(userId, rfqNo);
        if (rfq == null) throw new BusinessException(404, "询价单不存在");
        return quoteMapper.selectList(Wrappers.<RfqQuote>lambdaQuery()
                .eq(RfqQuote::getRfqId, rfq.getRfqId())
                .orderByAsc(RfqQuote::getTotalAmount));
    }
}