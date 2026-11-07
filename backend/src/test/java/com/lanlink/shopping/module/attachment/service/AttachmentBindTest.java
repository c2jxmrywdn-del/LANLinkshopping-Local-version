package com.lanlink.shopping.module.attachment.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.module.attachment.entity.Attachment;
import com.lanlink.shopping.module.attachment.mapper.AttachmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 材料绑定业务对象。
 *
 * <p>单号不是秘密（秒级时间戳 + 6 位随机段），枚举可行性取决于随机段空间；这里要求越权回 403
 * 的真正理由是：业务域与状态细节必须在归属校验通过之后才可见，否则非 owner 能用 400/403 差异
 * 读出他人材料属于哪个业务域。
 */
class AttachmentBindTest {

    private static final String NO = "AT20261104134320A9";

    private AttachmentMapper mapper;
    private AttachmentService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AttachmentMapper.class);
        service = new AttachmentService(mock(StorageService.class), mapper, java.util.List.of(new AttachmentOwnership() {
            public boolean supports(AttachmentBizType t) { return true; }
            public boolean owns(AttachmentBizType t, Long b, Long u) { return true; }
        }));
        // 条件更新默认命中一行；需要演练"抢输"的用例在自己内部改返回 0
        when(mapper.update(any(Attachment.class), any())).thenReturn(1);
    }

    private Attachment pendingRow() {
        Attachment row = new Attachment();
        row.setAttachmentId(7L);
        row.setAttachNo(NO);
        row.setBizType("merchant-license");
        row.setOwnerUserId(1001L);
        row.setStatus("PENDING");
        row.setVisibility("PRIVATE");
        return row;
    }

    private void given(Attachment row) {
        when(mapper.selectOne(any())).thenReturn(row);
    }

    @Test
    void bindMovesPendingRowToBoundWithBizId() {
        given(pendingRow());

        service.bind(NO, AttachmentBizType.MERCHANT_LICENSE, 55L, 1001L, false);

        ArgumentCaptor<Attachment> saved = ArgumentCaptor.forClass(Attachment.class);
        verify(mapper).update(saved.capture(), any());
        assertEquals("BOUND", saved.getValue().getStatus());
        assertEquals(55L, saved.getValue().getBizId());
        assertNull(saved.getValue().getUpdateTime(), "SET 载荷里不得出现 update_time");
    }

    @Test
    void bindIsIdempotentWhenAlreadyBoundToSameBiz() {
        Attachment row = pendingRow();
        row.setStatus("BOUND");
        row.setBizId(55L);
        given(row);

        assertDoesNotThrow(() -> service.bind(NO, AttachmentBizType.MERCHANT_LICENSE, 55L, 1001L, false));
        verify(mapper, never()).update(any(Attachment.class), any());
    }

    @Test
    void bindByStrangerWithMismatchedBizTypeIsStillForbidden() {
        given(pendingRow());

        // 域校验不能排在归属校验之前：否则非 owner 可以拿 7 个线名逐个试，
        // 用 400 与 403 的差异读出他人材料的业务域——这是比枚举单号更强的 oracle。
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.bind(NO, AttachmentBizType.TECH_EXPERT_PROFILE, 55L, 2002L, false));

        assertEquals(403, e.getCode().intValue());
    }

    @Test
    void bindUsesConditionalUpdateAndNeverWritesRowBackWholesale() {
        given(pendingRow());

        service.bind(NO, AttachmentBizType.MERCHANT_LICENSE, 55L, 1001L, false);

        // updateById 会把读出来的旧 update_time 一起写回 SET，压掉表上的 ON UPDATE CURRENT_TIMESTAMP，
        // 审计时间戳就永远停在上传时刻；并发下也没有 status='PENDING' 条件，两个请求会互相覆盖。
        verify(mapper).update(any(Attachment.class), any());
        verify(mapper, never()).updateById(any(Attachment.class));
    }

    @Test
    void bindTreatsLostRaceAsIdempotentSuccess() {
        Attachment bound = pendingRow();
        bound.setStatus("BOUND");
        bound.setBizId(55L);
        when(mapper.selectOne(any())).thenReturn(pendingRow(), bound);
        when(mapper.update(any(Attachment.class), any())).thenReturn(0);

        assertDoesNotThrow(() -> service.bind(NO, AttachmentBizType.MERCHANT_LICENSE, 55L, 1001L, false));
    }

    @Test
    void bindReportsConflictWhenRaceLostToAnotherBiz() {
        Attachment boundElsewhere = pendingRow();
        boundElsewhere.setStatus("BOUND");
        boundElsewhere.setBizId(77L);
        when(mapper.selectOne(any())).thenReturn(pendingRow(), boundElsewhere);
        when(mapper.update(any(Attachment.class), any())).thenReturn(0);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.bind(NO, AttachmentBizType.MERCHANT_LICENSE, 55L, 1001L, false));

        assertEquals(400, e.getCode().intValue());
    }

    @Test
    void bindByAnotherUserIsForbiddenAndLeavesRowUntouched() {
        given(pendingRow());

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.bind(NO, AttachmentBizType.MERCHANT_LICENSE, 55L, 2002L, false));

        assertEquals(403, e.getCode().intValue());
        verify(mapper, never()).updateById(any(Attachment.class));
    }

    @Test
    void adminMayBindOwnersMaterial() {
        given(pendingRow());

        assertDoesNotThrow(() -> service.bind(NO, AttachmentBizType.MERCHANT_LICENSE, 55L, 9001L, true));

        verify(mapper).update(any(Attachment.class), any());
    }

    @Test
    void bindRejectsBizTypeMismatch() {
        given(pendingRow());

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.bind(NO, AttachmentBizType.TECH_EXPERT_PROFILE, 55L, 1001L, false));

        assertEquals(400, e.getCode().intValue());
        verify(mapper, never()).updateById(any(Attachment.class));
    }

    @Test
    void bindRejectsRetakingARowBoundToAnotherBiz() {
        Attachment row = pendingRow();
        row.setStatus("BOUND");
        row.setBizId(55L);
        given(row);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.bind(NO, AttachmentBizType.MERCHANT_LICENSE, 77L, 1001L, false));

        assertEquals(400, e.getCode().intValue(), "改挂必须走显式换绑语义，不能被幂等吞掉");
        verify(mapper, never()).updateById(any(Attachment.class));
    }

    @Test
    void bindOnExpiredRowIsRejected() {
        Attachment row = pendingRow();
        row.setStatus("EXPIRED");
        given(row);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.bind(NO, AttachmentBizType.MERCHANT_LICENSE, 55L, 1001L, false));

        assertEquals(400, e.getCode().intValue());
        verify(mapper, never()).updateById(any(Attachment.class));
    }

    @Test
    void unknownAttachmentNumberIsNotFound() {
        when(mapper.selectOne(any())).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.bind(NO, AttachmentBizType.MERCHANT_LICENSE, 55L, 1001L, false));

        assertEquals(404, e.getCode().intValue());
    }
}
