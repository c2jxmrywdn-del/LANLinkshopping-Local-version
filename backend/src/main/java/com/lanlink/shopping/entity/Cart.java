package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_cart")
public class Cart {
    @TableId(type = IdType.AUTO)
    private Long cartId;
    private Long userId;
    private Long prodId;
    private Integer quantity;
    private Integer checked;
    private LocalDateTime createTime;
}
