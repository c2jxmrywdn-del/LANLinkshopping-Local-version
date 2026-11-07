package com.lanlink.shopping.module.attachment.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.module.attachment.entity.Attachment;
import com.lanlink.shopping.module.attachment.mapper.AttachmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 材料删除。
 *
 * <p>顺序是"先把行标失效、再删对象"：反过来一旦对象删了而行还在，前端会拿到一条
 * 永远下载失败的记录；而行先失效最坏只留下一个没人引用的对象，可被运维兜底。
 * 已绑定（BOUND）的材料属于交付内容，普通用户不能自行抹除，只有运营能下线。
 */
class AttachmentDeleteTest {

    private static final String NO = "AT20261104134320A9";
    private static final String KEY = "attachments/merchant-license/2026-11/abc.pdf";

    private AttachmentMapper mapper;
    private StorageService storage;
    private AttachmentService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AttachmentMapper.class);
        storage = mock(StorageService.class);
        service = new AttachmentService(storage, mapper, java.util.List.of());
        // 条件更新默认命中；演练"被并发抢先"的用例自己改返回 0
        when(mapper.update(any(Attachment.class), any())).thenReturn(1);
    }

    private Attachment row(String status, long owner) {
        Attachment a = new Attachment();
        a.setAttachmentId(3L);
        a.setAttachNo(NO);
        a.setBizType("merchant-license");
        a.setOwnerUserId(owner);
        a.setObjectKey(KEY);
        a.setStatus(status);
        a.setVisibility("PRIVATE");
        when(mapper.selectOne(any())).thenReturn(a);
        return a;
    }

    @Test
    void ownerDeleteMarksRowExpiredBeforeRemovingObject() {
        row("PENDING", 1001L);

        service.delete(NO, 1001L, false);

        InOrder order = inOrder(mapper, storage);
        order.verify(mapper).update(any(Attachment.class), any());
        order.verify(storage).delete(KEY);
    }

    @Test
    void markedRowCarriesExpiredStatus() {
        row("PENDING", 1001L);

        service.delete(NO, 1001L, false);

        var captor = org.mockito.ArgumentCaptor.forClass(Attachment.class);
        verify(mapper).update(captor.capture(), any());
        assertEquals("EXPIRED", captor.getValue().getStatus());
    }

    @Test
    void strangerDeleteIsForbiddenAndTouchesNothing() {
        row("PENDING", 1001L);

        BusinessException e = assertThrows(BusinessException.class, () -> service.delete(NO, 2002L, false));

        assertEquals(403, e.getCode().intValue());
        verify(mapper, never()).update(any(Attachment.class), any());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void ownerCannotEraseAlreadyDeliveredMaterial() {
        row("BOUND", 1001L);

        BusinessException e = assertThrows(BusinessException.class, () -> service.delete(NO, 1001L, false));

        assertEquals(400, e.getCode().intValue());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void adminMayTakeDeliveredMaterialDown() {
        row("BOUND", 1001L);

        assertDoesNotThrow(() -> service.delete(NO, 9001L, true));

        verify(storage).delete(KEY);
    }

    @Test
    void deleteIsIdempotentOnAlreadyExpiredRow() {
        row("EXPIRED", 1001L);

        assertDoesNotThrow(() -> service.delete(NO, 1001L, false));

        verify(mapper, never()).update(any(Attachment.class), any());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void objectRemovalFailureStillLeavesRowMarkedAndDoesNotThrowAtUser() {
        row("PENDING", 1001L);
        doThrow(new RuntimeException("对象存储不可用")).when(storage).delete(KEY);

        // 行已失效，用户侧的删除在业务上已经成立；对象残留交给回收与运维兜底，
        // 把存储故障直接冒成用户"删除失败"会让人反复重点导致更多残留。
        assertDoesNotThrow(() -> service.delete(NO, 1001L, false));

        verify(mapper).update(any(Attachment.class), any());
    }

    @Test
    void deleteAbortsObjectRemovalWhenRowWasBoundInBetween() {
        row("PENDING", 1001L);
        when(mapper.update(any(Attachment.class), any())).thenReturn(0);

        assertDoesNotThrow(() -> service.delete(NO, 1001L, false));

        // 读行与置失效之间被并发绑定（或被运营抢先处理）时不得删对象，
        // 否则一次点击就能抹掉别人刚交付成功的材料
        verify(storage, never()).delete(anyString());
    }

    @Test
    void anonymousDeleteIsUnauthorized() {
        assertThrows(BusinessException.class, () -> service.delete(NO, null, false));
    }
}
