package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_tech_milestone")
public class TechMilestone {
    @TableId(type = IdType.AUTO)
    private Long milestoneId;
    private Long projectId;
    private Integer seq;
    private String name;
    private String description;
    private String status;
    private LocalDate dueDate;
    private LocalDateTime completedAt;
    private String deliverable;
    private String note;
    private LocalDateTime createTime;
}
