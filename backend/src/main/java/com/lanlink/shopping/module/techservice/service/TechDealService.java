package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.domain.TechEvidenceHasher;
import com.lanlink.shopping.module.techservice.domain.TechNoGenerator;
import com.lanlink.shopping.module.techservice.dto.TechDealCreateRequest;
import com.lanlink.shopping.module.techservice.entity.TechAchievement;
import com.lanlink.shopping.module.techservice.entity.TechDeal;
import com.lanlink.shopping.module.techservice.event.TechSideEffectEvent;
import com.lanlink.shopping.module.techservice.mapper.TechAchievementMapper;
import com.lanlink.shopping.module.techservice.mapper.TechDealMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 技术交易意向聚合。
 *
 * <p>平台只收服务费、不归集交易本金；资金监管与结算由持牌合作机构办理。
 * 交易记录写入存证哈希，用于事后核对关键字段是否被改动。
 */
@Service
public class TechDealService {

    private static final Set<String> DEAL_TYPES = Set.of("ASSIGN", "LICENSE", "OPEN_LICENSE", "SERVICE");
    private static final Set<String> PAYMENT_MODES = Set.of("ESCROW", "INSTALLMENT", "PAY_AFTER_USE");

    private final TechDealMapper dealMapper;
    private final TechAchievementMapper achievementMapper;
    private final TechProjectService projectService;
    private final ApplicationEventPublisher eventPublisher;

    public TechDealService(TechDealMapper dealMapper,
                           TechAchievementMapper achievementMapper,
                           TechProjectService projectService,
                           ApplicationEventPublisher eventPublisher) {
        this.dealMapper = dealMapper;
        this.achievementMapper = achievementMapper;
        this.projectService = projectService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public TechDeal createDeal(Long userId, TechDealCreateRequest req) {
        TechAchievement achievement = achievementMapper.selectById(req.getAchievementId());
        if (achievement == null) {
            throw new BusinessException(404, "技术成果不存在");
        }
        if (!Set.of("LISTED", "MATCHING", "IN_DEAL").contains(achievement.getStatus())) {
            throw new BusinessException(400, "该成果当前不可发起交易");
        }
        if (Objects.equals(achievement.getOwnerUserId(), userId)) {
            throw new BusinessException(400, "不能对自己发布的成果发起购买意向");
        }
        String dealType = req.getDealType() == null ? "" : req.getDealType().trim();
        if (!DEAL_TYPES.contains(dealType)) {
            throw new BusinessException(400, "不支持的交易方式");
        }
        String paymentMode = req.getPaymentMode() == null || req.getPaymentMode().isBlank()
                ? "ESCROW" : req.getPaymentMode().trim();
        if (!PAYMENT_MODES.contains(paymentMode)) {
            throw new BusinessException(400, "不支持的付款方式");
        }
        BigDecimal rate = req.getCommissionRate() == null ? new BigDecimal("15.00") : req.getCommissionRate();
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException(400, "分成比例需在 0-100 之间");
        }
        BigDecimal commission = req.getAmount().multiply(rate)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

        TechDeal deal = new TechDeal();
        deal.setDealNo(TechNoGenerator.next("TDL"));
        deal.setAchievementId(achievement.getAchievementId());
        deal.setBuyerUserId(userId);
        deal.setSellerUserId(achievement.getOwnerUserId());
        deal.setDealType(dealType);
        deal.setAmount(req.getAmount());
        deal.setCommissionRate(rate);
        deal.setCommissionAmount(commission);
        deal.setPaymentMode(paymentMode);
        deal.setStatus("INTENT");
        deal.setEvidenceHash(TechEvidenceHasher.sha256(deal.getDealNo() + "|" + achievement.getAchievementId()
                + "|" + req.getAmount() + "|" + System.currentTimeMillis()));
        dealMapper.insert(deal);

        var project = projectService.createProject(userId, "TRANSACTION", "技术交易 · " + achievement.getTitle(),
                "CONTRACT", "等待交易材料审核与合同确认", req.getAmount(), rate,
                "DEAL", deal.getDealId(), null, null,
                new String[]{"意向确认", "尽职调查", "合同签署", "资金监管与交割"});
        deal.setProjectId(project.getProjectId());
        dealMapper.updateById(deal);

        eventPublisher.publishEvent(new TechSideEffectEvent(userId, "交易意向已提交",
                "您对「" + achievement.getTitle() + "」发起了" + dealTypeLabel(dealType)
                        + "意向，交易金额 ¥" + req.getAmount() + "。陪跑项目 " + project.getProjectNo()
                        + " 已创建；资金监管与结算由持牌合作机构办理（演示环境不划转真实资金）。", deal.getDealNo()));
        eventPublisher.publishEvent(new TechSideEffectEvent(achievement.getOwnerUserId(), "收到交易意向",
                "您的成果「" + achievement.getTitle() + "」收到新的" + dealTypeLabel(dealType)
                        + "意向，平台技术经纪人将跟进权属核查与交易材料。", deal.getDealNo()));
        return deal;
    }

    public List<TechDeal> myDeals(Long userId) {
        return dealMapper.selectList(Wrappers.<TechDeal>lambdaQuery()
                .and(w -> w.eq(TechDeal::getBuyerUserId, userId).or().eq(TechDeal::getSellerUserId, userId))
                .orderByDesc(TechDeal::getCreateTime));
    }

    private static String dealTypeLabel(String type) {
        return switch (type) {
            case "ASSIGN" -> "转让";
            case "OPEN_LICENSE" -> "开放许可";
            case "SERVICE" -> "技术服务";
            default -> "许可";
        };
    }
}
