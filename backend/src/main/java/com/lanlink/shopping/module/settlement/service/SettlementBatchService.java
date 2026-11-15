package com.lanlink.shopping.module.settlement.service;

import com.lanlink.shopping.common.BusinessException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 结算批次：生成（聚合 AVAILABLE 台账）→ 审核 → 打款登记，以及商户佣金率维护。
 *
 * <p>批次状态机单向：{@code DRAFT → APPROVED → PAID}，任何跳步/回退一律
 * {@link BusinessException}(400)；台账侧由 {@code generate} 把被纳入的 EARN 置
 * {@code SETTLED}（{@code PENDING → AVAILABLE → SETTLED}，冲正行 {@code * → REVERSED} 由同步服务负责），
 * {@code pay} 再把整批明细对应的收益行推进到终态 {@code PAID}（{@code SETTLED → PAID}）。
 *
 * <p><b>PAID 语义</b>：收益行的 {@code PAID} 表示其所属结算批次已完成线下对公打款的登记（终态）。
 * PAID 行不再参与任何批次聚合（carry 负向结转除外：仅作为被冲正对象判定「已结算/已打款」），
 * 也不计入商户可提余额——否则同一笔已打款净额会被提现通道再付一次。
 *
 * <p><b>负向结转</b>：已结算/已打款（SETTLED/PAID）EARN 之后发生的退款冲正，REVERSE 行的净额为负。
 * 这类行不能改动已冻结的历史批次（不追讨、不回滚），而是在下一批聚合时作为负项纳入，
 * 商户端展示为「上期退款冲减」。{@code NOT EXISTS(t_merchant_settlement_item)} 保证同一条
 * REVERSE 只被结转一次（跨批次不重复）。
 *
 * <p>金额口径：全程 BigDecimal，落库 DECIMAL(14,2)，汇总用 HALF_UP scale 2。
 * 平台只做记账与对账（二清红线）：PAID 仅是「人工对公打款已登记」，平台不代收代付。
 *
 * <p><b>资金互斥（P0-A 通道 B）</b>：{@code pay} 与 {@code generate} 均在写路径第一步取
 * {@link MerchantFundLockService#lockMerchant(Long)} 商户行锁，与提现申请/审核/驳回/撤回共用同一把锁；
 * 锁序统一为 商户行 → 批次行 → 收益行。{@code pay} 在条件更新成功后、置收益行 PAID 之前做
 * 「可提余额 ≥ 批次净额」覆盖守卫（守卫失败抛 400 → 事务回滚，批次保持 APPROVED）。
 */
@Service
public class SettlementBatchService {

    /** 保留以固定契约（Task 4 装配与测试构造签名），批次写路径当前走 JdbcTemplate 条件更新。 */
    private final MerchantEarningMapper earningMapper;
    private final MerchantSettlementMapper settleMapper;
    private final MerchantSettlementItemMapper itemMapper;
    private final MerchantMapper merchantMapper;
    /** 资金互斥点与可提余额口径唯一实现点（通道 B；禁止在结算侧内联 settled/paid SQL）。 */
    private final MerchantFundLockService fundLock;

    public SettlementBatchService(MerchantEarningMapper earningMapper,
                                 MerchantSettlementMapper settleMapper,
                                 MerchantSettlementItemMapper itemMapper,
                                 MerchantMapper merchantMapper,
                                 MerchantFundLockService fundLock) {
        this.earningMapper = earningMapper;
        this.settleMapper = settleMapper;
        this.itemMapper = itemMapper;
        this.merchantMapper = merchantMapper;
        this.fundLock = fundLock;
    }

    /**
     * 生成批次：把该商户 {@code status='AVAILABLE' AND type='EARN'} 的台账行聚合成 DRAFT 批次，
     * 冻结明细快照（order_no / item_id / type / net_amount），并把被纳入的 EARN 置 {@code SETTLED}；
     * 同时纳入「已结算/已打款（SETTLED/PAID）EARN 的 REVERSE 净额」作为负向结转（不改历史批次）。
     *
     * <p>无可用收益时 400，且不落空批次（否则批次列表会被零额草稿刷满，审核端无从判断）。
     *
     * <p><b>并发安全</b>：先做一次条件 UPDATE「认领」AVAILABLE 行（{@code WHERE status='AVAILABLE'}
     * 让并发 generate 只有一个能把行推进到 SETTLED），再按 {@code status='SETTLED' AND 未被任何批次纳入}
     * 取回本批的明细来源。这样两个管理员同时点「生成」不会把同一批收益结算两次。
     *
     * @param merId       商户
     * @param periodStart 账期起（含，可为 null：按实际纳入行手工填写）
     * @param periodEnd   账期止（不含，可为 null）
     * @param jdbc        由调用方（管理端控制器）透传，避免服务再注入一个数据源依赖
     */
    @Transactional
    public MerchantSettlement generate(Long merId, LocalDateTime periodStart, LocalDateTime periodEnd,
                                       JdbcTemplate jdbc) {
        if (merId == null) {
            throw new BusinessException(400, "请指定商户");
        }
        // 同商户资金路径统一互斥点（P0-A 通道 B，P1 硬化）：批次生成会改变可提余额基数
        // （AVAILABLE→SETTLED 从「不在口径」进入口径），取商户行锁与 pay/提现路径保持同一锁序。
        fundLock.lockMerchant(merId);
        // 认领：并发下只有一个事务能把 AVAILABLE 推到 SETTLED（行锁 + 条件），另一个影响行数变少
        jdbc.update("UPDATE t_merchant_earning SET status='SETTLED' "
                + "WHERE mer_id=? AND type='EARN' AND status='AVAILABLE'", merId);
        // 本批来源：已 SETTLED 但尚未被任何批次纳入的 EARN（认领成功的那批就是这些行）
        List<Map<String, Object>> avail = jdbc.queryForList(
                "SELECT earning_id, order_no, item_id, type, net_amount, commission_amount "
                        + "FROM t_merchant_earning e "
                        + "WHERE e.mer_id=? AND e.type='EARN' AND e.status='SETTLED' "
                        + "AND NOT EXISTS(SELECT 1 FROM t_merchant_settlement_item si "
                        + "                WHERE si.earning_id=e.earning_id) "
                        + "ORDER BY e.earning_id", merId);
        if (avail.isEmpty()) {
            throw new BusinessException(400, "该商户无可结算收益");
        }

        MerchantSettlement s = new MerchantSettlement();
        s.setSettleNo(nextSettleNo(merId));
        s.setMerId(merId);
        s.setPeriodStart(periodStart);
        s.setPeriodEnd(periodEnd);
        s.setStatus("DRAFT");
        // 先占位插入拿 settle_id（明细需要外键），汇总列随后回填
        s.setTotalNet(BigDecimal.ZERO.setScale(2));
        s.setCommissionTotal(BigDecimal.ZERO.setScale(2));
        s.setItemCount(0);
        s.setCreateTime(LocalDateTime.now());
        s.setUpdateTime(LocalDateTime.now());
        settleMapper.insert(s);
        Long settleId = s.getSettleId();

        BigDecimal totalNet = BigDecimal.ZERO;
        BigDecimal commissionTotal = BigDecimal.ZERO;
        int count = 0;

        for (Map<String, Object> r : avail) {
            Long earningId = toLong(r.get("earning_id"));
            BigDecimal net = toMoney(r.get("net_amount"));
            BigDecimal comm = toMoney(r.get("commission_amount"));
            MerchantSettlementItem item = new MerchantSettlementItem();
            item.setSettleId(settleId);
            item.setEarningId(earningId);
            item.setOrderNo((String) r.get("order_no"));
            item.setItemId(toLong(r.get("item_id")));
            item.setType((String) r.get("type"));
            item.setNetAmount(net);
            itemMapper.insert(item);
            totalNet = totalNet.add(net);
            commissionTotal = commissionTotal.add(comm);
            count++;
            // EARN→SETTLED 已在方法开头的条件 UPDATE 批量完成（WHERE status='AVAILABLE' AND type='EARN'），
            // 这里不再逐行改状态：冲正行（REVERSE）不满足 type='EARN'，永远不会被误置 SETTLED。
        }

        // 负向结转：ref 的 EARN 已结算（钱已进过批次）的 REVERSE 行，纳入本批做冲减。
        // NOT EXISTS 保证同一条 REVERSE 不会被后续批次重复结转。
        List<Map<String, Object>> carry = jdbc.queryForList(
                "SELECT e.earning_id, e.order_no, e.item_id, e.type, e.net_amount, e.commission_amount "
                        + "FROM t_merchant_earning e "
                        + "JOIN t_merchant_earning orig ON e.ref_earning_id=orig.earning_id "
                        // 已被打款（orig 已 PAID）的 EARN 同样要结转其冲正，否则该笔退款冲正
                        // 会永远进不了任何批次 = 平台静默多付。这是新增 PAID 终态后必须同步扩的点。
                        + "WHERE e.mer_id=? AND e.type='REVERSE' AND orig.status IN ('SETTLED','PAID') "
                        + "AND e.status='REVERSED' "
                        + "AND NOT EXISTS(SELECT 1 FROM t_merchant_settlement_item si "
                        + "                WHERE si.earning_id=e.earning_id) "
                        + "ORDER BY e.earning_id", merId);
        for (Map<String, Object> r : carry) {
            Long earningId = toLong(r.get("earning_id"));
            BigDecimal net = toMoney(r.get("net_amount"));
            MerchantSettlementItem item = new MerchantSettlementItem();
            item.setSettleId(settleId);
            item.setEarningId(earningId);
            item.setOrderNo((String) r.get("order_no"));
            item.setItemId(toLong(r.get("item_id")));
            item.setType("REVERSE");
            item.setNetAmount(net);
            itemMapper.insert(item);
            // 冲正行净额与佣金均为负：一并计入，保证 total_net + commission_total == Σ 纳入货值
            totalNet = totalNet.add(net);
            commissionTotal = commissionTotal.add(toMoney(r.get("commission_amount")));
            count++;
        }

        s.setTotalNet(scale(totalNet));
        s.setCommissionTotal(scale(commissionTotal));
        s.setItemCount(count);
        s.setUpdateTime(LocalDateTime.now());
        settleMapper.updateById(s);
        return s;
    }

    /** DRAFT → APPROVED；非 DRAFT 一律 400（含重复审核，避免静默成功掩盖误操作）。 */
    @Transactional
    public MerchantSettlement approve(Long settleId) {
        MerchantSettlement s = require(settleId);
        if (!"DRAFT".equals(s.getStatus())) {
            throw new BusinessException(400, "仅草稿批次可审核");
        }
        // 空批次不得流转：approve/pay 原先都不校验明细，空批次可一路推进到打款登记。
        if (s.getItemCount() == null || s.getItemCount() <= 0) {
            throw new BusinessException(400, "批次无明细，禁止审核");
        }
        // 条件更新：WHERE status='DRAFT'。否则两个管理员并发时，迟到的一方会用
        // 旧实体整行写回，把已 PAID 的批次覆盖回 APPROVED（可再次登记打款）。
        // 用字符串列名的 UpdateWrapper：条件更新语义不变，且不依赖 lambda 元数据缓存
        int updated = settleMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<MerchantSettlement>()
                .eq("settle_id", settleId)
                .eq("status", "DRAFT")
                .set("status", "APPROVED")
                .set("update_time", LocalDateTime.now()));
        if (updated == 0) {
            throw new BusinessException(400, "批次状态已变更（可能已被审核），请刷新后重试");
        }
        s.setStatus("APPROVED");
        s.setUpdateTime(LocalDateTime.now());
        return s;
    }

    /** APPROVED → PAID；打款凭证必填（线下对公打款登记，平台不代付）。 */
    @Transactional
    public MerchantSettlement pay(Long settleId, String payVoucher) {
        MerchantSettlement s = require(settleId);
        if (!"APPROVED".equals(s.getStatus())) {
            throw new BusinessException(400, "仅已审核批次可登记打款");
        }
        if (payVoucher == null || payVoucher.isBlank()) {
            throw new BusinessException(400, "请填写打款凭证号");
        }
        // 明细守卫：无明细的空批次不得流转到打款；item_count 与实际明细行数不一致
        // 说明批次已被外部改写，一律拒绝（避免空批次/脏批次被登记打款）。
        List<MerchantSettlementItem> items = itemMapper.selectList(
                Wrappers.<MerchantSettlementItem>lambdaQuery()
                        .eq(MerchantSettlementItem::getSettleId, settleId));
        if (items.isEmpty()) {
            throw new BusinessException(400, "批次无明细，禁止登记打款");
        }
        if (s.getItemCount() != null && s.getItemCount() != items.size()) {
            throw new BusinessException(400, "批次明细数与汇总不一致，请重新生成批次");
        }
        // P0-A 通道 B：资金互斥点。与 WithdrawalService 同一把 t_merchant 行锁
        // （实现见 MerchantFundLockService），锁序：商户行 → 批次行 → 收益行。
        // 与提现申请/审核/驳回/撤回串行化，杜绝「提现先成立、批次后打款」的并发双付。
        fundLock.lockMerchant(s.getMerId());
        // 条件更新：WHERE status='APPROVED'，并发重复登记时只有一方成功，
        // 避免同一批被登记两次线下打款或凭证被后写者覆盖。
        int updated = settleMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<MerchantSettlement>()
                .eq("settle_id", settleId)
                .eq("status", "APPROVED")
                .set("status", "PAID")
                .set("pay_voucher", payVoucher.trim())
                .set("update_time", LocalDateTime.now()));
        if (updated == 0) {
            throw new BusinessException(400, "批次状态已变更（可能已登记打款），请刷新后重试");
        }
        // P0-A 通道 B 覆盖守卫：**先条件更新（并发裁决点）后验资**。裁决胜出者才进入余额守卫，
        // 因此并发重复 pay 的败者拿到的是准确的「批次状态已变更」文案，而不是误导性的余额不足。
        // 守卫失败抛 BusinessException（RuntimeException）→ @Transactional 整体回滚，批次回到 APPROVED，
        // 不会出现「已 PAID 但未过余额校验」。用 total_net 而非正项和：total_net 含负向结转，与余额口径严格对应。
        FundBalance fb = fundLock.lockedBalance(s.getMerId());
        if (s.getTotalNet() != null && s.getTotalNet().signum() > 0 && !fb.covers(s.getTotalNet())) {
            throw new BusinessException(400,
                    "登记打款失败：本批次净额 ¥" + s.getTotalNet().toPlainString()
                            + " 已被提现通道占用（当前可提余额 ¥" + fb.balance().toPlainString()
                            + "）。请先在「提现审核」驳回相关在途提现单，或请商户撤回提现申请后重试");
        }
        // P0-A 结算双付修复：同事务把本批明细对应的收益行置终态 PAID。
        // 可提余额口径是 Σ(earning.net_amount WHERE status IN ('SETTLED','REVERSED')) − 已打款提现,
        // 因此批次一旦登记线下对公打款，其收益行必须离开该口径，否则商户可就同一笔钱再提一次。
        // EARN 与 REVERSE 两类行都要置：只置 EARN 会让已打款批次的负向冲正继续留在余额里，
        // 导致该商户后续被永久少付（净损失）。
        List<Long> earningIds = items.stream()
                .map(MerchantSettlementItem::getEarningId)
                .filter(Objects::nonNull)
                .toList();
        if (!earningIds.isEmpty()) {
            earningMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<MerchantEarning>()
                            .in("earning_id", earningIds)
                            .in("status", "SETTLED", "REVERSED")
                            .set("status", "PAID"));
        }
        s.setStatus("PAID");
        s.setPayVoucher(payVoucher.trim());
        s.setUpdateTime(LocalDateTime.now());
        return s;
    }

    /**
     * 设置/清除商户佣金率（%）。{@code null} = 清除，回落全局默认（app.settlement.default-commission-rate）。
     * 校验 0..100 且小数位 &lt;= 2（与 t_merchant.commission_rate DECIMAL(5,2) 同口径）。
     * 只影响后续记入的 EARN（费率已快照在历史行上），不追溯已结算批次。
     */
    @Transactional
    public void setCommissionRate(Long merId, BigDecimal rate) {
        if (rate != null) {
            if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(new BigDecimal("100")) > 0) {
                throw new BusinessException(400, "佣金率需在 0-100 之间");
            }
            if (rate.scale() > 2) {
                throw new BusinessException(400, "佣金率最多两位小数");
            }
        }
        Merchant m = merchantMapper.selectById(merId);
        if (m == null) {
            throw new BusinessException(404, "商户不存在");
        }
        // 显式 set：MyBatis-Plus 默认 updateStrategy=NOT_NULL 会把 null 字段从 UPDATE 中剔除，
        // updateById 无法把 commission_rate 清回 NULL（清除回默认会静默失效）。用 update wrapper 显式置列。
        merchantMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<Merchant>update()
                .eq("mer_id", merId)
                .set("commission_rate", rate));
    }

    private MerchantSettlement require(Long settleId) {
        MerchantSettlement s = settleMapper.selectById(settleId);
        if (s == null) {
            throw new BusinessException(404, "批次不存在");
        }
        return s;
    }

    /**
     * 批次号：{@code SETTLE + yyyyMMddHHmmssSSS + "-" + merId}。
     * 毫秒精度 + merId 后缀：避免同秒内同商户（重复点击）或不同商户撞 uk_settle_no。
     * 长度上限：SETTLE(6)+17+1+merId(≤19)=43 —— 超出 uk_settle_no VARCHAR(40)，故 merId 截断到尾部 6 位，
     * 实际唯一性由毫秒时间戳保证，merId 仅作可读线索。
     */
    private static String nextSettleNo(Long merId) {
        String tail = String.valueOf(merId);
        if (tail.length() > 6) {
            tail = tail.substring(tail.length() - 6);
        }
        return "SETTLE" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
                + "-" + tail;
    }

    /** DECIMAL 列读取兜底：NULL 按 0（列虽 NOT NULL，历史脏数据/聚合空集仍可能出现 NULL）。 */
    private static BigDecimal toMoney(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return (v instanceof BigDecimal) ? (BigDecimal) v : new BigDecimal(v.toString());
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
