package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_order")
public class Order {
    @TableId      // 主键为订单号(字符串)，非自增
    private String orderNo;
    private Long userId;
    private Long entId;
    private BigDecimal totalAmount;
    private String payType;
    /** 积分抵扣金额（元，整元） */
    private BigDecimal pointDeductAmount;
    /** 积分抵扣消耗积分数（1000 积分 = 1 元） */
    private Integer pointDeductPoints;
    private Integer payStatus;
    private Integer orderStatus;
    private String receiver;
    private String phone;
    private String address;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
    /** 支付渠道：wechat | alipay | mock */
    private String payChannel;
    /** 第三方渠道交易号 */
    private String transactionId;
    /** 微信预下单号 */
    private String prepayId;
    /** 退款状态：none | processing | success */
    private String refundStatus;
    /** 渠道退款单号 */
    private String refundId;
    /** 已退金额 */
    private BigDecimal refundAmount;
    private LocalDateTime refundTime;
    private LocalDateTime updateTime;
}
