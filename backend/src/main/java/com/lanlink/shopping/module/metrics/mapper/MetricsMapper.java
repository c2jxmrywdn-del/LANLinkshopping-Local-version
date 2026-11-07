package com.lanlink.shopping.module.metrics.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

/**
 * 平台经营指标看板聚合 SQL（只读，直查 t_traffic_event / t_order / t_ad_campaign 原始表）。
 * 窗口口径：近 N 个自然日（含今日），与商户流量中心 TrafficMapper.trendByDay 的
 * DATE(x)>=DATE_SUB(CURDATE(),INTERVAL (N-1) DAY) 对齐，便于逐日手工复算。
 * 注意 source_type 为自由列（公开埋点端点可落 NULL 行），分组一律 COALESCE(source_type,'unknown')。
 */
@Mapper
public interface MetricsMapper {

    /** 全站漏斗：按事件类型计数（VISIT/VIEW_PRODUCT/CLICK_PRODUCT/ADD_CART/PAY...） */
    @Select("SELECT event_type eventType, COUNT(*) cnt FROM t_traffic_event " +
            "WHERE event_time>=DATE_SUB(CURDATE(),INTERVAL (#{days} - 1) DAY) " +
            "GROUP BY event_type")
    List<Map<String, Object>> funnelByType(@Param("days") int days);

    /** 全站漏斗逐日明细：浏览/点击/加购/支付计数（SUM(布尔) 经 JDBC 返回 Decimal，服务层归一） */
    @Select("SELECT DATE_FORMAT(event_time,'%Y-%m-%d') date, " +
            "  COALESCE(SUM(event_type='VISIT'),0) visits, " +
            "  COALESCE(SUM(event_type='VIEW_PRODUCT'),0) views, " +
            "  COALESCE(SUM(event_type='CLICK_PRODUCT'),0) clicks, " +
            "  COALESCE(SUM(event_type='ADD_CART'),0) carts, " +
            "  COALESCE(SUM(event_type='PAY'),0) pays " +
            "FROM t_traffic_event " +
            "WHERE event_time>=DATE_SUB(CURDATE(),INTERVAL (#{days} - 1) DAY) " +
            "GROUP BY DATE_FORMAT(event_time,'%Y-%m-%d') ORDER BY date")
    List<Map<String, Object>> funnelDaily(@Param("days") int days);

    /** 分来源浏览/点击（CTR 服务层算）；COALESCE 防 NULL source 行丢失 */
    @Select("SELECT COALESCE(source_type,'unknown') source, " +
            "  COALESCE(SUM(event_type='VIEW_PRODUCT'),0) views, " +
            "  COALESCE(SUM(event_type='CLICK_PRODUCT'),0) clicks " +
            "FROM t_traffic_event " +
            "WHERE event_time>=DATE_SUB(CURDATE(),INTERVAL (#{days} - 1) DAY) " +
            "GROUP BY COALESCE(source_type,'unknown') ORDER BY views DESC")
    List<Map<String, Object>> bySource(@Param("days") int days);

    /** 交易护栏逐日：GMV(实付订单 total_amount)/订单数/付款用户数，AOV 服务层算 */
    @Select("SELECT DATE_FORMAT(pay_time,'%Y-%m-%d') date, " +
            "  COALESCE(SUM(total_amount),0) gmv, " +
            "  COUNT(*) orders, " +
            "  COUNT(DISTINCT user_id) payUsers " +
            "FROM t_order " +
            "WHERE pay_status=1 AND DATE(pay_time)>=DATE_SUB(CURDATE(),INTERVAL (#{days} - 1) DAY) " +
            "GROUP BY DATE_FORMAT(pay_time,'%Y-%m-%d') ORDER BY date")
    List<Map<String, Object>> dailyTrade(@Param("days") int days);

    /** 广告推广汇总（全量口径：曝光/点击/花费/投放中计划数） */
    @Select("SELECT COUNT(*) campaigns, " +
            "  COALESCE(SUM(status='active'),0) activeCampaigns, " +
            "  COALESCE(SUM(impressions),0) impressions, " +
            "  COALESCE(SUM(clicks),0) clicks, " +
            "  COALESCE(SUM(spent),0) spent " +
            "FROM t_ad_campaign WHERE deleted=0")
    Map<String, Object> adsSummary();
}
