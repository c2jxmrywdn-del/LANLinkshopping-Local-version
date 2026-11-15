package com.lanlink.shopping.module.sku.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品 SKU 组合：一行 = 商品 + 一组规格值（spec_value_ids 为升序逗号拼接的规格值 ID）。
 * uk_sku_combo(prod_id, spec_value_ids) 保证同一组合只有一行；
 * uk_sku_code(prod_id, sku_code) 保证同商品下商家编码不重复（sku_code 可空，MySQL 唯一键允许多个 NULL）。
 * 与 support/SkuSchemaInitializer.java + sql/schema.sql 的 t_product_sku 双写一致。
 */
@Data
@TableName("t_product_sku")
public class ProductSku {
    @TableId(type = IdType.AUTO)
    private Long skuId;
    private Long prodId;
    private String skuCode;
    private String specValueIds;
    private BigDecimal price;
    private Integer stock;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}