package com.lanlink.shopping.module.membership.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.module.membership.entity.SubscriptionOrder;
import com.lanlink.shopping.module.membership.entity.UserSubscription;
import com.lanlink.shopping.module.membership.mapper.SubscriptionOrderMapper;
import com.lanlink.shopping.module.membership.mapper.UserSubscriptionMapper;
import com.lanlink.shopping.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 订阅自动续费调度（P1-4）：每日一次，串行三步——
 *  1. 批量惰性过期（status=active 且 end_time≤now → expired）+ 降级通知；
 *  2. 到期提醒（T-3 天，按 auto_renew 分「将自动续费 / 请手动续费」两种口径）；
 *  3. 自动续费扣款（到期日当天起扣，失败次日在宽限期内重试，扣款走 mock/沙箱渠道；
 *     重试达 {@link SubscriptionService#RENEW_RETRY_LIMIT} 或超宽限期 → failRenewal 收口置 cancelled）。
 *
 * <p>时区成对约束：容器 JVM 已在启动时 TimeZone.setDefault(Asia/Shanghai)
 * （LanlinkShoppingApplication.main），本类 @Scheduled(zone) 与 LocalDate.now(zone) 显式成对指定
 * 同一时区源，保证「cron 触发日」与「到期日比较」不因运行环境默认时区漂移而错位。
 *
 * <p>幂等：站内信按 relatedNo（业务键+到期日）去重；扣款以订阅单 pay_status 状态机防重放；
 * 过期翻转按 status='active' 条件更新，重跑安全。
 */
@Component
public class SubscriptionRenewScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionRenewScheduler.class);

    /** 与 cron 成对的时区源（见类注释） */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int REMIND_AHEAD_DAYS = 3;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_EXPIRED = "expired";

    private final SubscriptionService subscriptionService;
    private final UserSubscriptionMapper subMapper;
    private final SubscriptionOrderMapper orderMapper;
    private final MessageService messageService;

    public SubscriptionRenewScheduler(SubscriptionService subscriptionService, UserSubscriptionMapper subMapper,
                                      SubscriptionOrderMapper orderMapper, MessageService messageService) {
        this.subscriptionService = subscriptionService;
        this.subMapper = subMapper;
        this.orderMapper = orderMapper;
        this.messageService = messageService;
    }

    /** 每日 03:30（Asia/Shanghai）执行 */
    @Scheduled(cron = "0 30 3 * * ?", zone = "Asia/Shanghai")
    public void dailyRenew() {
        try {
            expireAndNotify();
            remindUpcoming();
            autoCharge();
        } catch (Exception e) {
            log.error("[subscription-renew] 每日续费调度全局异常", e);
        }
    }

    /** 步骤 1：批量过期 + 降级通知（对每个被翻转的订阅发一次「已到期失效」站内信） */
    void expireAndNotify() {
        LocalDateTime now = LocalDateTime.now();
        List<UserSubscription> due = subMapper.selectList(Wrappers.<UserSubscription>lambdaQuery()
                .eq(UserSubscription::getStatus, STATUS_ACTIVE)
                .le(UserSubscription::getEndTime, now));
        for (UserSubscription s : due) {
            // 条件更新防并发：仅 active 状态翻转（回调续费已顺延时不会误翻）
            int updated = subMapper.update(null, Wrappers.<UserSubscription>lambdaUpdate()
                    .eq(UserSubscription::getSubId, s.getSubId())
                    .eq(UserSubscription::getStatus, STATUS_ACTIVE)
                    .set(UserSubscription::getStatus, STATUS_EXPIRED)
                    .set(UserSubscription::getUpdatedAt, now));
            if (updated > 0) {
                sendOnce(s, "sub-downgrade:" + s.getSubId() + ":" + DAY.format(s.getEndTime().toLocalDate()),
                        "订阅已到期", "您的会员订阅已到期失效。等级折扣权益已回落，重新开通订阅即可恢复。");
            }
        }
    }

    /** 步骤 2：到期提醒（T-3 天窗口内仍生效中的订阅；自动续费/手动续费两种口径） */
    void remindUpcoming() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime until = now.plusDays(REMIND_AHEAD_DAYS);
        List<UserSubscription> upcoming = subMapper.selectList(Wrappers.<UserSubscription>lambdaQuery()
                .eq(UserSubscription::getStatus, STATUS_ACTIVE)
                .gt(UserSubscription::getEndTime, now)
                .le(UserSubscription::getEndTime, until));
        for (UserSubscription s : upcoming) {
            String endDate = DAY.format(s.getEndTime().toLocalDate());
            boolean auto = s.getAutoRenew() != null && s.getAutoRenew() != 0;
            if (auto) {
                sendOnce(s, "sub-remind:" + s.getSubId() + ":" + endDate, "订阅即将到期，将自动续费",
                        "您的会员订阅将于 " + endDate + " 到期，系统将按当前方案自动续费扣款，无需操作。");
            } else {
                sendOnce(s, "sub-remind:" + s.getSubId() + ":" + endDate, "订阅即将到期",
                        "您的会员订阅将于 " + endDate + " 到期，到期后等级折扣权益将失效，续费即可保留权益。");
            }
        }
    }

    /** 步骤 3：自动续费扣款（到期日当天起；宽限期内每日重试；上限/超期收口） */
    void autoCharge() {
        LocalDateTime now = LocalDateTime.now();
        // 扣款窗口：到期日在 [now - 宽限期, now + 1天]（提前一天覆盖"到期日当天"边界，重试自到期日起）
        List<UserSubscription> dueList = subMapper.selectList(Wrappers.<UserSubscription>lambdaQuery()
                .in(UserSubscription::getStatus, STATUS_ACTIVE, STATUS_EXPIRED)
                .eq(UserSubscription::getAutoRenew, 1)
                .gt(UserSubscription::getEndTime, now.minusDays(SubscriptionService.RENEW_GRACE_DAYS))
                .le(UserSubscription::getEndTime, now.plusDays(1)));
        for (UserSubscription s : dueList) {
            try {
                // 上限判定：同一张待支付续费单失败计次达上限（或已超宽限期）→ 收口
                SubscriptionOrder pending = subscriptionService.pendingRenewalOrder(s.getSubId());
                int retries = pending == null || pending.getRetryCount() == null ? 0 : pending.getRetryCount();
                boolean overGrace = s.getEndTime().isBefore(now.minusDays(SubscriptionService.RENEW_GRACE_DAYS));
                if (retries >= SubscriptionService.RENEW_RETRY_LIMIT || overGrace) {
                    subscriptionService.failRenewal(s, pending);
                    continue;
                }
                boolean ok = subscriptionService.autoRenewOnce(s);
                log.info("[subscription-renew] subId={} autoRenew={} retries={}", s.getSubId(), ok, retries);
            } catch (Exception e) {
                // 单个订阅失败不影响其余订阅的续费批次
                log.error("[subscription-renew] subId={} 自动续费异常", s.getSubId(), e);
            }
        }
    }

    private void sendOnce(UserSubscription s, String relatedNo, String title, String content) {
        if (messageService.countByRelatedNo(s.getUserId(), relatedNo) > 0) return;
        messageService.send(s.getUserId(), "order", title, content, relatedNo);
    }
}
