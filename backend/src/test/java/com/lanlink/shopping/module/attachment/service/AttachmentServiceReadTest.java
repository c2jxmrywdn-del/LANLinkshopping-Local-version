package com.lanlink.shopping.module.attachment.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.storage.StorageProperties;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.module.attachment.entity.Attachment;
import com.lanlink.shopping.module.attachment.mapper.AttachmentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 材料字节读取走真实本地盘（enabled=false 回退分支）。
 *
 * <p>用磁盘模式而非 mock 存储，是为了证明「不经匿名桶域名也能取到材料」这条真实路径；
 * 记录不存在、对象缺失、越界键名三种情况都要落在这里，端点层才有据可依。
 */
class AttachmentServiceReadTest {

    @TempDir
    Path root;

    private final AttachmentMapper mapper = mock(AttachmentMapper.class);

    private AttachmentService diskService() {
        StorageProperties p = new StorageProperties();
        p.setEnabled(false);
        p.getLocal().setRoot(root.toString());
        return new AttachmentService(new StorageService(p, null), mapper, java.util.List.of());
    }

    private void givenRow(String attachNo, String objectKey, long ownerUserId) {
        Attachment row = new Attachment();
        row.setAttachNo(attachNo);
        row.setBizType("merchant-license");
        row.setOwnerUserId(ownerUserId);
        row.setFileName("样本.pdf");
        row.setObjectKey(objectKey);
        row.setContentType("application/pdf");
        row.setStatus("BOUND");
        row.setVisibility("PRIVATE");
        when(mapper.selectOne(any())).thenReturn(row);
    }

    private void writeFile(String key) throws Exception {
        Path target = root.resolve(key);
        Files.createDirectories(target.getParent());
        Files.write(target, new byte[]{7, 8, 9});
    }

    @Test
    void ownerReadsBytesThroughRelayWithoutTouchingBucketUrl() throws Exception {
        String key = "attachments/merchant-license/2026-11/sample.pdf";
        writeFile(key);
        givenRow("AT1", key, 1001L);

        AttachmentService.Content content = diskService().loadContent("AT1", 1001L, false);

        assertArrayEquals(new byte[]{7, 8, 9}, content.bytes());
        assertEquals("样本.pdf", content.fileName());
        assertEquals("application/pdf", content.contentType());
    }

    @Test
    void strangerCannotReadMaterial() throws Exception {
        String key = "attachments/merchant-license/2026-11/sample.pdf";
        writeFile(key);
        givenRow("AT1", key, 1001L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> diskService().loadContent("AT1", 2002L, false));
        assertEquals(403, e.getCode().intValue());
    }

    @Test
    void missingObjectIsNotFoundAndMessageCarriesNoPath() throws Exception {
        givenRow("AT1", "attachments/merchant-license/2026-11/gone.pdf", 1001L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> diskService().loadContent("AT1", 1001L, false));

        assertEquals(404, e.getCode().intValue());
        assertTrue(!e.getMessage().contains("attachments/") && !e.getMessage().contains(root.toString()),
                "错误文案不得带出对象键或本地绝对路径：" + e.getMessage());
    }

    @Test
    void retiredMaterialCannotBeDownloadedEvenIfObjectSurvives() throws Exception {
        // 删除流程是"先置 EXPIRED 再删对象"，对象删除失败时字节还在桶/盘上；
        // 若不认状态，用户点了删除之后材料仍然可以长期下载，删除等于没生效
        String key = "attachments/merchant-license/2026-11/deleted.pdf";
        writeFile(key);
        givenRow("AT1", key, 1001L);
        when(mapper.selectOne(any())).thenAnswer(inv -> {
            Attachment a = new Attachment();
            a.setAttachNo("AT1");
            a.setOwnerUserId(1001L);
            a.setBizType("merchant-license");
            a.setObjectKey(key);
            a.setFileName("已删.pdf");
            a.setContentType("application/pdf");
            a.setStatus("EXPIRED");
            return a;
        });

        BusinessException e = assertThrows(BusinessException.class,
                () -> diskService().loadContent("AT1", 1001L, false));
        assertEquals(404, e.getCode().intValue());
    }

    @Test
    void traversalStyleRowKeyCannotEscapeRoot() {
        // 行里的 objectKey 理论上是服务端生成的，但一旦库里被脏数据写入越界键名，
        // 读取路径也必须拒掉，而不是把服务器目录交出去
        givenRow("AT1", "../../etc/passwd", 1001L);

        assertThrows(BusinessException.class, () -> diskService().loadContent("AT1", 1001L, false));
    }
}
