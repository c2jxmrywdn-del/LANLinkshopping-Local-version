package com.lanlink.shopping.module.rfq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RFQ 追加式留痕（发布/报价/接受/拒绝/过期/撤回与防篡改比对失败均落事件）。
 * 只追加不更新，便于审计追溯。
 */
@Data
@TableName("t_rfq_event")
public class RfqEvent {
    @TableId(type = IdType.AUTO)
    private Long eventId;
    private Long rfqId;
    private Long quoteId;
    private String eventType;
    private Long actorId;
    private String actorRole;
    private String detail;
    private LocalDateTime createTime;
}