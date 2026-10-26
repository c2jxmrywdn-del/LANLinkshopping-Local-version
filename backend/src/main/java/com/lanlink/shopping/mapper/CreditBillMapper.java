package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.CreditBill;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

public interface CreditBillMapper extends BaseMapper<CreditBill> {
    @Select("SELECT * FROM t_credit_bill WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    CreditBill selectForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    @Select("SELECT * FROM t_credit_bill WHERE id=#{id} FOR UPDATE")
    CreditBill selectByIdForUpdate(@Param("id") Long id);

    /** 逾期候选账单：到期日早于 today 且仍未结清（UNPAID），供定时逾期检查扫描 */
    @Select("SELECT * FROM t_credit_bill WHERE status='UNPAID' AND due_date < #{today} ORDER BY due_date")
    List<CreditBill> selectOverdueCandidates(@Param("today") LocalDate today);

    /** 条件流转 UNPAID → OVERDUE：仅未结清且已到期的账单生效，返回 1 表示本次流转成功（防并发/重复） */
    @Update("UPDATE t_credit_bill SET status='OVERDUE', update_time=NOW() WHERE id=#{id} AND status='UNPAID' AND due_date < #{today}")
    int markOverdueIfUnpaid(@Param("id") Long id, @Param("today") LocalDate today);
}
