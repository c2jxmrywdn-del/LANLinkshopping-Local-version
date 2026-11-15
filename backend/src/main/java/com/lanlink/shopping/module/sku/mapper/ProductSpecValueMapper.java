package com.lanlink.shopping.module.sku.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.sku.entity.ProductSpecValue;
import org.apache.ibatis.annotations.Mapper;

/** 商品规格值 Mapper（包路径亦命中 @MapperScan 的 com.lanlink.shopping.module.*.mapper 通配）。 */
@Mapper
public interface ProductSpecValueMapper extends BaseMapper<ProductSpecValue> {
}