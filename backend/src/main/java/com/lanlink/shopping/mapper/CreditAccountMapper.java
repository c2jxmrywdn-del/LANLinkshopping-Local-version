package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.CreditAccount;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

public interface CreditAccountMapper extends BaseMapper<CreditAccount> {
    @Update("UPDATE t_credit_account SET available_limit=available_limit-#{amount}, used_limit=used_limit+#{amount}, update_time=NOW() " +
            "WHERE id=#{id} AND status='ACTIVE' AND available_limit>=#{amount}")
    int reserve(@Param("id") Long id, @Param("amount") BigDecimal amount);

    @Update("UPDATE t_credit_account SET available_limit=available_limit+#{amount}, used_limit=GREATEST(0, used_limit-#{amount}), update_time=NOW() " +
            "WHERE id=#{id} AND status='ACTIVE'")
    int release(@Param("id") Long id, @Param("amount") BigDecimal amount);
}
