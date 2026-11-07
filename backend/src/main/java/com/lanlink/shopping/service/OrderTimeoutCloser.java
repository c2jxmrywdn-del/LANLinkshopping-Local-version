package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.mapper.OrderMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时未支付订单自动关闭。
 *
 * <p>为什么先做扫表而不是延迟消息：订单量级远未到需要秒级释放的程度（扫表 1 分钟一轮、
 * 每轮 50 单足够），而扫表以数据库为唯一事实来源、可重跑、不会因为消息丢失而漏单；
 * 等单量或 SLA 真的顶不住，再把它降级成兜底、由延迟消息做主力。
 *
 * <p>并发安全全部落在 SQL 条件更新上，不引入分布式锁：
 * 关单只改「pay_status=0 且 order_status=0 且非账期」的行（{@code closeUnpaidAtomic}），
 * 支付只改「pay_status=0 且 order_status=0」的行（{@code markPaidAtomic}），两者互斥；
 * 多实例同时扫到同一单时，只有一个实例能拿到 affected=1，也只有它去恢复库存与券。
 *
 * <p>账期单（pay_type=term）不参与超时关单：它的 pay_status=0 表示"已占用授信、待还款"，
 * 不是"没付钱"，应走账期到期与逾期催收。
 */
@Component
public class OrderTimeoutCloser {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutCloser.class);

    /** 单轮上限：限制一次扫描持有的行数，避免长时间锁表与长事务 */
    static final int BATCH = 50;

    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final MeterRegistry registry;

    @Value("${app.order.auto-close-enabled:true}")
    private boolean enabled;

    @Value("${app.order.auto-close-timeout-minutes:30}")
    private long timeoutMinutes;

    public OrderTimeoutCloser(OrderMapper orderMapper, OrderService orderService, MeterRegistry registry) {
        this.orderMapper = orderMapper;
        this.orderService = orderService;
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${app.order.auto-close-interval-ms:60000}",
            initialDelayString = "${app.order.auto-close-initial-delay-ms:30000}")
    public void sweep() {
        drain(LocalDateTime.now());
    }

    /**
     * 可注入时间的扫描入口：单测直接传固定 now，不依赖等待真实时钟。
     *
     * @return 本轮真正关闭的订单数
     */
    public int drain(LocalDateTime now) {
        if (!enabled) {
            return 0;
        }
        LocalDateTime deadline = now.minusMinutes(timeoutMinutes);
        List<Order> candidates = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getPayStatus, 0)
                .eq(Order::getOrderStatus, 0)
                // pay_type 可能为 NULL：SQL 的 <> 对 NULL 不成立，必须显式带上 IS NULL
                .and(w -> w.isNull(Order::getPayType).or().ne(Order::getPayType, "term"))
                .lt(Order::getCreateTime, deadline)
                .orderByAsc(Order::getCreateTime)
                .last("LIMIT " + BATCH));
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int closed = 0;
        for (Order o : candidates) {
            try {
                if (orderService.closeUnpaid(o.getOrderNo())) {
                    closed++;
                    registry.counter("lanlink.order.autoclose", "outcome", "closed").increment();
                } else {
                    // 扫描到关单之间被支付或被用户取消：正常竞态，不是失败
                    registry.counter("lanlink.order.autoclose", "outcome", "race").increment();
                }
            } catch (Exception e) {
                // 单条失败不拖垮整轮：资源释放已随事务回滚，下一轮还会扫到（状态仍是未支付）
                registry.counter("lanlink.order.autoclose", "outcome", "failed").increment();
                log.warn("[ORDER] 超时关单失败，留待下一轮 order={}", o.getOrderNo(), e);
            }
        }
        if (closed > 0) {
            log.info("[ORDER] 本轮超时关单 {} 单（候选 {}）", closed, candidates.size());
        }
        return closed;
    }
}
