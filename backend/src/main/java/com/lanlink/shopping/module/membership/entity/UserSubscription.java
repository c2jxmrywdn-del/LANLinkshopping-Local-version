package com.lanlink.shopping.module.membership.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户订阅记录（营销中台·订阅系统）
 * status: active 生效 / cancelled 已取消 / expired 已过期。
 * 生效判定 = status=active 且 end_time 晚于当前时间。
 */
@Data
@TableName("t_user_subscription")
public class UserSubscription {
    @TableId(type = IdType.AUTO)
    private Long subId;
    private Long userId;
    private Long planId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String status;
    private Integer autoRenew;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
