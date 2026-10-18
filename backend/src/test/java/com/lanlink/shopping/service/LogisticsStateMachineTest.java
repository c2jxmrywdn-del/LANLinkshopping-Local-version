package com.lanlink.shopping.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 运单状态机单元测试：合法边流转 / 禁止跳级回退 / 终态 / 幂等。
 */
class LogisticsStateMachineTest {

    @Test
    void happyPathTransitions() {
        assertTrue(LogisticsStateMachine.canTransit("pending_ship", "picked_up"));
        assertTrue(LogisticsStateMachine.canTransit("picked_up", "in_transit"));
        assertTrue(LogisticsStateMachine.canTransit("in_transit", "out_for_delivery"));
        assertTrue(LogisticsStateMachine.canTransit("out_for_delivery", "delivered"));
    }

    @Test
    void idempotentSameStatusAllowed() {
        // 轨迹重复同步（同状态）幂等放行
        assertTrue(LogisticsStateMachine.canTransit("in_transit", "in_transit"));
    }

    @Test
    void forbidJumpAndRollback() {
        // 禁止跳级
        assertFalse(LogisticsStateMachine.canTransit("pending_ship", "delivered"));
        // 禁止回退
        assertFalse(LogisticsStateMachine.canTransit("delivered", "in_transit"));
        assertFalse(LogisticsStateMachine.canTransit("out_for_delivery", "picked_up"));
    }

    @Test
    void exceptionBranch() {
        assertTrue(LogisticsStateMachine.canTransit("in_transit", "exception"));
        assertTrue(LogisticsStateMachine.canTransit("out_for_delivery", "exception"));
        assertTrue(LogisticsStateMachine.canTransit("exception", "in_transit")); // 异常解除继续运输
        assertTrue(LogisticsStateMachine.canTransit("exception", "returned"));
        assertTrue(LogisticsStateMachine.canTransit("exception", "lost"));
    }

    @Test
    void terminalStates() {
        assertTrue(LogisticsStateMachine.isTerminal("delivered"));
        assertTrue(LogisticsStateMachine.isTerminal("returned"));
        assertTrue(LogisticsStateMachine.isTerminal("lost"));
        assertTrue(LogisticsStateMachine.isTerminal("cancelled"));
        // 终态不可再流转
        assertFalse(LogisticsStateMachine.canTransit("delivered", "in_transit"));
        assertFalse(LogisticsStateMachine.canTransit("returned", "in_transit"));
        assertFalse(LogisticsStateMachine.canTransit("cancelled", "picked_up"));
    }

    @Test
    void invalidInput() {
        assertFalse(LogisticsStateMachine.isValid(null));
        assertFalse(LogisticsStateMachine.isValid("unknown_status"));
        assertTrue(LogisticsStateMachine.isValid("pending_ship"));
        assertFalse(LogisticsStateMachine.canTransit(null, "in_transit"));
        assertFalse(LogisticsStateMachine.canTransit("in_transit", null));
        assertFalse(LogisticsStateMachine.canTransit("in_transit", "bogus"));
    }
}
