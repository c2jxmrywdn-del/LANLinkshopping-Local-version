package com.lanlink.cloud.api.dto;

/**
 * 发起支付入参（PaymentApi）。
 */
public class PaymentCreateDTO {
    private String orderNo;
    private String channel;
    private Long userId;

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
}
