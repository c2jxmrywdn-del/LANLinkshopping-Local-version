package com.lanlink.shopping.common.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.entity.Product;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Product.coverUrl 出站兼容层（out/storage-mq-plan.md 任务 A4）：
 * @JsonSerialize(StorageUrlSerializer) 挂实体字段后，所有直接序列化 Product 的接口
 * （列表/详情/AI 工具响应等）自动把库存 key 渲染为公开绝对 URL。
 */
class StorageUrlSerializerTest {

    private static final String BASE = "http://localhost:9000/products";
    private final ObjectMapper mapper = new ObjectMapper();

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

    private String coverUrlJsonOf(String coverUrl) throws Exception {
        Product p = new Product();
        p.setProdId(1001L);
        p.setCoverUrl(coverUrl);
        return mapper.writeValueAsString(p);
    }

    @Test
    void serializesBareKeyToPublicAbsoluteUrl() throws Exception {
        String json = coverUrlJsonOf("1001.webp");
        assertTrue(json.contains("\"coverUrl\":\"" + BASE + "/1001.webp\""), json);
    }

    @Test
    void serializesUploadKeyToPublicAbsoluteUrl() throws Exception {
        String json = coverUrlJsonOf("products/prod_1730000000.webp");
        assertTrue(json.contains("\"coverUrl\":\"" + BASE + "/products/prod_1730000000.webp\""), json);
    }

    @Test
    void legacyRelativePathRendersWithoutDoublingPrefix() throws Exception {
        // 存量 DB 行不直改（规划 A4），出站拼公开 URL；G6：detail/1001 coverUrl 以存储域开头
        String json = coverUrlJsonOf("/products/1001.webp");
        assertTrue(json.contains("\"coverUrl\":\"" + BASE + "/1001.webp\""), json);
    }

    @Test
    void externalUrlAndLocalUploadDomainStayAsIs() throws Exception {
        assertTrue(coverUrlJsonOf("https://cdn.example.com/a.jpg")
                .contains("\"coverUrl\":\"https://cdn.example.com/a.jpg\""));
        assertTrue(coverUrlJsonOf("/api/uploads/products/x.jpg")
                .contains("\"coverUrl\":\"/api/uploads/products/x.jpg\""));
    }

    @Test
    void emptyCoverStaysEmpty() throws Exception {
        // verify-catalog.mjs:37 断言新发布商品 coverUrl==='' —— 空值必须原样透传
        assertTrue(coverUrlJsonOf("").contains("\"coverUrl\":\"\""));
    }
}
