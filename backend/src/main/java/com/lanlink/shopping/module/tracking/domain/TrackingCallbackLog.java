package com.lanlink.shopping.module.tracking.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 回调审计日志（out/logistics-tracking-plan.md §4.3）。
 * 验签失败也记脱敏原文（全量），供追查伪造来源与渠道联调排障；
 * 成功回调只存前 2KB 摘要；全表按 callback-log-retention-days 定期清理防膨胀。
 */
@Data
@TableName("tracking_callback_log")
public class TrackingCallbackLog {

    public static final String VERIFY_PASS = "PASS";
    public static final String VERIFY_FAIL = "FAIL";

    @TableId(type = IdType.AUTO)
    private Long logId;
    private String providerCode;
    private String remoteIp;
    private String verifyResult;
    private Integer httpStatus;
    private String bodyRaw;
    private String failReason;
    private Long eventId;
    private LocalDateTime createTime;
}
