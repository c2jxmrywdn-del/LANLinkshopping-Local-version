package com.lanlink.shopping.module.attachment;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.module.attachment.entity.Attachment;
import com.lanlink.shopping.module.attachment.mapper.AttachmentMapper;
import com.lanlink.shopping.module.attachment.service.AttachmentBizType;
import com.lanlink.shopping.module.attachment.service.AttachmentService;
import com.lanlink.shopping.module.attachment.service.AttachmentThumbStatus;
import com.lanlink.shopping.module.attachment.service.AttachmentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传侧的派生任务派发：什么时候进队列、什么时候直接判跳过、队列挂了上传还成不成。
 *
 * <p>这三条就是"异步化"的全部承诺：预览不再占用请求线程，但原件的落库与落桶仍然同步且优先。
 */
class AttachmentUploadDispatchTest {

    private AttachmentMapper mapper;
    private StorageService storage;
    private AttachmentImagePublisher publisher;
    private AttachmentService service;

    @BeforeEach
    void setUp() throws Exception {
        mapper = mock(AttachmentMapper.class);
        storage = mock(StorageService.class);
        publisher = mock(AttachmentImagePublisher.class);
        service = new AttachmentService(storage, mapper, List.of());
        inject(service, "imagePublisher", publisher);
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = AttachmentService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static byte[] pngBytes() {
        try {
            BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_3BYTE_BGR);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Attachment savedRow() {
        ArgumentCaptor<Attachment> captor = ArgumentCaptor.forClass(Attachment.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void imageUploadIsQueuedAndReportsPendingThumb() {
        AttachmentView view = service.upload(AttachmentBizType.MERCHANT_LICENSE, "license.png",
                pngBytes(), 7L);

        assertEquals(AttachmentThumbStatus.PENDING, view.thumbStatus());
        assertEquals(AttachmentThumbStatus.PENDING, savedRow().getThumbStatus());
        verify(publisher).publishQueued(any(AttachmentUploaded.class));
    }

    @Test
    void pdfIsMarkedSkippedWithoutTouchingTheQueue() {
        AttachmentView view = service.upload(AttachmentBizType.MERCHANT_TAX_PROOF, "tax.pdf",
                "%PDF-1.4\n%x".getBytes(), 7L);

        assertEquals(AttachmentThumbStatus.SKIPPED, view.thumbStatus());
        assertEquals(AttachmentThumbStatus.SKIPPED, savedRow().getThumbStatus());
        verify(publisher, never()).publishQueued(any());
    }

    @Test
    void objectWriteFailureStillExpiresTheRowAndNeverQueues() {
        doThrow(new IllegalStateException("minio down")).when(storage).put(anyString(), any(), anyString());

        assertThrows(RuntimeException.class, () -> service.upload(
                AttachmentBizType.MERCHANT_LICENSE, "license.png", pngBytes(), 7L));

        ArgumentCaptor<Attachment> dead = ArgumentCaptor.forClass(Attachment.class);
        verify(mapper).update(dead.capture(), any());
        assertEquals("EXPIRED", dead.getValue().getStatus());
        verify(publisher, never()).publishQueued(any());
    }

    @Test
    void previewEndpointRefusesUntilThumbnailIsReady() {
        Attachment row = new Attachment();
        row.setAttachNo("AT1");
        row.setStatus("PENDING");
        row.setOwnerUserId(7L);
        row.setObjectKey("attachments/merchant-license/2026-11/x.png");
        row.setThumbStatus(AttachmentThumbStatus.PENDING);
        when(mapper.selectOne(any())).thenReturn(row);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.loadContent("AT1", 7L, false, true));

        assertEquals(404, ex.getCode().intValue(), "预览没就绪就是 404，不能偷偷回落到原件");
        verify(storage, never()).getObject(anyString());
    }

    @Test
    void previewEndpointServesTheDerivedObjectWhenReady() {
        Attachment row = new Attachment();
        row.setAttachNo("AT2");
        row.setStatus("BOUND");
        row.setOwnerUserId(7L);
        row.setFileName("license.png");
        row.setObjectKey("attachments/merchant-license/2026-11/x.png");
        row.setThumbStatus(AttachmentThumbStatus.READY);
        row.setThumbKey("attachments/merchant-license/2026-11/thumb/x.jpg");
        when(mapper.selectOne(any())).thenReturn(row);
        when(storage.getObject("attachments/merchant-license/2026-11/thumb/x.jpg"))
                .thenReturn(new byte[]{1, 2, 3});

        AttachmentService.Content content = service.loadContent("AT2", 7L, false, true);

        assertEquals("image/jpeg", content.contentType());
        assertEquals("license.jpg", content.fileName(), "扩展名要跟着换了，否则前端按 .png 解码会花屏");
        verify(storage, never()).getObject("attachments/merchant-license/2026-11/x.png");
    }

    /**
     * 列表里的预览地址必须是单段 /content 加查询串。
     *
     * <p>曾经的实现把后缀写成 "/content?variant=thumb"，而 contentUrl 已经带了 /content，
     * 拼出 /content/content?variant=thumb。单测没覆盖这个串，真机浏览器里 <img> 一直是
     * naturalWidth=0——缩略图链路全绿但用户永远看不到预览，所以这条断言必须钉住完整 URL。
     */
    @Test
    void listViewExposesPreviewUrlWithASingleContentSegment() {
        Attachment row = new Attachment();
        row.setAttachNo("AT3");
        row.setFileName("license.png");
        row.setFileSize(2048L);
        row.setContentType("image/png");
        row.setStatus("BOUND");
        row.setThumbStatus(AttachmentThumbStatus.READY);
        row.setThumbKey("attachments/merchant-license/2026-11/thumb/at3.jpg");
        when(mapper.selectList(any(IPage.class), any(Wrapper.class))).thenReturn(List.of(row));

        List<AttachmentView> views = service.list(7L, AttachmentBizType.MERCHANT_LICENSE, 1L, false);

        assertEquals(1, views.size());
        assertEquals("/api/attachment/AT3/content", views.get(0).url());
        assertEquals("/api/attachment/AT3/content?variant=thumb", views.get(0).thumbUrl());
        assertEquals(1, views.get(0).thumbUrl().split("/content", -1).length - 1);
        assertEquals(AttachmentThumbStatus.READY, views.get(0).thumbStatus());
    }

    /** 预览没就绪时不给地址，前端只显示状态、不发必然 404 的请求。 */
    @Test
    void listViewOmitsPreviewUrlUntilThumbnailIsReady() {
        Attachment row = new Attachment();
        row.setAttachNo("AT4");
        row.setStatus("BOUND");
        row.setThumbStatus(AttachmentThumbStatus.PENDING);
        when(mapper.selectList(any(IPage.class), any(Wrapper.class))).thenReturn(List.of(row));

        List<AttachmentView> views = service.list(7L, AttachmentBizType.MERCHANT_LICENSE, 1L, false);

        assertEquals(AttachmentThumbStatus.PENDING, views.get(0).thumbStatus());
        assertNull(views.get(0).thumbUrl());
    }
}
