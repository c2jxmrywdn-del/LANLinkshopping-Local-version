package com.lanlink.shopping.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 支付流水日志：记录发起/回调/查询/退款等关键节点（敏感字段已脱敏后落库） */
@Data
@TableName("t_payment_log")
public class PaymentLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    /** wechat | alipay | mock */
    private String channel;
    /** CREATE | NOTIFY | QUERY | REFUND | VERIFY_FAIL | ERROR */
    private String action;
    /** OUT 请求渠道 / IN 渠道回调 */
    private String direction;
    /** 0 失败 1 成功 */
    private Integer success;
    /** 摘要（脱敏后的关键信息，不含完整密钥/签名原文） */
    private String detail;
    private LocalDateTime createTime;
}
