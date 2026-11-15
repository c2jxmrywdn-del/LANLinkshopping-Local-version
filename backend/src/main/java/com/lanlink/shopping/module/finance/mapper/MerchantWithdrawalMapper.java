package com.lanlink.shopping.module.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.finance.entity.MerchantWithdrawal;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 提现单读写（t_merchant_withdrawal）。
 * 状态机流转全部条件更新：WHERE 带前置状态，返回 0 = 被并发先行者抢占或状态不符，服务层翻译成 400。
 */
public interface MerchantWithdrawalMapper extends BaseMapper<MerchantWithdrawal> {

    /** 待审 → 已打款：凭证必填由服务层校验；返回 0 = 非待审态（已被驳回/撤回/并发打款）。 */
    @Update("UPDATE t_merchant_withdrawal SET status = 1, voucher_url = #{voucherUrl}, "
            + "reviewed_by = #{adminUserId}, reviewed_at = #{reviewedAt}, update_time = #{reviewedAt} "
            + "WHERE id = #{id} AND status = 0")
    int markPaid(@Param("id") Long id,
                 @Param("voucherUrl") String voucherUrl,
                 @Param("adminUserId") Long adminUserId,
                 @Param("reviewedAt") java.time.LocalDateTime reviewedAt);

    /** 待审 → 驳回（解冻）：返回 0 = 非待审态。 */
    @Update("UPDATE t_merchant_withdrawal SET status = 2, reject_reason = #{reason}, "
            + "reviewed_by = #{adminUserId}, reviewed_at = #{reviewedAt}, update_time = #{reviewedAt} "
            + "WHERE id = #{id} AND status = 0")
    int markRejected(@Param("id") Long id,
                     @Param("reason") String reason,
                     @Param("adminUserId") Long adminUserId,
                     @Param("reviewedAt") java.time.LocalDateTime reviewedAt);

    /** 待审 → 已撤回（解冻，商户本人）：mer_id 一并进 WHERE，防跨单越权。返回 0 = 非待审态。 */
    @Update("UPDATE t_merchant_withdrawal SET status = 3, reviewed_by = NULL, reviewed_at = NULL, "
            + "update_time = #{now} WHERE id = #{id} AND mer_id = #{merId} AND status = 0")
    int markWithdrawn(@Param("id") Long id,
                      @Param("merId") Long merId,
                      @Param("now") java.time.LocalDateTime now);

    /**
     * 累计已打款金额（余额扣减项）。历史驳回/撤回单不计入（其冻结已解冻）。
     * NULL 兜底 0（聚合空集）。仅展示/注解路径使用，余额判定走 {@link #sumPaidForUpdate}。
     */
    @Select("SELECT COALESCE(SUM(amount), 0) FROM t_merchant_withdrawal "
            + "WHERE mer_id = #{merId} AND status = 1")
    BigDecimal sumPaid(Long merId);

    /** 在途冻结金额（status=0 待审单的 amount 汇总）。仅展示路径使用，余额判定走 {@link #sumFrozenForUpdate}。 */
    @Select("SELECT COALESCE(SUM(amount), 0) FROM t_merchant_withdrawal "
            + "WHERE mer_id = #{merId} AND status = 0")
    BigDecimal sumFrozen(Long merId);

    /** 写路径专用锁定读（当前读）：RR 快照会漏掉并发对手已提交的打款额，判定必须当前读。 */
    @Select("SELECT COALESCE(SUM(amount), 0) FROM t_merchant_withdrawal "
            + "WHERE mer_id = #{merId} AND status = 1 FOR UPDATE")
    BigDecimal sumPaidForUpdate(Long merId);

    /** 写路径专用锁定读（当前读）：RR 快照会漏掉并发对手已提交的冻结，判定必须当前读。 */
    @Select("SELECT COALESCE(SUM(amount), 0) FROM t_merchant_withdrawal "
            + "WHERE mer_id = #{merId} AND status = 0 FOR UPDATE")
    BigDecimal sumFrozenForUpdate(Long merId);

    /** 在途冻结单数（列表页「审核中」提示用）。 */
    @Select("SELECT COUNT(*) FROM t_merchant_withdrawal WHERE mer_id = #{merId} AND status = 0")
    long countPending(Long merId);
}
