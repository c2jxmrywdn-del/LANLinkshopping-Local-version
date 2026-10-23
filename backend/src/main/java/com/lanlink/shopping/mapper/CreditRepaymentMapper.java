package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.CreditRepayment;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

public interface CreditRepaymentMapper extends BaseMapper<CreditRepayment> {

    @Select("SELECT * FROM t_credit_repayment WHERE id=#{id} FOR UPDATE")
    CreditRepayment selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT COALESCE(SUM(amount),0) FROM t_credit_repayment WHERE bill_id=#{billId} AND status='PENDING'")
    BigDecimal sumPendingAmount(@Param("billId") Long billId);

    /** 条件流转：仅 PENDING 可确认入账，影响行数为 0 说明已被并发处理 */
    @Update("UPDATE t_credit_repayment SET status='CONFIRMED', confirmed_time=NOW(), confirm_remark=#{remark} " +
            "WHERE id=#{id} AND status='PENDING'")
    int confirmPending(@Param("id") Long id, @Param("remark") String remark);

    /** 条件流转：仅 PENDING 可驳回 */
    @Update("UPDATE t_credit_repayment SET status='REJECTED', confirmed_time=NOW(), confirm_remark=#{remark} " +
            "WHERE id=#{id} AND status='PENDING'")
    int rejectPending(@Param("id") Long id, @Param("remark") String remark);

    /** 订单取消时批量驳回该账单下待确认的转账登记 */
    @Update("UPDATE t_credit_repayment SET status='REJECTED', confirmed_time=NOW(), confirm_remark=#{remark} " +
            "WHERE bill_id=#{billId} AND status='PENDING'")
    int rejectPendingByBill(@Param("billId") Long billId, @Param("remark") String remark);
}
