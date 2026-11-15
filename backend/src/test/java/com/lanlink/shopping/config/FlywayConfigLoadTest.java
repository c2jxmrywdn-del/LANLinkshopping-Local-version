package com.lanlink.shopping.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * P0-5：Flyway 配置加载单测（不连数据库）。
 * 1) 直接断言真实 application.yml / application-dev.yml / application-prod.yml 中的 spring.flyway.* 键；
 * 2) 用真实文件抽出的属性驱动 FlywayAutoConfiguration，断言 Flyway 配置对象真正绑定成功
 *    （防"键名拼错导致静默走默认值"）。
 * FlywayMigrationInitializer.afterPropertiesSet() 会调 flyway.migrate() 连库，
 * 因此 runner 里注册 no-op FlywayMigrationStrategy，只验证配置不触发真实迁移。
 */
class FlywayConfigLoadTest {

    private static final String SHARED_YML = "application.yml";
    private static final String DEV_YML = "application-dev.yml";
    private static final String PROD_YML = "application-prod.yml";

    /** 读取 classpath 下 yml 的属性（含占位符原样字符串，不解析环境变量）。 */
    static Properties loadYaml(String classpathLocation) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(classpathLocation));
        Properties props = factory.getObject();
        assertNotNull(props, "yml 应能解析: " + classpathLocation);
        return props;
    }

    static String[] flywayPropertyArray(Properties props) {
        // 不用 stringPropertyNames()：YamlPropertiesFactoryBean 会把 YAML 布尔字面量存成
        // java.lang.Boolean（实证），stringPropertyNames() 会静默丢弃非 String 值的条目。
        return props.entrySet().stream()
                .map(e -> Map.entry(String.valueOf(e.getKey()), String.valueOf(e.getValue())))
                .filter(e -> e.getKey().startsWith("spring.flyway."))
                .map(e -> e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
    }

    private static String sharedFlywayProp(String key) {
        return loadYaml(SHARED_YML).getProperty("spring.flyway." + key);
    }

    @Test
    void sharedYamlDeclaresFlywayBaseConfig() {
        assertEquals("classpath:db-migrations", sharedFlywayProp("locations"));
        assertEquals("true", sharedFlywayProp("baseline-on-migrate"));
        // baseline 必须【低于】所有存量迁移（V2026_10 / V2026_11），否则会被判 Below Baseline 静默跳过
        assertEquals("2026.9.1", sharedFlywayProp("baseline-version"));
        assertTrue(sharedFlywayProp("enabled") != null, "application.yml 应声明 spring.flyway.enabled");
    }

    @Test
    void devProfileEnablesFlywayWithEnvOverride() {
        String devEnabled = loadYaml(DEV_YML).getProperty("spring.flyway.enabled");
        assertEquals("${SPRING_FLYWAY_ENABLED:true}", devEnabled,
                "dev 默认开启 Flyway，且保留 SPRING_FLYWAY_ENABLED=false 关闭逃生门");
    }

    @Test
    void prodProfileDisablesFlywayUntilBaselineReconciled() {
        String prodEnabled = loadYaml(PROD_YML).getProperty("spring.flyway.enabled");
        assertEquals("false", prodEnabled,
                "拍板⑦-14 生产库对账（V2026_10/V2026_11 是否已手工执行）完成前 prod 必须关闭 Flyway");
    }

    @Test
    void flywayAutoConfigurationBindsRealSharedProperties() {
        String[] props = flywayPropertyArray(loadYaml(SHARED_YML));
        new ApplicationContextRunner()
                .withUserConfiguration(StubDataSourceConfig.class)
                .withPropertyValues(props)
                .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                .run(context -> {
                    assertEquals(1, context.getBeanNamesForType(FlywayMigrationInitializer.class).length,
                            "共享属性下 Flyway 迁移初始化器应被创建");
                    Configuration cfg = context.getBean(Flyway.class).getConfiguration();
                    assertEquals(1, cfg.getLocations().length);
                    assertEquals("classpath:db-migrations", cfg.getLocations()[0].getDescriptor());
                    assertTrue(cfg.isBaselineOnMigrate(), "baseline-on-migrate 应绑定成功");
                    assertEquals("2026.9.1", cfg.getBaselineVersion().getVersion(),
                            "baseline-version 应绑定成功");
                });
    }

    @Test
    void flywayDisabledWhenEnabledFalse() {
        String[] props = flywayPropertyArray(loadYaml(SHARED_YML));
        new ApplicationContextRunner()
                .withUserConfiguration(StubDataSourceConfig.class)
                .withPropertyValues("spring.flyway.enabled=false")
                .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                .run(context -> assertEquals(0, context.getBeanNamesForType(FlywayMigrationInitializer.class).length,
                        "spring.flyway.enabled=false 时（prod 口径）不应创建迁移初始化器"));
    }

    /** 仅提供 DataSource 类型满足自动配置条件；no-op 策略保证 migrate() 永不真实连库。 */
    static class StubDataSourceConfig {
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Bean
        FlywayMigrationStrategy noMigrationStrategy() {
            return flyway -> {
            };
        }
    }
}
