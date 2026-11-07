package com.lanlink.shopping.module.attachment.service;

import com.lanlink.shopping.common.storage.StorageProperties;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.module.attachment.entity.Attachment;
import com.lanlink.shopping.module.attachment.mapper.AttachmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传落 PENDING：先落对象与附件行，业务绑定另走 bind。
 *
 * <p>响应体必须只有中转地址，不能带出对象键——桶是匿名只读，键一旦进前端日志
 * 或浏览器历史就等于把材料开放下载。
 */
class AttachmentServiceUploadTest {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private AttachmentMapper mapper;
    private AttachmentService service;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setEnabled(false);
        props.getLocal().setRoot(root.toString());
        mapper = mock(AttachmentMapper.class);
        service = new AttachmentService(new StorageService(props, null), mapper, java.util.List.of());
    }

    @Test
    void uploadStoresPendingRowWithServerGeneratedKey() {
        AttachmentView view = service.upload(AttachmentBizType.MERCHANT_LICENSE,
                "营业执照.pdf", PDF_BYTES, 1001L);

        ArgumentCaptor<Attachment> saved = ArgumentCaptor.forClass(Attachment.class);
        verify(mapper).insert(saved.capture());
        Attachment row = saved.getValue();
        assertEquals("PENDING", row.getStatus(), "未绑定的材料必须先挂起，否则会被回收任务当成正式件");
        assertNull(row.getBizId());
        assertEquals(1001L, row.getOwnerUserId());
        assertEquals("merchant-license", row.getBizType());
        assertTrue(row.getObjectKey().startsWith("attachments/merchant-license/"),
                "对象键必须由服务端生成：" + row.getObjectKey());
        assertEquals(PDF_BYTES.length, row.getFileSize());
        assertEquals("application/pdf", row.getContentType());
        assertEquals(view.no(), row.getAttachNo());
    }

    @Test
    void uploadResponseExposesOnlyRelayUrl() {
        AttachmentView view = service.upload(AttachmentBizType.MERCHANT_LICENSE,
                "营业执照.pdf", PDF_BYTES, 1001L);

        assertEquals("/api/attachment/" + view.no() + "/content", view.url());
        assertFalse(view.toString().contains("attachments/"),
                "响应不得带出对象键结构：" + view);
        assertFalse(view.toString().contains("9000"),
                "响应不得带出匿名桶地址：" + view);
    }

    @Test
    void uploadWritesBytesUnderStorageRoot() throws Exception {
        service.upload(AttachmentBizType.MERCHANT_LICENSE, "营业执照.pdf", PDF_BYTES, 1001L);

        ArgumentCaptor<Attachment> saved = ArgumentCaptor.forClass(Attachment.class);
        verify(mapper).insert(saved.capture());
        Path written = root.resolve(saved.getValue().getObjectKey());
        assertTrue(Files.isRegularFile(written), "对象应先落存储再挂 PENDING 行：" + written);
        assertEquals(PDF_BYTES.length, Files.size(written));
    }

    @Test
    void uploadPersistsDisplayOnlyFileNameWithoutPathSegments() {
        AttachmentView view = service.upload(AttachmentBizType.MERCHANT_LICENSE,
                "..\\..\\evil\\营业执照.pdf", PDF_BYTES, 1001L);

        ArgumentCaptor<Attachment> saved = ArgumentCaptor.forClass(Attachment.class);
        verify(mapper).insert(saved.capture());
        assertFalse(saved.getValue().getFileName().contains(".."),
                "原始文件名只能作展示，路径段必须被截断：" + saved.getValue().getFileName());
        assertFalse(saved.getValue().getFileName().contains("\\"),
                "存储的文件名不得保留反斜杠：" + saved.getValue().getFileName());
        assertEquals("营业执照.pdf", saved.getValue().getFileName());
    }

    @Test
    void uploadRecordsContentHashOfExactlyWhatWasStored() throws Exception {
        AttachmentView view = service.upload(AttachmentBizType.MERCHANT_LICENSE,
                "营业执照.pdf", PDF_BYTES, 1001L);

        ArgumentCaptor<Attachment> saved = ArgumentCaptor.forClass(Attachment.class);
        verify(mapper).insert(saved.capture());
        String expected = sha256Hex(PDF_BYTES);
        assertEquals(expected, saved.getValue().getSha256(),
                "哈希必须对应真实入库字节，供权属材料做完整性与去重依据：" + view);
    }

    @Test
    void uploadRegistersRowBeforeWritingObject() {
        StorageProperties props = new StorageProperties();
        props.setEnabled(false);
        props.getLocal().setRoot(root.toString());
        StorageService failing = mock(StorageService.class);
        when(failing.put(any(), any(), any())).thenThrow(new IllegalStateException("存储不可用"));
        AttachmentService service = new AttachmentService(failing, mapper, java.util.List.of());

        assertThrows(IllegalStateException.class, () -> service.upload(
                AttachmentBizType.TECH_DEAL_DOCUMENT, "合同.pdf", PDF_BYTES, 1001L));

        // 必须先登记行、后写对象：这样任何中途失败留下的都是"有行无对象"（可被回收与重试发现），
        // 反过来会留下"有对象无行"——库里没有记录，回收任务永远扫不到，等于永久泄漏一份材料字节。
        InOrder inOrder = inOrder(failing, mapper);
        inOrder.verify(mapper).insert(any(Attachment.class));
        inOrder.verify(failing).put(any(), any(), any());
    }

    @Test
    void uploadLeavesTimestampsToDatabaseDefaults() {
        service.upload(AttachmentBizType.MERCHANT_LICENSE, "营业执照.pdf", PDF_BYTES, 1001L);

        ArgumentCaptor<Attachment> saved = ArgumentCaptor.forClass(Attachment.class);
        verify(mapper).insert(saved.capture());
        assertNull(saved.getValue().getCreateTime(),
                "时间只允许一个真值源（表 DEFAULT CURRENT_TIMESTAMP），代码再赋一次就是两处真相");
        assertNull(saved.getValue().getUpdateTime());
    }

    @Test
    void uploadMarksRowExpiredWhenObjectWriteFails() {
        StorageService failing = mock(StorageService.class);
        when(failing.put(any(), any(), any())).thenThrow(new IllegalStateException("存储不可用"));
        AttachmentService service = new AttachmentService(failing, mapper, java.util.List.of());

        assertThrows(IllegalStateException.class, () -> service.upload(
                AttachmentBizType.TECH_DEAL_DOCUMENT, "合同.pdf", PDF_BYTES, 1001L));

        // 行先落库（自动提交）再写对象：写失败必须把行标成 EXPIRED 留痕。
        // 若把整个方法包在一个事务里，失败会连行一起回滚，变成"库里查不到、桶里可能已有对象"的哑状态。
        ArgumentCaptor<Attachment> marked = ArgumentCaptor.forClass(Attachment.class);
        verify(mapper).update(marked.capture(), any());
        assertEquals("EXPIRED", marked.getValue().getStatus());
        verify(mapper).insert(any(Attachment.class));
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
