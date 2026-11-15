package com.lanlink.shopping.module.rfq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.rfq.entity.RfqQuote;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface RfqQuoteMapper extends BaseMapper<RfqQuote> {

    /** 一户一报价：按 (rfq_id, mer_id) 取本商户自己的报价，竞对不可见 */
    @Select("SELECT * FROM t_rfq_quote WHERE rfq_id = #{rfqId} AND mer_id = #{merId} LIMIT 1")
    RfqQuote selectByRfqAndMer(@Param("rfqId") Long rfqId, @Param("merId") Long merId);

    /** 有效报价数（撤回/驳回不占名额；ACCEPTED 仍占，防止成交后新商户插入） */
    @Select("SELECT COUNT(*) FROM t_rfq_quote WHERE rfq_id = #{rfqId} "
            + "AND status IN ('SUBMITTED', 'ACCEPTED')")
    long countByRfq(@Param("rfqId") Long rfqId);

    /** 首条报价落库后 PUBLISHED → QUOTED（条件更新，重复执行无副作用） */
    @Update("UPDATE t_rfq SET status = 'QUOTED', update_time = NOW() "
            + "WHERE rfq_id = #{rfqId} AND status = 'PUBLISHED'")
    int markQuoted(@Param("rfqId") Long rfqId);

    /** 接受抢闸：只有 SUBMITTED 能变成 ACCEPTED，并发接受只有一方成功 */
    @Update("UPDATE t_rfq_quote SET status = 'ACCEPTED', update_time = NOW() "
            + "WHERE quote_id = #{quoteId} AND status = 'SUBMITTED'")
    int acceptAtomic(@Param("quoteId") Long quoteId);

    /** 同事务拒绝兄弟报价（成交后其余 SUBMITTED 一律 REJECTED） */
    @Update("UPDATE t_rfq_quote SET status = 'REJECTED', update_time = NOW() "
            + "WHERE rfq_id = #{rfqId} AND quote_id <> #{keepQuoteId} AND status = 'SUBMITTED'")
    int rejectSiblings(@Param("rfqId") Long rfqId, @Param("keepQuoteId") Long keepQuoteId);
}