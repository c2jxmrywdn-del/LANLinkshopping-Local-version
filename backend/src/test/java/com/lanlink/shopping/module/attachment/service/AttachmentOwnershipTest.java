package com.lanlink.shopping.module.attachment.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.module.attachment.entity.Attachment;
import com.lanlink.shopping.module.attachment.mapper.AttachmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 绑定目标归属校验。
 *
 * <p>没有这条校验，任何登录用户都能把自己上传的材料挂到别人的 merId/expertId 上，
 * 把伪造件塞进别人的审核台并挤掉真实材料。
 * <p>关键取舍是"失败关闭"：某些业务域（如机构、交易）在库里根本没有归属人列，
 * 无法验证就只能拒绝自助绑定，而不是默认放过——运营（admin）仍可直接绑定。
 */
class AttachmentOwnershipTest {

    private static final String NO = "AT20261104134320A9";

    private AttachmentMapper mapper;
    private RecordingOwnership ownership;

    @BeforeEach
    void setUp() {
        mapper = mock(AttachmentMapper.class);
        ownership = new RecordingOwnership();
        when(mapper.update(any(Attachment.class), any())).thenReturn(1);
    }

    /** 行里的业务域必须和被绑的业务域一致，否则先撞上"域不匹配"，测不到归属校验。 */
    private void givenRowOf(AttachmentBizType type) {
        Attachment row = new Attachment();
        row.setAttachNo(NO);
        row.setBizType(type.wireName());
        row.setOwnerUserId(1001L);
        row.setStatus("PENDING");
        when(mapper.selectOne(any())).thenReturn(row);
    }

    private AttachmentService serviceWith(AttachmentOwnership... resolvers) {
        return new AttachmentService(mock(StorageService.class), mapper, List.of(resolvers));
    }

    /** 可编排的归属解析桩：supports/owns 都由用例显式声明。 */
    private static class RecordingOwnership implements AttachmentOwnership {
        boolean supports = true;
        boolean owns = true;

        public boolean supports(AttachmentBizType type) {
            return supports;
        }

        public boolean owns(AttachmentBizType type, Long bizId, Long userId) {
            return owns;
        }
    }

    @Test
    void bindIsAllowedWhenOwnershipConfirmed() {
        givenRowOf(AttachmentBizType.MERCHANT_LICENSE);
        serviceWith(ownership).bind(NO, AttachmentBizType.MERCHANT_LICENSE, 55L, 1001L, false);

        verify(mapper).update(any(Attachment.class), any());
    }

    @Test
    void bindIsForbiddenWhenTargetBelongsToSomeoneElse() {
        givenRowOf(AttachmentBizType.MERCHANT_LICENSE);
        ownership.owns = false;

        BusinessException e = assertThrows(BusinessException.class, () -> serviceWith(ownership)
                .bind(NO, AttachmentBizType.MERCHANT_LICENSE, 55L, 1001L, false));

        assertEquals(403, e.getCode().intValue());
        verify(mapper, never()).update(any(Attachment.class), any());
    }

    @Test
    void bindFailsClosedWhenNoResolverHandlesTheBizType() {
        // 机构/交易这类业务域没有归属人列，无法验证就必须拒绝自助绑定
        givenRowOf(AttachmentBizType.TECH_DEAL_DOCUMENT);
        ownership.supports = false;

        BusinessException e = assertThrows(BusinessException.class, () -> serviceWith(ownership)
                .bind(NO, AttachmentBizType.TECH_DEAL_DOCUMENT, 77L, 1001L, false));

        assertEquals(403, e.getCode().intValue());
        verify(mapper, never()).update(any(Attachment.class), any());
    }

    @Test
    void bindFailsClosedWhenNoResolverIsRegisteredAtAll() {
        givenRowOf(AttachmentBizType.TECH_ORGANIZATION);

        BusinessException e = assertThrows(BusinessException.class, () -> serviceWith()
                .bind(NO, AttachmentBizType.TECH_ORGANIZATION, 9L, 1001L, false));

        assertEquals(403, e.getCode().intValue());
    }

    @Test
    void adminSkipsTargetOwnershipForReviewWorkflows() {
        givenRowOf(AttachmentBizType.TECH_DEAL_DOCUMENT);
        ownership.supports = false;

        serviceWith(ownership).bind(NO, AttachmentBizType.TECH_DEAL_DOCUMENT, 77L, 9001L, true);

        verify(mapper).update(any(Attachment.class), any());
    }
}
