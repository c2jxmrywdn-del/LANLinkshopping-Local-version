package com.lanlink.cloud.api.dto;

/**
 * 跨服务商户信息（MerchantApi 返回）。
 */
public class MerchantDTO {
    private Long merId;
    private Long userId;
    private String name;
    private Integer status;

    public Long getMerId() { return merId; }
    public void setMerId(Long merId) { this.merId = merId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
