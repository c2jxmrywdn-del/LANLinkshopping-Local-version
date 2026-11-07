package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.LogisticsSettlement;
import com.lanlink.shopping.entity.LogisticsSettlementItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

public interface LogisticsSettlementMapper extends BaseMapper<LogisticsSettlement> {

    /** 同服务商同账期非作废结算单数（幂等判断用） */
    @Select("SELECT COUNT(*) FROM t_logistics_settlement WHERE provider_id = #{providerId} AND period_start = #{periodStart} AND period_end = #{periodEnd} AND deleted = 0")
    long countByProviderPeriod(@Param("providerId") Long providerId,
                               @Param("periodStart") LocalDate periodStart,
                               @Param("periodEnd") LocalDate periodEnd);

    /**
     * 账期担保支付原子抢占：仅 approved 单可置 paid，以 affected 行数判定成败。
     * 并发支付下后到事务的条件 UPDATE 命中 0 行（当前读已见 paid），先建账单方随事务回滚。
     */
    @Update("UPDATE t_logistics_settlement SET status = 'paid', pay_method = 'credit_guarantee',"
            + " paid_time = NOW(), update_time = NOW()"
            + " WHERE settlement_id = #{settlementId} AND status = 'approved' AND deleted = 0")
    int markPaidWithCredit(@Param("settlementId") Long settlementId);

    /** 抢占成功后回填账期账单关联（同事务内持有该行锁，条件兜底仍按 paid 状态判定） */
    @Update("UPDATE t_logistics_settlement SET credit_bill_id = #{creditBillId}, update_time = NOW()"
            + " WHERE settlement_id = #{settlementId} AND status = 'paid' AND deleted = 0")
    int markPaidCreditBill(@Param("settlementId") Long settlementId, @Param("creditBillId") Long creditBillId);
}
