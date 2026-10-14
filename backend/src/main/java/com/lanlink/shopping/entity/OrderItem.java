package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;

@Data
@TableName("t_order_item")
public class OrderItem {
    @TableId(type = IdType.AUTO)
    private Long itemId;
    private String orderNo;
    private Long prodId;
    private String prodName;
    private BigDecimal price;
    private Integer quantity;
    private BigDecimal subtotal;
    private String coverUrl;
}
