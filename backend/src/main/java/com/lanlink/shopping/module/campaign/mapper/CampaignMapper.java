package com.lanlink.shopping.module.campaign.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.campaign.entity.Campaign;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

public interface CampaignMapper extends BaseMapper<Campaign> {

    /**
     * 原子领取：领取数 +1，条件为已上架且未超发放总量（total_quota=0 表示不限量）。
     * 返回 0 表示已抢完/未上架/不在时间窗内。
     */
    @Update("UPDATE t_campaign SET claimed_count = claimed_count + 1 " +
            "WHERE campaign_id = #{campaignId} AND status = 1 " +
            "AND (start_time IS NULL OR start_time <= NOW()) " +
            "AND (end_time IS NULL OR end_time >= NOW()) " +
            "AND (total_quota IS NULL OR total_quota = 0 OR claimed_count < total_quota)")
    int claimOne(@Param("campaignId") Long campaignId);

    /**
     * 锁定活动行（SELECT ... FOR UPDATE），串行化同一活动的领券事务，
     * 让「每人限领」的 查询→插入 在并发下不存在竞态窗口。须在事务内调用。
     */
    @Select("SELECT campaign_id FROM t_campaign WHERE campaign_id = #{campaignId} FOR UPDATE")
    Long lockById(@Param("campaignId") Long campaignId);

    /**
     * 商户在时间窗内的已支付销售额（本商户商品明细小计之和）。
     * 用于商户激励活动的进度计算与达标领奖；start/end 由服务层保证非空（无界传极值）。
     */
    @Select("SELECT COALESCE(SUM(oi.subtotal), 0) FROM t_order_item oi " +
            "JOIN t_order o ON o.order_no = oi.order_no " +
            "JOIN t_product p ON p.prod_id = oi.prod_id " +
            "WHERE p.mer_id = #{merId} AND p.deleted = 0 " +
            "AND o.pay_status = 1 " +
            "AND o.pay_time >= #{start} AND o.pay_time <= #{end}")
    BigDecimal merchantSalesInWindow(@Param("merId") Long merId,
                                     @Param("start") java.time.LocalDateTime start,
                                     @Param("end") java.time.LocalDateTime end);
}
