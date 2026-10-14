package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_merchant")
public class Merchant {
    @TableId(type = IdType.AUTO)
    private Long merId;
    private Long entId;
    private Long userId;
    private String regType;         // 公司 / 个体工商户
    private BigDecimal regCapital;  // 注册资本(元)
    private Integer taxStatus;      // 0未知 1稳定
    private String joinType;        // 加盟/入驻/邀约
    private Integer reviewStatus;   // 0待审 1通过 2驳回
    private String rejectReason;
    private String licenseUrl;      // 营业执照图片URL(JPG/PNG)
    private String taxProofUrls;    // 近3个月税务缴纳证明URL(逗号分隔)
    private String taxRegNo;        // 税务登记号(查询纳税记录用)
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
