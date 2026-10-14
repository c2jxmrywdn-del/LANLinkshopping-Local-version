package com.lanlink.cloud.api.dto;

import java.math.BigDecimal;

/**
 * 跨服务商品信息（ProductApi 返回，下单计价依据）。
 */
public class ProductDTO {
    private Long prodId;
    private Long merId;
    private String title;
    private BigDecimal price;
    private Integer stock;
    private Integer status;

    public Long getProdId() { return prodId; }
    public void setProdId(Long prodId) { this.prodId = prodId; }
    public Long getMerId() { return merId; }
    public void setMerId(Long merId) { this.merId = merId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
