package com.lanlink.shopping.module.attachment.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.module.attachment.entity.Attachment;
import com.lanlink.shopping.module.attachment.mapper.AttachmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 挂起件回收任务。
 *
 * <p>扫表条件与 AttachmentRetention 的纯函数判定要同时成立：库里的 create_time 与应用时钟
 * 可能因时区/时钟漂移不一致，代码侧再确认一次，避免把刚上传的材料当过期件删掉。
 */
class AttachmentMaintenanceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 12, 0);

    private AttachmentMapper mapper;
    private StorageService storage;
    private AttachmentMaintenance maintenance;

    @BeforeEach
    void setUp() {
        mapper = mock(AttachmentMapper.class);
        storage = mock(StorageService.class);
        maintenance = new AttachmentMaintenance(storage, mapper);
        // 条件更新默认命中；演练"被并发抢先绑定"的用例自己改返回 0
        when(mapper.update(any(Attachment.class), anyWrapper())).thenReturn(1);
    }

    private static IPage<Attachment> anyPage() {
        return org.mockito.ArgumentMatchers.any();
    }

    private static Wrapper<Attachment> anyWrapper() {
        return org.mockito.ArgumentMatchers.any();
    }

    private Attachment pending(String no, String key, LocalDateTime createdAt) {
        Attachment a = new Attachment();
        a.setAttachNo(no);
        a.setObjectKey(key);
        a.setStatus("PENDING");
        a.setCreateTime(createdAt);
        return a;
    }

    @Test
    void reclaimMarksRowExpiredAndRemovesObjectForStalePendingMaterial() {
        when(mapper.selectList(anyPage(), anyWrapper()))
                .thenReturn(List.of(pending("AT_OLD", "attachments/merchant-license/2026-10/old.pdf",
                        NOW.minusHours(30))));

        assertEquals(1, maintenance.reclaim(NOW));

        verify(mapper).update(any(Attachment.class), anyWrapper());
        verify(storage).delete("attachments/merchant-license/2026-10/old.pdf");
    }

    @Test
    void reclaimSkipsRowsThatFailTheCodeSideCheck() {
        // 库里按条件捞回来的行仍要过一遍纯函数判定：时钟或时区口径不一致时不能误删
        when(mapper.selectList(anyPage(), anyWrapper()))
                .thenReturn(List.of(pending("AT_NEW", "attachments/merchant-license/2026-11/new.pdf",
                        NOW.minusMinutes(5))));

        assertEquals(0, maintenance.reclaim(NOW));

        verify(mapper, never()).update(any(Attachment.class), anyWrapper());
        verify(storage, never()).delete(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void objectRemovalFailureStillCountsRowAsRetired() {
        when(mapper.selectList(anyPage(), anyWrapper()))
                .thenReturn(List.of(pending("AT_OLD", "attachments/merchant-license/2026-10/old.pdf",
                        NOW.minusHours(30))));
        org.mockito.Mockito.doThrow(new RuntimeException("存储不可用"))
                .when(storage).delete("attachments/merchant-license/2026-10/old.pdf");

        assertEquals(1, maintenance.reclaim(NOW),
                "行已失效即视为回收完成，对象残留交给运维兜底，不能因为一次删除失败反复重扫");
        verify(mapper).update(any(Attachment.class), anyWrapper());
    }

    @Test
    void reclaimSkipsObjectDeletionWhenTheRowWasBoundInBetween() {
        // 扫到行与置失效之间存在 bind 竞态：用户卡在第 24 小时提交绑定。
        // 条件更新只影响 0 行就必须收手，否则会把刚交付的材料对象删掉。
        when(mapper.selectList(anyPage(), anyWrapper()))
                .thenReturn(List.of(pending("AT_RACE", "attachments/tech-deal-document/2026-11/deal.pdf",
                        NOW.minusHours(25))));
        when(mapper.update(any(Attachment.class), anyWrapper())).thenReturn(0);

        assertEquals(0, maintenance.reclaim(NOW));

        verify(storage, never()).delete(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void reclaimHandlesEmptyResultWithoutTouchingStorage() {
        when(mapper.selectList(anyPage(), anyWrapper())).thenReturn(List.of());

        assertEquals(0, maintenance.reclaim(NOW));

        verify(storage, never()).delete(org.mockito.ArgumentMatchers.anyString());
    }
}
