package com.lanlink.shopping.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 异步事件处理：下单后的销量统计/日志/推荐刷新等非实时任务，异步执行以缩短主链路响应。
 */
@Service
public class AsyncEventService {

    private static final Logger log = LoggerFactory.getLogger(AsyncEventService.class);

    @Async
    public void onOrderCreated(String orderNo, long amountCents) {
        try {
            // 模拟耗时：写埋点、刷新销量榜、发送通知等，均不阻塞下单响应
            Thread.sleep(50);
            log.info("[异步] 订单 {} 后续统计/通知处理完成, amount={}", orderNo, amountCents);
        } catch (Exception e) {
            log.warn("[异步] 订单后处理失败(不影响下单): {}", e.getMessage());
        }
    }
}
