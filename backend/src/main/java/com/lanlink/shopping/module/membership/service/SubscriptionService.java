package com.lanlink.shopping.module.membership.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.membership.entity.SubscriptionOrder;
import com.lanlink.shopping.module.membership.entity.SubscriptionPlan;
import com.lanlink.shopping.module.membership.entity.UserSubscription;
import com.lanlink.shopping.module.membership.mapper.SubscriptionOrderMapper;
import com.lanlink.shopping.module.membership.mapper.SubscriptionPlanMapper;
import com.lanlink.shopping.module.membership.mapper.UserSubscriptionMapper;
import com.lanlink.shopping.payment.service.SubscriptionPayHandler;
import com.lanlink.shopping.payment.service.PaymentService;
import com.lanlink.shopping.payment.service.SubscriptionOrderNos;
import com.lanlink.shopping.payment.vo.PayCreateVO;
import com.lanlink.shopping.service.AuditService;
import com.lanlink.shopping.service.MessageService;
import com.lanlink.shopping.service.SequenceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订阅系统服务（营销中台·订阅模块）。
 * 对外能力：listPlans / purchase(两段式) / mySubscription / hasActiveSubscription / cancel / orders / 生效回调。
 *
 * <p>P1 闭环语义（out/vip-subscription-design.md §4）：
 * <ul>
 *   <li>purchase 两段式：创建待支付订阅单（t_subscription_order，SUB 命名空间单号）→
 *       复用 PaymentService.create 渠道分发（wallet/mock/wechat/alipay/stripe）→ 返回支付参数。</li>
 *   <li>支付成功（回调/wallet/mock 同步结算）经 {@link #onSubscriptionOrderPaid} 驱动 t_user_subscription
 *       生效/顺延：续费 = 原到期日起顺延；失效续订（无生效订阅且超出宽限期）= 新起算。</li>
 *   <li>cancel（D3）：到期失效 + 撤销自动续费 + 保留历史——权益保留至 end_time，记录不删除，
 *       已取消用户可见历史并可经续费恢复。</li>
 * </ul>
 * 生效口径统一为 status=active 且 end_time 晚于当前时间；并发/幂等一律条件更新 + 状态机。
 */
@Service
public class SubscriptionService implements SubscriptionPayHandler {

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_CANCELLED = "cancelled";
    private static final String STATUS_EXPIRED = "expired";

    /** 自动续费宽限期（天）：到期后仍可完成自动续费扣款并从原到期日起顺延（P1-4） */
    public static final int RENEW_GRACE_DAYS = 3;
    /** 自动续费扣款失败重试上限：达到后撤销自动续费并置 cancelled（P1-4） */
    public static final int RENEW_RETRY_LIMIT = 3;
    /** 自动续费扣款渠道：走 mock/沙箱渠道（不触真实资金；mock 关闭时扣款失败进入重试/上限流程） */
    public static final String RENEW_CHANNEL = "mock";

    private final SubscriptionPlanMapper planMapper;
    private final UserSubscriptionMapper subMapper;
    private final SubscriptionOrderMapper orderMapper;
    private final AuditService auditService;
    private final SequenceService sequenceService;
    private final MessageService messageService;
    private final PaymentService paymentService;

    /**
     * {@code paymentService} 用 @Lazy 注入：订阅购买要复用支付渠道分发，
     * 而 PaymentService 又依赖 OrderService → PricingFacade → CampaignService → MembershipService，
     * 直接强依赖会让整条链在启动期形成环（实测 ConflictingBeanDefinitionException 之后的第二个
     * 启动崩溃点：form a cycle）。@Lazy 只把解析推迟到首次调用，运行期仍是同一个单例。
     */
    public SubscriptionService(SubscriptionPlanMapper planMapper, UserSubscriptionMapper subMapper,
                               SubscriptionOrderMapper orderMapper, AuditService auditService,
                               SequenceService sequenceService, MessageService messageService,
                               @org.springframework.context.annotation.Lazy PaymentService paymentService) {
        this.planMapper = planMapper;
        this.subMapper = subMapper;
        this.orderMapper = orderMapper;
        this.auditService = auditService;
        this.sequenceService = sequenceService;
        this.messageService = messageService;
        this.paymentService = paymentService;
    }

    /** 上架中的订阅方案（按 sort 升序） */
    public List<SubscriptionPlan> listPlans() {
        return planMapper.selectList(Wrappers.<SubscriptionPlan>lambdaQuery()
                .eq(SubscriptionPlan::getEnabled, 1)
                .orderByAsc(SubscriptionPlan::getSort));
    }

    /**
     * 购买订阅（P1-2 两段式）：创建待支付订阅单 → PaymentService.create 渠道分发 → 返回支付参数。
     * 不再在本方法内直接生效订阅；生效/顺延由支付成功回调 {@link #onSubscriptionOrderPaid} 驱动。
     * @return 支付参数（wallet 渠道同步完成结算；mock 渠道由前端 mock-confirm 确认）
     */
    @Transactional
    public PayCreateVO purchase(Long userId, Long planId, String channelReq, HttpServletRequest req) {
        if (userId == null) throw new BusinessException("请先登录");
        if (planId == null) throw new BusinessException("请选择订阅方案");
        SubscriptionPlan plan = planMapper.selectById(planId);
        if (plan == null) throw new BusinessException("订阅方案不存在");
        if (plan.getEnabled() == null || plan.getEnabled() != 1) throw new BusinessException("订阅方案已下架");
        String orderNo = createOrder(userId, plan, null);
        auditService.record(userId, "SUBSCRIPTION_ORDER_CREATE",
                "创建订阅单 " + orderNo + "（" + plan.getPlanName() + "，¥" + plan.getPriceCents() + "）", req);
        return paymentService.create(orderNo, userId, channelReq);
    }

    /** 创建待支付订阅单（金额/天数/自动续费为下单时快照；renewSubId 仅自动续费场景传入） */
    private String createOrder(Long userId, SubscriptionPlan plan, Long renewSubId) {
        String day = ORDER_DATE.format(LocalDateTime.now());
        String orderNo = SubscriptionOrderNos.PREFIX + day + String.format("%08d", sequenceService.next(ORDER_SEQ_KEY + ":" + day));
        SubscriptionOrder so = new SubscriptionOrder();
        so.setOrderNo(orderNo);
        so.setUserId(userId);
        so.setPlanId(plan.getPlanId());
        so.setPlanCode(plan.getPlanCode());
        so.setPlanName(plan.getPlanName());
        // 金额单位（钉死）：amount_cents = 整数元（8888 = ¥8,888），与 t_subscription_plan.price_cents 同一语义；
        // 渠道「分」换算在 PaymentService 以 BigDecimal 元 ×100 进行，禁止把本字段当分直接传渠道
        so.setAmountCents(plan.getPriceCents());
        so.setDurationDays(plan.getDurationDays());
        so.setAutoRenew(plan.getAutoRenew());
        so.setPayStatus(0);
        so.setApplyStatus(0);
        so.setRenewSubId(renewSubId);
        so.setRetryCount(0);
        so.setCreatedAt(LocalDateTime.now());
        orderMapper.insert(so);
        return orderNo;
    }

    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String ORDER_SEQ_KEY = "suborder";

    /**
     * 支付成功回调（P1-3，实现 SubscriptionPayHandler）：
     * 幂等闸 = apply_status 0→1 条件更新（并发回调/渠道重放只放行一个事务）；
     * 驱动 t_user_subscription：有生效订阅 → 原到期日起顺延；无生效订阅但在宽限期内有近期订阅 →
     * 从原到期日起顺延（宽限期内续费保持连续）；否则新起算（失效续订）。
     */
    @Override
    @Transactional
    public void onSubscriptionOrderPaid(String orderNo) {
        SubscriptionOrder so = orderMapper.selectById(orderNo);
        if (so == null) return;
        if (so.getApplyStatus() != null && so.getApplyStatus() == 1) return; // 幂等：已生效
        if (orderMapper.markAppliedAtomic(orderNo) == 0) return;             // 并发：被先行者抢占
        int days = so.getDurationDays() == null ? 0 : so.getDurationDays();
        LocalDateTime now = LocalDateTime.now();

        UserSubscription current = findActive(so.getUserId());
        UserSubscription sub;
        boolean renewal;
        if (current != null) {
            // 续费：在当前到期时间基础上顺延 duration_days（未过期则叠加，已回落 now 兜底）
            LocalDateTime base = (current.getEndTime() != null && current.getEndTime().isAfter(now))
                    ? current.getEndTime() : now;
            current.setPlanId(so.getPlanId());
            current.setEndTime(base.plusDays(days));
            current.setStatus(STATUS_ACTIVE);
            current.setAutoRenew(so.getAutoRenew());
            current.setUpdatedAt(now);
            subMapper.updateById(current);
            sub = current;
            renewal = true;
        } else {
            UserSubscription recent = latestWithinGrace(so.getUserId(), now);
            if (recent != null) {
                // 宽限期内续费：从原到期日起顺延（订阅记录保持连续，不重置成长/权益起算）
                recent.setPlanId(so.getPlanId());
                recent.setEndTime(recent.getEndTime().plusDays(days));
                recent.setStatus(STATUS_ACTIVE);
                recent.setAutoRenew(so.getAutoRenew());
                recent.setUpdatedAt(now);
                subMapper.updateById(recent);
                sub = recent;
                renewal = true;
            } else {
                // 失效续订 = 新起算
                sub = new UserSubscription();
                sub.setUserId(so.getUserId());
                sub.setPlanId(so.getPlanId());
                sub.setStartTime(now);
                sub.setEndTime(now.plusDays(days));
                sub.setStatus(STATUS_ACTIVE);
                sub.setAutoRenew(so.getAutoRenew());
                sub.setCreatedAt(now);
                sub.setUpdatedAt(now);
                subMapper.insert(sub);
                renewal = false;
            }
        }
        messageService.send(so.getUserId(), "order",
                renewal ? "订阅续费成功" : "订阅开通成功",
                "订阅「" + so.getPlanName() + "」已生效，有效期至 " + sub.getEndTime() + "。", orderNo);
        auditService.record(so.getUserId(), "SUBSCRIPTION_PAID",
                "订阅单 " + orderNo + " 支付成功，" + (renewal ? "续费顺延至 " : "开通生效至 ") + sub.getEndTime(), null);
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
     * 取消订阅（D3 语义）：到期失效 + 撤销自动续费 + 保留历史。
     * status 保持 active 至 end_time（已付费权益用满到期），仅把 auto_renew 条件更新为 0；
     * 该订阅名下待支付的自动续费单同步取消；历史记录不删除，到期后由惰性过期翻转为 expired，
     * 用户可随时经续费（新订阅单支付）恢复。
     * @return 仍生效中的订阅（至原到期日）；无生效订阅时返回 null
     */
    @Transactional
    public UserSubscription cancel(Long userId, HttpServletRequest req) {
        if (userId == null) throw new BusinessException("请先登录");
        UserSubscription active = currentActive(userId);
        if (active == null) return null;
        LocalDateTime now = LocalDateTime.now();
        // 条件更新防并发：仅 auto_renew 非 0 时翻转（重复取消幂等）
        subMapper.update(null, Wrappers.<UserSubscription>lambdaUpdate()
                .eq(UserSubscription::getSubId, active.getSubId())
                .and(w -> w.isNull(UserSubscription::getAutoRenew).or().ne(UserSubscription::getAutoRenew, 0))
                .set(UserSubscription::getAutoRenew, 0)
                .set(UserSubscription::getUpdatedAt, now));
        // 撤销自动续费后，名下待支付的续费单一并取消（状态机 pay_status 0→2）
        orderMapper.cancelPendingByRenewSub(active.getSubId());
        active.setAutoRenew(0);
        active.setUpdatedAt(now);
        auditService.record(userId, "SUBSCRIPTION_CANCEL",
                "撤销自动续费 subId=" + active.getSubId() + "（权益保留至 " + active.getEndTime() + "，到期失效）", req);
        return active;
    }

    /** 我的订阅订单（P1-5）：本人购买/续费流水分页（发票/对账依据，含支付状态与渠道） */
    public Map<String, Object> orders(Long userId, long page, long size) {
        if (userId == null) throw new BusinessException("请先登录");
        IPage<SubscriptionOrder> result = orderMapper.selectPage(new Page<>(page, Math.min(size, 100)),
                Wrappers.<SubscriptionOrder>lambdaQuery()
                        .eq(SubscriptionOrder::getUserId, userId)
                        .orderByDesc(SubscriptionOrder::getCreatedAt));
        Map<String, Object> out = new HashMap<>();
        out.put("records", result.getRecords());
        out.put("total", result.getTotal());
        out.put("page", result.getCurrent());
        out.put("size", result.getSize());
        return out;
    }

    // ===== 自动续费（P1-4，由 SubscriptionRenewScheduler 每日调度调用） =====

    /**
     * 单次自动续费尝试：复用/创建该订阅名下待支付续费单，经 mock/沙箱渠道扣款。
     * 成功 = 订阅单已结算（生效/顺延由结算回调同步驱动）；失败计次（retry_count +1），
     * 达上限或超出宽限期由调度器执行 {@link #failRenewal}。重试沿用同一张待支付单（单号即幂等键）。
     * @return 扣款是否成功
     */
    public boolean autoRenewOnce(UserSubscription sub) {
        SubscriptionPlan plan = planMapper.selectById(sub.getPlanId());
        if (plan == null) {
            // 方案已删除无法计价：直接失败收口（停止自动续费），不再空转重试
            failRenewal(sub, null);
            return false;
        }
        SubscriptionOrder pending = pendingRenewalOrder(sub.getSubId());
        if (pending == null) {
            String orderNo = createOrder(sub.getUserId(), plan, sub.getSubId());
            auditService.record(sub.getUserId(), "SUBSCRIPTION_AUTO_RENEW",
                    "自动续费扣款发起 subId=" + sub.getSubId() + "，订阅单 " + orderNo, null);
            pending = orderMapper.selectById(orderNo);
        }
        try {
            PayCreateVO vo = paymentService.create(pending.getOrderNo(), sub.getUserId(), RENEW_CHANNEL);
            if (vo.isMock()) {
                paymentService.mockConfirm(pending.getOrderNo(), sub.getUserId());
            }
        } catch (Exception e) {
            // 扣款失败（余额不足/渠道未配置等）：计次后由调度器判定重试或上限收口
            orderMapper.incrementRetry(pending.getOrderNo());
            return false;
        }
        SubscriptionOrder after = orderMapper.selectById(pending.getOrderNo());
        return after != null && after.getPayStatus() != null && after.getPayStatus() == 1;
    }

    /**
     * 自动续费失败收口：撤销自动续费（auto_renew→0）+ 取消待支付续费单 + 已到期订阅置 cancelled + 降级通知。
     * 条件更新保证与并发回调互斥（已支付单不会被置 cancelled）。
     */
    @Transactional
    public void failRenewal(UserSubscription sub, SubscriptionOrder pending) {
        LocalDateTime now = LocalDateTime.now();
        if (pending != null) orderMapper.markCancelledAtomic(pending.getOrderNo());
        subMapper.update(null, Wrappers.<UserSubscription>lambdaUpdate()
                .eq(UserSubscription::getSubId, sub.getSubId())
                .and(w -> w.isNull(UserSubscription::getAutoRenew).or().ne(UserSubscription::getAutoRenew, 0))
                .set(UserSubscription::getAutoRenew, 0)
                .set(UserSubscription::getUpdatedAt, now));
        // 已到期未续上：状态置 cancelled（D3 的「扣款失败重试上限后置 cancelled」收口）
        subMapper.update(null, Wrappers.<UserSubscription>lambdaUpdate()
                .eq(UserSubscription::getSubId, sub.getSubId())
                .eq(UserSubscription::getStatus, STATUS_ACTIVE)
                .le(UserSubscription::getEndTime, now)
                .set(UserSubscription::getStatus, STATUS_CANCELLED)
                .set(UserSubscription::getUpdatedAt, now));
        String endDate = sub.getEndTime() == null ? "-" : sub.getEndTime().toLocalDate().toString();
        if (!sendOnce(sub.getUserId(), "sub-downgrade:" + sub.getSubId() + ":" + endDate, "order",
                "自动续费未成功",
                "您的订阅自动续费扣款未成功，已停止自动续费。订阅权益已到期失效，可随时手动重新开通。")) {
            return;
        }
        auditService.record(sub.getUserId(), "SUBSCRIPTION_AUTO_RENEW_FAIL",
                "自动续费失败收口 subId=" + sub.getSubId() + "（重试上限/超宽限期，已置 cancelled）", null);
    }

    /** 该订阅名下最新的待支付续费单（无则 null） */
    public SubscriptionOrder pendingRenewalOrder(Long renewSubId) {
        List<SubscriptionOrder> list = orderMapper.selectList(Wrappers.<SubscriptionOrder>lambdaQuery()
                .eq(SubscriptionOrder::getRenewSubId, renewSubId)
                .eq(SubscriptionOrder::getPayStatus, 0)
                .orderByDesc(SubscriptionOrder::getCreatedAt)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
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

    /** 宽限期内最近一条订阅（已到期但到期时间仍在宽限期窗口内，含已翻转 expired 的记录）；无则 null */
    private UserSubscription latestWithinGrace(Long userId, LocalDateTime now) {
        List<UserSubscription> list = subMapper.selectList(Wrappers.<UserSubscription>lambdaQuery()
                .eq(UserSubscription::getUserId, userId)
                .in(UserSubscription::getStatus, STATUS_ACTIVE, STATUS_EXPIRED)
                .le(UserSubscription::getEndTime, now)
                .gt(UserSubscription::getEndTime, now.minusDays(RENEW_GRACE_DAYS))
                .orderByDesc(UserSubscription::getEndTime));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 先过期再取当前生效订阅，供 purchase/cancel 复用 */
    private UserSubscription currentActive(Long userId) {
        expireOld(userId);
        return findActive(userId);
    }

    /** 站内信去重发送：同一 relatedNo 只发一次（幂等，防调度重跑重复打扰） */
    private boolean sendOnce(Long userId, String relatedNo, String type, String title, String content) {
        Long exists = messageService.countByRelatedNo(userId, relatedNo);
        if (exists != null && exists > 0) return false;
        messageService.send(userId, type, title, content, relatedNo);
        return true;
    }
}
