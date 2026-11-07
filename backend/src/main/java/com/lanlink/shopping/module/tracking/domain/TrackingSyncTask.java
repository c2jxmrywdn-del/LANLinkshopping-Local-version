package com.lanlink.shopping.module.tracking.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 补拉任务（out/logistics-tracking-plan.md D4：outbox 式 @Scheduled + 行级 CAS 认领）。
 * uk_task_waybill 一单一任务；provider_code 存 TrackingChannel 路由键（补拉路由用，
 * 非承运商 provider_code——后者经 CarrierAdapterRegistry.resolveChannel 归一到渠道）。
 */
@Data
@TableName("tracking_sync_task")
public class TrackingSyncTask {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_DEAD = "dead";

    @TableId(type = IdType.AUTO)
    private Long taskId;
    private Long waybillId;
    private String providerCode;
    private String logisticsNo;
    private String status;
    /** 下次补拉时间（成功回调顺延 / 失败指数退避） */
    private LocalDateTime nextPollTime;
    private Integer retryCount;
    /** 当前生效通道连续失败次数（ProviderRouter 累计，达阈值切备用通道，成功清零；热扩列） */
    private Integer failCount;
    /** 当前生效通道（主备切换后为备用渠道编码；null=主通道 provider_code；热扩列） */
    private String activeChannel;
    /** CAS 认领者（实例标识），多副本部署防重 */
    private String owner;
    private LocalDateTime claimedAt;
    private LocalDateTime finishedTime;
    private String lastError;
    private LocalDateTime createTime;
}
