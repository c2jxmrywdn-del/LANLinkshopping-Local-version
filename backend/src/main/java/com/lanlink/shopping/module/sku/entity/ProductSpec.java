package com.lanlink.shopping.module.sku.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品规格维度（如「颜色」「尺码」），一个商品可挂多个维度，维度下再挂规格值。
 * 与 support/SkuSchemaInitializer.java + sql/schema.sql 的 t_product_spec 双写一致。
 */
@Data
@TableName("t_product_spec")
public class ProductSpec {
    @TableId(type = IdType.AUTO)
    private Long specId;
    private Long prodId;
    private String specName;
    private Integer sortOrder;
    private LocalDateTime createTime;
}