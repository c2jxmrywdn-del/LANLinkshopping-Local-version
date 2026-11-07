package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.RabbitConfig;
import com.lanlink.shopping.integration.mq.OrderPaidDlqHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 订单支付死信队列运维治理端点（Phase 2 / G11 运维闭环）。
 * 提供死信堆积查询、批量重放与清空操作。受管理端鉴权拦截。
 */
@RestController
@RequestMapping("/admin/mq/dlq")
@ConditionalOnProperty(name = "app.mq.order-flow", havingValue = "mq", matchIfMissing = true)
public class AdminMqDlqController {

    private final OrderPaidDlqHandler dlqHandler;

    public AdminMqDlqController(OrderPaidDlqHandler dlqHandler) {
        this.dlqHandler = dlqHandler;
    }

    /** 查询死信积压条数 */
    @GetMapping("/depth")
    public R<Map<String, Object>> getDepth() {
        int depth = dlqHandler.depth();
        return R.ok(Map.of(
                "queue", RabbitConfig.ORDER_PAID_DLQ,
                "depth", depth
        ));
    }

    /** 批量重放至多 max 条死信回订单主题交换机 */
    @PostMapping("/replay")
    public R<Map<String, Object>> replay(@RequestParam(defaultValue = "10") int max) {
        int replayed = dlqHandler.replay(Math.max(1, Math.min(max, 500)));
        return R.ok("重放完成", Map.of(
                "replayed", replayed,
                "targetExchange", RabbitConfig.ORDER_EXCHANGE,
                "targetRoutingKey", RabbitConfig.ORDER_PAID_ROUTING_KEY
        ));
    }

    /** 清空死信队列（危险操作） */
    @PostMapping("/purge")
    public R<Map<String, Object>> purge() {
        int purged = dlqHandler.purge();
        return R.ok("死信队列已清空", Map.of(
                "purged", purged,
                "queue", RabbitConfig.ORDER_PAID_DLQ
        ));
    }
}
