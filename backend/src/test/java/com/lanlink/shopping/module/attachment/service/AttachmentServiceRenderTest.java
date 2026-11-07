package com.lanlink.shopping.module.attachment.service;

import com.lanlink.shopping.common.storage.StorageProperties;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.module.attachment.mapper.AttachmentMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.services.s3.S3Client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 附件出站渲染：一律走后端中转地址。
 *
 * <p>实测（2026-11-04）桶策略是匿名只读——minio-init 执行 mc anonymous set download，
 * 无凭证 GET http://localhost:9000/products/1001.webp 返回 200/52992 字节。资质、完税、
 * 权属与合同这类材料若渲染成桶 URL，等于把「知道 key 就能下载」当作访问控制，
 * key 一旦进日志或浏览器历史即泄露。中转地址同时让 enabled=true/false 两态输出一致，
 * 回退开关不再改变前端拿到的形状。
 */
class AttachmentServiceRenderTest {

    private static final String NO = "AT20261104A1B2C3";

    private StorageProperties props(boolean enabled) {
        StorageProperties p = new StorageProperties();
        p.setEnabled(enabled);
        p.setPublicBaseUrl("http://localhost:9000/products");
        return p;
    }

    private AttachmentMapper mapper() {
        return Mockito.mock(AttachmentMapper.class);
    }

    @Test
    void materialRendersBackendRelayUrl() {
        StorageService storage = new StorageService(props(true), Mockito.mock(S3Client.class));
        AttachmentService service = new AttachmentService(storage, mapper(), java.util.List.of());

        assertEquals("/api/attachment/" + NO + "/content", service.contentUrl(NO));
    }

    @Test
    void relayUrlDoesNotLeakObjectKeyOrBucketUrl() {
        StorageService storage = new StorageService(props(true), Mockito.mock(S3Client.class));
        String url = new AttachmentService(storage, mapper(), java.util.List.of()).contentUrl(NO);

        assertFalse(url.contains("attachments/"), "出站地址不得带出对象键结构：" + url);
        assertFalse(url.contains("9000"), "出站地址不得指向可匿名下载的桶域名：" + url);
    }

    @Test
    void relayUrlIsIdenticalWhetherObjectStorageEnabledOrDisabled() {
        String onS3 = new AttachmentService(
                new StorageService(props(true), Mockito.mock(S3Client.class)), mapper(),
                java.util.List.of()).contentUrl(NO);
        String onDisk = new AttachmentService(
                new StorageService(props(false), null), mapper(),
                java.util.List.of()).contentUrl(NO);

        assertEquals(onS3, onDisk, "存储回退开关不应改变前端拿到的地址形状");
    }
}
