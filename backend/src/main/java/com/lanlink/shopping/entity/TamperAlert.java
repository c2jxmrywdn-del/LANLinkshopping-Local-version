package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 敏感字段篡改告警：后端重算值与客户端传入值不一致、状态非法流转等异常请求落库，
 * 供运营追溯恶意攻击行为（配合 t_audit_log 审计链）。
 */
@Data
@TableName("t_tamper_alert")
public class TamperAlert {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String bizType;
    private String fieldName;
    private String clientValue;
    private String serverValue;
    private String reason;
    private String clientIp;
    private String userAgent;
    private LocalDateTime createTime;
}
