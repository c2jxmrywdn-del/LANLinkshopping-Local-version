package com.lanlink.shopping.module.campaign.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 活动商户报名（平台活动报名 + 商户激励参与）
 * status: 1 已报名
 * rewarded: 0 未发放 / 1 激励奖励已发放
 */
@Data
@TableName("t_campaign_merchant")
public class CampaignMerchant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long campaignId;
    private Long merId;
    private Integer status;
    private Integer rewarded;
    private LocalDateTime joinTime;
}
