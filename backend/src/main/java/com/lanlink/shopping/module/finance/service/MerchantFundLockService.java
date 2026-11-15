package com.lanlink.shopping.module.finance.service;

import com.lanlink.shopping.module.finance.dto.FundBalance;
import com.lanlink.shopping.module.finance.mapper.MerchantWithdrawalMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 商户资金互斥点 + 可提余额口径的唯一实现点（P0-A 通道 B）。
 *
 * <p>所有涉及商户可提余额的**写**路径（提现申请/审核/驳回/撤回、结算批次登记打款）必须先
 * {@link #lockMerchant(Long)}，随后用本类提供的锁定读聚合做判定；**读**路径（展示）只能用
 * {@link #settled(Long)} 普通读，不得用于余额判定。
 *
 * <p>并发纪律（继承自 {@code WithdrawalService} 类注释）：商户行排他锁是唯一互斥点；
 * 锁定读为当前读，不依赖 REPEATABLE READ 快照，防止「后到者读视图早于先到者提交」的双花。
 * 锁序统一为：{@code t_merchant}（PK 单行）→ {@code t_merchant_settlement}/{@code t_merchant_withdrawal}
 * （PK 单行）→ {@code t_merchant_earning}（PK 行 / {@code idx_earning_mer_status}）。
 * 新增资金路径必须第一步调用 {@link #lockMerchant(Long)}，否则会重开并发双付窗口。
 */
@Service
public class MerchantFundLockService {

    /** 已进批次未打款净额（PAID 已随批次打款，不在本口径；REVERSED 负额计入冲减）。 */
    static final String SETTLED_NET_SQL =
            "SELECT COALESCE(SUM(net_amount), 0) FROM t_merchant_earning "
                    + "WHERE mer_id = ? AND status IN ('SETTLED','REVERSED')";
    /** 写路径专用锁定读：当前读防快照漏并发冻结/打款。 */
    static final String SETTLED_NET_LOCKED_SQL = SETTLED_NET_SQL + " FOR UPDATE";
    /** 资金互斥点：商户行排他锁（主键精确行锁，避免 FOR UPDATE 全表扫描）。 */
    static final String MERCHANT_FUND_LOCK_SQL =
            "SELECT mer_id FROM t_merchant WHERE mer_id = ? FOR UPDATE";

    private final JdbcTemplate jdbc;
    private final MerchantWithdrawalMapper withdrawalMapper;

    public MerchantFundLockService(JdbcTemplate jdbc, MerchantWithdrawalMapper withdrawalMapper) {
        this.jdbc = jdbc;
        this.withdrawalMapper = withdrawalMapper;
    }

    /**
     * 资金互斥点：同商户全部资金路径在同一 {@code t_merchant} 行锁上排队（写路径第一步）。
     *
     * <p>商户不存在时 {@code queryForObject} 抛 {@code EmptyResultDataAccessException}（属数据异常，
     * 不静默放行）；生产路径传入的 merId 均来自已校验的商户/单据。
     */
    public void lockMerchant(Long merId) {
        jdbc.queryForObject(MERCHANT_FUND_LOCK_SQL, Long.class, merId);
    }

    /** 展示路径：普通一致性读，不加锁（不得用于余额判定）。 */
    public BigDecimal settled(Long merId) {
        return money(jdbc.queryForObject(SETTLED_NET_SQL, BigDecimal.class, merId));
    }

    /** 写路径：锁定读已进批次未打款净额（调用方必须已 {@link #lockMerchant(Long)}）。 */
    public BigDecimal lockedSettled(Long merId) {
        return money(jdbc.queryForObject(SETTLED_NET_LOCKED_SQL, BigDecimal.class, merId));
    }

    /** 写路径：锁定读累计已打款（调用方必须已 {@link #lockMerchant(Long)}）。 */
    public BigDecimal lockedPaid(Long merId) {
        return money(withdrawalMapper.sumPaidForUpdate(merId));
    }

    /** 写路径：锁定读可提余额四元组（调用方必须已 {@link #lockMerchant(Long)}）。 */
    public FundBalance lockedBalance(Long merId) {
        BigDecimal settled = lockedSettled(merId);
        BigDecimal paid = lockedPaid(merId);
        BigDecimal frozen = money(withdrawalMapper.sumFrozenForUpdate(merId));
        return new FundBalance(settled, paid, frozen, settled.subtract(paid).subtract(frozen));
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(2) : v.setScale(2, RoundingMode.HALF_UP);
    }
}
