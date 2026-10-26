package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.AdCampaign;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 广告推广计划 Mapper。
 * 计费走原子 UPDATE（行级条件 + LEAST 截断），并发点击下花费不会超过总预算。
 */
@Mapper
public interface AdCampaignMapper extends BaseMapper<AdCampaign> {

    /**
     * 点击计费（原子）：仅投放中的计划生效；点击数 +1，花费按 CPC 累计并以总预算截断；
     * 花费扣满预算的当次即置为 ended/budget_exhausted。返回 1 表示计费成功。
     * 注意：MySQL SET 子句按声明顺序执行，status/end_reason 判断中的 spent 已是
     * 本次更新后的新值（LEAST 截断结果），故直接比较 spent >= total_budget。
     */
    @Update("UPDATE t_ad_campaign SET clicks = clicks + 1,"
            + " spent = LEAST(spent + #{cpc}, total_budget),"
            + " status = IF(spent >= total_budget, 'ended', status),"
            + " end_reason = IF(spent >= total_budget, 'budget_exhausted', end_reason),"
            + " update_time = NOW()"
            + " WHERE campaign_id = #{campaignId} AND status = 'active'")
    int chargeClick(@Param("campaignId") Long campaignId, @Param("cpc") java.math.BigDecimal cpc);

    /** 曝光计数（原子，不扣费）：仅投放中的计划生效 */
    @Update("UPDATE t_ad_campaign SET impressions = impressions + 1, update_time = NOW()"
            + " WHERE campaign_id = #{campaignId} AND status = 'active'")
    int countImpression(@Param("campaignId") Long campaignId);

    /** 广告位候选：投放中的计划（JOIN 在售商品），按剩余预算降序轮换 */
    @Select("SELECT c.* FROM t_ad_campaign c JOIN t_product p ON p.prod_id = c.prod_id"
            + " WHERE c.status = 'active' AND c.deleted = 0 AND c.end_time > NOW()"
            + " AND p.status = 1 AND p.deleted = 0"
            + " ORDER BY (c.total_budget - c.spent) DESC, c.campaign_id LIMIT #{limit}")
    List<AdCampaign> activeSlots(@Param("limit") int limit);

    /** 到期待结算的计划（投放中/暂停 且已过结束时间） */
    @Select("SELECT * FROM t_ad_campaign WHERE status IN ('active','paused') AND end_time <= NOW() AND deleted = 0")
    List<AdCampaign> expiredCampaigns();
}
