package com.lanlink.shopping.module.settlement.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.module.finance.dto.FundBalance;
import com.lanlink.shopping.module.finance.service.MerchantFundLockService;
import com.lanlink.shopping.module.settlement.entity.MerchantEarning;
import com.lanlink.shopping.module.settlement.entity.MerchantSettlement;
import com.lanlink.shopping.module.settlement.entity.MerchantSettlementItem;
import com.lanlink.shopping.module.settlement.mapper.MerchantEarningMapper;
import com.lanlink.shopping.module.settlement.mapper.MerchantSettlementItemMapper;
import com.lanlink.shopping.module.settlement.mapper.MerchantSettlementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结算批次服务单测：批次状态机（DRAFT→APPROVED→PAID 单向）+ 佣金率校验 + 通道 B 锁/覆盖守卫。
 *
 * <p>四条 mapper + 资金锁服务全 mock，只锁「钱/状态相关的服务端判断」：
 * <ul>
 *   <li>非法流转一律 {@link BusinessException} code=400（管理端会把它映射成 400 响应）；</li>
 *   <li>打款凭证必填（空白串同样拒绝）；</li>
 *   <li>佣金率 0..100 且 scale&lt;=2，null=清除；商户不存在 404；</li>
 *   <li>通道 B：pay 先锁商户行、再条件更新裁决、再锁定读验资；余额不足 400 且不置收益行 PAID。</li>
 * </ul>
 */
class SettlementBatchServiceTest {

    private MerchantEarningMapper earningMapper;
    private MerchantSettlementMapper settleMapper;
    private MerchantSettlementItemMapper itemMapper;
    private MerchantMapper merchantMapper;
    private MerchantFundLockService fundLock;
    private JdbcTemplate jdbc;
    private SettlementBatchService svc;

    @BeforeEach
    void setup() {
        earningMapper = mock(MerchantEarningMapper.class);
        settleMapper = mock(MerchantSettlementMapper.class);
        itemMapper = mock(MerchantSettlementItemMapper.class);
        merchantMapper = mock(MerchantMapper.class);
        fundLock = mock(MerchantFundLockService.class);
        jdbc = mock(JdbcTemplate.class);
        // 默认可提余额充足；覆盖守卫用例按需覆盖为不足
        when(fundLock.lockedBalance(anyLong()))
                .thenReturn(new FundBalance(bd("1000.00"), bd("0.00"), bd("0.00"), bd("1000.00")));
        svc = new SettlementBatchService(earningMapper, settleMapper, itemMapper, merchantMapper, fundLock);
    }

    private static MerchantSettlement batch(long settleId, String status) {
        MerchantSettlement s = new MerchantSettlement();
        s.setSettleId(settleId);
        s.setMerId(7L);
        s.setStatus(status);
        return s;
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    // ==================== 批次状态机 ====================

    @Test
    void approve_rejects_non_draft() {
        when(settleMapper.selectById(1L)).thenReturn(batch(1L, "PAID"));
        BusinessException e = assertThrows(BusinessException.class, () -> svc.approve(1L));
        assertEquals(400, e.getCode());
        verify(settleMapper, never()).updateById(any(MerchantSettlement.class));
    }

    @Test
    void approve_rejects_approved_batch() {
        // 幂等性防线：APPROVED 再审核一次也必须是 400，不能静默成功
        when(settleMapper.selectById(4L)).thenReturn(batch(4L, "APPROVED"));
        BusinessException e = assertThrows(BusinessException.class, () -> svc.approve(4L));
        assertEquals(400, e.getCode());
    }

    @Test
    void approve_draft_transitions_to_approved() {
        MerchantSettlement s = batch(5L, "DRAFT");
        // 空批次守卫：approve 现在要求 item_count > 0（防空批次流转到打款）
        s.setItemCount(1);
        when(settleMapper.selectById(5L)).thenReturn(s);
        // 条件更新（WHERE status='DRAFT'）影响 1 行 = 状态机推进成功
        when(settleMapper.update(isNull(), any())).thenReturn(1);
        MerchantSettlement out = svc.approve(5L);
        assertEquals("APPROVED", out.getStatus());
        verify(settleMapper).update(isNull(), any());
    }

    @Test
    void approve_unknown_batch_is_404() {
        when(settleMapper.selectById(99L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class, () -> svc.approve(99L));
        assertEquals(404, e.getCode());
    }

    @Test
    void pay_rejects_non_approved() {
        when(settleMapper.selectById(2L)).thenReturn(batch(2L, "DRAFT"));
        BusinessException e = assertThrows(BusinessException.class, () -> svc.pay(2L, "V1"));
        assertEquals(400, e.getCode());
        verify(settleMapper, never()).updateById(any(MerchantSettlement.class));
    }

    @Test
    void pay_rejects_already_paid() {
        when(settleMapper.selectById(6L)).thenReturn(batch(6L, "PAID"));
        BusinessException e = assertThrows(BusinessException.class, () -> svc.pay(6L, "V2"));
        assertEquals(400, e.getCode());
    }

    @Test
    void pay_requires_voucher() {
        when(settleMapper.selectById(3L)).thenReturn(batch(3L, "APPROVED"));
        BusinessException blank = assertThrows(BusinessException.class, () -> svc.pay(3L, "  "));
        assertEquals(400, blank.getCode());
        BusinessException nul = assertThrows(BusinessException.class, () -> svc.pay(3L, null));
        assertEquals(400, nul.getCode());
        verify(settleMapper, never()).updateById(any(MerchantSettlement.class));
    }

    @Test
    void pay_approved_sets_trimmed_voucher_and_paid() {
        MerchantSettlement s = batch(7L, "APPROVED");
        // 明细守卫：pay 现在要求批次至少 1 条明细且与 item_count 一致
        s.setItemCount(1);
        when(settleMapper.selectById(7L)).thenReturn(s);
        MerchantSettlementItem line = new MerchantSettlementItem();
        line.setSettleId(7L);
        line.setEarningId(100L);
        when(itemMapper.selectList(any())).thenReturn(List.of(line));
        when(settleMapper.update(isNull(), any())).thenReturn(1);
        MerchantSettlement out = svc.pay(7L, "  V-2026-11-09  ");
        assertEquals("PAID", out.getStatus());
        assertEquals("V-2026-11-09", out.getPayVoucher());
        verify(settleMapper).update(isNull(), any());
    }

    @Test
    void pay_marks_batch_earnings_paid() {
        MerchantSettlement s = batch(8L, "APPROVED");
        s.setItemCount(1);
        when(settleMapper.selectById(8L)).thenReturn(s);
        MerchantSettlementItem line = new MerchantSettlementItem();
        line.setSettleId(8L);
        line.setEarningId(200L);
        when(itemMapper.selectList(any())).thenReturn(List.of(line));
        when(settleMapper.update(isNull(), any())).thenReturn(1);

        MerchantSettlement out = svc.pay(8L, "V-PAID-1");
        assertEquals("PAID", out.getStatus());

        // P0-A 结算双付修复：批次打款成功后，本批明细对应的收益行必须置终态 PAID
        //（离开可提余额口径，否则同一笔钱可被提现通道再付一次）。
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<MerchantEarning>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(earningMapper, times(1)).update(isNull(), cap.capture());
        String setSql = cap.getValue().getSqlSet();
        assertTrue(setSql != null && setSql.contains("status"), "必须 SET status=PAID，实际: " + setSql);
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue("PAID"),
                "PAID 必须作为参数写入 UPDATE: " + cap.getValue().getParamNameValuePairs());
    }

    @Test
    void pay_rejects_empty_details() {
        MerchantSettlement s = batch(9L, "APPROVED");
        s.setItemCount(1);
        when(settleMapper.selectById(9L)).thenReturn(s);
        when(itemMapper.selectList(any())).thenReturn(List.of());

        BusinessException e = assertThrows(BusinessException.class, () -> svc.pay(9L, "V-EMPTY"));
        assertEquals(400, e.getCode());
        // 空明细必须在批次状态翻转、收益行置 PAID 之前被拒绝（更在取商户锁之前）
        verify(fundLock, never()).lockMerchant(anyLong());
        verify(settleMapper, never()).update(isNull(), any());
        verify(earningMapper, never()).update(isNull(), any());
    }

    // ==================== 通道 B：锁 + 覆盖守卫 ====================

    /**
     * T-B4：可提余额被在途提现占用时 pay 必须 400；顺序严格按定稿——
     * 明细守卫 → 锁商户行 → 条件更新（并发裁决）→ 锁定读验资：
     * 条件更新「已发生一次」而收益行「未置 PAID」（守卫在两者之间，抛异常回滚）。
     */
    @Test
    void pay_rejects_when_balance_occupied() {
        MerchantSettlement s = batch(11L, "APPROVED");
        s.setItemCount(1);
        s.setTotalNet(bd("800.00"));
        when(settleMapper.selectById(11L)).thenReturn(s);
        MerchantSettlementItem line = new MerchantSettlementItem();
        line.setSettleId(11L);
        line.setEarningId(301L);
        when(itemMapper.selectList(any())).thenReturn(List.of(line));
        when(settleMapper.update(isNull(), any())).thenReturn(1);
        // 可提余额 700 < 批次净额 800（被在途提现冻结占用）
        when(fundLock.lockedBalance(7L))
                .thenReturn(new FundBalance(bd("800.00"), bd("0.00"), bd("100.00"), bd("700.00")));

        BusinessException e = assertThrows(BusinessException.class, () -> svc.pay(11L, "V-OCCUPIED"));
        assertEquals(400, e.getCode());
        // 文案必须可行动：说明现象 + 给出驳回/撤回两条修复路径
        assertTrue(e.getMessage().contains("提现"), "文案须提示提现占用: " + e.getMessage());
        assertTrue(e.getMessage().contains("驳回") || e.getMessage().contains("撤回"),
                "文案须给出可行动路径（驳回/撤回）: " + e.getMessage());

        // 锁 → 条件更新 → 锁定读验资，顺序不可颠倒（先裁决后验资，避免误导性文案）
        InOrder order = inOrder(fundLock, settleMapper);
        order.verify(fundLock).lockMerchant(7L);
        order.verify(settleMapper).update(isNull(), any());
        order.verify(fundLock).lockedBalance(7L);
        // 守卫失败：收益行未置 PAID（事务回滚由 @Transactional 保证）
        verify(earningMapper, never()).update(isNull(), any());
    }

    /** T-B4：纯负向/零额批次不产生现金流出，跳过覆盖守卫（余额为负也必须放行）。 */
    @Test
    void pay_negative_batch_skips_coverage_guard() {
        MerchantSettlement s = batch(12L, "APPROVED");
        s.setItemCount(1);
        s.setTotalNet(bd("-30.00"));
        when(settleMapper.selectById(12L)).thenReturn(s);
        MerchantSettlementItem line = new MerchantSettlementItem();
        line.setSettleId(12L);
        line.setEarningId(302L);
        when(itemMapper.selectList(any())).thenReturn(List.of(line));
        when(settleMapper.update(isNull(), any())).thenReturn(1);
        // 余额为负也放行：负额批次是冲减，不产生现金流出
        when(fundLock.lockedBalance(7L))
                .thenReturn(new FundBalance(bd("-100.00"), bd("0.00"), bd("0.00"), bd("-100.00")));

        MerchantSettlement out = svc.pay(12L, "V-NEGATIVE");
        assertEquals("PAID", out.getStatus());
        verify(earningMapper, times(1)).update(isNull(), any());
    }

    /** generate 也必须参与同商户资金互斥点（P1 硬化，锁在 AVAILABLE→SETTLED 认领之前）。 */
    @Test
    void generate_takes_merchant_lock_before_claim() {
        stubOneEarnOneReverseCarry();
        when(settleMapper.insert(any(MerchantSettlement.class))).thenAnswer(inv -> {
            inv.getArgument(0, MerchantSettlement.class).setSettleId(503L);
            return 1;
        });

        svc.generate(7L, null, null, jdbc);

        InOrder order = inOrder(fundLock, jdbc);
        order.verify(fundLock).lockMerchant(7L);
        order.verify(jdbc).update(contains("SET status='SETTLED'"), any(Object[].class));
    }

    // ==================== 佣金率校验 ====================

    @Test
    void set_commission_rejects_out_of_range() {
        BusinessException high = assertThrows(BusinessException.class,
                () -> svc.setCommissionRate(7L, bd("120")));
        assertEquals(400, high.getCode());
        BusinessException low = assertThrows(BusinessException.class,
                () -> svc.setCommissionRate(7L, bd("-1")));
        assertEquals(400, low.getCode());
        verify(merchantMapper, never()).updateById(any(Merchant.class));
    }

    @Test
    void set_commission_rejects_scale_gt2() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.setCommissionRate(7L, bd("5.123")));
        assertEquals(400, e.getCode());
        verify(merchantMapper, never()).updateById(any(Merchant.class));
    }

    @Test
    void set_commission_accepts_bounds() {
        Merchant m = new Merchant();
        m.setMerId(7L);
        when(merchantMapper.selectById(7L)).thenReturn(m);
        svc.setCommissionRate(7L, bd("0"));
        svc.setCommissionRate(7L, bd("100.00"));
        // 两次都通过显式 update wrapper 落库（非 updateById，后者会剔除 null）
        verify(merchantMapper, times(2)).update(eq(null), any());
        verify(merchantMapper, never()).updateById(any(Merchant.class));
    }

    @Test
    void set_commission_null_clears_rate() {
        Merchant m = new Merchant();
        m.setMerId(7L);
        m.setCommissionRate(bd("5.00"));
        when(merchantMapper.selectById(7L)).thenReturn(m);
        svc.setCommissionRate(7L, null);
        // 关键：清除必须走显式 wrapper（set commission_rate=NULL），而非 updateById（会静默剔除 null 字段）
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Merchant>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(merchantMapper).update(eq(null), cap.capture());
        String setSql = cap.getValue().getSqlSet();
        assertTrue(setSql != null && setSql.contains("commission_rate"),
                "UPDATE 必须显式 set commission_rate（含 NULL），实际: " + setSql);
        verify(merchantMapper, never()).updateById(any(Merchant.class));
    }

    @Test
    void set_commission_unknown_merchant_is_404() {
        when(merchantMapper.selectById(8L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.setCommissionRate(8L, bd("7.00")));
        assertEquals(404, e.getCode());
        verify(merchantMapper, never()).update(eq(null), any());
        verify(merchantMapper, never()).updateById(any(Merchant.class));
    }

    // ==================== 生成 ====================

    /** 两条 AVAILABLE EARN（净额 93.00 + 46.50，佣金 7.00 + 3.50）+ 一条负向结转 REVERSE（净额 -93.00）。 */
    private void stubOneEarnOneReverseCarry() {
        when(jdbc.queryForList(contains("status='SETTLED'"), any(Object[].class))).thenReturn(List.of(
                earningRow(1L, "ORD-1", 11L, "EARN", "93.00", "7.00"),
                earningRow(2L, "ORD-2", 12L, "EARN", "46.50", "3.50")));
        when(jdbc.queryForList(contains("type='REVERSE'"), any(Object[].class))).thenReturn(List.of(
                earningRow(3L, "ORD-0", 10L, "REVERSE", "-93.00", "-7.00")));
    }

    private static Map<String, Object> earningRow(Long earningId, String orderNo, Long itemId,
                                                  String type, String net, String commission) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("earning_id", earningId);
        m.put("order_no", orderNo);
        m.put("item_id", itemId);
        m.put("type", type);
        m.put("net_amount", bd(net));
        m.put("commission_amount", bd(commission));
        return m;
    }

    @Test
    void generate_rejects_when_no_available_earnings() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.generate(7L, null, null, jdbc));
        assertEquals(400, e.getCode());
        // 无可用收益时不得插入空批次
        verify(settleMapper, never()).insert(any(MerchantSettlement.class));
    }

    @Test
    void generate_freezes_items_and_nets_negative_carry_forward() {
        stubOneEarnOneReverseCarry();
        when(settleMapper.insert(any(MerchantSettlement.class))).thenAnswer(inv -> {
            inv.getArgument(0, MerchantSettlement.class).setSettleId(500L);
            return 1;
        });

        MerchantSettlement s = svc.generate(7L, null, null, jdbc);

        // 明细 = 2 EARN + 1 负向结转 REVERSE
        ArgumentCaptor<MerchantSettlementItem> items = ArgumentCaptor.forClass(MerchantSettlementItem.class);
        verify(itemMapper, times(3)).insert(items.capture());
        assertEquals(List.of("EARN", "EARN", "REVERSE"),
                items.getAllValues().stream().map(MerchantSettlementItem::getType).toList());
        assertTrue(items.getAllValues().stream().allMatch(i -> Long.valueOf(500L).equals(i.getSettleId())));

        // totalNet = 93.00 + 46.50 - 93.00 = 46.50；commissionTotal = 7.00 + 3.50 - 7.00 = 3.50
        assertEquals(0, s.getTotalNet().compareTo(bd("46.50")));
        assertEquals(0, s.getCommissionTotal().compareTo(bd("3.50")));
        assertEquals(3, s.getItemCount());
        assertEquals("DRAFT", s.getStatus());
        assertEquals(7L, s.getMerId());
        assertTrue(s.getSettleNo().startsWith("SETTLE"), "批次号前缀: " + s.getSettleNo());
        assertTrue(s.getSettleNo().endsWith("-7"), "批次号须带商户后缀避免同秒撞唯一键: " + s.getSettleNo());
        verify(settleMapper).updateById(s);
    }

    @Test
    void generate_marks_only_available_earn_settled_not_reverse() {
        stubOneEarnOneReverseCarry();
        when(settleMapper.insert(any(MerchantSettlement.class))).thenAnswer(inv -> {
            inv.getArgument(0, MerchantSettlement.class).setSettleId(501L);
            return 1;
        });

        svc.generate(7L, null, null, jdbc);

        ArgumentCaptor<String> sqls = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(1)).update(sqls.capture(), any(Object[].class));
        String sql = sqls.getValue();
        assertTrue(sql.contains("SET status='SETTLED'"), sql);
        assertTrue(sql.contains("type='EARN'"), "只能认领 EARN，不得把 REVERSE 置 SETTLED: " + sql);
        assertTrue(sql.contains("status='AVAILABLE'"), "只认领 AVAILABLE: " + sql);
    }

    @Test
    void generate_carry_forward_query_guards_double_carry() {
        stubOneEarnOneReverseCarry();
        when(settleMapper.insert(any(MerchantSettlement.class))).thenAnswer(inv -> {
            inv.getArgument(0, MerchantSettlement.class).setSettleId(502L);
            return 1;
        });

        svc.generate(7L, null, null, jdbc);

        ArgumentCaptor<String> sqls = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).queryForList(sqls.capture(), any(Object[].class));
        String carrySql = sqls.getAllValues().get(1);
        assertTrue(carrySql.contains("NOT EXISTS"), "负向结转必须排除已纳入过的 REVERSE: " + carrySql);
        assertTrue(carrySql.contains("t_merchant_settlement_item"), carrySql);
        // P0-A：已打款（PAID）的 EARN 同样要结转其冲正，否则该笔退款冲正永远进不了任何批次
        // = 平台静默多付。故条件必须是 IN ('SETTLED','PAID')，不能只判 SETTLED。
        assertTrue(carrySql.contains("orig.status IN ('SETTLED','PAID')"),
                "必须结转「已结算或已打款」EARN 的 REVERSE: " + carrySql);
        assertTrue(carrySql.contains("e.status='REVERSED'"), carrySql);
    }

    @Test
    void generate_null_merchant_is_400() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.generate(null, null, null, jdbc));
        assertEquals(400, e.getCode());
        verify(settleMapper, never()).insert(any(MerchantSettlement.class));
    }
}
