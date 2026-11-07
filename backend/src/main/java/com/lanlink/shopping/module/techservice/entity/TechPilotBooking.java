package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 中试/概念验证预约（P1）。status: APPLIED / ACCEPTED / COMPLETED / REJECTED。
 */
@Data
@TableName("t_tech_pilot_booking")
public class TechPilotBooking {
    @TableId(type = IdType.AUTO)
    private Long bookingId;
    private String bookingNo;
    private Long resourceId;
    private Long userId;
    private Long projectId;
    private String requirement;
    private LocalDate scheduledDate;
    private String status;
    private String adminNote;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 展示用派生字段（不入库）：预约资源名称 */
    @TableField(exist = false)
    private String resourceName;
}
