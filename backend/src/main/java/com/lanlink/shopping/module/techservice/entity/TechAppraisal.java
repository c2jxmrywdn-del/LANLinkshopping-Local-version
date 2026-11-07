package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_tech_appraisal")
public class TechAppraisal {
    @TableId(type = IdType.AUTO)
    private Long appraisalId;
    private String appraisalNo;
    private Long userId;
    private Long achievementId;
    private Long projectId;
    private Long institutionId;
    private String standardCode;
    private Integer techScore;
    private Integer economicScore;
    private Integer legalScore;
    private Integer maturityScore;
    private String conclusionLevel;
    private String reportNo;
    private String status;
    private String disclaimer;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
