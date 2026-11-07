package com.lanlink.shopping.integration.mq;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 订单支付事件 outbox：与业务事务同写，broker 不可用时由 worker 补投。 */
@Data
@TableName("t_order_outbox")
public class OrderOutbox {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SENT = 1;
    public static final int STATUS_DEAD = 2;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String messageKey;
    private String payload;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
