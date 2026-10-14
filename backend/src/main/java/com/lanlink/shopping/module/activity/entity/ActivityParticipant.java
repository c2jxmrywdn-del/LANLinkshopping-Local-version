package com.lanlink.shopping.module.activity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 活动参与记录（活动系统）
 * activity_id + user_id 唯一约束保证同一活动不可重复参与（防冲突）。
 */
@Data
@TableName("t_activity_participant")
public class ActivityParticipant {
    @TableId(type = IdType.AUTO)
    private Long participantId;
    private Long activityId;
    private Long userId;
    private Integer bonusPoints;
    private LocalDateTime joinTime;
}
