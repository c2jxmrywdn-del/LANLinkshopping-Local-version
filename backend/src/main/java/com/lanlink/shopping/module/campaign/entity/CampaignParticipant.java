package com.lanlink.shopping.module.campaign.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 活动参与记录（用户激励类：注册有礼 / 消费有礼）
 */
@Data
@TableName("t_campaign_participant")
public class CampaignParticipant {
    @TableId(type = IdType.AUTO)
    private Long participantId;
    private Long campaignId;
    private Long userId;
    private Integer bonusPoints;
    private LocalDateTime joinTime;
}
