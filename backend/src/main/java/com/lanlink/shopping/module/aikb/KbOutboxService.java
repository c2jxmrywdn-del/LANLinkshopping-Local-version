package com.lanlink.shopping.module.aikb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 商品知识同步 outbox。
 *
 * <p>商品写操作在自身事务中落一条 SYNC 任务；真正嵌入由 KbOutboxWorker 在事务外执行。
 * pending_key 仅对 status=0 有效，利用 MySQL 唯一约束原子合并同商品 pending 任务；
 * 领取后置空，允许处理期间产生的新变更另开一条补偿任务。</p>
 */
@Service
public class KbOutboxService {

    private static final Logger log = LoggerFactory.getLogger(KbOutboxService.class);
    private static final int MAX_RETRY = 8;
    private static final int STATUS_PENDING = 0;
    private static final int STATUS_PROCESSING = 1;
    private static final int STATUS_DONE = 2;
    private static final int STATUS_DEAD = 3;

    private final JdbcTemplate jdbc;

    public KbOutboxService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record OutboxRow(long id, long prodId, String action, int retryCount) {}

    /**
     * 同事务投递商品知识同步任务。唯一 pending_key 将同商品多次变更合并为一次当前状态重同步；
     * 正在处理的任务不会被改写，其后续变更会生成新的 pending 行，避免“先领取、后变化”丢失。
     */
    public void enqueue(Long prodId, String action) {
        if (prodId == null) return;
        String act = (action == null || action.isBlank()) ? "SYNC" : action.trim();
        jdbc.update("INSERT INTO t_ai_kb_outbox (prod_id, action, pending_key) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE action = VALUES(action), next_retry_at = NOW(), "
                        + "updated_at = NOW(), last_error = NULL",
                prodId, act, prodId);
    }

    /**
     * 抢占一批待处理任务。SELECT FOR UPDATE SKIP LOCKED 与翻转 status/pending_key 同事务完成；
     * 调用方必须在事务返回后再做远程嵌入，禁止持数据库事务调用模型。
     */
    @Transactional
    public List<OutboxRow> claimBatch(int limit) {
        int max = Math.max(1, Math.min(limit, 20));
        List<OutboxRow> rows = jdbc.query(
                "SELECT id, prod_id, action, retry_count FROM t_ai_kb_outbox "
                        + "WHERE status = 0 AND next_retry_at <= NOW() "
                        + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, rowNum) -> new OutboxRow(rs.getLong("id"), rs.getLong("prod_id"),
                        rs.getString("action"), rs.getInt("retry_count")),
                max);
        for (OutboxRow row : rows) {
            jdbc.update("UPDATE t_ai_kb_outbox SET status = 1, pending_key = NULL, locked_at = NOW() "
                    + "WHERE id = ? AND status = 0", row.id());
        }
        return rows;
    }

    public void markDone(long id) {
        jdbc.update("UPDATE t_ai_kb_outbox SET status = 2, pending_key = NULL, locked_at = NULL, "
                + "last_error = NULL, next_retry_at = NOW() WHERE id = ?", id);
    }

    public void markFailed(long id, int retryCount, String error) {
        markFailed(id, retryCount, error, false);
    }

    public void markFailed(long id, int retryCount, String error, boolean permanent) {
        String detail = trim(error);
        if (permanent) {
            jdbc.update("UPDATE t_ai_kb_outbox SET status = 3, pending_key = NULL, locked_at = NULL, "
                    + "retry_count = ?, last_error = ? WHERE id = ?",
                    Math.max(0, retryCount) + 1, detail, id);
            log.error("[kb-outbox] permanent failure id={}: {}", id, detail);
            return;
        }
        int retry = Math.max(0, retryCount) + 1;
        if (retry >= MAX_RETRY) {
            jdbc.update("UPDATE t_ai_kb_outbox SET status = 3, pending_key = NULL, locked_at = NULL, "
                    + "retry_count = ?, last_error = ? WHERE id = ?", retry, detail, id);
            log.error("[kb-outbox] task {} moved to dead letter after {} retries: {}", id, retry, detail);
            return;
        }
        long base = 15L * (1L << Math.min(7, retry - 1));
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1L, base / 2L));
        int delay = (int) Math.min(3600L, base + jitter);
        jdbc.update("UPDATE t_ai_kb_outbox SET status = 0, pending_key = NULL, locked_at = NULL, "
                        + "retry_count = ?, last_error = ?, next_retry_at = TIMESTAMPADD(SECOND, ?, NOW()) "
                        + "WHERE id = ?",
                retry, detail, delay, id);
    }

    /** 暂时性资源竞争（全量灌库/同商品锁）不计入失败重试，只延迟回队。 */
    public void markBusy(long id) {
        jdbc.update("UPDATE t_ai_kb_outbox SET status = 0, pending_key = NULL, locked_at = NULL, "
                        + "last_error = 'knowledge ingest busy', next_retry_at = TIMESTAMPADD(SECOND, 15, NOW()) "
                        + "WHERE id = ?", id);
    }

    /**
     * 进程在处理中崩溃时回收超时 lease。每次回收也计入重试，避免永久性错误无限重放；
     * 超过上限直接转死信，保留现场供管理端排查。
     */
    public int resetStuck(int minutes) {
        int expired = jdbc.update("UPDATE t_ai_kb_outbox SET status = 3, pending_key = NULL, "
                        + "retry_count = retry_count + 1, last_error = 'lease expired' "
                        + "WHERE status = 1 AND locked_at IS NOT NULL "
                        + "AND locked_at < TIMESTAMPADD(MINUTE, ?, NOW()) AND retry_count + 1 >= ?",
                -Math.max(1, minutes), MAX_RETRY);
        int recovered = jdbc.update("UPDATE t_ai_kb_outbox SET status = 0, pending_key = NULL, "
                        + "retry_count = retry_count + 1, last_error = 'lease expired', next_retry_at = NOW() "
                        + "WHERE status = 1 AND locked_at IS NOT NULL "
                        + "AND locked_at < TIMESTAMPADD(MINUTE, ?, NOW())",
                -Math.max(1, minutes));
        return expired + recovered;
    }

    /** 成功任务保留 7 天后清理；失败/死信保留，避免事故证据被定时任务删除。 */
    public int cleanupDone(int days) {
        return jdbc.update("DELETE FROM t_ai_kb_outbox "
                        + "WHERE status = 2 AND updated_at < TIMESTAMPADD(DAY, ?, NOW())",
                -Math.max(1, days));
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pending", countStatus(STATUS_PENDING));
        out.put("processing", countStatus(STATUS_PROCESSING));
        out.put("done", countStatus(STATUS_DONE));
        out.put("dead", countStatus(STATUS_DEAD));
        Long oldest = queryLong("SELECT UNIX_TIMESTAMP(MIN(created_at)) FROM t_ai_kb_outbox "
                + "WHERE status IN (0, 1)");
        out.put("oldestPendingAgeSeconds", oldest == null ? 0 : Math.max(0, System.currentTimeMillis() / 1000 - oldest));
        Long oldestFailure = queryLong("SELECT UNIX_TIMESTAMP(MAX(updated_at)) FROM t_ai_kb_outbox WHERE status = 3");
        out.put("lastDeadAt", oldestFailure == null ? null : new java.sql.Timestamp(oldestFailure * 1000L).toString());
        return out;
    }

    private long countStatus(int status) {
        Long v = queryLong("SELECT COUNT(*) FROM t_ai_kb_outbox WHERE status = ?", status);
        return v == null ? 0 : v;
    }

    private Long queryLong(String sql, Object... args) {
        try {
            return jdbc.queryForObject(sql, Long.class, args);
        } catch (Exception e) {
            return null;
        }
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) return "unknown error";
        return value.length() > 490 ? value.substring(0, 490) : value;
    }
}
