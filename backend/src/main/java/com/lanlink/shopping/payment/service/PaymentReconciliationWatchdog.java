package com.lanlink.shopping.payment.service;

import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.mapper.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 支付各渠道退款主动对账与超时补偿看门狗 (P2 阶段对账补偿 Watchdog)。
 * 周期性扫描在途处理中（refund_status = 'processing'）超过 15 分钟的退款单，
 * 主动轮询对应微信/支付宝/Stripe 渠道，自动推进到账成功或安全回滚，彻底消除卡单死锁。
 */
@Component
public class PaymentReconciliationWatchdog {

    private static final Logger log = LoggerFactory.getLogger("PAYMENT");
    private static final int TIMEOUT_MINUTES = 15;
    private static final int BATCH_LIMIT = 50;

    private final OrderMapper orderMapper;
    private final PaymentService paymentService;

    public PaymentReconciliationWatchdog(OrderMapper orderMapper, PaymentService paymentService) {
        this.orderMapper = orderMapper;
        this.paymentService = paymentService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void scanAndReconcile() {
        try {
            List<Order> stales = orderMapper.listStaleProcessingRefunds(TIMEOUT_MINUTES, BATCH_LIMIT);
            if (stales == null || stales.isEmpty()) {
                return;
            }

            log.info("[WATCHDOG] 扫描到 {} 笔超时在途处理中退款订单(>{}分钟)，启动主动对账补偿...",
                    stales.size(), TIMEOUT_MINUTES);

            for (Order o : stales) {
                try {
                    log.info("[WATCHDOG] 正在补偿对账退款单: orderNo={}, channel={}, updateTime={}",
                            o.getOrderNo(), o.getPayChannel(), o.getUpdateTime());
                    boolean reconciled = paymentService.syncRefundStatus(o.getOrderNo());
                    if (reconciled) {
                        log.info("[WATCHDOG] 订单 {} 退款对账自愈成功", o.getOrderNo());
                    } else {
                        log.info("[WATCHDOG] 订单 {} 渠道尚未出账或已安全回退", o.getOrderNo());
                    }
                } catch (Exception ex) {
                    log.error("[WATCHDOG] 订单 {} 对账处理异常: {}", o.getOrderNo(), ex.getMessage(), ex);
                }
            }
        } catch (Exception e) {
            log.error("[WATCHDOG] 扫描在途退款订单全局异常: {}", e.getMessage(), e);
        }
    }
}
