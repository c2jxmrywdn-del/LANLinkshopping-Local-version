package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.CreditBill;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface CreditBillMapper extends BaseMapper<CreditBill> {
    @Select("SELECT * FROM t_credit_bill WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    CreditBill selectForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
