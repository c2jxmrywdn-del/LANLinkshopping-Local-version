package com.lanlink.cloud.api.dto;

import java.math.BigDecimal;

/**
 * 跨服务购物车项（CartApi 返回）。
 */
public class CartItemDTO {
    private Long cartId;
    private Long prodId;
    private String title;
    private BigDecimal price;
    private Integer quantity;

    public Long getCartId() { return cartId; }
    public void setCartId(Long cartId) { this.cartId = cartId; }
    public Long getProdId() { return prodId; }
    public void setProdId(Long prodId) { this.prodId = prodId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
}
