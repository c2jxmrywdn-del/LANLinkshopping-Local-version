package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.RabbitConfig;
import com.lanlink.shopping.integration.mq.OrderPaidDlqHandler;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单支付死信队列运维治理端点单测 (Phase 2 / G11)。
 */
class AdminMqDlqControllerTest {

    @Test
    void getDepthReturnsCurrentMessageCount() {
        OrderPaidDlqHandler handler = mock(OrderPaidDlqHandler.class);
        when(handler.depth()).thenReturn(3);

        AdminMqDlqController controller = new AdminMqDlqController(handler);
        R<Map<String, Object>> res = controller.getDepth();

        assertNotNull(res);
        assertEquals(200, res.getCode());
        assertEquals(3, res.getData().get("depth"));
        assertEquals(RabbitConfig.ORDER_PAID_DLQ, res.getData().get("queue"));
    }

    @Test
    void replayInvokesHandlerWithClampedMax() {
        OrderPaidDlqHandler handler = mock(OrderPaidDlqHandler.class);
        when(handler.replay(25)).thenReturn(25);

        AdminMqDlqController controller = new AdminMqDlqController(handler);
        R<Map<String, Object>> res = controller.replay(25);

        assertNotNull(res);
        assertEquals(200, res.getCode());
        assertEquals(25, res.getData().get("replayed"));
        verify(handler).replay(25);
    }

    @Test
    void purgeInvokesHandlerPurge() {
        OrderPaidDlqHandler handler = mock(OrderPaidDlqHandler.class);
        when(handler.purge()).thenReturn(7);

        AdminMqDlqController controller = new AdminMqDlqController(handler);
        R<Map<String, Object>> res = controller.purge();

        assertNotNull(res);
        assertEquals(200, res.getCode());
        assertEquals(7, res.getData().get("purged"));
        verify(handler).purge();
    }
}
