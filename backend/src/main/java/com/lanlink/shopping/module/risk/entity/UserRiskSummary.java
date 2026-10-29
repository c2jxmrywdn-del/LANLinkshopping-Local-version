package com.lanlink.shopping.module.risk.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户风险总分（30 天窗口物化；score=未复核事件分值和封顶 100；level 阈值分级） */
@Data
@TableName("t_user_risk_summary")
public class UserRiskSummary {
    @TableId
    private Long userId;
    private Integer totalScore;
    private String level;
    private Integer hitCount;
    private LocalDateTime lastEventTime;
    private LocalDateTime updatedAt;
}
