package com.lanlink.shopping.module.task.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.membership.service.MembershipService;
import com.lanlink.shopping.module.task.entity.TaskRecord;
import com.lanlink.shopping.module.task.mapper.TaskRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 任务激励单测（G2 口径）：refNo 幂等（重复事件只落一次）/ 每日上限 / 唯一键兜底 / 任务列表进度。
 * 纯 Mockito 环境（同 CampaignServiceTest 口径），lambda 列名解析延迟到 SQL 渲染，mock 下安全。
 */
class TaskServiceTest {

    private TaskRecordMapper taskRecordMapper;
    private MembershipService membershipService;
    private TaskService service;

    @BeforeEach
    void setUp() {
        taskRecordMapper = mock(TaskRecordMapper.class);
        membershipService = mock(MembershipService.class);
        service = new TaskService(taskRecordMapper, membershipService);
    }

    private TaskRecord record(String taskKey, String refNo, LocalDateTime createTime) {
        TaskRecord r = new TaskRecord();
        r.setUserId(7L);
        r.setTaskKey(taskKey);
        r.setRefNo(refNo);
        r.setPoints(100);
        r.setGrowth(100);
        r.setCreateTime(createTime);
        return r;
    }

    // ===== 首单任务：refNo 幂等（重复事件只落一次、只发一次分） =====

    @Test
    void completeFirstOrderInsertsRecordAndAwardsWithFixedRefNo() {
        when(taskRecordMapper.selectCount(any())).thenReturn(0L);

        service.completeFirstOrder(7L, "ORD1");

        ArgumentCaptor<TaskRecord> captor = ArgumentCaptor.forClass(TaskRecord.class);
        verify(taskRecordMapper).insert(captor.capture());
        TaskRecord saved = captor.getValue();
        assertEquals(7L, saved.getUserId());
        assertEquals("first_order", saved.getTaskKey());
        assertEquals("ORD1", saved.getRefNo()); // 幂等引用 = 订单号（规划 DDL 口径）
        assertEquals(100, saved.getPoints());
        assertEquals(100, saved.getGrowth());
        // 积分走 awardPoints，引用号固定为任务级（与订单号无关）→ 重复事件不重复计分
        verify(membershipService).awardPoints(eq(7L), eq(100), eq("task:first_order:7"), any());
    }

    @Test
    void completeFirstOrderReplayIsFullyIdempotent() {
        // 第一次发放
        when(taskRecordMapper.selectCount(any())).thenReturn(0L);
        service.completeFirstOrder(7L, "ORD1");
        // 事件重放（重复 OrderPaidEvent / MQ 重复投递）：守卫查到已有流水 → 直接返回
        when(taskRecordMapper.selectCount(any())).thenReturn(1L);
        service.completeFirstOrder(7L, "ORD1");

        // 重复事件只落一次、只发一次分
        verify(taskRecordMapper, times(1)).insert(any(TaskRecord.class));
        verify(membershipService, times(1)).awardPoints(anyLong(), anyInt(), any(), any());
    }

    @Test
    void completeFirstOrderDuplicateKeyIsIdempotent() {
        // 并发/重复事件绕过守卫后撞唯一键 uk_task_user_key_ref：幂等返回且不发分
        when(taskRecordMapper.selectCount(any())).thenReturn(0L);
        when(taskRecordMapper.insert(any(TaskRecord.class)))
                .thenThrow(new DuplicateKeyException("uk_task_user_key_ref"));

        assertDoesNotThrow(() -> service.completeFirstOrder(7L, "ORD1"));
        verify(membershipService, never()).awardPoints(anyLong(), anyInt(), any(), any());
    }

    /**
     * 并发两笔不同订单都通过「无流水」守卫时：任务记录可各落一条（refNo=orderNo 不同，唯一键不拦），
     * 但 awardPoints 引用号固定为 task:first_order:{userId} —— 积分流水唯一键
     * uk_point_user_type_ref(user_id, change_type, ref_order_no) 只放行一次，积分语义上仍只发一次。
     */
    @Test
    void completeFirstOrderUsesFixedAwardRefNoAcrossDifferentOrders() {
        when(taskRecordMapper.selectCount(any())).thenReturn(0L);

        service.completeFirstOrder(7L, "ORD-A");
        service.completeFirstOrder(7L, "ORD-B");

        verify(membershipService, times(2)).awardPoints(eq(7L), eq(100), eq("task:first_order:7"), any());
    }

    @Test
    void completeFirstOrderSkipsInvalidInput() {
        service.completeFirstOrder(null, "ORD1");
        service.completeFirstOrder(7L, null);
        service.completeFirstOrder(7L, " ");
        verify(taskRecordMapper, never()).insert(any(TaskRecord.class));
        verify(membershipService, never()).awardPoints(anyLong(), anyInt(), any(), any());
    }

    // ===== 分享任务：回执 + 服务端日期幂等 + 每日上限 =====

    @Test
    void claimShareComposesServerSideDateRefAndAwards() {
        when(taskRecordMapper.selectCount(any())).thenReturn(0L);

        Map<String, Object> out = service.claim(7L, "share_product", "123");

        String expectedDate = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        assertEquals("share_product", out.get("taskKey"));
        assertEquals("123:" + expectedDate, out.get("refNo")); // 回执标识:yyyyMMdd（服务端拼接）
        assertEquals(true, out.get("awarded"));

        ArgumentCaptor<TaskRecord> captor = ArgumentCaptor.forClass(TaskRecord.class);
        verify(taskRecordMapper).insert(captor.capture());
        assertEquals("123:" + expectedDate, captor.getValue().getRefNo());
        assertEquals(5, captor.getValue().getPoints());
        verify(membershipService).awardPoints(eq(7L), eq(5),
                eq("task:share_product:123:" + expectedDate), any());
    }

    @Test
    void claimShareRejectsWhenDailyLimitReached() {
        // 当日已有 3 条 → 每日上限（服务端按当日流水计数）
        when(taskRecordMapper.selectCount(any())).thenReturn(3L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.claim(7L, "share_product", "124"));
        assertTrue(ex.getMessage().contains("上限"));
        verify(taskRecordMapper, never()).insert(any(TaskRecord.class));
        verify(membershipService, never()).awardPoints(anyLong(), anyInt(), any(), any());
    }

    @Test
    void claimShareDuplicateKeyIsIdempotentWithoutDoubleAward() {
        // 同一回执当日重复提交：守卫与上限都通过，落库撞唯一键 → 幂等返回 awarded=false，不重复发分
        when(taskRecordMapper.selectCount(any())).thenReturn(0L);
        when(taskRecordMapper.insert(any(TaskRecord.class)))
                .thenThrow(new DuplicateKeyException("uk_task_user_key_ref"));

        Map<String, Object> out = assertDoesNotThrow(() -> service.claim(7L, "share_product", "123"));

        assertEquals(false, out.get("awarded"));
        verify(membershipService, never()).awardPoints(anyLong(), anyInt(), any(), any());
    }

    @Test
    void claimRejectsUnknownTaskFirstOrderOrMissingRef() {
        // first_order 由事件自动发放，不可手动领取
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.claim(7L, "first_order", "ORD1"));
        assertTrue(ex.getMessage().contains("自动发放"));
        // 未知道具 / 缺回执
        assertThrows(BusinessException.class, () -> service.claim(7L, "unknown_task", "x"));
        assertThrows(BusinessException.class, () -> service.claim(7L, "share_product", null));
        assertThrows(BusinessException.class, () -> service.claim(7L, "share_product", " "));
        assertThrows(BusinessException.class, () -> service.claim(null, "share_product", "123"));
        verify(taskRecordMapper, never()).insert(any(TaskRecord.class));
    }

    // ===== 任务中心列表：目录 + 进度 + 完成态 =====

    @Test
    void myTasksReturnsCatalogWithProgressAndStatus() {
        TaskRecord firstOrder = record("first_order", "ORD1", LocalDateTime.now());
        TaskRecord shareToday = record("share_product", "123:" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE),
                LocalDateTime.now());
        when(taskRecordMapper.selectList(any())).thenReturn(List.of(firstOrder, shareToday));

        List<Map<String, Object>> rows = service.myTasks(7L);

        assertEquals(2, rows.size());
        Map<String, Object> first = rows.get(0);
        assertEquals("first_order", first.get("taskKey"));
        assertEquals("completed", first.get("status")); // 一次性任务：有流水即完成
        assertEquals(1, first.get("totalDone"));

        Map<String, Object> share = rows.get(1);
        assertEquals("share_product", share.get("taskKey"));
        assertEquals("open", share.get("status"));       // 今日 1/3，未达上限
        assertEquals(1, share.get("todayDone"));
        assertEquals(2, share.get("remainingToday"));
    }

    @Test
    void myTasksCountsOnlyTodayRecordsForDailyProgress() {
        TaskRecord shareYesterday = record("share_product", "123:20260101", LocalDateTime.now().minusDays(1));
        when(taskRecordMapper.selectList(any())).thenReturn(List.of(shareYesterday));

        List<Map<String, Object>> rows = service.myTasks(7L);

        Map<String, Object> share = rows.stream()
                .filter(r -> "share_product".equals(r.get("taskKey"))).findFirst().orElseThrow();
        assertEquals(1, share.get("totalDone"));
        assertEquals(0, share.get("todayDone")); // 昨日流水不计今日进度（跨日重置）
        assertEquals(3, share.get("remainingToday"));
        assertEquals("open", share.get("status"));
    }

    @Test
    void myTasksRequiresLogin() {
        assertThrows(BusinessException.class, () -> service.myTasks(null));
    }
}
