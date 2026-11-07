package com.lanlink.shopping.integration.mq;

import com.lanlink.shopping.config.RabbitConfig;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.membership.service.MembershipService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 会员积分 MQ 消费者（out/storage-mq-plan.md 任务 B4，门禁 G9/G10）。
 *
 * 消费 q.order.paid.membership → 先经 {@link OrderPaidMessageValidator} 反查 t_order/t_credit_bill
 * 确认载荷真伪（security-hardening-plan.md P1-6.1：伪造 orderNo 铸积分收口）→
 * 复用 MembershipService.earnGrowth；双倍积分判据（D2）= 身份 VIP ∪ 有效订阅，
 * 统一走 {@link MembershipService#multiplierOf}（与事件通道同源）。
 *
 * 幂等（R2）：earnGrowth 内部按 orderNo 查重（MembershipService.java:89-97），
 * at-least-once 重复投递/重放不重复计分（G10：重投后积分记录计数不变）。
 *
 * 失败策略（R3）：业务异常不再吞掉、直接上抛 → spring-retry 3 次（1s/2s/4s）→
 * default-requeue-rejected=false 不回队列 → 经 lanlink.dlx 进 q.order.paid.dlq（G11）。
 *
 * 开关：app.mq.order-flow=event 时本类不注册（消费回退 Spring 事件通道）。
 */
@Component
@ConditionalOnProperty(name = "app.mq.order-flow", havingValue = "mq", matchIfMissing = true)
public class MembershipOrderMqConsumer {

    private static final Logger log = LoggerFactory.getLogger(MembershipOrderMqConsumer.class);

    private final MembershipService membershipService;
    private final UserMapper userMapper;
    private final OrderPaidMessageValidator validator;

    public MembershipOrderMqConsumer(MembershipService membershipService,
                                     UserMapper userMapper, OrderPaidMessageValidator validator) {
        this.membershipService = membershipService;
        this.userMapper = userMapper;
        this.validator = validator;
    }

    @RabbitListener(queues = RabbitConfig.ORDER_PAID_MEMBERSHIP_QUEUE)
    public void onOrderPaid(OrderPaidMessage message) {
        // 入账前反查真实订单（SG-61）：不符即 MessageIntegrityException → 重试耗尽 → DLQ
        validator.requireLegitOrderPaid(message);
        User user = userMapper.selectById(message.userId());
        Long roleId = user == null ? null : user.getRoleId();
        // D2 双倍积分判据统一收敛到 MembershipService.multiplierOf（身份 VIP ∪ 有效订阅），与事件通道共用
        int multiplier = membershipService.multiplierOf(message.userId(), roleId);
        membershipService.earnGrowth(message.userId(), message.amount(), message.orderNo(), multiplier);
        log.info("[mq][membership] 订单 {} 会员积分累计完成 userId={} multiplier={}",
                message.orderNo(), message.userId(), multiplier);
    }
}
