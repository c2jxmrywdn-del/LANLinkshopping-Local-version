package com.lanlink.shopping.module.settlement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.settlement.entity.MerchantEarning;
import org.apache.ibatis.annotations.Mapper;

/** 商户收益台账 Mapper（包路径命中 @MapperScan 的 com.lanlink.shopping.module.*.mapper 通配）。 */
@Mapper
public interface MerchantEarningMapper extends BaseMapper<MerchantEarning> {
}
