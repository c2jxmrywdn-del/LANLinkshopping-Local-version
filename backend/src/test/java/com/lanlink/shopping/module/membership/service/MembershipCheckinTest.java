package com.lanlink.shopping.module.membership.service;

import com.lanlink.shopping.module.membership.entity.CheckinRecord;
import com.lanlink.shopping.module.membership.entity.MemberCard;
import com.lanlink.shopping.module.membership.entity.MemberPointLog;
import com.lanlink.shopping.module.membership.mapper.CheckinMapper;
import com.lanlink.shopping.module.membership.mapper.MemberCardMapper;
import com.lanlink.shopping.module.membership.mapper.MemberLevelMapper;
import com.lanlink.shopping.module.membership.mapper.MemberPointLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 每日签到单测：首签/连续/断签重置/幂等/奖励档位/并发唯一键兜底/本月日历。
 * 纯 JUnit5 + Mockito（直接 new + mock mapper，同 MerchantChatServiceTest 模式）。
 */
class MembershipCheckinTest {

    private MemberCardMapper cardMapper;
    private MemberLevelMapper levelMapper;
    private MemberPointLogMapper pointLogMapper;
    private SubscriptionService subscriptionService;
    private CheckinMapper checkinMapper;
    private MembershipService service;
    private MemberCard card;

    @BeforeEach
    void setUp() {
        cardMapper = mock(MemberCardMapper.class);
        levelMapper = mock(MemberLevelMapper.class);
        pointLogMapper = mock(MemberPointLogMapper.class);
        subscriptionService = mock(SubscriptionService.class);
        checkinMapper = mock(CheckinMapper.class);
        service = new MembershipService(cardMapper, levelMapper, pointLogMapper, subscriptionService, checkinMapper);

        card = new MemberCard();
        card.setUserId(7L);
        card.setLevelId(1L);
        card.setGrowth(0);
        card.setPoints(100);
        when(cardMapper.selectOne(any())).thenReturn(card);
        when(cardMapper.updateById(any(MemberCard.class))).thenReturn(1);
        when(pointLogMapper.selectCount(any())).thenReturn(0L);
        when(pointLogMapper.insert(any(MemberPointLog.class))).thenReturn(1);
        when(checkinMapper.insert(any(CheckinRecord.class))).thenReturn(1);
    }

    private CheckinRecord record(LocalDate date, int points, int streak) {
        CheckinRecord r = new CheckinRecord();
        r.setUserId(7L);
        r.setCheckinDate(date);
        r.setPoints(points);
        r.setStreak(streak);
        return r;
    }

    // ===== 奖励档位：10 + min(streak-1, 6) × 5 =====

    @Test
    void pointsTierTable() {
        assertEquals(10, MembershipService.checkinPointsFor(1));
        assertEquals(15, MembershipService.checkinPointsFor(2));
        assertEquals(20, MembershipService.checkinPointsFor(3));
        assertEquals(40, MembershipService.checkinPointsFor(7));
        assertEquals(40, MembershipService.checkinPointsFor(30)); // 封顶
    }

    // ===== 首签 =====

    @Test
    void firstCheckinCreatesRecordWithStreakOneAndAwardsPoints() {
        when(checkinMapper.selectOne(any())).thenReturn(null, (CheckinRecord) null);

        Map<String, Object> out = service.checkin(7L);

        assertEquals(LocalDate.now().toString(), out.get("date"));
        assertEquals(10, out.get("points"));
        assertEquals(1, out.get("streak"));
        assertEquals(Boolean.TRUE, out.get("signedToday"));

        ArgumentCaptor<CheckinRecord> captor = ArgumentCaptor.forClass(CheckinRecord.class);
        verify(checkinMapper).insert(captor.capture());
        assertEquals(7L, captor.getValue().getUserId());
        assertEquals(LocalDate.now(), captor.getValue().getCheckinDate());
        assertEquals(1, captor.getValue().getStreak());
        assertEquals(10, captor.getValue().getPoints());

        // 积分经 awardPoints 幂等落账（refNo=checkin:yyyy-MM-dd）
        ArgumentCaptor<MemberPointLog> logCaptor = ArgumentCaptor.forClass(MemberPointLog.class);
        verify(pointLogMapper).insert(logCaptor.capture());
        assertEquals("checkin:" + LocalDate.now(), logCaptor.getValue().getRefOrderNo());
        assertEquals(10, logCaptor.getValue().getChangeVal());
        ArgumentCaptor<MemberCard> cardCaptor = ArgumentCaptor.forClass(MemberCard.class);
        verify(cardMapper).updateById(cardCaptor.capture());
        assertEquals(110, cardCaptor.getValue().getPoints());
    }

    // ===== 连续签到 =====

    @Test
    void consecutiveCheckinIncrementsStreakAndBonus() {
        CheckinRecord yesterday = record(LocalDate.now().minusDays(1), 25, 4);
        when(checkinMapper.selectOne(any())).thenReturn(null, yesterday);

        Map<String, Object> out = service.checkin(7L);

        assertEquals(5, out.get("streak"));
        assertEquals(30, out.get("points")); // 10 + min(4,6)×5
    }

    @Test
    void streakBonusCappedAtSixSteps() {
        CheckinRecord yesterday = record(LocalDate.now().minusDays(1), 40, 10);
        when(checkinMapper.selectOne(any())).thenReturn(null, yesterday);

        Map<String, Object> out = service.checkin(7L);

        assertEquals(11, out.get("streak"));
        assertEquals(40, out.get("points")); // 封顶：10 + 6×5
    }

    // ===== 断签重置 =====

    @Test
    void brokenStreakResetsToOne() {
        // 昨天无记录（断签），今天重签 → streak 回 1
        when(checkinMapper.selectOne(any())).thenReturn(null, (CheckinRecord) null);

        Map<String, Object> out = service.checkin(7L);

        assertEquals(1, out.get("streak"));
        assertEquals(10, out.get("points"));
    }

    // ===== 幂等：今日已签 =====

    @Test
    void checkinIsIdempotentWhenAlreadySignedToday() {
        CheckinRecord today = record(LocalDate.now(), 15, 2);
        when(checkinMapper.selectOne(any())).thenReturn(today);

        Map<String, Object> out = service.checkin(7L);

        assertEquals(LocalDate.now().toString(), out.get("date"));
        assertEquals(15, out.get("points"));
        assertEquals(2, out.get("streak"));
        assertEquals(Boolean.TRUE, out.get("signedToday"));
        // 不重复落库、不重复加分
        verify(checkinMapper, never()).insert(any(CheckinRecord.class));
        verify(pointLogMapper, never()).insert(any(MemberPointLog.class));
        verify(cardMapper, never()).updateById(any(MemberCard.class));
    }

    // ===== 并发重复签到：唯一键兜底按已签处理 =====

    @Test
    void concurrentDuplicateCheckinFallsBackToExistingRecord() {
        CheckinRecord today = record(LocalDate.now(), 10, 1);
        // 第 1 次查今日=未签，第 2 次查昨日=无，insert 撞唯一键，第 3 次查今日=已有记录
        when(checkinMapper.selectOne(any())).thenReturn(null, null, today);
        when(checkinMapper.insert(any(CheckinRecord.class)))
                .thenThrow(new DuplicateKeyException("uk_checkin_user_date"));

        Map<String, Object> out = assertDoesNotThrow(() -> service.checkin(7L));

        assertEquals(Boolean.TRUE, out.get("signedToday"));
        assertEquals(10, out.get("points"));
        verify(pointLogMapper, never()).insert(any(MemberPointLog.class));
    }

    // ===== 签到状态 =====

    @Test
    void statusReportsSignedTodayWithMonthCalendar() {
        LocalDate d1 = LocalDate.now().withDayOfMonth(1);
        CheckinRecord today = record(LocalDate.now(), 20, 3);
        when(checkinMapper.selectOne(any())).thenReturn(today);
        when(checkinMapper.selectList(any())).thenReturn(List.of(record(d1, 10, 1), today));

        Map<String, Object> out = service.checkinStatus(7L);

        assertEquals(Boolean.TRUE, out.get("signedToday"));
        assertEquals(3, out.get("streak"));
        assertEquals(20, out.get("points"));
        assertEquals(2, ((List<?>) out.get("month")).size());
    }

    @Test
    void statusWhenNotSignedKeepsYesterdayStreakAlive() {
        CheckinRecord yesterday = record(LocalDate.now().minusDays(1), 20, 3);
        when(checkinMapper.selectOne(any())).thenReturn(null, yesterday); // 今日未签、昨日有记录
        when(checkinMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> out = service.checkinStatus(7L);

        assertEquals(Boolean.FALSE, out.get("signedToday"));
        assertEquals(3, out.get("streak")); // 未签时延续昨日连签
        assertEquals(0, out.get("points"));
    }
}
