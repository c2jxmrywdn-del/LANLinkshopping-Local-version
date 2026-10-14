package com.lanlink.shopping.payment.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 支付/订单状态查询返回 */
@Data
public class PayStatusVO {
    private String orderNo;
    /** 0未支付 1已支付 2已退款 */
    private Integer payStatus;
    /** 0待发货 1已发货 2已完成 3已取消 4已退款 */
    private Integer orderStatus;
    /** wechat/alipay/mock/wallet */
    private String payChannel;
    private String transactionId;
    private BigDecimal totalAmount;
    private LocalDateTime payTime;
    /** 退款状态：none/processing/success */
    private String refundStatus;
    /** 已退金额 */
    private BigDecimal refundAmount;
}
