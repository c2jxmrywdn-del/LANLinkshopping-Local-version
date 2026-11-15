package com.lanlink.shopping.module.marketingimage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 营销图生成任务表幂等初始化器。
 *
 * <p>为什么在代码里建表而不是只写 schema.sql：schema.sql 只对全新库生效，
 * 已经在跑的库不会重放；用 CREATE TABLE IF NOT EXISTS 让新老库都能补齐，
 * 同时不干扰存量数据。
 */
@Component
@Order(2)
public class MarketingImageSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketingImageSchemaInitializer.class);

    private final JdbcTemplate jdbc;

    public MarketingImageSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[marketing-image-schema] 正在检查并初始化营销图任务表...");
        // task_no 为对外可见的任务号（同时用于对象存储文件名），唯一键防止重复提交覆盖文件；
        // (owner_type, owner_id, created_at) 覆盖「我的任务按时间倒序」这一唯一高频查询。
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS t_marketing_image_task (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              task_no VARCHAR(40) NOT NULL COMMENT '对外任务号',
              owner_type VARCHAR(16) NOT NULL COMMENT '归属类型 MERCHANT/ADMIN',
              owner_id BIGINT NOT NULL COMMENT '归属用户ID',
              prompt VARCHAR(800) NOT NULL COMMENT '正向提示词',
              negative_prompt VARCHAR(500) COMMENT '反向提示词',
              model VARCHAR(64) COMMENT '实际使用的模型',
              size VARCHAR(16) COMMENT '图片尺寸 1024x1024/1280x720',
              status VARCHAR(16) DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED',
              object_key VARCHAR(300) COMMENT '对象存储键',
              image_url VARCHAR(500) COMMENT '对外可访问URL',
              error_message VARCHAR(500) COMMENT '失败原因',
              cost_micros BIGINT DEFAULT 0 COMMENT '成本(微元)，0.22元=220000',
              created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              UNIQUE KEY uk_task_no (task_no),
              KEY idx_owner (owner_type, owner_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
              COMMENT='营销图生成任务';
            """);
        log.info("[marketing-image-schema] 营销图任务表初始化完成。");
    }
}
