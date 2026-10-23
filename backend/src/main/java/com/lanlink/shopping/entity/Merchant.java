package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lanlink.shopping.crypto.EncryptTypeHandler;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName(value = "t_merchant", autoResultMap = true)
public class Merchant {
    @TableId(type = IdType.AUTO)
    private Long merId;
    private Long entId;
    private Long userId;
    private String regType;         // 公司 / 个体工商户
    private BigDecimal regCapital;  // 注册资本(元)
    private Integer taxStatus;      // 0未知 1稳定
    private String joinType;        // 加盟/入驻/邀约
    private String joinCode;        // 平台邀约码 / 特约加盟码
    private Integer reviewStatus;   // 0待审 1通过 2驳回
    private String rejectReason;
    private String licenseUrl;      // 营业执照图片URL(JPG/PNG)
    private String taxProofUrls;    // 近3个月税务缴纳证明URL(逗号分隔)
    private String taxRegNo;        // 税务登记号(查询纳税记录用)
    private Integer hasSpecialPermit; // 是否有特殊经营许可证 0无 1有
    private String specialPermitName; // 特殊经营许可证名称
    private String specialPermitUrl;  // 特殊经营许可证附件URL

    // ===== 商户重要敏感信息（AES-256-GCM 加密存储，展示层脱敏） =====
    @TableField(typeHandler = EncryptTypeHandler.class)
    private String legalPerson;     // 法人真实姓名
    @TableField(typeHandler = EncryptTypeHandler.class)
    private String legalIdCard;     // 法人身份证号
    @TableField(typeHandler = EncryptTypeHandler.class)
    private String bankAccount;     // 对公银行账号
    @TableField(typeHandler = EncryptTypeHandler.class)
    private String bankName;        // 开户银行及支行
    @TableField(typeHandler = EncryptTypeHandler.class)
    private String contactPhone;    // 负责人联系电话
    @TableField(typeHandler = EncryptTypeHandler.class)
    private String contactEmail;    // 负责人联系邮箱
    @TableField(typeHandler = EncryptTypeHandler.class)
    private String businessAddress; // 经营场所/注册地址

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    @JsonIgnore
    private Integer deleted;
}
