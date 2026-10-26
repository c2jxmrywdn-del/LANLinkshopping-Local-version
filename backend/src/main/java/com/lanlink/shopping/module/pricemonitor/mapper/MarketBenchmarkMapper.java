package com.lanlink.shopping.module.pricemonitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.pricemonitor.entity.MarketBenchmark;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

public interface MarketBenchmarkMapper extends BaseMapper<MarketBenchmark> {

    /** 行业现售商品均价（在售且过审）——当日基准的数据源 */
    @Select("SELECT COALESCE(AVG(price), 0) FROM t_product " +
            "WHERE ind_id = #{indId} AND deleted = 0 AND status = 1 AND review_status = 1")
    BigDecimal avgPriceByIndustry(@Param("indId") Long indId);
}
