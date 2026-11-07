package com.lanlink.shopping.module.attachment.service;

import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.module.attachment.entity.Attachment;
import com.lanlink.shopping.module.attachment.mapper.AttachmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 材料清单的可见性口径。
 *
 * <p>本人：能看到自己的全部状态（未提交的挂起件是自己上传的，必须可见可删）。
 * 运营：只能看到已绑定（BOUND）的材料——挂起件还没交付，不该出现在审核台。
 * 这条差别是有意设计的，不是查询偷懒。
 */
class AttachmentListTest {

    private AttachmentMapper mapper;
    private AttachmentService service;

    /** MP 的 BaseMapper 有两个双参 selectList 重载，any() 裸匹配会歧义，这里给匹配器定类型。 */
    private static com.baomidou.mybatisplus.core.metadata.IPage<Attachment> anyPage() {
        return org.mockito.ArgumentMatchers.any();
    }

    private static com.baomidou.mybatisplus.core.conditions.Wrapper<Attachment> anyWrapper() {
        return org.mockito.ArgumentMatchers.any();
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AttachmentMapper.class);
        service = new AttachmentService(mock(StorageService.class), mapper, java.util.List.of());
    }

    private Attachment row(long owner, String status, String bizType, Long bizId) {
        Attachment a = new Attachment();
        a.setAttachmentId(1L);
        a.setAttachNo("AT20261104134320A9");
        a.setBizType(bizType);
        a.setBizId(bizId);
        a.setOwnerUserId(owner);
        a.setFileName("营业执照.pdf");
        a.setObjectKey("attachments/merchant-license/2026-11/abc.pdf");
        a.setContentType("application/pdf");
        a.setFileSize(44L);
        a.setStatus(status);
        a.setVisibility("PRIVATE");
        return a;
    }

    @Test
    void ownerSeesOwnRowsIncludingPendingOnes() {
        when(mapper.selectList(anyPage(), anyWrapper())).thenReturn(List.of(row(1001L, "PENDING", "merchant-license", null)));

        List<AttachmentView> views = service.list(1001L, AttachmentBizType.MERCHANT_LICENSE, null, false);

        assertEquals(1, views.size());
        assertTrue(views.get(0).url().startsWith("/api/attachment/"));
    }

    @Test
    void adminScopeIsByBizObjectAndOnlyBoundMaterials() {
        AttachmentService.Scope scope =
                AttachmentService.scopeOf(9001L, AttachmentBizType.MERCHANT_LICENSE, 55L, true);

        assertEquals("merchant-license", scope.bizType());
        assertEquals(55L, scope.bizId());
        assertEquals("BOUND", scope.requiredStatus(), "运营只看已交付的绑定件");
        assertNull(scope.ownerUserId(), "按业务对象查时不叠加单个归属人限制");
    }

    @Test
    void nonAdminScopeIgnoresBizIdAndStaysOnOwner() {
        AttachmentService.Scope scope =
                AttachmentService.scopeOf(1001L, AttachmentBizType.MERCHANT_LICENSE, 55L, false);

        assertEquals(1001L, scope.ownerUserId(), "普通用户视图必须锁在自己的归属人上");
        assertNull(scope.bizId(), "普通用户传入的 bizId 不得参与查询，否则可按别人的业务对象越界取数");
        assertNull(scope.requiredStatus(), "本人要能看到自己的挂起件，不能限定已绑定");
    }

    @Test
    void adminWithoutBizIdFallsBackToOwnerScope() {
        AttachmentService.Scope scope =
                AttachmentService.scopeOf(9001L, AttachmentBizType.MERCHANT_LICENSE, null, true);

        assertEquals(9001L, scope.ownerUserId(), "运营不给业务对象时退化为看自己提交的材料，不开全表口子");
        assertNull(scope.bizId());
    }

    @Test
    void listIsHardCappedAtFiftyRows() {
        service.list(1001L, AttachmentBizType.MERCHANT_LICENSE, null, false);

        var pageCaptor = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.metadata.IPage.class);
        verify(mapper).selectList(pageCaptor.capture(), anyWrapper());
        assertEquals(50L, pageCaptor.getValue().getSize(),
                "必须有硬上限，不能因为调用方要得多就全表拉回");
    }

    @Test
    void listResultCarriesNoObjectKeyOrBucketAddress() {
        when(mapper.selectList(anyPage(), anyWrapper())).thenReturn(List.of(row(1001L, "BOUND", "merchant-license", 55L)));

        List<AttachmentView> views = service.list(1001L, AttachmentBizType.MERCHANT_LICENSE, null, false);

        assertFalse(views.toString().contains("attachments/"),
                "清单结果不得带出对象键：" + views);
        assertFalse(views.toString().contains("9000"),
                "清单结果不得带出匿名桶地址：" + views);
    }
}
