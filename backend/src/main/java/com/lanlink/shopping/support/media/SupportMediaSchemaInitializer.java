package com.lanlink.shopping.support.media;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 客服多模态存储基座建表（P0）：媒体表、outbox、分发日志、模型用量四张表。
 *
 * <p>全部 CREATE TABLE IF NOT EXISTS，存量库无需手工升级；首次建库口径由 sql/schema.sql
 * 全量 DDL 负责，两处必须一致。@Order(2) 与订阅域迁移同批，排在核心表初始化之后。
 */
@Component
@Order(2)
public class SupportMediaSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public SupportMediaSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_support_media (
              id BIGINT NOT NULL AUTO_INCREMENT,
              media_no VARCHAR(40) NOT NULL COMMENT '媒体单号（SM+时间戳+随机段），对外唯一标识',
              conversation_id BIGINT NULL COMMENT '客服会话 ID，未绑定会话时为空',
              message_id BIGINT NULL COMMENT '所属消息 ID，解析完成后回填',
              visitor_token CHAR(36) NOT NULL COMMENT '访客令牌（归属校验唯一依据）',
              kind VARCHAR(16) NOT NULL COMMENT 'IMAGE/AUDIO/VIDEO/DOCUMENT',
              object_key VARCHAR(300) NOT NULL COMMENT 'support/{yyyyMM}/{hash16}/{mediaNo}.{ext}',
              file_name VARCHAR(200) NOT NULL COMMENT '原始展示名（已去路径段与限长）',
              content_type VARCHAR(80) NOT NULL,
              file_size BIGINT NOT NULL DEFAULT 0,
              sha256 CHAR(64) NULL COMMENT '确认时流式计算的全量摘要',
              width INT NULL,
              height INT NULL,
              duration_ms INT NULL,
              page_count INT NULL,
              status VARCHAR(16) NOT NULL DEFAULT 'INIT'
                COMMENT 'INIT/UPLOADED/PARSING/READY/FAILED/EXPIRED',
              engine VARCHAR(40) NULL COMMENT '解析引擎标识（OCR/ASR 供应商）',
              ocr_text MEDIUMTEXT NULL,
              asr_text MEDIUMTEXT NULL,
              summary TEXT NULL,
              entities_json TEXT NULL,
              confidence DECIMAL(4,3) NULL,
              error_message VARCHAR(500) NULL,
              retry_count INT NOT NULL DEFAULT 0,
              expires_at DATETIME NULL COMMENT '软删时间点（创建时 + 保留天数）',
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (id),
              UNIQUE KEY ux_support_media_no (media_no),
              UNIQUE KEY ux_support_media_object_key (object_key),
              KEY idx_support_media_visitor (visitor_token, created_at),
              KEY idx_support_media_conversation (conversation_id),
              KEY idx_support_media_message (message_id),
              KEY idx_support_media_status (status, updated_at),
              KEY idx_support_media_expires (expires_at, status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
              COMMENT='客服多模态媒体（库内只存 object key，读取一律经后端中转）'
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_support_media_outbox (
              id BIGINT NOT NULL AUTO_INCREMENT,
              media_id BIGINT NOT NULL,
              status TINYINT NOT NULL DEFAULT 0 COMMENT '0 待投递 / 1 已投递',
              retry_count INT NOT NULL DEFAULT 0,
              next_retry_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              locked_at DATETIME NULL COMMENT 'CAS 锁定时间，5 分钟后可被重新认领',
              last_error VARCHAR(500) NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (id),
              UNIQUE KEY ux_support_media_outbox_media (media_id),
              KEY idx_support_media_outbox_due (status, next_retry_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
              COMMENT='客服媒体解析任务发件箱（先落库再投递）'
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_support_dispatch_log (
              id BIGINT NOT NULL AUTO_INCREMENT,
              visitor_token CHAR(36) NOT NULL,
              question_hash CHAR(64) NOT NULL COMMENT '提问内容 SHA-256，避免明文留存',
              decision VARCHAR(24) NOT NULL COMMENT '路由决策（KB/RAG/LLM/MEDIA/...）',
              intent_confidence DECIMAL(4,3) NULL,
              rag_top_score DECIMAL(4,3) NULL,
              media_kinds VARCHAR(64) NULL COMMENT '本条消息附带的媒体类型，逗号分隔',
              external_called TINYINT NOT NULL DEFAULT 0,
              latency_ms INT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (id),
              KEY idx_support_dispatch_visitor (visitor_token, created_at),
              KEY idx_support_dispatch_created (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
              COMMENT='客服分发决策日志（只存哈希，用于效果回放与调参）'
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_support_llm_usage (
              id BIGINT NOT NULL AUTO_INCREMENT,
              visitor_token CHAR(36) NULL,
              channel VARCHAR(20) NULL COMMENT '调用通道（local/external/...）',
              model VARCHAR(64) NULL,
              capability VARCHAR(16) NULL COMMENT 'TEXT/AUDIO/VISION/DOC（P2：VISION 图像理解、DOC 文档解析）',
              prompt_tokens INT NULL,
              completion_tokens INT NULL,
              audio_seconds DECIMAL(10,2) NULL,
              cost_micros BIGINT NULL COMMENT '成本（微元），避免浮点累计误差',
              media_id BIGINT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (id),
              KEY idx_support_llm_usage_visitor (visitor_token, created_at),
              KEY idx_support_llm_usage_created (created_at),
              KEY idx_support_llm_usage_media (media_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
              COMMENT='客服模型用量账本（token/音频秒数/成本）'
            """);
    }
}
