package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 直播间关联商品。
 */
@Data
@TableName("t_live_product")
public class LiveProduct {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long roomId;
    private Long prodId;
    private BigDecimal livePrice;
    private Integer liveStock;
    private Integer sortOrder;
    private LocalDateTime createTime;

    /** 关联完整商品详情 (非数据库字段) */
    @TableField(exist = false)
    private Product product;
}
