package com.lanlink.shopping.module.tracking.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 渠道原始事件存档（out/logistics-tracking-plan.md D1/D2）。
 * 只存渠道脱敏原文与解析元数据；规范化轨迹不在此表（唯一写路径是 t_logistics_trace）。
 * 幂等键 uk_event(provider_code, external_event_id)：重复推送 INSERT 冲突即按渠道语义幂等返回。
 */
@Data
@TableName("tracking_event")
public class TrackingEvent {

    public static final String TYPE_POLL = "poll";
    public static final String TYPE_CALLBACK = "callback";
    public static final String TYPE_MANUAL = "manual";

    public static final String STATUS_RECEIVED = "received";
    public static final String STATUS_NORMALIZED = "normalized";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_DUPLICATE = "duplicate";
    public static final String STATUS_SKIPPED = "skipped";

    @TableId(type = IdType.AUTO)
    private Long eventId;
    private String providerCode;
    private String externalEventId;
    private Long waybillId;
    private String logisticsNo;
    private String eventType;
    private String status;
    /** 渠道原文（落库前已经 CallbackDesensitizer 脱敏） */
    private String rawBody;
    /** 渠道侧最新节点时间（入库延迟 P95 指标 = create_time - event_time；热扩列） */
    private LocalDateTime eventTime;
    private Integer normalizedCount;
    private String errorMsg;
    private LocalDateTime createTime;
}
