package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_tech_achievement")
public class TechAchievement {
    @TableId(type = IdType.AUTO)
    private Long achievementId;
    private Long ownerUserId;
    private String title;
    private String achievementType;
    private String patentNo;
    private String patentType;
    private String legalStatus;
    private String owners;
    private String industry;
    private String techField;
    private Integer maturityLevel;
    private String summary;
    private String highlights;
    private String applicationScenarios;
    private String licensingMode;
    private BigDecimal expectedPrice;
    private String priceMode;
    private Integer dormant;
    private Integer verified;
    private String status;
    private Integer viewCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
