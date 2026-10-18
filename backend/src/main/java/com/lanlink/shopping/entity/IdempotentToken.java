package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 幂等令牌：核心接口（下单/支付/退款/结算）重复提交拦截。
 * 唯一键(biz_type, biz_key) 借数据库唯一约束实现跨实例互斥。
 */
@Data
@TableName("t_idempotent_token")
public class IdempotentToken {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String bizType;
    private String bizKey;
    private Long userId;
    /** 首次执行结果引用（如订单号），重复请求直接返回首次结果 */
    private String resultRef;
    private LocalDateTime createTime;
    private LocalDateTime expireTime;
}
