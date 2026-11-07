package com.lanlink.shopping.module.campaign.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户券包（领券活动的持券记录）
 * status: 0 未使用 / 1 已使用（无 status=2 写入方，过期不落库，由活动时间窗 effectiveNow 实时判定）
 */
@Data
@TableName("t_user_coupon")
public class UserCoupon {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long campaignId;
    private Long userId;
    private Integer status;
    private LocalDateTime receiveTime;
    private LocalDateTime usedTime;
    /** 核销订单号（取消/全额退款回退依据） */
    private String orderNo;
}
