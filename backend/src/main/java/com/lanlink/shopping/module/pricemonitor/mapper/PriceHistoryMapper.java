package com.lanlink.shopping.module.pricemonitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.pricemonitor.entity.PriceHistory;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface PriceHistoryMapper extends BaseMapper<PriceHistory> {

    /** 商品按日的末次价格序列（走势图数据源：每日最后一条记录的 new_price） */
    @Select("SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS day, " +
            "SUBSTRING_INDEX(GROUP_CONCAT(new_price ORDER BY create_time DESC), ',', 1) AS price " +
            "FROM t_price_history WHERE prod_id = #{prodId} " +
            "GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d') ORDER BY day")
    List<Map<String, Object>> dailyLastPrices(@Param("prodId") Long prodId);

    /** 商品按日的 OHLC（K 线数据源）：开=当日首条旧价，收=当日末条新价，高/低=当日新旧价极值。 */
    @Select("SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS day, " +
            "SUBSTRING_INDEX(GROUP_CONCAT(old_price ORDER BY create_time ASC), ',', 1) AS open, " +
            "MAX(GREATEST(old_price, new_price)) AS high, " +
            "MIN(LEAST(old_price, new_price)) AS low, " +
            "SUBSTRING_INDEX(GROUP_CONCAT(new_price ORDER BY create_time DESC), ',', 1) AS close, " +
            "COUNT(*) AS trades " +
            "FROM t_price_history WHERE prod_id = #{prodId} " +
            "GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d') ORDER BY day")
    List<Map<String, Object>> dailyOhlc(@Param("prodId") Long prodId);

    /** 首页每日行情：有调价留痕的在售商品（按最近调价时间排序）。 */
    @Select("SELECT p.prod_id AS prodId, p.title, p.price, p.cover_url AS coverUrl, " +
            "p.ind_id AS indId, p.sales, p.brand, COUNT(h.id) AS changes, " +
            "MAX(h.create_time) AS lastChangeTime " +
            "FROM t_product p JOIN t_price_history h ON h.prod_id = p.prod_id " +
            "WHERE p.status = 1 AND p.review_status = 1 AND p.price >= 1 " +
            "GROUP BY p.prod_id, p.title, p.price, p.cover_url, p.ind_id, p.sales, p.brand " +
            "ORDER BY MAX(h.create_time) DESC LIMIT #{limit}")
    List<Map<String, Object>> productsWithHistory(@Param("limit") int limit);

    /** 全平台近期价格变动排行（管理端异常监控：按绝对变动幅度降序） */
    @Select("SELECT h.*, p.title AS prod_title, p.cover_url AS cover_url " +
            "FROM t_price_history h JOIN t_product p ON p.prod_id = h.prod_id " +
            "WHERE h.create_time >= #{since} " +
            "ORDER BY ABS(h.change_pct) DESC LIMIT #{limit}")
    List<Map<String, Object>> recentChanges(@Param("since") java.time.LocalDateTime since,
                                            @Param("limit") int limit);
}
