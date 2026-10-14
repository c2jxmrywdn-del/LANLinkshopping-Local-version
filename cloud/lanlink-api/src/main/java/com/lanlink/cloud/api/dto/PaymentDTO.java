package com.lanlink.cloud.api.dto;

import java.math.BigDecimal;

/**
 * 支付结果（PaymentApi 返回）。
 */
public class PaymentDTO {
    private String payNo;
    private String orderNo;
    private String channel;
    private BigDecimal amount;
    private Integer status;
    private boolean mock;

    public String getPayNo() { return payNo; }
    public void setPayNo(String payNo) { this.payNo = payNo; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public boolean isMock() { return mock; }
    public void setMock(boolean mock) { this.mock = mock; }
}
