package com.lanlink.shopping.common.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * key ↔ URL 归一边界（out/storage-mq-plan.md 任务 A2/A3/A4）。
 * 桶布局以 docker-compose minio-init 实际镜像为准：frontend/public/products 保留相对路径
 * 镜像到桶根（1001.webp / ind/A1.webp），上传对象带 products/ 前缀；public-base-url 含桶路径。
 */
class StorageUrlsTest {

    private static final String BASE = "http://localhost:9000/products";

    @BeforeEach
    void enableStorage() {
        StorageProperties props = new StorageProperties();
        props.setEnabled(true);
        props.setEndpoint("http://localhost:9000");
        props.setPublicBaseUrl(BASE);
        StorageUrls.init(props);
    }

    @AfterEach
    void resetToNeutral() {
        StorageProperties props = new StorageProperties();
        props.setEnabled(false);
        StorageUrls.init(props);
    }

    private static StorageProperties disabledProps() {
        StorageProperties props = new StorageProperties();
        props.setEnabled(false);
        return props;
    }

    // ==================== toKey：写入端归一 ====================

    @Test
    void toKeyExtractsKeyFromOwnStorageUrl() {
        assertEquals("1001.webp", StorageUrls.toKey(BASE + "/1001.webp"));
        assertEquals("products/prod_1730000000.webp",
                StorageUrls.toKey(BASE + "/products/prod_1730000000.webp"));
    }

    @Test
    void toKeyExtractsKeyFromEndpointDomainUrl() {
        // 商户粘贴内网 endpoint 形态 URL（compose 内 LANLINK_S3_ENDPOINT=http://minio:9000 同理）
        assertEquals("1001.webp", StorageUrls.toKey("http://localhost:9000/1001.webp"));
    }

    @Test
    void toKeyMapsLegacyRelativePathToBucketRootKey() {
        assertEquals("1001.webp", StorageUrls.toKey("/products/1001.webp"));
        assertEquals("ind/A1.webp", StorageUrls.toKey("/products/ind/A1.webp"));
    }

    @Test
    void toKeyKeepsBareKeysLocalUploadPathsAndExternalUrls() {
        assertEquals("1001.webp", StorageUrls.toKey("1001.webp"));
        assertEquals("products/prod_1.webp", StorageUrls.toKey("products/prod_1.webp"));
        // 本地盘域（头像/证照）与外域 URL 原样放行
        assertEquals("/api/uploads/products/x.jpg", StorageUrls.toKey("/api/uploads/products/x.jpg"));
        assertEquals("/api/uploads/avatars/1.png", StorageUrls.toKey("/api/uploads/avatars/1.png"));
        assertEquals("https://cdn.example.com/a.jpg", StorageUrls.toKey("https://cdn.example.com/a.jpg"));
    }

    @Test
    void toKeyToleratesBlankInput() {
        assertNull(StorageUrls.toKey(null));
        assertEquals("", StorageUrls.toKey(""));
        assertEquals("   ", StorageUrls.toKey("   "));
    }

    // ==================== publicUrlOf：出站渲染 ====================

    @Test
    void publicUrlJoinsBaseUrlForBareKeys() {
        assertEquals(BASE + "/1001.webp", StorageUrls.publicUrlOf("1001.webp"));
        assertEquals(BASE + "/products/prod_1730000000.webp",
                StorageUrls.publicUrlOf("products/prod_1730000000.webp"));
        assertEquals(BASE + "/ind/A1.webp", StorageUrls.publicUrlOf("ind/A1.webp"));
    }

    @Test
    void publicUrlNormalizesLegacyRelativePathWithoutDoublingPrefix() {
        // 存量 DB 行（/products/{id}.webp）不直改，出站拼成桶根 key 的公开 URL（G6 口径）
        assertEquals(BASE + "/1001.webp", StorageUrls.publicUrlOf("/products/1001.webp"));
    }

    @Test
    void publicUrlKeepsLocalUploadDomainAndExternalUrlsAsIs() {
        assertEquals("/api/uploads/products/x.jpg",
                StorageUrls.publicUrlOf("/api/uploads/products/x.jpg"));
        assertEquals("/uploads/chat/a.png", StorageUrls.publicUrlOf("/uploads/chat/a.png"));
        assertEquals("https://cdn.example.com/a.jpg",
                StorageUrls.publicUrlOf("https://cdn.example.com/a.jpg"));
    }

    @Test
    void publicUrlCanonicalizesOwnDomainUrl() {
        assertEquals(BASE + "/1001.webp", StorageUrls.publicUrlOf(BASE + "/1001.webp"));
        assertEquals(BASE + "/products/prod_1.webp",
                StorageUrls.publicUrlOf(BASE + "/products/prod_1.webp"));
    }

    @Test
    void publicUrlPassesThroughEverythingWhenDisabled() {
        // 规划 G12 回退口径：LANLINK_STORAGE_ENABLED=false → 出站原样输出
        StorageUrls.init(disabledProps());
        assertEquals("1001.webp", StorageUrls.publicUrlOf("1001.webp"));
        assertEquals("/products/1001.webp", StorageUrls.publicUrlOf("/products/1001.webp"));
        assertEquals(BASE + "/1001.webp", StorageUrls.publicUrlOf(BASE + "/1001.webp"));
        assertEquals("", StorageUrls.publicUrlOf(""));
        assertNull(StorageUrls.publicUrlOf(null));
    }

    // ==================== storageKeyOrNull：视觉审核回源判定（A6） ====================

    @Test
    void storageKeyOrNullAcceptsBareKeysAndOwnUrlsOnly() {
        assertEquals("1001.webp", StorageUrls.storageKeyOrNull("1001.webp"));
        assertEquals("products/prod_1.webp", StorageUrls.storageKeyOrNull("products/prod_1.webp"));
        assertEquals("products/prod_1.webp", StorageUrls.storageKeyOrNull(BASE + "/products/prod_1.webp"));
    }

    @Test
    void storageKeyOrNullRejectsLegacyLocalAndExternalShapes() {
        // /products/ 相对路径仍走前端静态资源分支；本地盘域走磁盘分支；外域走 SSRF 白名单分支
        assertNull(StorageUrls.storageKeyOrNull("/products/1001.webp"));
        assertNull(StorageUrls.storageKeyOrNull("/api/uploads/products/x.jpg"));
        assertNull(StorageUrls.storageKeyOrNull("https://evil.example.com/a.jpg"));
        assertNull(StorageUrls.storageKeyOrNull("not a key with spaces"));
        assertNull(StorageUrls.storageKeyOrNull(""));
        assertNull(StorageUrls.storageKeyOrNull(null));
    }
}
