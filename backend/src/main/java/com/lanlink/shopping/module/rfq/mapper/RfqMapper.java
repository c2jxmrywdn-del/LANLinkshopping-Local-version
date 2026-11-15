package com.lanlink.shopping.module.rfq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.rfq.entity.Rfq;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface RfqMapper extends BaseMapper<Rfq> {

    /**
     * 最近 7 天内同账号同标题（未取消）的存量条数：≥5 触发重复询价拒绝。
     * 加时间窗是为了避免过期/历史询价永久占用配额，同时仍能拦住短时间刷单；
     * 7 天为我方设计值，不归因任何平台。
     */
    @Select("SELECT COUNT(*) FROM t_rfq WHERE user_id = #{userId} AND title = #{title} "
            + "AND status <> 'CANCELLED' AND create_time >= NOW() - INTERVAL 7 DAY")
    long countSameTitle(@Param("userId") Long userId, @Param("title") String title);

    /** 详情按归属收窄：查别人的询价单只会得到 null */
    @Select("SELECT * FROM t_rfq WHERE user_id = #{userId} AND rfq_no = #{rfqNo} LIMIT 1")
    Rfq selectOwned(@Param("userId") Long userId, @Param("rfqNo") String rfqNo);

    /**
     * 报价/接受路径读取询价单并加行锁：串行化"名额计数-插入"与"接受抢闸"，
     * 防止并发突破 max_quotes，也防止接受落单瞬间仍插入新报价。
     */
    @Select("SELECT * FROM t_rfq WHERE rfq_no = #{rfqNo} FOR UPDATE")
    Rfq selectByNoForUpdate(@Param("rfqNo") String rfqNo);

    /** 接受路径按主键加行锁：与报价/过期扫表串行，防止在已过期单上成交 */
    @Select("SELECT * FROM t_rfq WHERE rfq_id = #{rfqId} FOR UPDATE")
    Rfq selectByIdForUpdate(@Param("rfqId") Long rfqId);

    /** 过期扫表：单条条件 UPDATE，PUBLISHED/QUOTED 且已过 quote_deadline → EXPIRED */
    @Update("UPDATE t_rfq SET status = 'EXPIRED', update_time = NOW() "
            + "WHERE status IN ('PUBLISHED', 'QUOTED') AND quote_deadline < NOW()")
    int expireOverdue();

    /** 接受报价后置成交（QUOTED/PUBLISHED → ORDERED），重复执行无副作用 */
    @Update("UPDATE t_rfq SET status = 'ORDERED', update_time = NOW() "
            + "WHERE rfq_id = #{rfqId} AND status IN ('PUBLISHED', 'QUOTED')")
    int markOrdered(@Param("rfqId") Long rfqId);
}