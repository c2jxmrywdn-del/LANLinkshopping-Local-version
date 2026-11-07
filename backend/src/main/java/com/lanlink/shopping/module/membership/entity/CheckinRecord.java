package com.lanlink.shopping.module.membership.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日签到记录（营销中台·会员系统）
 * uk_checkin_user_date(user_id, checkin_date) 保证每人每日一条；streak 为当日连续签到天数。
 */
@Data
@TableName("t_checkin")
public class CheckinRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private LocalDate checkinDate;
    /** 当日获得积分 */
    private Integer points;
    /** 连续签到天数 */
    private Integer streak;
    private LocalDateTime createTime;
}
