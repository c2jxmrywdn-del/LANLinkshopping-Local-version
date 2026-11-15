package com.lanlink.shopping.module.membership.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.membership.entity.MemberCard;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface MemberCardMapper extends BaseMapper<MemberCard> {

    /**
     * 积分抵现扣减：余额充足才扣，返回 0 行即"不足或已被并发扣走"。
     * 与 ProductMapper.deductStockAtomic 同口径，避免读-改-写把并发扣减覆盖成双花。
     */
    @Update("UPDATE t_member_card SET points = points - #{points}, update_time = NOW() "
            + "WHERE user_id = #{userId} AND points >= #{points}")
    int deductPointsAtomic(@Param("userId") Long userId, @Param("points") int points);
}
