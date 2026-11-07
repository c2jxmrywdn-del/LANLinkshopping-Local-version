package com.lanlink.shopping.module.tracking;

import com.lanlink.shopping.module.tracking.adapter.ProviderRouter;
import com.lanlink.shopping.module.tracking.adapter.TrackingChannel;
import com.lanlink.shopping.module.tracking.config.TrackingProperties;
import com.lanlink.shopping.module.tracking.domain.TrackingSyncTask;
import com.lanlink.shopping.module.tracking.mapper.TrackingSyncTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 双通道路由主备决策单测（out/logistics-tracking-plan.md P2/P3 任务 1 / ask 任务 6「通道切换阈值」）：
 * 主通道连续失败 <3 次不切换；第 3 次失败后 decide 切到备用通道（fail_count 累计 + 切换日志）；
 * 成功清零；未配置备用映射不切换。
 */
class TrackingProviderRouterTest {

    private TrackingSyncTaskMapper taskMapper;
    private TrackingProperties properties;
    private ProviderRouter router;
    private TrackingSyncTask task;

    @BeforeEach
    void setUp() {
        taskMapper = mock(TrackingSyncTaskMapper.class);
        properties = new TrackingProperties();
        properties.setPrimaryFailThreshold(3);
        properties.getFallbackRoutes().put("kuaidi100", "mock");
        router = new ProviderRouter(properties, taskMapper);

        task = new TrackingSyncTask();
        task.setTaskId(11L);
        task.setWaybillId(77L);
        task.setProviderCode("kuaidi100");
        task.setStatus(TrackingSyncTask.STATUS_PENDING);
        task.setRetryCount(0);
    }

    @Test
    @DisplayName("连续失败 1-2 次：fail_count 累计但不切换，仍走主通道")
    void consecutiveFailuresBelowThresholdStayPrimary() {
        router.recordFailure(task, "upstream 500");
        router.recordFailure(task, "upstream 500");

        assertEquals(2, task.getFailCount());

        ProviderRouter.RouteDecision decision = router.decide(task);
        assertEquals(TrackingChannel.KUAIDI100, decision.channel());
        assertFalse(decision.switched());
        assertNull(task.getActiveChannel());
        verify(taskMapper, times(2)).updateById(any(TrackingSyncTask.class));
    }

    @Test
    @DisplayName("连续失败达 3 次阈值：decide 切换备用通道并落切换日志（active_channel + last_error）")
    void thresholdReachedSwitchesToFallbackWithSwitchLog() {
        router.recordFailure(task, "upstream 500");
        router.recordFailure(task, "upstream 500");
        router.recordFailure(task, "upstream timeout");

        assertEquals(3, task.getFailCount());

        ProviderRouter.RouteDecision decision = router.decide(task);
        assertTrue(decision.switched(), "连续失败 3 次应切换备用通道");
        assertEquals(TrackingChannel.MOCK, decision.channel());
        assertEquals("mock", task.getActiveChannel());
        // 备用通道重新计数
        assertEquals(0, task.getFailCount());
        // 切换日志持久化在任务行上，且主备通道名可见
        assertTrue(task.getLastError().contains("kuaidi100"), task.getLastError());
        assertTrue(task.getLastError().contains("mock"), task.getLastError());
        assertTrue(decision.switchReason().contains("3 consecutive times"));
    }

    @Test
    @DisplayName("成功后 fail_count 清零，后续决策维持主通道")
    void successResetsConsecutiveFailCount() {
        router.recordFailure(task, "upstream 500");
        router.recordSuccess(task);

        assertEquals(0, task.getFailCount());
        ProviderRouter.RouteDecision decision = router.decide(task);
        assertEquals(TrackingChannel.KUAIDI100, decision.channel());
        assertFalse(decision.switched());
    }

    @Test
    @DisplayName("未配置备用映射的渠道：达阈值也不切换（原渠道退避重试）")
    void noFallbackConfiguredStaysPrimary() {
        properties.getFallbackRoutes().clear();
        router.recordFailure(task, "upstream 500");
        router.recordFailure(task, "upstream 500");
        router.recordFailure(task, "upstream 500");

        ProviderRouter.RouteDecision decision = router.decide(task);
        assertEquals(TrackingChannel.KUAIDI100, decision.channel());
        assertFalse(decision.switched());
        assertNull(task.getActiveChannel());
    }

    @Test
    @DisplayName("已在备用通道：失败继续累计但不二次切换；activeChannel 缺省回退 providerCode")
    void fallbackChannelStaysPutAndCurrentChannelFallsBackToProviderCode() {
        assertEquals(TrackingChannel.KUAIDI100, router.currentChannel(task),
                "未设置 active_channel 时回退主通道 providerCode");

        task.setActiveChannel("mock");
        assertEquals(TrackingChannel.MOCK, router.currentChannel(task));

        router.recordFailure(task, "fallback also down");
        router.recordFailure(task, "fallback also down");
        router.recordFailure(task, "fallback also down");
        ProviderRouter.RouteDecision decision = router.decide(task);
        assertEquals(TrackingChannel.MOCK, decision.channel());
        assertFalse(decision.switched(), "备用通道失败不再二次切换");
        assertEquals("mock", task.getActiveChannel());
        assertEquals(3, task.getFailCount());
    }

    @Test
    @DisplayName("路由映射不符时（fallback 指向自身）不切换")
    void selfMappedFallbackDoesNotSwitch() {
        properties.getFallbackRoutes().put("kuaidi100", "KUAIDI100");
        router.recordFailure(task, "upstream 500");
        router.recordFailure(task, "upstream 500");
        router.recordFailure(task, "upstream 500");
        ProviderRouter.RouteDecision decision = router.decide(task);
        assertFalse(decision.switched());
        assertEquals(TrackingChannel.KUAIDI100, decision.channel());
    }
}
