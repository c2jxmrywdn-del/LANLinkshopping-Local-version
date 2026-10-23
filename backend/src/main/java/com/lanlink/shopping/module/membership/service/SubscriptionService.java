package com.lanlink.shopping.module.membership.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.membership.entity.SubscriptionPlan;
import com.lanlink.shopping.module.membership.entity.UserSubscription;
import com.lanlink.shopping.module.membership.mapper.SubscriptionPlanMapper;
import com.lanlink.shopping.module.membership.mapper.UserSubscriptionMapper;
import com.lanlink.shopping.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订阅系统服务（营销中台·订阅模块）。
 * 对外能力：listPlans / purchase / mySubscription / hasActiveSubscription / cancel。
 * 会员等级是否解锁由 MembershipService 通过 hasActiveSubscription() 反向查询，
 * 本模块不依赖订单/会员细节，保证模块隔离。生效口径统一为 status=active 且 end_time 晚于当前时间。
 */
@Service
public class SubscriptionService {

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_CANCELLED = "cancelled";
    private static final String STATUS_EXPIRED = "expired";

    private final SubscriptionPlanMapper planMapper;
    private final UserSubscriptionMapper subMapper;
    private final AuditService auditService;

    public SubscriptionService(SubscriptionPlanMapper planMapper, UserSubscriptionMapper subMapper,
                               AuditService auditService) {
        this.planMapper = planMapper;
        this.subMapper = subMapper;
        this.auditService = auditService;
    }

    /** 上架中的订阅方案（按 sort 升序） */
    public List<SubscriptionPlan> listPlans() {
        return planMapper.selectList(Wrappers.<SubscriptionPlan>lambdaQuery()
                .eq(SubscriptionPlan::getEnabled, 1)
                .orderByAsc(SubscriptionPlan::getSort));
    }

    /**
     * 购买订阅。已有生效订阅时按「续费叠加」顺延 end_time（从当前到期时间起再延长 duration_days），
     * 否则新建订阅（start=now，end=now+duration_days，status=active）。记录审计日志。
     * @return 生效中的订阅记录
     */
    @Transactional
    public UserSubscription purchase(Long userId, Long planId, HttpServletRequest req) {
        if (userId == null) throw new BusinessException("请先登录");
        if (planId == null) throw new BusinessException("请选择订阅方案");
        SubscriptionPlan plan = planMapper.selectById(planId);
        if (plan == null) throw new BusinessException("订阅方案不存在");
        if (plan.getEnabled() == null || plan.getEnabled() != 1) throw new BusinessException("订阅方案已下架");
        int days = plan.getDurationDays() == null ? 0 : plan.getDurationDays();
        LocalDateTime now = LocalDateTime.now();

        UserSubscription active = currentActive(userId);
        UserSubscription sub;
        if (active != null) {
            // 续费：在当前到期时间基础上顺延 duration_days（未过期则叠加，已回落 now 兜底）
            LocalDateTime base = (active.getEndTime() != null && active.getEndTime().isAfter(now))
                    ? active.getEndTime() : now;
            active.setPlanId(plan.getPlanId());
            active.setEndTime(base.plusDays(days));
            active.setStatus(STATUS_ACTIVE);
            active.setAutoRenew(plan.getAutoRenew());
            active.setUpdatedAt(now);
            subMapper.updateById(active);
            sub = active;
        } else {
            sub = new UserSubscription();
            sub.setUserId(userId);
            sub.setPlanId(plan.getPlanId());
            sub.setStartTime(now);
            sub.setEndTime(now.plusDays(days));
            sub.setStatus(STATUS_ACTIVE);
            sub.setAutoRenew(plan.getAutoRenew());
            sub.setCreatedAt(now);
            sub.setUpdatedAt(now);
            subMapper.insert(sub);
        }
        auditService.record(userId, "SUBSCRIPTION_PURCHASE",
                "订阅「" + plan.getPlanName() + "」(" + plan.getPlanCode() + ")，有效期至 " + sub.getEndTime(), req);
        return sub;
    }

    /** 当前生效订阅（先惰性过期，再返回 status=active 且未到期的一条；无则 null） */
    @Transactional
    public UserSubscription mySubscription(Long userId) {
        if (userId == null) return null;
        expireOld(userId);
        return findActive(userId);
    }

    /** 是否存在生效订阅（纯读取：status=active 且 end_time 晚于当前时间） */
    public boolean hasActiveSubscription(Long userId) {
        if (userId == null) return false;
        Long count = subMapper.selectCount(Wrappers.<UserSubscription>lambdaQuery()
                .eq(UserSubscription::getUserId, userId)
                .eq(UserSubscription::getStatus, STATUS_ACTIVE)
                .gt(UserSubscription::getEndTime, LocalDateTime.now()));
        return count != null && count > 0;
    }

    /**
     * 取消当前生效订阅：status 置为 cancelled。
     * @return 被取消的订阅；无生效订阅时返回 null
     */
    @Transactional
    public UserSubscription cancel(Long userId, HttpServletRequest req) {
        if (userId == null) throw new BusinessException("请先登录");
        UserSubscription active = currentActive(userId);
        if (active == null) return null;
        LocalDateTime now = LocalDateTime.now();
        active.setStatus(STATUS_CANCELLED);
        active.setUpdatedAt(now);
        subMapper.updateById(active);
        auditService.record(userId, "SUBSCRIPTION_CANCEL",
                "取消订阅 subId=" + active.getSubId() + "（原有效期至 " + active.getEndTime() + "）", req);
        return active;
    }

    // ===== 内部辅助 =====

    /** 惰性过期：把 status=active 但已到期的记录翻转为 expired */
    private void expireOld(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<UserSubscription> stale = subMapper.selectList(Wrappers.<UserSubscription>lambdaQuery()
                .eq(UserSubscription::getUserId, userId)
                .eq(UserSubscription::getStatus, STATUS_ACTIVE)
                .le(UserSubscription::getEndTime, now));
        for (UserSubscription s : stale) {
            s.setStatus(STATUS_EXPIRED);
            s.setUpdatedAt(now);
            subMapper.updateById(s);
        }
    }

    /** 生效订阅（不做状态翻转，仅按 end_time 取最新一条） */
    private UserSubscription findActive(Long userId) {
        List<UserSubscription> list = subMapper.selectList(Wrappers.<UserSubscription>lambdaQuery()
                .eq(UserSubscription::getUserId, userId)
                .eq(UserSubscription::getStatus, STATUS_ACTIVE)
                .gt(UserSubscription::getEndTime, LocalDateTime.now())
                .orderByDesc(UserSubscription::getEndTime));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 先过期再取当前生效订阅，供 purchase/cancel 复用 */
    private UserSubscription currentActive(Long userId) {
        expireOld(userId);
        return findActive(userId);
    }
}
