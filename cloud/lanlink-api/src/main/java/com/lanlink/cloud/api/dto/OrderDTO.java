package com.lanlink.cloud.api.dto;

import java.math.BigDecimal;

/**
 * 跨服务订单信息（OrderApi 返回，支付取金额用）。
 */
public class OrderDTO {
    private String orderNo;
    private Long userId;
    private BigDecimal totalAmount;
    private Integer payStatus;

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public Integer getPayStatus() { return payStatus; }
    public void setPayStatus(Integer payStatus) { this.payStatus = payStatus; }
}
