package com.lanlink.shopping.module.activity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 活动（营销中台·活动系统）
 * type: register 注册有礼 / purchase 消费有礼
 * status: 0 未开始 / 1 进行中 / 2 已结束
 */
@Data
@TableName("t_activity")
public class Activity {
    @TableId(type = IdType.AUTO)
    private Long activityId;
    private String title;
    private String type;
    private String description;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
