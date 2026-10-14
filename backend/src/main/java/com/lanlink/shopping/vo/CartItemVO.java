package com.lanlink.shopping.vo;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 购物车行(含商品快照)
 */
@Data
public class CartItemVO {
    private Long cartId;
    private Long prodId;
    private String title;
    private String coverUrl;
    private BigDecimal price;
    private Integer quantity;
    private Integer stock;
    private Integer checked;
    private BigDecimal subtotal;
}
