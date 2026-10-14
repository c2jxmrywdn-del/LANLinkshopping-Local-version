package com.lanlink.shopping.module.activity.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.activity.entity.Activity;
import com.lanlink.shopping.module.activity.entity.ActivityParticipant;
import com.lanlink.shopping.module.activity.mapper.ActivityMapper;
import com.lanlink.shopping.module.activity.mapper.ActivityParticipantMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 活动系统单测：参与幂等 / 状态校验 / 消费活动自动参与
 */
class ActivityServiceTest {

    private ActivityMapper activityMapper;
    private ActivityParticipantMapper participantMapper;
    private com.lanlink.shopping.module.membership.service.MembershipService membershipService;
    private ActivityService service;

    private final LocalDateTime now = LocalDateTime.now();

    @BeforeEach
    void setUp() {
        activityMapper = mock(ActivityMapper.class);
        participantMapper = mock(ActivityParticipantMapper.class);
        membershipService = mock(com.lanlink.shopping.module.membership.service.MembershipService.class);
        service = new ActivityService(activityMapper, participantMapper, membershipService);
    }

    private Activity activity(Long id, String type, Integer status) {
        Activity a = new Activity();
        a.setActivityId(id);
        a.setTitle("活动" + id);
        a.setType(type);
        a.setStatus(status);
        a.setStartTime(now.minusDays(1));
        a.setEndTime(now.plusDays(1));
        return a;
    }

    @Test
    void joinOngoingActivityOk() {
        when(activityMapper.selectById(1L)).thenReturn(activity(1L, "register", 1));
        when(participantMapper.selectCount(any())).thenReturn(0L);
        assertDoesNotThrow(() -> service.join(2L, 1L));
        verify(participantMapper, times(1)).insert(any(ActivityParticipant.class));
    }

    @Test
    void joinRejectedWhenDuplicate() {
        when(activityMapper.selectById(1L)).thenReturn(activity(1L, "register", 1));
        when(participantMapper.selectCount(any())).thenReturn(1L); // 已参与
        BusinessException ex = assertThrows(BusinessException.class, () -> service.join(2L, 1L));
        assertTrue(ex.getMessage().contains("已参与"));
    }

    @Test
    void joinRejectedWhenNotOngoing() {
        when(activityMapper.selectById(1L)).thenReturn(activity(1L, "register", 2)); // 已结束
        when(participantMapper.selectCount(any())).thenReturn(0L);
        assertThrows(BusinessException.class, () -> service.join(2L, 1L));
    }

    @Test
    void autoJoinPurchaseOnlyOngoingAndNotJoined() {
        when(activityMapper.selectList(any())).thenReturn(List.of(activity(1L, "purchase", 1)));
        // 未参与 → 自动记录
        when(participantMapper.selectCount(any())).thenReturn(0L);
        service.autoJoinPurchase(2L);
        verify(participantMapper, times(1)).insert(any(ActivityParticipant.class));

        // 已参与 → 不重复插入
        reset(participantMapper);
        when(participantMapper.selectCount(any())).thenReturn(1L);
        service.autoJoinPurchase(2L);
        verify(participantMapper, never()).insert(any(ActivityParticipant.class));
    }
}
