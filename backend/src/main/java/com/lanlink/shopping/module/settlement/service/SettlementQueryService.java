package com.lanlink.shopping.module.settlement.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.settlement.dto.SettlementViews.BatchVO;
import com.lanlink.shopping.module.settlement.dto.SettlementViews.EarningVO;
import com.lanlink.shopping.module.settlement.dto.SettlementViews.SummaryVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 结算只读查询：商户端（汇总 / 台账 / 批次）与管理端（批次列表 / 批次详情）。
 *
 * <p>全部走 {@link JdbcTemplate} 直查，不引入新依赖也不复用 Mapper 的条件构造：
 * 分页用 {@code LIMIT ? OFFSET ?}，总数用「同一段 WHERE-SQL 包一层 COUNT(*)」，
 * 两者共用同一份参数数组，避免「先拼 LIMIT 再 COUNT」这种把分页条件算进总数的经典错。
 *
 * <p>金额口径：DECIMAL(14,2) → BigDecimal 原样透出，不做二次舍入（汇总列在 SQL 侧 COALESCE）。
 * 平台只做记账与对账（二清红线），本服务全为只读。
 */
@Service
public class SettlementQueryService {

    /** 批次列清单（三处查询共用，防止列名漂移导致映射静默变成 null）。 */
    private static final String BATCH_COLUMNS =
            "settle_id, settle_no, period_start, period_end, total_net, commission_total, "
                    + "item_count, status, pay_voucher, create_time";

    private static final String EARNING_COLUMNS =
            "earning_id, order_no, item_id, type, gross_amount, commission_amount, net_amount, "
                    + "status, available_at, create_time";

    private final JdbcTemplate jdbc;

    public SettlementQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 商户四池汇总。
     *
     * <p>{@code available} 按设计 §3.4 的口径扣减冲正：可结算池 = Σ(AVAILABLE EARN.net)
     * − Σ(REVERSE.net，其被冲 EARN 为 AVAILABLE 或 SETTLED/PAID)。REVERSE.net 本身为负，故这里是「加上负数」。
     * 「已结算」池含 {@code PAID}：收益行随批次打款进入终态后仍属已结算（与批次 total_net 对账），
     * 只是不再计入可提余额（见 MerchantFundLockService）。
     *
     * <p>扣减范围刻意加 {@code NOT EXISTS(t_merchant_settlement_item)}：已被某个批次作为负向结转
     * 纳入过的 REVERSE 已经体现在那批的 {@code total_net} 里，若仍在这里持续扣减，可结算池会被
     * 永久压低成负数（同一笔冲正扣两次）。未结转的冲正才需要从可结算池里预先扣掉。
     */
    public SummaryVO merchantSummary(Long merId) {
        BigDecimal pending = sum(
                "SELECT COALESCE(SUM(net_amount),0) FROM t_merchant_earning "
                        + "WHERE mer_id=? AND type='EARN' AND status='PENDING'", merId);
        BigDecimal available = sum(
                "SELECT COALESCE(SUM(net_amount),0) FROM t_merchant_earning "
                        + "WHERE mer_id=? AND type='EARN' AND status='AVAILABLE'", merId);
        BigDecimal settled = sum(
                "SELECT COALESCE(SUM(net_amount),0) FROM t_merchant_earning "
                        + "WHERE mer_id=? AND type='EARN' AND status IN ('SETTLED','PAID')", merId);
        // 冲正扣减可结算池：只算「被冲 EARN 已 AVAILABLE/SETTLED/PAID 且尚未结转进任何批次」的行
        // （PENDING 的冲正已在 pending 池里自然抵消）
        BigDecimal reversed = sum(
                "SELECT COALESCE(SUM(e.net_amount),0) FROM t_merchant_earning e "
                        + "JOIN t_merchant_earning orig ON e.ref_earning_id=orig.earning_id "
                        + "WHERE e.mer_id=? AND e.type='REVERSE' "
                        + "AND orig.status IN ('AVAILABLE','SETTLED','PAID') "
                        + "AND NOT EXISTS(SELECT 1 FROM t_merchant_settlement_item si "
                        + "                WHERE si.earning_id=e.earning_id)", merId);
        // 已结转进批次的冲正（其原 EARN 已 SETTLED/PAID 且已被某批次纳入）——这些负额已计入对应批次 total_net，
        // 故「已结算」口径须同口径扣减，才能与批次明细对账（否则 settled 高估、与 batch.total_net 不吻合）。
        BigDecimal settledReversed = sum(
                "SELECT COALESCE(SUM(e.net_amount),0) FROM t_merchant_earning e "
                        + "JOIN t_merchant_earning orig ON e.ref_earning_id=orig.earning_id "
                        + "WHERE e.mer_id=? AND e.type='REVERSE' AND orig.status IN ('SETTLED','PAID') "
                        + "AND EXISTS(SELECT 1 FROM t_merchant_settlement_item si "
                        + "            WHERE si.earning_id=e.earning_id)", merId);
        // 平台累计佣金含冲正回冲（REVERSE.commission_amount 为负），故可能小于 0
        BigDecimal totalCommission = sum(
                "SELECT COALESCE(SUM(commission_amount),0) FROM t_merchant_earning WHERE mer_id=?", merId);
        // REVERSE.net 本身为负，直接相加即「扣减」；min(ZERO) 保证脏数据不抬高池；
        // 未结转冲正可能使 available 暂时为负（商户实欠），展示侧钳到 0，欠额随下次 generate 结转。
        BigDecimal availableNet = available.add(reversed.min(BigDecimal.ZERO)).max(BigDecimal.ZERO);
        BigDecimal settledNet = settled.add(settledReversed);
        return new SummaryVO(scale(pending), scale(availableNet),
                scale(settledNet), scale(totalCommission));
    }

    /** 商户收益台账分页（含 EARN/REVERSE 两类行；status 可选过滤）。 */
    public Map<String, Object> merchantEarnings(Long merId, long current, long size, String status) {
        StringBuilder where = new StringBuilder(" FROM t_merchant_earning WHERE mer_id=? ");
        List<Object> args = new ArrayList<>();
        args.add(merId);
        if (status != null && !status.isBlank()) {
            where.append("AND status=? ");
            args.add(status.trim());
        }
        return page("SELECT " + EARNING_COLUMNS + where, "ORDER BY earning_id DESC",
                current, size, args, SettlementQueryService::earningMapper);
    }

    /** 商户结算批次分页。 */
    public Map<String, Object> merchantBatches(Long merId, long current, long size) {
        return page("SELECT " + BATCH_COLUMNS + " FROM t_merchant_settlement WHERE mer_id=? ",
                "ORDER BY settle_id DESC", current, size,
                List.of(merId), SettlementQueryService::batchMapper);
    }

    /** 管理端批次分页（merId / status 可选过滤）。 */
    public Map<String, Object> adminList(long current, long size, Long merId, String status) {
        StringBuilder where = new StringBuilder(" FROM t_merchant_settlement WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (merId != null) {
            where.append("AND mer_id=? ");
            args.add(merId);
        }
        if (status != null && !status.isBlank()) {
            where.append("AND status=? ");
            args.add(status.trim());
        }
        return page("SELECT " + BATCH_COLUMNS + where, "ORDER BY settle_id DESC",
                current, size, args, SettlementQueryService::batchMapper);
    }

    /**
     * 管理端批次详情：{@code {"batch": BatchVO, "items": List<EarningVO>}}。
     *
     * <p>明细是纳入时点的快照（order_no / item_id / type / net_amount），台账侧的
     * gross/commission/status 不在明细表里，故这些字段为 null（前端按「明细只展示快照」渲染）。
     * 批次不存在时 404，而不是让 queryForObject 抛 EmptyResultDataAccessException
     * 被全局处理器脱敏成 500。
     */
    public Map<String, Object> adminDetail(Long settleId) {
        List<BatchVO> found = jdbc.query(
                "SELECT " + BATCH_COLUMNS + " FROM t_merchant_settlement WHERE settle_id=?",
                SettlementQueryService::batchRowMapper, settleId);
        if (found.isEmpty()) {
            throw new BusinessException(404, "批次不存在");
        }
        List<EarningVO> items = jdbc.query(
                "SELECT earning_id, order_no, item_id, type, net_amount "
                        + "FROM t_merchant_settlement_item WHERE settle_id=? ORDER BY id",
                (rs, i) -> new EarningVO(rs.getLong("earning_id"), rs.getString("order_no"),
                        rs.getLong("item_id"), rs.getString("type"),
                        null, null, rs.getBigDecimal("net_amount"), null, null, null),
                settleId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batch", found.get(0));
        out.put("items", items);
        return out;
    }

    /**
     * 通用分页：{@code selectSql} 只含 SELECT 列 + FROM + WHERE（不含 ORDER BY / LIMIT），
     * 总数在**未分页**的同一段 SQL 上包一层 COUNT，行查询才追加 ORDER BY + LIMIT/OFFSET。
     */
    private Map<String, Object> page(String selectSql, String orderBy, long current, long size,
                                     List<Object> args, Function<Map<String, Object>, Object> mapper) {
        long safeSize = size <= 0 ? 10 : size;
        long safeCurrent = current <= 0 ? 1 : current;
        long offset = Math.max(0, (safeCurrent - 1) * safeSize);

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + selectSql + ") t", Long.class,
                args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList(
                selectSql + " " + orderBy + " LIMIT ? OFFSET ?",
                append(args, safeSize, offset));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", rows.stream().map(mapper).toList());
        out.put("total", total == null ? 0L : total);
        out.put("current", safeCurrent);
        out.put("size", safeSize);
        return out;
    }

    private static Object[] append(List<Object> args, Object... more) {
        Object[] r = Arrays.copyOf(args.toArray(), args.size() + more.length);
        System.arraycopy(more, 0, r, args.size(), more.length);
        return r;
    }

    private static Object earningMapper(Map<String, Object> r) {
        return new EarningVO(toLong(r.get("earning_id")), (String) r.get("order_no"),
                toLong(r.get("item_id")), (String) r.get("type"),
                toMoney(r.get("gross_amount")), toMoney(r.get("commission_amount")),
                toMoney(r.get("net_amount")), (String) r.get("status"),
                toLdt(r.get("available_at")), toLdt(r.get("create_time")));
    }

    private static Object batchMapper(Map<String, Object> r) {
        return new BatchVO(toLong(r.get("settle_id")), (String) r.get("settle_no"),
                toLdt(r.get("period_start")), toLdt(r.get("period_end")),
                toMoney(r.get("total_net")), toMoney(r.get("commission_total")),
                toInt(r.get("item_count")), (String) r.get("status"),
                (String) r.get("pay_voucher"), toLdt(r.get("create_time")));
    }

    /**
     * 批次 RowMapper（{@code adminDetail} 用）：逐列从 ResultSet 读到 LinkedHashMap，
     * 再交给与分页路径同一个 {@link #batchMapper}，保证两条读路径的字段映射只有一处实现。
     */
    private static BatchVO batchRowMapper(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settle_id", rs.getLong("settle_id"));
        m.put("settle_no", rs.getString("settle_no"));
        m.put("period_start", rs.getTimestamp("period_start"));
        m.put("period_end", rs.getTimestamp("period_end"));
        m.put("total_net", rs.getBigDecimal("total_net"));
        m.put("commission_total", rs.getBigDecimal("commission_total"));
        m.put("item_count", rs.getInt("item_count"));
        m.put("status", rs.getString("status"));
        m.put("pay_voucher", rs.getString("pay_voucher"));
        m.put("create_time", rs.getTimestamp("create_time"));
        return (BatchVO) batchMapper(m);
    }

    private BigDecimal sum(String sql, Long merId) {
        BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class, merId);
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal toMoney(Object v) {
        if (v == null) {
            return null;
        }
        return (v instanceof BigDecimal) ? (BigDecimal) v : new BigDecimal(v.toString());
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static Integer toInt(Object v) {
        return v == null ? null : ((Number) v).intValue();
    }

    /** JDBC 侧 DATETIME 可能是 Timestamp，也可能被驱动映射成 LocalDateTime，两种都兜住。 */
    private static LocalDateTime toLdt(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (o instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (o instanceof java.util.Date d) {
            return LocalDateTime.ofInstant(d.toInstant(), java.time.ZoneId.systemDefault());
        }
        return null;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
