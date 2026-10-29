package com.lanlink.shopping.module.campaign.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.campaign.entity.UserCoupon;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /**
     * 原子核销：仅当券属于该用户且当前未使用（status=0 或空）时置为已使用。
     * 返回 0 表示已被并发订单核销 / 已过期 / 不属于该用户，调用方须抛业务异常回滚订单事务。
     */
    @Update("UPDATE t_user_coupon SET status = 1, used_time = NOW(), order_no = #{orderNo} " +
            "WHERE id = #{couponId} AND user_id = #{userId} AND (status = 0 OR status IS NULL)")
    int useOne(@Param("couponId") Long couponId,
               @Param("userId") Long userId,
               @Param("orderNo") String orderNo);
}
