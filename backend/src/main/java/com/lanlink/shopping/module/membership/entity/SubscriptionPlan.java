package com.lanlink.shopping.module.membership.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 订阅方案（营销中台·订阅系统）
 * price_cents 以「元」为单位（企业级 B2B 定价：8888 = ¥8,888）；duration_days 订阅有效天数。
 * auto_renew / enabled 为 0/1 标志；plan_code 全局唯一，作为幂等种子的业务键。
 */
@Data
@TableName("t_subscription_plan")
public class SubscriptionPlan {
    @TableId(type = IdType.AUTO)
    private Long planId;
    private String planName;
    private String planCode;
    private Integer priceCents;
    private Integer durationDays;
    private Integer autoRenew;
    private String discountLabel;
    private Integer sort;
    private Integer enabled;
    private LocalDateTime createdAt;
}
