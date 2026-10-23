package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.CreditAdjustRequest;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface CreditAdjustRequestMapper extends BaseMapper<CreditAdjustRequest> {

    @Select("SELECT * FROM t_credit_adjust_request WHERE account_id=#{accountId} AND status='PENDING' ORDER BY id DESC LIMIT 1")
    CreditAdjustRequest selectPendingByAccount(@Param("accountId") Long accountId);

    @Select("SELECT * FROM t_credit_adjust_request WHERE id=#{id} FOR UPDATE")
    CreditAdjustRequest selectByIdForUpdate(@Param("id") Long id);

    @Update("UPDATE t_credit_adjust_request SET status='WITHDRAWN', review_remark=#{remark}, reviewed_time=NOW() " +
            "WHERE account_id=#{accountId} AND status='PENDING'")
    int withdrawPendingByAccount(@Param("accountId") Long accountId, @Param("remark") String remark);
}
