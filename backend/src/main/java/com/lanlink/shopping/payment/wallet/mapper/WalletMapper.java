package com.lanlink.shopping.payment.wallet.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.payment.wallet.entity.Wallet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface WalletMapper extends BaseMapper<Wallet> {

    /**
     * 条件扣款（原子防透支）：仅当余额充足时扣减，返回受影响行数。
     * 并发下两个请求同时通过余额检查也只有一条能命中，杜绝负余额。
     */
    @Update("UPDATE t_wallet SET balance = balance - #{amount} " +
            "WHERE user_id = #{userId} AND balance >= #{amount}")
    int deduct(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 入账（充值/退款），返回受影响行数 */
    @Update("UPDATE t_wallet SET balance = balance + #{amount} WHERE user_id = #{userId}")
    int credit(@Param("userId") Long userId, @Param("amount") BigDecimal amount);
}
