package com.lanlink.shopping.vo;

import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.OrderItem;
import lombok.Data;
import java.util.List;

@Data
public class OrderDetailVO {
    private Order order;
    private List<OrderItem> items;
}
