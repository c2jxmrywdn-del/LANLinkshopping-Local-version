package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 机构交付任务（P1 机构工作台）：平台派单给评价/代理机构，机构侧提交交付物，平台验收。
 * status: ASSIGNED / IN_PROGRESS / SUBMITTED / ACCEPTED / REJECTED。
 */
@Data
@TableName("t_tech_org_task")
public class TechOrgTask {
    @TableId(type = IdType.AUTO)
    private Long taskId;
    private String taskNo;
    private Long orgId;
    private Long projectId;
    private Long assigneeUserId;
    private String taskType;
    private String title;
    private String scope;
    private String status;
    private LocalDate dueDate;
    private String deliverableText;
    private String deliverableHash;
    private LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;
    private String reviewNote;
    private Long createdBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
