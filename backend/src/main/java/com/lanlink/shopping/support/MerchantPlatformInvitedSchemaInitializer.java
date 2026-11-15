package com.lanlink.shopping.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 存量库补齐 t_merchant.platform_invited。必须早于任何会查询商户表的启动任务。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MerchantPlatformInvitedSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public MerchantPlatformInvitedSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer exists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_merchant' AND COLUMN_NAME = 'platform_invited'
                """, Integer.class);
        if (exists != null && exists > 0) return;
        jdbcTemplate.execute("""
                ALTER TABLE t_merchant ADD COLUMN platform_invited TINYINT NOT NULL DEFAULT 0
                COMMENT '平台特邀商户 0否 1是（后台标记或运营邀约入驻）'
                """);
    }
}
