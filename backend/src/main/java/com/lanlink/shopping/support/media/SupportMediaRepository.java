package com.lanlink.shopping.support.media;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 客服媒体持久层（JdbcTemplate）。
 *
 * <p>这里刻意不用 MyBatis-Plus：四张表都是「条件更新 + 有限列读」的窄表，裸 SQL 更能
 * 直接表达 CAS 语义（WHERE status=? 抢状态），也避免为 outbox 再造一套实体/Mapper。
 *
 * <p>所有行状态常量集中在本类，回收/对账/消费端都引用同一处，避免字面量各写一份漂移。
 */
@Repository
public class SupportMediaRepository {

    public static final String STATUS_INIT = "INIT";
    public static final String STATUS_UPLOADED = "UPLOADED";
    public static final String STATUS_PARSING = "PARSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    private static final int OUTBOX_PENDING = 0;

    private final JdbcTemplate jdbc;

    public SupportMediaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 媒体行快照（只含基座需要的列，解析结果在 P1 消费端回写） */
    public record Row(Long id, String mediaNo, Long conversationId, Long messageId, String visitorToken,
                      String kind, String objectKey, String fileName, String contentType, long fileSize,
                      String sha256, Integer width, Integer height, Integer durationMs, Integer pageCount,
                      String status, String engine, String ocrText, String asrText, String summary,
                      String entitiesJson, BigDecimal confidence, String errorMessage, int retryCount,
                      LocalDateTime expiresAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** 待投递任务（outbox 与媒体行 join 后的投影） */
    public record OutboxTask(long outboxId, long mediaId, String mediaNo, String kind,
                             String objectKey, int retryCount) {
    }

    private static final RowMapper<Row> ROW_MAPPER = (rs, i) -> new Row(
            rs.getLong("id"),
            rs.getString("media_no"),
            (Long) rs.getObject("conversation_id", Long.class),
            (Long) rs.getObject("message_id", Long.class),
            rs.getString("visitor_token"),
            rs.getString("kind"),
            rs.getString("object_key"),
            rs.getString("file_name"),
            rs.getString("content_type"),
            rs.getLong("file_size"),
            rs.getString("sha256"),
            (Integer) rs.getObject("width", Integer.class),
            (Integer) rs.getObject("height", Integer.class),
            (Integer) rs.getObject("duration_ms", Integer.class),
            (Integer) rs.getObject("page_count", Integer.class),
            rs.getString("status"),
            rs.getString("engine"),
            rs.getString("ocr_text"),
            rs.getString("asr_text"),
            rs.getString("summary"),
            rs.getString("entities_json"),
            rs.getBigDecimal("confidence"),
            rs.getString("error_message"),
            rs.getInt("retry_count"),
            rs.getObject("expires_at", LocalDateTime.class),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class));

    // ==================== 媒体行 ====================

    /** 预签名前落 INIT 行；media_no 唯一索引兜底，重复即抛，由调用方释放配额 */
    public long insertInit(String mediaNo, Long conversationId, String visitorToken, String kind,
                           String objectKey, String fileName, String contentType, long declaredSize,
                           LocalDateTime expiresAt) {
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO t_support_media
                      (media_no, conversation_id, visitor_token, kind, object_key, file_name,
                       content_type, file_size, status, expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, mediaNo);
            if (conversationId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, conversationId);
            }
            ps.setString(3, visitorToken);
            ps.setString(4, kind);
            ps.setString(5, objectKey);
            ps.setString(6, fileName);
            ps.setString(7, contentType);
            ps.setLong(8, declaredSize);
            ps.setString(9, STATUS_INIT);
            ps.setObject(10, expiresAt);
            ps.setObject(11, now);
            ps.setObject(12, now);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    /** 消费端按主键定位：MQ 载荷里带的是 mediaId，比单号少一次字符串比较，也不再依赖单号唯一性 */
    public Optional<Row> findById(long id) {
        List<Row> rows = jdbc.query("SELECT * FROM t_support_media WHERE id = ?", ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    public Optional<Row> findByMediaNo(String mediaNo) {
        List<Row> rows = jdbc.query("SELECT * FROM t_support_media WHERE media_no = ?", ROW_MAPPER, mediaNo);
        return rows.stream().findFirst();
    }

    public Optional<Row> findByMediaNoAndVisitor(String mediaNo, String visitorToken) {
        List<Row> rows = jdbc.query(
                "SELECT * FROM t_support_media WHERE media_no = ? AND visitor_token = ?",
                ROW_MAPPER, mediaNo, visitorToken);
        return rows.stream().findFirst();
    }

    /**
     * 批量按单号 + 访客归属查询：客服一次问答可能带多份附件，逐条查会放大数据库往返。
     * 占位符只按数量生成，单号始终走绑定参数——外部传入的 mediaNo 绝不拼进 SQL。
     */
    public List<Row> findByMediaNosAndVisitor(List<String> mediaNos, String visitorToken) {
        if (mediaNos == null || mediaNos.isEmpty() || visitorToken == null || visitorToken.isBlank()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(mediaNos.size(), "?"));
        Object[] args = new Object[mediaNos.size() + 1];
        for (int i = 0; i < mediaNos.size(); i++) {
            args[i] = mediaNos.get(i);
        }
        args[mediaNos.size()] = visitorToken;
        return jdbc.query(
                "SELECT * FROM t_support_media WHERE media_no IN (" + placeholders + ") AND visitor_token = ?",
                ROW_MAPPER, args);
    }

    /** 回填核验结果；只有仍处于 INIT 的行会被改写，重复确认不会覆盖后续状态 */
    public int markUploaded(String mediaNo, long actualSize, String sha256, String contentType) {
        return jdbc.update("""
                UPDATE t_support_media
                   SET status = ?, file_size = ?, sha256 = ?, content_type = ?
                 WHERE media_no = ? AND status = ?
                """, STATUS_UPLOADED, actualSize, sha256, contentType, mediaNo, STATUS_INIT);
    }

    /** CAS 置终态/中间态；失败返回 0（并发下已有别的请求改过） */
    public int updateStatus(long id, String fromStatus, String toStatus, String errorMessage) {
        return jdbc.update(
                "UPDATE t_support_media SET status = ?, error_message = ? WHERE id = ? AND status = ?",
                toStatus, errorMessage, id, fromStatus);
    }

    /**
     * 解析认领：只有把行从 UPLOADED 改成 PARSING 的那个消费者才继续。
     * 重复投递、并发消费与「已解析完成后再被重投」的旧消息都被这一条 CAS 挡在外面。
     */
    public int markParsing(long id) {
        return jdbc.update(
                "UPDATE t_support_media SET status = ?, error_message = NULL WHERE id = ? AND status = ?",
                STATUS_PARSING, id, STATUS_UPLOADED);
    }

    /** 解析成功回写；仅当行仍处于 PARSING 时生效，避免覆盖回收任务已改写的终态。
     *  entitiesJson：P2 起携带 partial 截断标记（null 时不清空既有值） */
    public int markReady(long id, String ocrText, String asrText, String summary, String engine,
                         BigDecimal confidence, Integer pageCount, Integer durationMs, String entitiesJson) {
        return jdbc.update("""
                UPDATE t_support_media
                   SET status = ?, ocr_text = ?, asr_text = ?, summary = ?, engine = ?, confidence = ?,
                       page_count = ?, duration_ms = ?, entities_json = COALESCE(?, entities_json),
                       error_message = NULL
                 WHERE id = ? AND status = ?
                """, STATUS_READY, ocrText, asrText, summary, engine, confidence, pageCount, durationMs,
                entitiesJson, id, STATUS_PARSING);
    }

    /** 未达重试上限：回置 UPLOADED，让重投（MQ 重试或对账任务）能重新认领 */
    public int markRetry(long id, int retryCount, String errorMessage) {
        return jdbc.update("""
                UPDATE t_support_media
                   SET status = ?, retry_count = ?, error_message = ?
                 WHERE id = ? AND status = ?
                """, STATUS_UPLOADED, retryCount, truncate(errorMessage, 500), id, STATUS_PARSING);
    }

    /** 达到重试上限：置 FAILED 并留下最后一条错误，便于客服侧展示「为什么没解析出来」 */
    public int markFailed(long id, int retryCount, String errorMessage) {
        return jdbc.update("""
                UPDATE t_support_media
                   SET status = ?, retry_count = ?, error_message = ?
                 WHERE id = ? AND status = ?
                """, STATUS_FAILED, retryCount, truncate(errorMessage, 500), id, STATUS_PARSING);
    }

    public List<Row> findExpiredInit(LocalDateTime createdBefore, int limit) {
        return jdbc.query(
                "SELECT * FROM t_support_media WHERE status = ? AND created_at < ? ORDER BY id LIMIT ?",
                ROW_MAPPER, STATUS_INIT, createdBefore, limit);
    }

    /** 软删：到期行置 EXPIRED，保留错误说明便于审计「为什么没解析」 */
    public int markExpiredById(long id, String reason) {
        return jdbc.update(
                "UPDATE t_support_media SET status = ?, error_message = COALESCE(error_message, ?) "
                        + "WHERE id = ? AND status <> ?",
                STATUS_EXPIRED, reason, id, STATUS_EXPIRED);
    }

    public List<Row> findDueForSoftDelete(LocalDateTime expireBefore, int limit) {
        return jdbc.query(
                "SELECT * FROM t_support_media WHERE expires_at IS NOT NULL AND expires_at < ? "
                        + "AND status <> ? ORDER BY id LIMIT ?",
                ROW_MAPPER, expireBefore, STATUS_EXPIRED, limit);
    }

    public List<Row> findPurgeableExpired(LocalDateTime expiredBefore, int limit) {
        return jdbc.query(
                "SELECT * FROM t_support_media WHERE status = ? AND updated_at < ? ORDER BY id LIMIT ?",
                ROW_MAPPER, STATUS_EXPIRED, expiredBefore, limit);
    }

    /** 物理删除：先删 outbox 再删媒体行，避免留下指向空行的待办 */
    public void deleteMedia(long id) {
        jdbc.update("DELETE FROM t_support_media_outbox WHERE media_id = ?", id);
        jdbc.update("DELETE FROM t_support_media WHERE id = ?", id);
    }

    // ==================== usage 账本 ====================

    /** 计量账本唯一写入路径（SupportLlmUsageRecorder 专用）：capability=AUDIO 记音频秒数与成本 */
    public void insertLlmUsage(String visitorToken, String capability, String model,
                               BigDecimal audioSeconds, long costMicros, Long mediaId) {
        jdbc.update("""
                INSERT INTO t_support_llm_usage
                  (visitor_token, channel, model, capability, audio_seconds, cost_micros, media_id, created_at)
                VALUES (?, 'perception', ?, ?, ?, ?, ?, NOW())
                """, visitorToken, model, capability, audioSeconds, costMicros, mediaId);
    }

    // ==================== outbox ====================

    /** 幂等写待办：已投递的任务被重投时重置为待投递（对账任务用得上） */
    public void insertOutbox(long mediaId) {
        jdbc.update("""
                INSERT INTO t_support_media_outbox (media_id, status, retry_count, next_retry_at)
                VALUES (?, ?, 0, NOW())
                ON DUPLICATE KEY UPDATE status = ?, next_retry_at = NOW(), locked_at = NULL
                """, mediaId, OUTBOX_PENDING, OUTBOX_PENDING);
    }

    public List<OutboxTask> findDueOutbox(int limit) {
        return jdbc.query("""
                SELECT o.id AS outbox_id, o.retry_count AS outbox_retry,
                       m.id AS media_id, m.media_no, m.kind, m.object_key
                  FROM t_support_media_outbox o
                  JOIN t_support_media m ON m.id = o.media_id
                 WHERE o.status = 0 AND o.next_retry_at <= NOW()
                 ORDER BY o.id
                 LIMIT ?
                """, (rs, i) -> new OutboxTask(
                rs.getLong("outbox_id"),
                rs.getLong("media_id"),
                rs.getString("media_no"),
                rs.getString("kind"),
                rs.getString("object_key"),
                rs.getInt("outbox_retry")), limit);
    }

    /** CAS 锁定：多实例下只有一条 UPDATE 成功；锁定超过 5 分钟视为持有者已崩溃 */
    public boolean lockOutbox(long outboxId) {
        return jdbc.update(
                "UPDATE t_support_media_outbox SET locked_at = NOW() "
                        + "WHERE id = ? AND (locked_at IS NULL OR locked_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE))",
                outboxId) == 1;
    }

    public void markOutboxSent(long outboxId) {
        jdbc.update("UPDATE t_support_media_outbox SET status = 1, locked_at = NULL, last_error = NULL WHERE id = ?",
                outboxId);
    }

    public void markOutboxFailed(long outboxId, LocalDateTime nextRetryAt, String error) {
        jdbc.update("""
                UPDATE t_support_media_outbox
                   SET retry_count = retry_count + 1, next_retry_at = ?, locked_at = NULL, last_error = ?
                 WHERE id = ?
                """, nextRetryAt, truncate(error, 500), outboxId);
    }

    /** 确认成功但 outbox 未投递的媒体（对账任务重投；已投递的不再重复） */
    public List<Long> findUploadedNotEnqueued(LocalDateTime uploadedBefore, int limit) {
        return jdbc.queryForList("""
                SELECT m.id
                  FROM t_support_media m
                  LEFT JOIN t_support_media_outbox o ON o.media_id = m.id
                 WHERE m.status = ? AND m.updated_at < ?
                   AND (o.id IS NULL OR o.status = 0)
                 ORDER BY m.id
                 LIMIT ?
                """, Long.class, STATUS_UPLOADED, uploadedBefore, limit);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
