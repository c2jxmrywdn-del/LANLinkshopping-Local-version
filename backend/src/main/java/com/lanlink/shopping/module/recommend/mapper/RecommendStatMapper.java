package com.lanlink.shopping.module.recommend.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

/**
 * 推荐统计 SQL（共现矩阵/热度榜的物化与查询）。
 * 物化策略：数据量小（万级行内），直接 MySQL 全量重算 + ON DUPLICATE KEY 覆盖，幂等可回滚。
 */
@Mapper
public interface RecommendStatMapper {

    /** 共现矩阵：近 30 天 session 内加权共现（VIEW=1,CLICK=2,ADD_CART=3,PAY=5），全量重建 */
    @Delete("DELETE FROM t_prod_cooccur")
    int clearCooccur();

    @Insert("INSERT INTO t_prod_cooccur(prod_a, prod_b, cnt, score) " +
            "SELECT pairs.a_id, pairs.b_id, SUM(pairs.w), 0 FROM (" +
            "  SELECT t1.product_id a_id, t2.product_id b_id, " +
            "    CASE t1.event_type WHEN 'PAY' THEN 5 WHEN 'ADD_CART' THEN 3 WHEN 'CLICK_PRODUCT' THEN 2 ELSE 1 END w " +
            "  FROM t_traffic_event t1 JOIN t_traffic_event t2 " +
            "    ON t1.session_id=t2.session_id AND t1.product_id<>t2.product_id " +
            "  WHERE t1.event_time>=DATE_SUB(NOW(),INTERVAL 30 DAY) " +
            "    AND t2.event_time>=DATE_SUB(NOW(),INTERVAL 30 DAY) " +
            "    AND t1.product_id IS NOT NULL AND t2.product_id IS NOT NULL " +
            "    AND t1.event_type IN ('VIEW_PRODUCT','CLICK_PRODUCT','ADD_CART','PAY') " +
            "    AND t2.event_type IN ('VIEW_PRODUCT','CLICK_PRODUCT','ADD_CART','PAY')" +
            ") pairs GROUP BY pairs.a_id, pairs.b_id " +
            "ON DUPLICATE KEY UPDATE cnt=VALUES(cnt)")
    int rebuildCooccurCounts();

    /** 余弦归一：score = cnt / sqrt(total_a * total_b)（total_a=prod_a 全部共现cnt和，total_b 同理） */
    @Update("UPDATE t_prod_cooccur c JOIN (" +
            "  SELECT prod_a, SUM(cnt) ta FROM t_prod_cooccur GROUP BY prod_a" +
            ") ta ON c.prod_a=ta.prod_a JOIN (" +
            "  SELECT prod_b, SUM(cnt) tb FROM t_prod_cooccur GROUP BY prod_b" +
            ") tb ON c.prod_b=tb.prod_b " +
            "SET c.score = ROUND(c.cnt / SQRT(ta.ta * tb.tb), 6)")
    int normalizeCooccurCosine();

    /** 看了又看：来源商品 → 共现商品按余弦分降序 */
    @Select("SELECT prod_b FROM t_prod_cooccur WHERE prod_a=#{prodId} ORDER BY score DESC, cnt DESC LIMIT #{n}")
    List<Long> similarIds(@Param("prodId") Long prodId, @Param("n") int n);

    /** 热度榜 TopN（统计表为空时调用方回退 t_product.sales） */
    @Select("SELECT prod_id FROM t_prod_hot_stat WHERE stat_date=(SELECT MAX(stat_date) FROM t_prod_hot_stat) " +
            "ORDER BY hot_score DESC LIMIT #{n}")
    List<Long> hotTopIds(@Param("n") int n);

    /** 热度榜含分值（推流个性化用，归一后作热度项） */
    @Select("SELECT prod_id prodId, hot_score hotScore FROM t_prod_hot_stat " +
            "WHERE stat_date=(SELECT MAX(stat_date) FROM t_prod_hot_stat) " +
            "ORDER BY hot_score DESC LIMIT #{n}")
    List<Map<String, Object>> hotTopWithScores(@Param("n") int n);

    /** 热度榜全量重算：VIEW/CART/PAY 汇总 + Wilson 下界×时间衰减（30 天窗口） */
    @Delete("DELETE FROM t_prod_hot_stat")
    int clearHotStat();

    @Insert("INSERT INTO t_prod_hot_stat(prod_id, ind_id, cat_id, view_cnt, cart_cnt, pay_cnt, hot_score, stat_date) " +
            "SELECT p.prod_id, p.ind_id, p.cat_id, " +
            "  COALESCE(SUM(e.event_type='VIEW_PRODUCT'),0), COALESCE(SUM(e.event_type='ADD_CART'),0), COALESCE(SUM(e.event_type='PAY'),0), 0, CURDATE() " +
            "FROM t_product p LEFT JOIN t_traffic_event e " +
            "  ON e.product_id=p.prod_id AND e.event_time>=DATE_SUB(NOW(),INTERVAL 30 DAY) " +
            "   AND e.event_type IN ('VIEW_PRODUCT','ADD_CART','PAY') " +
            "WHERE p.status=1 AND p.review_status=1 AND p.deleted=0 " +
            "GROUP BY p.prod_id, p.ind_id, p.cat_id")
    int loadHotCounters();

    /** Wilson 下界（pay/(pay+view) 置信下界，销量作先验弱信号并入 n） */
    @Update("UPDATE t_prod_hot_stat h JOIN t_product p ON p.prod_id=h.prod_id " +
            "SET h.hot_score = ROUND( " +
            "  ((h.pay_cnt + 0.02*p.sales) + 1.9208) / ((h.pay_cnt + 0.02*p.sales) + (h.view_cnt + 0.02*p.sales) + 3.8416) - " +
            "  1.96 * SQRT( ((h.pay_cnt + 0.02*p.sales) * (h.view_cnt + 0.02*p.sales)) / GREATEST((h.pay_cnt + 0.02*p.sales) + (h.view_cnt + 0.02*p.sales) + 3.8416, 0.0001) ) / " +
            "       (1 + 3.8416 / GREATEST((h.pay_cnt + 0.02*p.sales) + (h.view_cnt + 0.02*p.sales) + 3.8416, 0.0001)) " +
            ", 6) ")
    int computeHotWilson();

    @Update("UPDATE t_prod_hot_stat h JOIN (" +
            "  SELECT prod_id, TIMESTAMPDIFF(DAY, MIN(create_time), NOW()) age FROM t_product GROUP BY prod_id" +
            ") a ON h.prod_id=a.prod_id " +
            "SET h.hot_score = ROUND(h.hot_score / POWER(a.age + 2, 0.4), 6)")
    int applyHotTimeDecay();
}
