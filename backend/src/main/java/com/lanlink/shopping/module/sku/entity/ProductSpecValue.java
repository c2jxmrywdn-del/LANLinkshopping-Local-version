package com.lanlink.shopping.module.sku.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品规格值（如「红色」「XL」），归属某个规格维度。
 * prod_id 为冗余商品锚点：商户归属校验与按商品清理无需回表 JOIN t_product_spec。
 * 与 support/SkuSchemaInitializer.java + sql/schema.sql 的 t_product_spec_value 双写一致。
 */
@Data
@TableName("t_product_spec_value")
public class ProductSpecValue {
    @TableId(type = IdType.AUTO)
    private Long valueId;
    private Long specId;
    private Long prodId;
    private String specValue;
    private Integer sortOrder;
    private LocalDateTime createTime;
}