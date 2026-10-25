package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * “加入我们”申请：求职（job）与渠道合作（partner）两类统一落库。
 * 闭环：用户提交 → 待审核 → 运营审核（通过/驳回+备注）→ 站内消息通知 → 个人中心查看进度。
 */
@Data
@TableName("t_join_application")
public class JoinApplication {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** job 求职 / partner 渠道合作 */
    private String applyType;
    /** 求职：应聘职位 */
    private String position;
    /** 合作：公司/机构名称 */
    private String companyName;
    private String contactName;
    private String phone;
    private String email;
    /** 城市 / 意向区域 */
    private String city;
    /** 求职：工作年限，如 1-3 年 */
    private String experience;
    /** 自我介绍 / 合作说明 */
    private String message;
    /** PENDING待审核 APPROVED通过 REJECTED驳回 */
    private String status;
    private String reviewRemark;
    private Long reviewBy;
    private LocalDateTime applyTime;
    private LocalDateTime reviewedTime;
    private LocalDateTime updateTime;
}
