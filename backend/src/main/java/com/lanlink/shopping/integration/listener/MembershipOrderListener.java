package com.lanlink.shopping.integration.listener;

import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.integration.event.OrderPaidEvent;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.membership.service.MembershipService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 会员系统 × 订单系统 事件桥接。
 * 监听订单支付事件 → 累计会员成长值/积分/升级等级；双倍积分判据（D2）= 身份 VIP ∪ 有效订阅，
 * 统一走 {@link MembershipService#multiplierOf}（与 MQ 通道同源）。
 * AFTER_COMMIT 消费：订单回滚不再产生积分副作用；REQUIRES_NEW + 异常吞掉，保证核心交易一致性。
 * 积分累计按 orderNo 幂等，重复事件不会重复计分。
 *
 * <p>互斥开关：app.mq.order-flow=event 时生效；默认 (mq) 模式下由 {@code MembershipOrderMqConsumer} 承载，
 * 避免双通道重复执行。
 */
@Component
@ConditionalOnProperty(name = "app.mq.order-flow", havingValue = "event")
public class MembershipOrderListener {

    private static final Logger log = LoggerFactory.getLogger(MembershipOrderListener.class);

    private final MembershipService membershipService;
    private final UserMapper userMapper;

    public MembershipOrderListener(MembershipService membershipService, UserMapper userMapper) {
        this.membershipService = membershipService;
        this.userMapper = userMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderPaid(OrderPaidEvent event) {
        try {
            User u = userMapper.selectById(event.getUserId());
            Long roleId = u == null ? null : u.getRoleId();
            // D2 双倍积分判据统一收敛到 MembershipService.multiplierOf（身份 VIP ∪ 有效订阅），与 MQ 通道共用
            int multiplier = membershipService.multiplierOf(event.getUserId(), roleId);
            membershipService.earnGrowth(event.getUserId(), event.getAmount(), event.getOrderNo(), multiplier);
            log.info("[membership] 订单 {} 支付成功，会员成长值/积分已累计 userId={} multiplier={}",
                    event.getOrderNo(), event.getUserId(), multiplier);
        } catch (Exception e) {
            // 隔离原则：营销侧异常不得影响订单主流程，仅记录
            log.error("[membership] 会员积分累计失败 orderNo={}", event.getOrderNo(), e);
        }
    }
}
