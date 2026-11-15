package com.lanlink.shopping.module.merchantexit.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.merchantexit.config.MerchantExitProperties;
import com.lanlink.shopping.module.merchantexit.entity.MerchantDeposit;
import com.lanlink.shopping.module.merchantexit.mapper.MerchantDepositMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 保证金台账：append-only 复式记账，方向由 type 决定（PAY 入，REFUND/DEDUCT/FORFEIT 出）。
 *
 * <p>并发：所有写操作先对 t_merchant 行 SELECT ... FOR UPDATE 串行化同商户流水，
 * 再算余额、写 balance_after 快照——同一商户的并发入账不会互相覆盖。
 * 台账只 INSERT，任何历史行都不 UPDATE/DELETE（审计可追溯）。
 */
@Service
public class MerchantDepositService {

    private static final Set<String> TYPES = Set.of("PAY", "REFUND", "DEDUCT", "FORFEIT");
    private static final String BALANCE_SQL =
            "SELECT COALESCE(SUM(CASE WHEN type = 'PAY' THEN amount ELSE -amount END), 0)"
                    + " FROM t_merchant_deposit WHERE mer_id = ?";
    // 写路径的余额读必须走锁定读（current read）：调用方事务若早已做过普通 SELECT，
    // 一致性快照会停留在加锁前，普通读将漏掉并发已提交的流水。
    private static final String LOCKED_BALANCE_SQL = BALANCE_SQL + " FOR UPDATE";
    private static final String LOCK_SQL =
            "SELECT mer_id FROM t_merchant WHERE mer_id = ? AND deleted = 0 FOR UPDATE";
    private static final String COUNT_PAY_SQL =
            "SELECT COUNT(*) FROM t_merchant_deposit WHERE mer_id = ? AND type = 'PAY' FOR UPDATE";

    private final MerchantDepositMapper depositMapper;
    private final JdbcTemplate jdbc;
    private final MerchantExitProperties props;

    public MerchantDepositService(MerchantDepositMapper depositMapper, JdbcTemplate jdbc,
                                  MerchantExitProperties props) {
        this.depositMapper = depositMapper;
        this.jdbc = jdbc;
        this.props = props;
    }

    public BigDecimal balanceOf(Long merId) {
        return normalize(jdbc.queryForObject(BALANCE_SQL, BigDecimal.class, merId));
    }

    private BigDecimal normalize(BigDecimal v) {
        return v == null ? new BigDecimal("0.00") : v.setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional
    public MerchantDeposit pay(Long merId, BigDecimal amount, String remark) {
        return record(merId, "PAY", amount, remark);
    }

    @Transactional
    public MerchantDeposit refund(Long merId, BigDecimal amount, String remark) {
        return record(merId, "REFUND", amount, remark);
    }

    @Transactional
    public MerchantDeposit deduct(Long merId, BigDecimal amount, String remark) {
        return record(merId, "DEDUCT", amount, remark);
    }

    @Transactional
    public MerchantDeposit forfeit(Long merId, BigDecimal amount, String remark) {
        return record(merId, "FORFEIT", amount, remark);
    }

    /** 入驻过审首缴（幂等：该商户已有任一 PAY 行则跳过）。 */
    @Transactional
    public boolean ensureInitialDeposit(Long merId) {
        // 必须先取行锁再判重：普通 SELECT 会建立 REPEATABLE READ 一致性快照，
        // 判重若在锁前，并发首缴的第二笔会读到提交前快照而双双入账。
        lockMerchant(merId);
        Long pays = jdbc.queryForObject(COUNT_PAY_SQL, Long.class, merId);
        if (pays != null && pays > 0) {
            return false;
        }
        record(merId, "PAY", props.getDepositAmount(), "入驻保证金缴纳（账面登记）");
        return true;
    }

    private void lockMerchant(Long merId) {
        if (jdbc.queryForList(LOCK_SQL, Long.class, merId).isEmpty()) {
            throw new BusinessException(404, "商户不存在");
        }
    }

    private MerchantDeposit record(Long merId, String type, BigDecimal amount, String remark) {
        if (merId == null || !TYPES.contains(type)) {
            throw new BusinessException(400, "保证金流水参数不合法");
        }
        if (amount == null) {
            throw new BusinessException(400, "保证金金额必须大于 0");
        }
        BigDecimal amt = amount.setScale(2, RoundingMode.HALF_UP);
        if (amt.signum() <= 0) {
            // 先归一化再校验：0.001~0.004 四舍五入后为 0.00，不能写入台账
            throw new BusinessException(400, "保证金金额必须大于 0");
        }
        lockMerchant(merId);
        BigDecimal balance = normalize(jdbc.queryForObject(LOCKED_BALANCE_SQL, BigDecimal.class, merId));
        if (!"PAY".equals(type) && balance.compareTo(amt) < 0) {
            throw new BusinessException(400, "保证金余额不足（当前 ¥" + balance.toPlainString() + "）");
        }
        BigDecimal after = "PAY".equals(type) ? balance.add(amt) : balance.subtract(amt);
        MerchantDeposit d = new MerchantDeposit();
        d.setMerId(merId);
        d.setType(type);
        d.setAmount(amt);
        d.setBalanceAfter(after);
        d.setRemark(remark);
        d.setCreateTime(LocalDateTime.now());
        depositMapper.insert(d);
        return d;
    }
}
