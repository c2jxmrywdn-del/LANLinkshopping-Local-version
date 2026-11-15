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

    /**
     * 品类现售商品均价（在售且过审）。
     *
     * <p>为什么需要它：只按 ind_id 求平均会把同一行业里量级完全不同的商品混在一起
     * （实测「建筑行业」里 ¥26.5 的管材与 ¥3980 的钢材同池平均，导致 94% 的商品
     * 被判定为「显著偏离基准」的 URGENT）。品类（cat_id）才是价格可比的最小粒度。
     */
    @Select("SELECT COALESCE(AVG(price), 0) FROM t_product " +
            "WHERE cat_id = #{catId} AND deleted = 0 AND status = 1 AND review_status = 1")
    BigDecimal avgPriceByCategory(@Param("catId") Long catId);

    /** 某品类下的在售商品数，用于判断样本是否足够（样本太少时不用品类基准） */
    @Select("SELECT COUNT(*) FROM t_product " +
            "WHERE cat_id = #{catId} AND deleted = 0 AND status = 1 AND review_status = 1")
    int countOnSaleByCategory(@Param("catId") Long catId);

    /**
     * 品类价格中位数。
     *
     * <p>为什么用中位数而不是均值：同一品类内价差仍可达数十倍（实测某品类 6800 ~ 24500），
     * 均值会被少数高价商品拉到远离大多数商品的位置，导致绝大多数商品被判「显著偏离」。
     * 中位数对极端值免疫，代表的是「这个品类里最典型的价格」。
     *
     * <p>MySQL 没有 MEDIAN 聚合函数，用窗口函数按行号取中间位（偶数个取两条平均）。
     */
    @Select("SELECT ROUND(AVG(t.price), 2) FROM ("
            + "  SELECT price, ROW_NUMBER() OVER (ORDER BY price) rn, COUNT(*) OVER () cnt"
            + "  FROM t_product WHERE cat_id = #{catId} AND deleted = 0 AND status = 1 AND review_status = 1"
            + ") t WHERE t.rn IN (FLOOR((t.cnt + 1) / 2), CEIL((t.cnt + 1) / 2))")
    BigDecimal medianPriceByCategory(@Param("catId") Long catId);

    /** 行业价格中位数（品类样本不足时的回退口径，理由同上） */
    @Select("SELECT ROUND(AVG(t.price), 2) FROM ("
            + "  SELECT price, ROW_NUMBER() OVER (ORDER BY price) rn, COUNT(*) OVER () cnt"
            + "  FROM t_product WHERE ind_id = #{indId} AND deleted = 0 AND status = 1 AND review_status = 1"
            + ") t WHERE t.rn IN (FLOOR((t.cnt + 1) / 2), CEIL((t.cnt + 1) / 2))")
    BigDecimal medianPriceByIndustry(@Param("indId") Long indId);
}
