package com.lanlink.shopping.module.marketingimage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 营销图任务读写。
 *
 * <p>这里用 JdbcTemplate 而不是 MyBatis-Plus：表结构固定、只有 4 类操作（插入/查单/分页/更新状态），
 * 用 SQL 直写比引入 Entity + Mapper + XML 更小的维护面，也便于单测用 mock 替身。
 */
@Repository
public class MarketingImageTaskRepository {

    private final JdbcTemplate jdbc;

    public MarketingImageTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 任务行快照（不可变，跨线程传递安全） */
    public record TaskRow(long id, String taskNo, String ownerType, Long ownerId, String prompt,
                          String negativePrompt, String model, String size, String status,
                          String objectKey, String imageUrl, String errorMessage, long costMicros,
                          LocalDateTime createdAt, LocalDateTime updatedAt) { }

    private static final RowMapper<TaskRow> ROW_MAPPER = (ResultSet rs, int rowNum) -> new TaskRow(
            rs.getLong("id"),
            rs.getString("task_no"),
            rs.getString("owner_type"),
            rs.getObject("owner_id") == null ? null : rs.getLong("owner_id"),
            rs.getString("prompt"),
            rs.getString("negative_prompt"),
            rs.getString("model"),
            rs.getString("size"),
            rs.getString("status"),
            rs.getString("object_key"),
            rs.getString("image_url"),
            rs.getString("error_message"),
            rs.getLong("cost_micros"),
            toLocalDateTime(rs.getTimestamp("created_at")),
            toLocalDateTime(rs.getTimestamp("updated_at")));

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    public void insert(String taskNo, String ownerType, Long ownerId, String prompt,
                       String negativePrompt, String model, String size) {
        jdbc.update("""
                INSERT INTO t_marketing_image_task
                  (task_no, owner_type, owner_id, prompt, negative_prompt, model, size,
                   status, cost_micros, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, NOW(), NOW())
                """, taskNo, ownerType, ownerId, prompt, negativePrompt, model, size);
    }

    public Optional<TaskRow> findByTaskNo(String taskNo) {
        List<TaskRow> list = jdbc.query(
                "SELECT * FROM t_marketing_image_task WHERE task_no = ?", ROW_MAPPER, taskNo);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 分页查询；ownerType 为 null 表示管理员看全部。
     * 排序固定 created_at DESC, id DESC，保证同一秒插入的多条任务顺序稳定（分页不漏不重）。
     */
    public List<TaskRow> page(String ownerType, Long ownerId, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM t_marketing_image_task");
        List<Object> args = new ArrayList<>();
        appendOwnerWhere(sql, args, ownerType, ownerId);
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public long count(String ownerType, Long ownerId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM t_marketing_image_task");
        List<Object> args = new ArrayList<>();
        appendOwnerWhere(sql, args, ownerType, ownerId);
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    private static void appendOwnerWhere(StringBuilder sql, List<Object> args, String ownerType, Long ownerId) {
        if (ownerType == null) return;
        sql.append(" WHERE owner_type = ? AND owner_id = ?");
        args.add(ownerType);
        args.add(ownerId);
    }

    /** 近 30 天窗口统计：让前端能给出「近 30 天生成/成功/花费」的可核对提示 */
    public record WindowStats(long total, long success, long costMicros) { }

    public WindowStats statsLast30Days(String ownerType, Long ownerId) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) AS total, "
                        + "COALESCE(SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS success_count, "
                        + "COALESCE(SUM(cost_micros), 0) AS cost "
                        + "FROM t_marketing_image_task WHERE created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)");
        List<Object> args = new ArrayList<>();
        if (ownerType != null) {
            sql.append(" AND owner_type = ? AND owner_id = ?");
            args.add(ownerType);
            args.add(ownerId);
        }
        return jdbc.queryForObject(sql.toString(), (rs, rowNum) -> new WindowStats(
                rs.getLong("total"), rs.getLong("success_count"), rs.getLong("cost")), args.toArray());
    }

    /** 当月已花费成本（微元）：预算闸与前端预算提示共用这一个口径 */
    public long sumCostMicrosThisMonth() {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(cost_micros), 0) FROM t_marketing_image_task "
                        + "WHERE created_at >= DATE_FORMAT(CURDATE(), '%Y-%m-01')",
                Long.class);
        return sum == null ? 0L : sum;
    }

    public void markSuccess(String taskNo, String objectKey, String imageUrl, long costMicros) {
        jdbc.update("""
                UPDATE t_marketing_image_task
                   SET status = 'SUCCESS', object_key = ?, image_url = ?, error_message = NULL,
                       cost_micros = ?, updated_at = NOW()
                 WHERE task_no = ?
                """, objectKey, imageUrl, costMicros, taskNo);
    }

    public void markFailed(String taskNo, String errorMessage) {
        jdbc.update("""
                UPDATE t_marketing_image_task
                   SET status = 'FAILED', error_message = ?, updated_at = NOW()
                 WHERE task_no = ?
                """, errorMessage, taskNo);
    }
}
