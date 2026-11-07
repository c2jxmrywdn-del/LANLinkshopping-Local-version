package com.lanlink.shopping.common.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭据 fail-fast 与配置前缀统一（security-hardening-plan.md P1-1，门禁 SG-11/12 + SR-2）。
 *
 * <p>三个纪律点：
 * <ol>
 *   <li>enabled=true 而 S3 凭据空白/为 minioadmin → 启动即 IllegalStateException（禁止静默弱凭据）；</li>
 *   <li>enabled=false → 不创建 S3Client、不要求凭据，上传回退本地盘（回退真实生效）；</li>
 *   <li>LANLINK_STORAGE_ENABLED（系统环境变量名）经宽松绑定直达 lanlink.storage.enabled ——
 *       旧 app.storage 前缀下该变量绑不上、开关形同虚设，这里是回归锚。</li>
 * </ol>
 */
class StorageCredentialGuardTest {

    /** enabled=true 与 application.yml 默认值同口径（${LANLINK_STORAGE_ENABLED:true}）：条件命中才谈凭据校验 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("lanlink.storage.enabled=true")
            .withUserConfiguration(StorageConfig.class)
            .withBean(StorageProperties.class);

    private static StorageProperties props(String access, String secret) {
        StorageProperties props = new StorageProperties();
        props.setAccessKey(access);
        props.setSecretKey(secret);
        return props;
    }

    // ==================== fail-fast（SG-12） ====================

    /** BeanCreationException 包裹后的根因（ApplicationContextRunner 把启动失败包了一层） */
    private static Throwable rootCause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    @Test
    void blankCredentialsRefuseToStart() {
        // 默认字段值即空串（application.yml 兜底也是空串）：缺 env 必须炸而不是静默 minioadmin
        runner.run(context -> {
            assertTrue(context.getStartupFailure() != null, "空白凭据必须导致启动失败");
            Throwable root = rootCause(context.getStartupFailure());
            assertTrue(root instanceof IllegalStateException,
                    "空白凭据应抛 IllegalStateException，实际: " + context.getStartupFailure());
            assertTrue(root.getMessage().contains("LANLINK_S3_ACCESS_KEY"),
                    "错误信息应指向缺失的 env 变量组");
        });
    }

    @Test
    void minioadminCredentialsRefuseToStart() {
        new ApplicationContextRunner()
                .withPropertyValues("lanlink.storage.enabled=true")
                .withUserConfiguration(StorageConfig.class)
                .withBean("storageProperties", StorageProperties.class,
                        () -> props("minioadmin", "minioadmin"))
                .run(context -> {
                    assertNotNull(context.getStartupFailure(), "minioadmin 弱凭据必须拒绝启动");
                    assertTrue(rootCause(context.getStartupFailure()).getMessage().contains("minioadmin"),
                            "错误信息应点名弱默认值");
                });
    }

    @Test
    void validCredentialsStartAndCreateS3Client() {
        new ApplicationContextRunner()
                .withPropertyValues("lanlink.storage.enabled=true")
                .withUserConfiguration(StorageConfig.class)
                .withBean("storageProperties", StorageProperties.class,
                        () -> props("lanlink-app", "pxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
                .run(context -> {
                    assertNull(context.getStartupFailure(), "正常凭据应能建 Bean: " + context.getStartupFailure());
                    assertTrue(context.containsBean("s3Client"), "S3Client Bean 应被创建");
                });
    }

    // ==================== enabled=false 回退真实生效 ====================

    @Test
    void disabledSkipsS3ClientAndCredentialCheck() {
        new ApplicationContextRunner()
                .withPropertyValues("lanlink.storage.enabled=false")
                .withUserConfiguration(StorageConfig.class, StorageService.class)
                .withBean("storageProperties", StorageProperties.class,
                        () -> props("", ""))
                .run(context -> {
                    assertNull(context.getStartupFailure(), "关闭开关时空白凭据不应触发校验: " + context.getStartupFailure());
                    assertFalse(context.containsBean("s3Client"), "enabled=false 不得创建 S3Client Bean");
                    assertTrue(context.containsBean("storageService"), "StorageService 回退分支仍应在位");
                });
    }

    @Test
    void localFallbackPutsFileAndReturnsLegacyUrl() throws Exception {
        Path root = Files.createTempDirectory("storage-fallback");
        StorageProperties props = new StorageProperties();
        props.setEnabled(false);
        props.getLocal().setRoot(root.toString());
        StorageService storage = new StorageService(props, null);

        String url = storage.put("products/prod_1.webp", new byte[]{1}, "image/webp");

        assertEquals("/api/uploads/products/prod_1.webp", url);
        assertTrue(Files.isRegularFile(root.resolve("products").resolve("prod_1.webp")));
    }

    // ==================== 前缀统一：LANLINK_* 环境变量绑定（回归锚） ====================

    @Test
    void lanlinkStoragePrefixBindsFromEnvironmentVariableNames() {
        // Boot 系统环境变量宽松绑定（规范映射）：LANLINK_STORAGE_ENABLED → lanlink.storage.enabled。
        // 旧前缀 app.storage 下该变量名绑不上（会需要 APP_STORAGE_ENABLED），开关失灵。
        // 注意 LANLINK_S3_* 变量名不含 STORAGE_，规范映射对不上 lanlink.storage.*——
        // 它们经 application.yml 的 ${LANLINK_S3_*:} 占位符桥接（同 app.mq.order-flow 的既有纪律）。
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "test-system-env", Map.of("LANLINK_STORAGE_ENABLED", "false",
                "LANLINK_STORAGE_BUCKET", "products-b")));
        StorageProperties bound = new StorageProperties();
        org.springframework.boot.context.properties.bind.BindResult<StorageProperties> result =
                Binder.get(env).bind("lanlink.storage", Bindable.ofInstance(bound));
        assertTrue(result.isBound(), "lanlink.storage 前缀应可绑定");
        assertFalse(bound.isEnabled(), "LANLINK_STORAGE_ENABLED=false 应关闭开关");
        assertEquals("products-b", bound.getBucket(), "LANLINK_STORAGE_BUCKET 应绑定到 bucket");
    }
}
