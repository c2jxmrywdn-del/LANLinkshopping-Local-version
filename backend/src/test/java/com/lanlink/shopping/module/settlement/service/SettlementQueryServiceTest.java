package com.lanlink.shopping.module.settlement.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.settlement.dto.SettlementViews.BatchVO;
import com.lanlink.shopping.module.settlement.dto.SettlementViews.EarningVO;
import com.lanlink.shopping.module.settlement.dto.SettlementViews.SummaryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结算查询服务单测：锁住两处易错实现（计划期明确点名的修正点）。
 *
 * <ol>
 *   <li><b>分页</b>：COUNT 必须在「未加 LIMIT/OFFSET」的同一段 SQL 上包一层，
 *       否则总数会被分页条件截断（第 2 页 total 变成 0）；行查询才追加 LIMIT/OFFSET。</li>
 *   <li><b>adminDetail</b>：批次必须经真正的 {@link RowMapper} 逐列映射（不是返回 null 的占位），
 *       且批次不存在时 404 而不是抛 EmptyResultDataAccessException。</li>
 * </ol>
 * 另覆盖 summary 的冲正扣减口径（REVERSE.net 为负，加进可结算池）。
 */
class SettlementQueryServiceTest {

    private JdbcTemplate jdbc;
    private SettlementQueryService svc;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        svc = new SettlementQueryService(jdbc);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 桩 jdbc.query(String, RowMapper, Object...)：两处调用共用同一 settleId，靠 thenAnswer 内 SQL 区分。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubQuery(QueryAnswer answer) {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(inv -> {
            try {
                return answer.answer(inv);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** 允许抛 SQLException 的桩回调（RowMapper.mapRow 声明了受检异常）。 */
    private interface QueryAnswer {
        List answer(org.mockito.invocation.InvocationOnMock inv) throws Exception;
    }

    @Test
    void paging_counts_without_limit_and_rows_with_limit() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(42L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                row("settle_id", 9L, "settle_no", "SETTLE1-7", "period_start", null, "period_end", null,
                        "total_net", bd("100.00"), "commission_total", bd("7.00"), "item_count", 2,
                        "status", "DRAFT", "pay_voucher", null, "create_time", null)));

        Map<String, Object> out = svc.merchantBatches(7L, 2, 10);

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(countSql.capture(), eq(Long.class), any(Object[].class));
        assertTrue(countSql.getValue().startsWith("SELECT COUNT(*) FROM ("),
                "总数必须包一层子查询: " + countSql.getValue());
        assertFalse(countSql.getValue().toUpperCase().contains("LIMIT"),
                "COUNT SQL 不得含 LIMIT（否则总数被分页截断）: " + countSql.getValue());

        ArgumentCaptor<String> rowsSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(rowsSql.capture(), any(Object[].class));
        assertTrue(rowsSql.getValue().toUpperCase().contains("LIMIT ? OFFSET ?"),
                "行查询必须带 LIMIT/OFFSET: " + rowsSql.getValue());

        assertEquals(42L, out.get("total"));
        assertEquals(2L, out.get("current"));
        assertEquals(10L, out.get("size"));
        @SuppressWarnings("unchecked")
        List<Object> records = (List<Object>) out.get("records");
        assertEquals(1, records.size());
        BatchVO vo = (BatchVO) records.get(0);
        assertEquals(9L, vo.settleId());
        assertEquals("DRAFT", vo.status());
        assertEquals(2, vo.itemCount());
    }

    @Test
    void paging_second_page_keeps_full_total() {
        // 回归点：老实现先拼 LIMIT 再 COUNT，第 2 页总数会退化成当前页条数
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(3L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        Map<String, Object> out = svc.adminList(3, 10, 7L, "PAID");
        assertEquals(3L, out.get("total"));
        @SuppressWarnings("unchecked")
        List<Object> records = (List<Object>) out.get("records");
        assertTrue(records.isEmpty());
    }

    @Test
    void adminDetail_maps_batch_through_real_rowmapper() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("settle_id")).thenReturn(11L);
        when(rs.getString("settle_no")).thenReturn("SETTLE20260101-7");
        Timestamp ps = Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0));
        Timestamp pe = Timestamp.valueOf(LocalDateTime.of(2026, 2, 1, 0, 0));
        when(rs.getTimestamp("period_start")).thenReturn(ps);
        when(rs.getTimestamp("period_end")).thenReturn(pe);
        when(rs.getBigDecimal("total_net")).thenReturn(bd("93.00"));
        when(rs.getBigDecimal("commission_total")).thenReturn(bd("7.00"));
        when(rs.getInt("item_count")).thenReturn(1);
        when(rs.getString("status")).thenReturn("APPROVED");
        when(rs.getString("pay_voucher")).thenReturn("V-1");
        when(rs.getTimestamp("create_time")).thenReturn(ps);

        // 批次查询走 RowMapper：用 thenAnswer 真正调用 mapper，验证逐列映射不是空实现
        stubQuery(inv -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = inv.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        });

        Map<String, Object> out = svc.adminDetail(11L);

        BatchVO batch = (BatchVO) out.get("batch");
        assertEquals(11L, batch.settleId());
        assertEquals("SETTLE20260101-7", batch.settleNo());
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), batch.periodStart());
        assertEquals(LocalDateTime.of(2026, 2, 1, 0, 0), batch.periodEnd());
        assertEquals(0, batch.totalNet().compareTo(bd("93.00")));
        assertEquals(0, batch.commissionTotal().compareTo(bd("7.00")));
        assertEquals(1, batch.itemCount());
        assertEquals("APPROVED", batch.status());
        assertEquals("V-1", batch.payVoucher());
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), batch.createTime());
    }

    @Test
    void adminDetail_items_are_snapshot_only() {
        stubQuery(inv -> {
            String sql = inv.getArgument(0);
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = inv.getArgument(1);
            if (sql.contains("t_merchant_settlement_item")) {
                return List.of(new EarningVO(3L, "ORD-1", 11L, "EARN", null, null, bd("-9.00"), null, null, null));
            }
            return List.of(new BatchVO(1L, "S1", null, null, bd("10.00"), bd("1.00"), 1,
                    "DRAFT", null, null));
        });
        Map<String, Object> out = svc.adminDetail(1L);
        assertTrue(out.containsKey("batch"));
        assertTrue(out.containsKey("items"));
        @SuppressWarnings("unchecked")
        List<EarningVO> items = (List<EarningVO>) out.get("items");
        assertEquals(1, items.size());
        assertEquals(3L, items.get(0).earningId());
        assertNull(items.get(0).grossAmount(), "明细只存快照，gross/commission/status 不在明细表");
        assertEquals(0, items.get(0).netAmount().compareTo(bd("-9.00")));
    }

    @Test
    void adminDetail_unknown_batch_is_404() {
        stubQuery(inv -> List.of());
        BusinessException e = assertThrows(BusinessException.class, () -> svc.adminDetail(404L));
        assertEquals(404, e.getCode());
    }

    @Test
    void summary_offsets_available_pool_by_reversals() {
        // pending 10 / available 100 / settled 200 / commission 21
        // 未结转冲正 -30（可结算池扣减） / 已结转冲正 -20（已结算口径扣减，与批次对账）
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("status='PENDING'")) {
                        return bd("10.00");
                    }
                    if (sql.contains("type='EARN' AND status='AVAILABLE'")) {
                        return bd("100.00");
                    }
                    if (sql.contains("type='EARN' AND status IN ('SETTLED','PAID')")) {
                        return bd("200.00");
                    }
                    if (sql.contains("type='REVERSE'") && sql.contains("NOT EXISTS")) {
                        return bd("-30.00"); // 未结转冲正 → 扣可结算池
                    }
                    if (sql.contains("type='REVERSE'") && sql.contains("EXISTS")) {
                        return bd("-20.00"); // 已结转冲正 → 扣已结算口径
                    }
                    return bd("21.00"); // 累计佣金
                });

        SummaryVO vo = svc.merchantSummary(7L);

        assertEquals(0, vo.pending().compareTo(bd("10.00")));
        assertEquals(0, vo.available().compareTo(bd("70.00")), "可结算池 = 100 + (-30)");
        assertEquals(0, vo.settled().compareTo(bd("180.00")), "已结算 = 200 + (-20) 已结转冲正");
        assertEquals(0, vo.totalCommission().compareTo(bd("21.00")));
    }

    @Test
    void summary_available_clamped_non_negative() {
        // 未结转冲正使 available 暂为负（商户实欠），展示侧钳到 0
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("type='REVERSE'") && sql.contains("NOT EXISTS")) {
                        return bd("-50.00");
                    }
                    return BigDecimal.ZERO;
                });
        SummaryVO vo = svc.merchantSummary(7L);
        assertEquals(0, vo.available().compareTo(BigDecimal.ZERO), "可结算池不得为负");
    }

    @Test
    void summary_reversal_deduction_excludes_already_carried_batches() {
        // 回归点：已作为负向结转纳入批次的 REVERSE 不得再从可结算池里扣一次（否则池被永久压低）
        ArgumentCaptor<String> sqls = ArgumentCaptor.forClass(String.class);
        when(jdbc.queryForObject(sqls.capture(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(BigDecimal.ZERO);
        svc.merchantSummary(7L);
        String reversalSql = sqls.getAllValues().stream()
                .filter(s -> s.contains("type='REVERSE'"))
                .findFirst()
                .orElseThrow();
        assertTrue(reversalSql.contains("NOT EXISTS"), reversalSql);
        assertTrue(reversalSql.contains("t_merchant_settlement_item"), reversalSql);
        assertTrue(reversalSql.contains("orig.status IN ('AVAILABLE','SETTLED','PAID')"), reversalSql);
    }

    @Test
    void summary_null_sums_degrade_to_zero() {
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class))).thenReturn(null);
        SummaryVO vo = svc.merchantSummary(7L);
        assertEquals(0, vo.pending().compareTo(BigDecimal.ZERO));
        assertEquals(0, vo.available().compareTo(BigDecimal.ZERO));
        assertEquals(0, vo.settled().compareTo(BigDecimal.ZERO));
        assertEquals(0, vo.totalCommission().compareTo(BigDecimal.ZERO));
    }

    @Test
    void earnings_mapper_nullable_available_at_stays_null() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                row("earning_id", 5L, "order_no", "ORD-1", "item_id", 11L, "type", "EARN",
                        "gross_amount", bd("100.00"), "commission_amount", bd("7.00"),
                        "net_amount", bd("93.00"), "status", "PENDING",
                        "available_at", null, "create_time", Timestamp.valueOf("2026-01-01 00:00:00"))));
        Map<String, Object> out = svc.merchantEarnings(7L, 1, 10, null);
        @SuppressWarnings("unchecked")
        List<Object> records = (List<Object>) out.get("records");
        EarningVO vo = (EarningVO) records.get(0);
        assertEquals(5L, vo.earningId());
        assertEquals("ORD-1", vo.orderNo());
        assertNull(vo.availableAt());
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), vo.createTime());
    }
}
