package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目验收记录。result: PASS 通过 / REJECT 不通过。
 * 通过后项目归档为 COMPLETED；评分仅用于服务改进，不作为对外评级依据。
 */
@Data
@TableName("t_tech_acceptance")
public class TechAcceptance {
    @TableId(type = IdType.AUTO)
    private Long acceptanceId;
    private String acceptanceNo;
    private Long projectId;
    private Long contractId;
    private String result;
    private Integer score;
    private String note;
    private String evidenceHash;
    private Long acceptedBy;
    private LocalDateTime createTime;
}
