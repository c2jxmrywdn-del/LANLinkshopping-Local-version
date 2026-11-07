package com.lanlink.shopping.common.storage;

import com.lanlink.shopping.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对象存储门面（out/storage-mq-plan.md 任务 A2/A3/A6）：
 * S3 分支（put/get/exists/delete + 公开 URL）与 enabled=false 本地盘回退分支
 * （与历史 /api/uploads/** 行为逐字节等价、键名防越界）。
 */
class StorageServiceTest {

    private static final String BASE = "http://localhost:9000/products";

    @TempDir
    Path root;

    @BeforeEach
    void resetUrlHolder() {
        StorageProperties props = new StorageProperties();
        props.setEnabled(false);
        StorageUrls.init(props);
    }

    private static StorageProperties enabledProps() {
        StorageProperties props = new StorageProperties();
        props.setEnabled(true);
        props.setEndpoint("http://localhost:9000");
        props.setBucket("products");
        props.setPublicBaseUrl(BASE);
        return props;
    }

    // ==================== S3 分支（enabled=true） ====================

    @Test
    void putUploadsToS3AndReturnsPublicUrl() {
        S3Client s3 = mock(S3Client.class);
        StorageService storage = new StorageService(enabledProps(), s3);
        byte[] content = {1, 2, 3};

        String url = storage.put("products/prod_1730000000.webp", content, "image/webp");

        assertEquals(BASE + "/products/prod_1730000000.webp", url);
        var captor = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));
        assertEquals("products", captor.getValue().bucket());
        assertEquals("products/prod_1730000000.webp", captor.getValue().key());
        assertEquals("image/webp", captor.getValue().contentType());
    }

    @Test
    void getObjectReadsS3BytesForVisionSource() {
        S3Client s3 = mock(S3Client.class);
        byte[] content = {9, 8, 7};
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
                ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), content));
        StorageService storage = new StorageService(enabledProps(), s3);

        assertArrayEquals(content, storage.getObject("1001.webp"));
    }

    @Test
    void existsDistinguishesPresentAndMissingKeys() {
        S3Client s3 = mock(S3Client.class);
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build())
                .thenThrow(NoSuchKeyException.builder().message("no such key").build());
        StorageService storage = new StorageService(enabledProps(), s3);

        assertTrue(storage.exists("1001.webp"));
        assertFalse(storage.exists("missing.webp"));
    }

    @Test
    void deleteDelegatesToS3() {
        S3Client s3 = mock(S3Client.class);
        StorageService storage = new StorageService(enabledProps(), s3);

        storage.delete("products/prod_old.webp");

        verify(s3).deleteObject(DeleteObjectRequest.builder()
                .bucket("products").key("products/prod_old.webp").build());
    }

    @Test
    void enabledWithoutClientFailsFast() {
        StorageService storage = new StorageService(enabledProps(), null);
        assertThrows(IllegalStateException.class,
                () -> storage.put("products/a.webp", new byte[]{1}, "image/webp"));
    }

    // ==================== 本地盘回退分支（enabled=false，G12 回退口径） ====================

    private StorageService localStorage() {
        StorageProperties props = new StorageProperties();
        props.setEnabled(false);
        props.getLocal().setRoot(root.toString());
        props.getLocal().setUrlPrefix("/api/uploads");
        return new StorageService(props, null);
    }

    @Test
    void putFallsBackToLocalDiskWithLegacyUrlShape() throws Exception {
        StorageService storage = localStorage();
        byte[] content = {1, 2, 3};

        String url = storage.put("products/prod_1730000000.webp", content, "image/webp");

        // 与改造前 ProductController：{user.dir}/uploads/products + /api/uploads/products/** 逐字节等价
        assertEquals("/api/uploads/products/prod_1730000000.webp", url);
        assertTrue(Files.isRegularFile(root.resolve("products").resolve("prod_1730000000.webp")),
                "本地盘回退应落在配置根目录下的同键名路径");
    }

    @Test
    void localBranchSupportsGetExistsDelete() {
        StorageService storage = localStorage();
        storage.put("1001.webp", new byte[]{4, 5}, "image/webp");

        assertArrayEquals(new byte[]{4, 5}, storage.getObject("1001.webp"));
        assertTrue(storage.exists("1001.webp"));
        assertFalse(storage.exists("missing.webp"));
        storage.delete("1001.webp");
        assertFalse(storage.exists("1001.webp"));
    }

    @Test
    void localPutRejectsKeysThatEscapeRoot() {
        StorageService storage = localStorage();
        String tag = UUID.randomUUID().toString();
        for (String prefix : new String[]{"../", "a/../../", "/etc/", "..\\..\\", "products/../../"}) {
            String key = prefix + "evil-" + tag + ".txt";
            BusinessException e = assertThrows(BusinessException.class,
                    () -> storage.put(key, new byte[]{1}, "image/png"),
                    "越界键名必须被拒绝: " + key);
            assertEquals(400, e.getCode().intValue(), "越界键名属于客户端非法输入，应回 400: " + key);
        }
        assertFalse(Files.exists(root.resolveSibling("evil-" + tag + ".txt")),
                "拒绝的同时不得真的写到根目录之外");
    }
}
