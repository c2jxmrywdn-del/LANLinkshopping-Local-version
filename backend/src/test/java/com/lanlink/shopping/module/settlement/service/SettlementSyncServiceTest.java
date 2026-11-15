package com.lanlink.shopping.module.settlement.service;

import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.settlement.config.SettlementProperties;
import com.lanlink.shopping.module.settlement.entity.MerchantEarning;
import com.lanlink.shopping.module.settlement.entity.MerchantSettlement;
import com.lanlink.shopping.module.settlement.entity.MerchantSettlementItem;
import com.lanlink.shopping.module.settlement.mapper.MerchantEarningMapper;
import com.lanlink.shopping.module.settlement.mapper.MerchantSettlementItemMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结算同步服务单测。
 *
 * <p>两部分覆盖：
 * <ol>
 *   <li><b>纯函数</b>：货值分摊（A2）/ 佣金率解析 / 佣金计算 / 退款分摊（B1）；</li>
 *   <li><b>同步步骤</b>（{@code syncAll} 三段）：用 Mockito 桩 {@link JdbcTemplate} 的查询结果，
 *       对 {@code earningMapper.insert} 与 {@code jdbc.update} 做 verify，锁住幂等、多次部分退增量、
 *       全额退状态迁移、REVERSE 复用 EARN 费率快照、唯一键冲突吞掉这五条钱相关行为。</li>
 * </ol>
 *
 * <p>桩按 service 内实际 SQL 片段匹配（{@code contains(...)}）：无参 {@code queryForList}
 * 靠 {@code order_status=2} / {@code refund_amount} 区分；带参 {@code queryForList} 靠
 * {@code FROM t_order_item i} / {@code type='EARN'} 区分；三处 {@code queryForObject}
 * 靠返回类型（Long / Integer / BigDecimal）区分。
 */
class SettlementSyncServiceTest {
    private MerchantEarningMapper earningMapper;
    private MerchantSettlementItemMapper itemMapper;
    private ProductMapper productMapper;
    private MerchantMapper merchantMapper;
    private SettlementProperties props;
    private JdbcTemplate jdbc;
    private SettlementSyncService svc;

    @BeforeAll
    static void initTableInfo() {
        // 纯 mock 环境无 TableInfo 缓存，MyBatis-Plus 元信息需显式注册
        var a = new MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(a, MerchantEarning.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(a, MerchantSettlement.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(a, MerchantSettlementItem.class);
    }

    @BeforeEach
    void setup() {
        earningMapper = mock(MerchantEarningMapper.class);
        itemMapper = mock(MerchantSettlementItemMapper.class);
        productMapper = mock(ProductMapper.class);
        merchantMapper = mock(MerchantMapper.class);
        props = new SettlementProperties();
        jdbc = mock(JdbcTemplate.class);
        svc = new SettlementSyncService(earningMapper, itemMapper, productMapper, merchantMapper, props, jdbc);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** 构造 JdbcTemplate 结果行（交替 key/value）。 */
    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** recordEarnings 查不到任何已完成订单（隔离被测步骤）。 */
    private void stubNoEarningOrders() {
        when(jdbc.queryForList(contains("order_status=2"))).thenReturn(List.of());
    }

    /** reverseRefunds 查不到任何有退款的订单。 */
    private void stubNoRefundOrders() {
        when(jdbc.queryForList(contains("refund_amount"))).thenReturn(List.of());
    }

    /**
     * 单行订单：ORD-1 / 行 item_id=11 / 商品 prod_id=21 / 小计 100.00；无退款（隔离 reverseRefunds）。
     *
     * <p>recordEarnings 已从「逐单逐行查」改为「批量预取」：订单行、商品商户归属、
     * 商户佣金率、已记 EARN 各一次批量查询，所以桩按批量 SQL 的片段匹配。
     */
    private void stubSingleLineOrder() {
        stubNoRefundOrders();
        when(jdbc.queryForList(contains("order_status=2"))).thenReturn(
                List.of(row("order_no", "ORD-1", "total_amount", bd("100.00"))));
        when(jdbc.queryForList(contains("FROM t_order_item i"), any(Object[].class))).thenReturn(
                List.of(row("order_no", "ORD-1", "item_id", 11L, "prod_id", 21L, "subtotal", bd("100.00"))));
        when(jdbc.queryForList(contains("SELECT prod_id, mer_id"), any(Object[].class))).thenReturn(
                List.of(row("prod_id", 21L, "mer_id", 7L)));
        when(jdbc.queryForList(contains("commission_rate"), any(Object[].class))).thenReturn(List.of());
        // 已记 EARN 的批量预检（默认无已记行）
        when(jdbc.queryForList(contains("type='EARN' AND refund_seq=0"), any(Object[].class)))
                .thenReturn(List.of());
    }

    /** 被冲正的单条 EARN 行：earning_id=1 / item_id=11 / mer_id=7 / gross 100.00 / 费率快照按入参。 */
    private void stubSingleEarnRow(String commissionRate) {
        when(jdbc.queryForList(contains("type='EARN'"), any(Object[].class))).thenReturn(
                List.of(row("earning_id", 1L, "item_id", 11L, "mer_id", 7L,
                        "gross_amount", bd("100.00"), "commission_rate", bd(commissionRate))));
    }

    private void stubAlreadyReversed(String v) {
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class))).thenReturn(bd(v));
    }

    private void stubMaxRefundSeq(int v) {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(v);
    }

    private static Product productWithMerchant(long prodId, long merId) {
        Product p = new Product();
        p.setProdId(prodId);
        p.setMerId(merId);
        return p;
    }

    @Test
    void allocate_gross_proportional_with_last_row_correction() {
        // subtotal 30/70，order_total 90（有 10 优惠）→ 行 27/63，和 = 90
        BigDecimal r1 = svc.allocateOrderGross(bd("30"), bd("90"), bd("100"), false, bd("0"));
        BigDecimal r2 = svc.allocateOrderGross(bd("70"), bd("90"), bd("100"), true, bd("27"));
        assertEquals(0, r1.compareTo(bd("27")));
        assertEquals(0, r2.compareTo(bd("63")));
        assertEquals(0, r1.add(r2).compareTo(bd("90")));
    }

    @Test
    void allocate_gross_zero_sumsubtotal_is_zero() {
        assertEquals(0, svc.allocateOrderGross(bd("10"), bd("5"), bd("0"), true, bd("0")).compareTo(bd("0")));
    }

    @Test
    void resolve_rate_merchant_override_beats_default() {
        var m = new Merchant();
        m.setMerId(7L);
        m.setCommissionRate(bd("5.00"));
        when(merchantMapper.selectById(7L)).thenReturn(m);
        assertEquals(0, svc.resolveRate(7L).compareTo(bd("5.00")));
    }

    @Test
    void resolve_rate_null_falls_back_to_default() {
        var m = new Merchant();
        m.setMerId(7L);
        m.setCommissionRate(null);
        when(merchantMapper.selectById(7L)).thenReturn(m);
        assertEquals(0, svc.resolveRate(7L).compareTo(bd("7.00")));
    }

    @Test
    void commission_half_up_scale2() {
        // gross 33.50 × 7% = 2.345，第三位恰为 5：只有 HALF_UP 得 2.35（HALF_EVEN / DOWN 会得 2.34）
        assertEquals(0, svc.computeCommission(bd("33.50"), bd("7.00")).compareTo(bd("2.35")));
    }

    @Test
    void allocate_refund_proportional_with_last_row_correction() {
        // 行货值 27/63，本次退款 30 → 行 9.00/21.00，和 = 30
        BigDecimal r1 = svc.allocateRefund(bd("30"), bd("27"), bd("90"), false, bd("0"));
        BigDecimal r2 = svc.allocateRefund(bd("30"), bd("63"), bd("90"), true, bd("9.00"));
        assertEquals(0, r1.compareTo(bd("9.00")));
        assertEquals(0, r2.compareTo(bd("21.00")));
        assertEquals(0, r1.add(r2).compareTo(bd("30")));
    }

    @Test
    void allocate_refund_zero_earngross_is_zero() {
        assertEquals(0, svc.allocateRefund(bd("10"), bd("0"), bd("0"), true, bd("0")).compareTo(bd("0")));
    }

    // ==================== recordEarnings（同步步骤） ====================

    @Test
    void recordEarnings_skips_line_when_earn_already_exists() {
        // (a) 幂等：批量预检发现该行已记 EARN → 不得再 insert
        stubSingleLineOrder();
        when(jdbc.queryForList(contains("type='EARN' AND refund_seq=0"), any(Object[].class)))
                .thenReturn(List.of(row("order_no", "ORD-1", "item_id", 11L)));

        svc.syncAll();

        verify(earningMapper, never()).insert(any(MerchantEarning.class));
    }

    @Test
    void recordEarnings_inserts_pending_earn_with_rate_snapshot() {
        // 对照组：未记过 → 记一笔 PENDING，佣金率取商户当前费率快照
        stubSingleLineOrder();
        // 商户佣金率改为一次批量查询（SELECT mer_id, commission_rate ...）
        when(jdbc.queryForList(contains("commission_rate"), any(Object[].class)))
                .thenReturn(List.of(row("mer_id", 7L, "commission_rate", bd("5.00"))));

        svc.syncAll();

        ArgumentCaptor<MerchantEarning> cap = ArgumentCaptor.forClass(MerchantEarning.class);
        verify(earningMapper, times(1)).insert(cap.capture());
        MerchantEarning e = cap.getValue();
        assertEquals("EARN", e.getType());
        assertEquals(0, e.getRefundSeq().intValue());
        assertEquals("PENDING", e.getStatus());
        assertEquals(0, e.getGrossAmount().compareTo(bd("100.00")));
        assertEquals(0, e.getCommissionRate().compareTo(bd("5.00")));
        assertEquals(0, e.getCommissionAmount().compareTo(bd("5.00")));
        assertEquals(0, e.getNetAmount().compareTo(bd("95.00")));
    }

    @Test
    void recordEarnings_null_subtotal_does_not_abort_sync() {
        // (Important #4) t_order_item.subtotal 可空：单行 NULL 若直接参与 BigDecimal 运算会 NPE，
        // 并回滚整个 @Transactional 同步。此处行 1 故意回 NULL（模拟 COALESCE 未生效 / 老代码路径），
        // 断言不抛且另一行仍被记入 —— 说明 NULL 被兜成 0 而不是掀翻整轮。
        stubNoRefundOrders();
        when(jdbc.queryForList(contains("order_status=2"))).thenReturn(
                List.of(row("order_no", "ORD-1", "total_amount", bd("100.00"))));
        when(jdbc.queryForList(contains("FROM t_order_item i"), any(Object[].class))).thenReturn(
                List.of(row("order_no", "ORD-1", "item_id", 11L, "prod_id", 21L, "subtotal", null),
                        row("order_no", "ORD-1", "item_id", 12L, "prod_id", 22L, "subtotal", bd("100.00"))));
        when(jdbc.queryForList(contains("SELECT prod_id, mer_id"), any(Object[].class))).thenReturn(
                List.of(row("prod_id", 21L, "mer_id", 7L), row("prod_id", 22L, "mer_id", 7L)));
        when(jdbc.queryForList(contains("commission_rate"), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForList(contains("type='EARN' AND refund_seq=0"), any(Object[].class)))
                .thenReturn(List.of());

        assertDoesNotThrow(() -> svc.syncAll());

        // SQL 侧也带 COALESCE 兜底（DB 层不返回 NULL）
        verify(jdbc).queryForList(
                argThat(sql -> sql.contains("COALESCE(i.subtotal,0)")
                        && sql.contains("ORDER BY i.item_id")),
                any(Object[].class));

        ArgumentCaptor<MerchantEarning> cap = ArgumentCaptor.forClass(MerchantEarning.class);
        verify(earningMapper, times(2)).insert(cap.capture());
        // 货值按 Σsubtotal=100 分摊：首行 0.00，末行吸收全部 100.00
        assertEquals(0, cap.getAllValues().get(0).getGrossAmount().compareTo(bd("0.00")));
        assertEquals(0, cap.getAllValues().get(1).getGrossAmount().compareTo(bd("100.00")));
    }

    @Test
    void recordEarnings_swallows_duplicate_key_on_insert() {
        // (e) 并发下唯一键冲突视为已插入，syncAll 不得抛
        stubSingleLineOrder();
        doThrow(new DuplicateKeyException("uk_earning_line")).when(earningMapper).insert(any(MerchantEarning.class));

        assertDoesNotThrow(() -> svc.syncAll());
        verify(earningMapper, times(1)).insert(any(MerchantEarning.class));
    }

    @Test
    void recordEarnings_commission_base_excludes_freight() {
        // P1-5：订单 goods_amount=100 货款 + 10 运费 = total_amount 110；佣金基数必须是 100（不含运费）。
        stubNoRefundOrders();
        when(jdbc.queryForList(contains("order_status=2"))).thenReturn(
                List.of(row("order_no", "ORD-1", "total_amount", bd("110.00"),
                        "goods_amount", bd("100.00"))));
        when(jdbc.queryForList(contains("FROM t_order_item i"), any(Object[].class))).thenReturn(
                List.of(row("order_no", "ORD-1", "item_id", 11L, "prod_id", 21L, "subtotal", bd("100.00"))));
        when(jdbc.queryForList(contains("SELECT prod_id, mer_id"), any(Object[].class))).thenReturn(
                List.of(row("prod_id", 21L, "mer_id", 7L)));
        when(jdbc.queryForList(contains("commission_rate"), any(Object[].class)))
                .thenReturn(List.of(row("mer_id", 7L, "commission_rate", bd("7.00"))));
        when(jdbc.queryForList(contains("type='EARN' AND refund_seq=0"), any(Object[].class)))
                .thenReturn(List.of());

        svc.syncAll();

        // SQL 必须显式按 COALESCE(goods_amount, total_amount) 取基数（禁止裸 total_amount）
        // 注：reverseRefunds 的订单查询同样带该 COALESCE，故用 order_status=2 限定到记入查询
        verify(jdbc).queryForList(
                argThat(sql -> sql.contains("COALESCE(o.goods_amount, o.total_amount)")
                        && sql.contains("order_status=2")));
        ArgumentCaptor<MerchantEarning> cap = ArgumentCaptor.forClass(MerchantEarning.class);
        verify(earningMapper, times(1)).insert(cap.capture());
        MerchantEarning e = cap.getValue();
        assertEquals(0, e.getGrossAmount().compareTo(bd("100.00")), "行货值=货款，不含运费");
        assertEquals(0, e.getCommissionAmount().compareTo(bd("7.00")), "佣金 = 100 × 7%，不含运费");
        assertEquals(0, e.getNetAmount().compareTo(bd("93.00")));
        // 订单总额快照仍保留 total_amount（含运费），供对账
        assertEquals(0, e.getOrderTotal().compareTo(bd("110.00")));
    }

    @Test
    void recordEarnings_null_goods_amount_falls_back_to_total() {
        // 存量兼容：goods_amount 为 NULL（或旧桩只返回 total_amount）→ 基数回落 total_amount
        stubNoRefundOrders();
        when(jdbc.queryForList(contains("order_status=2"))).thenReturn(
                List.of(row("order_no", "ORD-1", "total_amount", bd("100.00"), "goods_amount", null)));
        when(jdbc.queryForList(contains("FROM t_order_item i"), any(Object[].class))).thenReturn(
                List.of(row("order_no", "ORD-1", "item_id", 11L, "prod_id", 21L, "subtotal", bd("100.00"))));
        when(jdbc.queryForList(contains("SELECT prod_id, mer_id"), any(Object[].class))).thenReturn(
                List.of(row("prod_id", 21L, "mer_id", 7L)));
        when(jdbc.queryForList(contains("commission_rate"), any(Object[].class)))
                .thenReturn(List.of(row("mer_id", 7L, "commission_rate", bd("7.00"))));
        when(jdbc.queryForList(contains("type='EARN' AND refund_seq=0"), any(Object[].class)))
                .thenReturn(List.of());

        svc.syncAll();

        ArgumentCaptor<MerchantEarning> cap = ArgumentCaptor.forClass(MerchantEarning.class);
        verify(earningMapper, times(1)).insert(cap.capture());
        assertEquals(0, cap.getValue().getGrossAmount().compareTo(bd("100.00")), "NULL 回落 total_amount");
        assertEquals(0, cap.getValue().getCommissionAmount().compareTo(bd("7.00")));
    }

    // ==================== reverseRefunds（同步步骤） ====================

    @Test
    void reverse_two_partial_refunds_accumulate_increment_only() {
        // (b) 多次部分退：refund_amount 是累计口径，由 30 涨到 50；
        // 第二次只冲增量 20（不是 50），refund_seq 依次 1、2，Σ(REVERSE.gross) = 30 + 20 = 50。
        stubNoEarningOrders();
        stubSingleEarnRow("7.00");

        // 第一轮：累计退款 30，此前无冲正
        when(jdbc.queryForList(contains("refund_amount"))).thenReturn(
                List.of(row("order_no", "ORD-1", "total_amount", bd("100.00"), "refund_amount", bd("30.00"))));
        stubAlreadyReversed("0");
        stubMaxRefundSeq(0);

        svc.syncAll();

        ArgumentCaptor<MerchantEarning> first = ArgumentCaptor.forClass(MerchantEarning.class);
        verify(earningMapper, times(1)).insert(first.capture());
        MerchantEarning rev1 = first.getValue();
        assertEquals(1, rev1.getRefundSeq().intValue());
        assertEquals(0, rev1.getGrossAmount().compareTo(bd("30.00")));

        // 第二轮：累计退款涨到 50，已冲正 30，该行已有 refund_seq=1
        when(jdbc.queryForList(contains("refund_amount"))).thenReturn(
                List.of(row("order_no", "ORD-1", "total_amount", bd("100.00"), "refund_amount", bd("50.00"))));
        stubAlreadyReversed("30.00");
        stubMaxRefundSeq(1);

        svc.syncAll();

        ArgumentCaptor<MerchantEarning> second = ArgumentCaptor.forClass(MerchantEarning.class);
        verify(earningMapper, times(2)).insert(second.capture());
        MerchantEarning rev2 = second.getValue();
        assertEquals(2, rev2.getRefundSeq().intValue());
        assertEquals(0, rev2.getGrossAmount().compareTo(bd("20.00"))); // 增量 20，不是 50
        assertEquals(0, rev1.getGrossAmount().add(rev2.getGrossAmount()).compareTo(bd("50.00")));
    }

    @Test
    void reverse_full_refund_marks_earn_reversed_even_when_delta_is_zero() {
        // (c) Critical #1：某行在 recordEarnings 被跳过（ΣEARN=60 < total=100），
        // 全额退时 alreadyReversed 已等于 ΣEARN ⇒ delta 收敛为 0；
        // 状态迁移必须仍然执行，否则订单永远停在 PENDING/AVAILABLE 并被结算付款。
        stubNoEarningOrders();
        when(jdbc.queryForList(contains("refund_amount"))).thenReturn(
                List.of(row("order_no", "ORD-1", "total_amount", bd("100.00"), "refund_amount", bd("100.00"))));
        when(jdbc.queryForList(contains("type='EARN'"), any(Object[].class))).thenReturn(
                List.of(row("earning_id", 1L, "item_id", 11L, "mer_id", 7L,
                        "gross_amount", bd("60.00"), "commission_rate", bd("7.00"))));
        // delta = 100 - 60 = 40，上界 60 - 60 = 0 ⇒ delta 收敛为 0
        stubAlreadyReversed("60.00");
        stubMaxRefundSeq(1);

        svc.syncAll();

        // delta 为 0：不得再插 REVERSE 行
        verify(earningMapper, never()).insert(any(MerchantEarning.class));
        // 但全额退状态迁移必须发出，且带 SETTLED 守卫（Important #3）
        verify(jdbc).update(
                argThat(sql -> sql.contains("SET status='REVERSED'")
                        && sql.contains("status IN ('PENDING','AVAILABLE')")),
                eq("ORD-1"));
    }

    @Test
    void reverse_full_goods_refund_marks_earn_reversed_when_freight_not_refunded() {
        // P1-5：订单 100 货款 + 10 运费 = 110；已发货后退回 100 货款（运费不退）。
        // refund_amount(100) < total_amount(110)，但 == goods_amount(100) ⇒ 必须判定为货款全退，
        // 否则 EARN 会继续留在可结算池、被结算打款给商户（多付）。
        stubNoEarningOrders();
        when(jdbc.queryForList(contains("refund_amount"))).thenReturn(
                List.of(row("order_no", "ORD-1", "total_amount", bd("110.00"),
                        "goods_amount", bd("100.00"), "refund_amount", bd("100.00"))));
        stubSingleEarnRow("7.00");
        stubAlreadyReversed("0");
        stubMaxRefundSeq(0);

        svc.syncAll();

        verify(jdbc).update(
                argThat(sql -> sql.contains("SET status='REVERSED'")
                        && sql.contains("status IN ('PENDING','AVAILABLE')")),
                eq("ORD-1"));
        ArgumentCaptor<MerchantEarning> cap = ArgumentCaptor.forClass(MerchantEarning.class);
        verify(earningMapper, times(1)).insert(cap.capture());
        MerchantEarning rev = cap.getValue();
        assertEquals("REVERSE", rev.getType());
        assertEquals(0, rev.getGrossAmount().compareTo(bd("100.00")));
        // 冲正行同样保留订单总额快照（含运费）
        assertEquals(0, rev.getOrderTotal().compareTo(bd("110.00")));
    }

    @Test
    void reverse_uses_earn_rate_snapshot_not_current_merchant_rate() {
        // (d) Important #2：EARN 快照 5.00，商户当前费率 9.00 → REVERSE 必须按 5.00 算佣金，
        // 否则全额退后 Σ(REVERSE.commission) != Σ(EARN.commission)，商户净额不为零。
        stubNoEarningOrders();
        when(jdbc.queryForList(contains("refund_amount"))).thenReturn(
                List.of(row("order_no", "ORD-1", "total_amount", bd("100.00"), "refund_amount", bd("100.00"))));
        stubSingleEarnRow("5.00");
        stubAlreadyReversed("0");
        stubMaxRefundSeq(0);
        Merchant m = new Merchant();
        m.setMerId(7L);
        m.setCommissionRate(bd("9.00")); // 当前费率：存在但不得被冲正使用
        when(merchantMapper.selectById(7L)).thenReturn(m);

        svc.syncAll();

        ArgumentCaptor<MerchantEarning> cap = ArgumentCaptor.forClass(MerchantEarning.class);
        verify(earningMapper, times(1)).insert(cap.capture());
        MerchantEarning rev = cap.getValue();
        assertEquals("REVERSE", rev.getType());
        assertEquals(0, rev.getGrossAmount().compareTo(bd("100.00")));
        assertEquals(0, rev.getCommissionRate().compareTo(bd("5.00")));
        assertEquals(0, rev.getCommissionAmount().compareTo(bd("-5.00"))); // 100 × 5% 取负
        assertEquals(0, rev.getNetAmount().compareTo(bd("-95.00")));       // -(100 - 5)
        // 全程不得回查商户当前费率
        verify(merchantMapper, never()).selectById(any());
    }

    @Test
    void reverse_partial_refund_does_not_touch_earn_status() {
        // 部分退不得把 EARN 置 REVERSED（否则商户剩余应收被提前抹掉）
        stubNoEarningOrders();
        when(jdbc.queryForList(contains("refund_amount"))).thenReturn(
                List.of(row("order_no", "ORD-1", "total_amount", bd("100.00"), "refund_amount", bd("30.00"))));
        stubSingleEarnRow("7.00");
        stubAlreadyReversed("0");
        stubMaxRefundSeq(0);

        svc.syncAll();

        verify(jdbc, never()).update(argThat(sql -> sql.contains("SET status='REVERSED'")), eq("ORD-1"));
    }

    @Test
    void promote_available_runs_holding_period_update() {
        // 持有期到点：本轮无新记入、无退款，但必须执行 PENDING→AVAILABLE 的到期 UPDATE
        stubNoEarningOrders();
        stubNoRefundOrders();

        svc.syncAll();

        verify(jdbc).update(argThat((String sql) ->
                sql.contains("SET status='AVAILABLE'")
                        && sql.contains("status='PENDING'")
                        && sql.contains("available_at<=NOW()")));
    }
}
