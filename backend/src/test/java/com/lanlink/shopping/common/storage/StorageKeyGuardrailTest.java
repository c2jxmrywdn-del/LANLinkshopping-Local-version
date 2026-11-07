package com.lanlink.shopping.common.storage;

import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对象 key 形态白名单冻结（security-hardening-plan.md P1-3.3，SG-33）：
 * 公读桶只允许公开商品图两类 key（种子 {id}.webp / 上传 products/prod_{ts}.{kind}），
 * 敏感对象（头像/证照 /api/uploads/** 域）不经本桶。
 *
 * <p>同时固化「库内一律存 key」（A2）：publish/myUpdate 入库前把含 base 段的 URL
 * 统一截回裸 key，消除 products/products 双写；存量裸 key 与外域 URL 原样兼容。
 */
class StorageKeyGuardrailTest {

    private static final String BASE = "http://localhost:9000/products";

    @BeforeEach
    void initUrlHolder() {
        StorageProperties props = new StorageProperties();
        props.setEnabled(true);
        props.setEndpoint("http://localhost:9000");
        props.setPublicBaseUrl(BASE);
        StorageUrls.init(props);
    }

    // ==================== key 形态白名单（冻结清单） ====================

    @Test
    void publicBucketKeyWhitelistOnlyAllowsPublicProductImages() {
        Pattern seed = Pattern.compile("^\\d+\\.webp$");
        Pattern upload = Pattern.compile("^products/prod_\\d+\\.(webp|jpg|png)$");

        List<String> allowed = List.of("1.webp", "999999.webp",
                "products/prod_1730000000000.webp", "products/prod_1730000000000.jpg",
                "products/prod_1730000000000.png");
        for (String key : allowed) {
            assertTrue(seed.matcher(key).matches() || upload.matcher(key).matches(),
                    "白名单内 key 应匹配: " + key);
        }
        // 非商品图 / 路径穿越 / 嵌套目录一律不在白名单
        List<String> denied = List.of("avatar/u1.png", "license/x.jpg", "ind/../1.webp",
                "products/prod_abc.webp", "1.jpg", "products/1.webp");
        for (String key : denied) {
            assertFalse(seed.matcher(key).matches() || upload.matcher(key).matches(),
                    "白名单外 key 必须拒绝: " + key);
        }
    }

    @Test
    void bareKeyShapeRejectsTraversalSegments() {
        assertTrue(StorageUrls.isBareKeyShape("products/prod_1730000000000.webp"));
        assertTrue(StorageUrls.isBareKeyShape("1001.webp"));
        assertFalse(StorageUrls.isBareKeyShape("products/../1.webp"), ".. 段必须拒绝");
        assertFalse(StorageUrls.isBareKeyShape("..\\evil.webp"), "反斜杠/.. 必须拒绝");
        assertFalse(StorageUrls.isBareKeyShape("/1.webp"), "前导斜杠不是裸 key");
    }

    @Test
    void toKeyStripsBaseUrlSegmentRemovingDoubleWrite() {
        // 上传响应 url 拼了 base 段（base=/products + key=products/prod_x.webp → /products/products/prod_x.webp）：
        // 入库前截回裸 key，消除「products/products」双写
        assertEquals("products/prod_1730000000000.webp",
                StorageUrls.toKey("/products/products/prod_1730000000000.webp"));
        // 绝对 URL（本存储域）同样截 key
        assertEquals("products/prod_1730000000000.webp",
                StorageUrls.toKey(BASE + "/products/prod_1730000000000.webp"));
        // 遗留相对路径（种子图形态）
        assertEquals("1.webp", StorageUrls.toKey("/products/1.webp"));
        // 存量裸 key / 外域 URL 原样放行
        assertEquals("1001.webp", StorageUrls.toKey("1001.webp"));
        assertEquals("https://cdn.example.com/x.webp", StorageUrls.toKey("https://cdn.example.com/x.webp"));
    }

    @Test
    void outboundRendersBareKeyToPublicUrlForLegacyRows() {
        // 存量裸 key 行出站兼容（A4）：publicUrlOf 拼 base 段
        assertEquals(BASE + "/1001.webp", StorageUrls.publicUrlOf("1001.webp"));
        assertEquals(BASE + "/products/prod_1730000000000.webp",
                StorageUrls.publicUrlOf("products/prod_1730000000000.webp"));
        // 本地盘域（头像/证照）不迁，出站原样
        assertEquals("/api/uploads/avatar/u1.png", StorageUrls.publicUrlOf("/api/uploads/avatar/u1.png"));
    }

    // ==================== publish/myUpdate 入库归一 ====================

    private static Product validProduct(String coverUrl) {
        Product p = new Product();
        p.setTitle("测试商品标题");
        p.setPrice(new BigDecimal("10.50"));
        p.setStock(10);
        p.setCatId(1L);
        p.setIndId(1L);
        p.setCoverUrl(coverUrl);
        return p;
    }

    @Test
    void publishNormalizesCoverUrlToBareKeyBeforeInsert() {
        ProductMapper mapper = mock(ProductMapper.class);
        ProductService service = new ProductService(mapper);
        Product p = validProduct("/products/products/prod_1730000000000.webp");

        service.publish(9L, p);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(mapper).insert(captor.capture());
        assertEquals("products/prod_1730000000000.webp", captor.getValue().getCoverUrl(),
                "publish 入库必须截回裸 key（A2 库内一律存 key）");
    }

    @Test
    void publishKeepsBareKeyAndExternalUrlAsIs() {
        ProductMapper mapper = mock(ProductMapper.class);
        ProductService service = new ProductService(mapper);
        Product p = validProduct("products/prod_1730000000000.webp");
        service.publish(9L, p);
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(mapper).insert(captor.capture());
        assertEquals("products/prod_1730000000000.webp", captor.getValue().getCoverUrl(),
                "裸 key 不应被二次改写");

        ProductMapper mapper2 = mock(ProductMapper.class);
        ProductService service2 = new ProductService(mapper2);
        Product external = validProduct("https://cdn.example.com/x.webp");
        service2.publish(9L, external);
        ArgumentCaptor<Product> captor2 = ArgumentCaptor.forClass(Product.class);
        verify(mapper2).insert(captor2.capture());
        assertEquals("https://cdn.example.com/x.webp", captor2.getValue().getCoverUrl(),
                "外域 URL 原样放行（仍受 255 长度约束）");
    }

    @Test
    void myUpdateNormalizesCoverUrlToBareKeyBeforeUpdate() {
        ProductMapper mapper = mock(ProductMapper.class);
        Product existing = validProduct("1001.webp");
        existing.setProdId(5L);
        existing.setMerId(9L);
        when(mapper.selectById(5L)).thenReturn(existing);
        ProductService service = new ProductService(mapper);

        Product patch = new Product();
        patch.setCoverUrl(BASE + "/products/prod_1730000000001.webp");
        service.myUpdate(9L, 5L, patch);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(mapper).updateById(captor.capture());
        assertEquals("products/prod_1730000000001.webp", captor.getValue().getCoverUrl(),
                "myUpdate 入库必须截回裸 key");
    }
}
