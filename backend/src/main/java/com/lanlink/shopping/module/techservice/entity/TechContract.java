package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 技术服务合同（六类技术合同 + 平台中介合同）。
 * status: DRAFT 草稿 / PENDING_SIGN 待签署 / SIGNED 已签署 / ARCHIVED 已归档。
 * 存证哈希仅证明内容一致，不等于法律意义上的电子签名；生产接入持牌电子签服务。
 */
@Data
@TableName("t_tech_contract")
public class TechContract {
    @TableId(type = IdType.AUTO)
    private Long contractId;
    private String contractNo;
    private Long projectId;
    private Long dealId;
    private String templateCode;
    private String contractType;
    private String title;
    private String partyA;
    private String partyB;
    private BigDecimal amount;
    private BigDecimal commissionRate;
    private String status;
    private String fileHash;
    private String signProvider;
    private String signRef;
    private LocalDateTime signedAt;
    private Long createdBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
