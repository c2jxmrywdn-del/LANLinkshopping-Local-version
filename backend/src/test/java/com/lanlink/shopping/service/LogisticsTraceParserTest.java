package com.lanlink.shopping.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 轨迹解析器单元测试：描述关键词 → 状态 + 异常标记 + 告警类型。
 */
class LogisticsTraceParserTest {

    private final LogisticsTraceParser parser = new LogisticsTraceParser();

    @Test
    void normalNodes() {
        assertEquals("picked_up", parser.parse("快件已揽收").status());
        assertEquals("in_transit", parser.parse("快件已到达广州中转场").status());
        assertEquals("out_for_delivery", parser.parse("快递员正在派送中").status());
        assertEquals("delivered", parser.parse("快件已签收，签收人：本人").status());
    }

    @Test
    void abnormalNodes() {
        var reject = parser.parse("收件人拒收，快件退回");
        assertTrue(reject.abnormal());
        assertEquals("delivered", parser.parse("已签收").status());
        assertFalse(parser.parse("已签收").abnormal());
    }

    @Test
    void rejectAndReturn() {
        var reject = parser.parse("客户拒收");
        assertTrue(reject.abnormal());
        assertEquals("reject", reject.alertType());
        var ret = parser.parse("快件退回发件方");
        assertEquals("return", ret.alertType());
        assertEquals("returned", ret.status());
    }

    @Test
    void damageAndLost() {
        assertEquals("damage", parser.parse("运输途中破损").alertType());
        var lost = parser.parse("快件丢失");
        assertEquals("lost", lost.alertType());
        assertEquals("lost", lost.status());
    }

    @Test
    void delay() {
        var d = parser.parse("快件滞留超时，异常停留");
        assertTrue(d.abnormal());
        assertEquals("delay", d.alertType());
    }

    @Test
    void unknownDescriptionDefaultsToTransit() {
        var u = parser.parse("状态未知描述");
        assertEquals("in_transit", u.status());
        assertFalse(u.abnormal());
    }

    @Test
    void nullDescriptionSafe() {
        var n = parser.parse(null);
        assertEquals("in_transit", n.status());
        assertFalse(n.abnormal());
    }
}
