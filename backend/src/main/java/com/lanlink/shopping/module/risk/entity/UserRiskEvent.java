package com.lanlink.shopping.module.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户风控命中事件（打分卡单条命中记录；reviewed_status 供人工复核/误报白名单） */
@Data
@TableName("t_user_risk_event")
public class UserRiskEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String ruleCode;
    private Integer score;
    private String detail;
    private String refType;
    private String refId;
    private Integer reviewedStatus;
    private Long reviewedBy;
    private LocalDateTime reviewedTime;
    private LocalDateTime createTime;
}
