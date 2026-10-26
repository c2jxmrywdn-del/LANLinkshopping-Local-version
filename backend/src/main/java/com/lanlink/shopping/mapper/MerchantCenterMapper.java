package com.lanlink.shopping.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商户中心工作台/资金结算聚合查询。
 * 口径与 TrafficMapper 一致：金额 = 本商户商品明细小计（order_item→product 关联），
 * 已支付口径 pay_status=1。
 */
@Mapper
public interface MerchantCenterMapper {

    String MINE = " oi.prod_id IN (SELECT prod_id FROM t_product WHERE mer_id = #{merId} AND deleted = 0) ";

    /** 商户商品计数（可按审核状态过滤） */
    @Select("SELECT COUNT(*) FROM t_product WHERE mer_id = #{merId} AND deleted = 0"
            + " AND (#{reviewStatus} IS NULL OR review_status = #{reviewStatus})")
    long countProducts(@Param("merId") Long merId, @Param("reviewStatus") Integer reviewStatus);

    /** 待发货订单数（已支付且 order_status=0，且含本商户商品） */
    @Select("SELECT COUNT(DISTINCT o.order_no) FROM t_order o JOIN t_order_item oi ON oi.order_no = o.order_no"
            + " WHERE o.pay_status = 1 AND o.order_status = 0 AND" + MINE)
    long countPendingShip(@Param("merId") Long merId);

    /** 已发货(在途)订单数：order_status=1 */
    @Select("SELECT COUNT(DISTINCT o.order_no) FROM t_order o JOIN t_order_item oi ON oi.order_no = o.order_no"
            + " WHERE o.pay_status = 1 AND o.order_status = 1 AND" + MINE)
    long countShipping(@Param("merId") Long merId);

    /** 待平台处理的退款申请数（refund_status='applied'，含本商户商品） */
    @Select("SELECT COUNT(DISTINCT o.order_no) FROM t_order o JOIN t_order_item oi ON oi.order_no = o.order_no"
            + " WHERE o.refund_status = 'applied' AND" + MINE)
    long countRefundPending(@Param("merId") Long merId);

    /** 近 N 个自然日销售额/订单数（含今日；与 fillTrend 的自然日窗口对齐，避免滚动 24h 窗口错位 1 天） */
    @Select("SELECT COALESCE(SUM(oi.subtotal),0) amount, COUNT(DISTINCT o.order_no) orders"
            + " FROM t_order_item oi JOIN t_order o ON oi.order_no = o.order_no"
            + " WHERE o.pay_status = 1 AND DATE(o.pay_time) >= DATE_SUB(CURDATE(), INTERVAL (#{days} - 1) DAY) AND" + MINE)
    Map<String, Object> sumRecent(@Param("merId") Long merId, @Param("days") int days);

    /** 近 N 个自然日按日销售额（补齐由服务层处理） */
    @Select("SELECT DATE_FORMAT(o.pay_time,'%Y-%m-%d') date, SUM(oi.subtotal) amount, COUNT(DISTINCT o.order_no) orders"
            + " FROM t_order_item oi JOIN t_order o ON oi.order_no = o.order_no"
            + " WHERE o.pay_status = 1 AND DATE(o.pay_time) >= DATE_SUB(CURDATE(), INTERVAL (#{days} - 1) DAY) AND" + MINE
            + " GROUP BY DATE_FORMAT(o.pay_time,'%Y-%m-%d') ORDER BY date")
    List<Map<String, Object>> trendByDay(@Param("merId") Long merId, @Param("days") int days);

    /** 累计已结算收入（历史已支付，本商户商品明细小计） */
    @Select("SELECT COALESCE(SUM(oi.subtotal),0) FROM t_order_item oi JOIN t_order o ON oi.order_no = o.order_no"
            + " WHERE o.pay_status = 1 AND" + MINE)
    BigDecimal sumIncomeTotal(@Param("merId") Long merId);

    /** 累计退款金额（真实退款：pay_status=2 且 refund_status=success；账期单取消同样置 pay_status=2 但无退款资金，须排除） */
    @Select("SELECT COALESCE(SUM(oi.subtotal),0) FROM t_order_item oi JOIN t_order o ON oi.order_no = o.order_no"
            + " WHERE o.pay_status = 2 AND o.refund_status = 'success' AND" + MINE)
    BigDecimal sumRefundTotal(@Param("merId") Long merId);

    /** 待结算：已支付且订单未完成（待发货+已发货；order_status 0/1）的金额，订单完成（2）即转入可结算 */
    @Select("SELECT COALESCE(SUM(oi.subtotal),0) FROM t_order_item oi JOIN t_order o ON oi.order_no = o.order_no"
            + " WHERE o.pay_status = 1 AND o.order_status < 2 AND" + MINE)
    BigDecimal sumPendingSettle(@Param("merId") Long merId);

    /** 结算明细：最近已支付订单（去重，含我的小计），供资金页表格 */
    @Select("SELECT o.order_no orderNo, o.pay_time payTime, o.pay_channel payChannel, o.order_status orderStatus,"
            + " o.refund_status refundStatus, SUM(oi.subtotal) myAmount, COUNT(DISTINCT oi.prod_id) myKinds"
            + " FROM t_order o JOIN t_order_item oi ON oi.order_no = o.order_no"
            + " WHERE o.pay_status IN (1, 2) AND" + MINE
            + " GROUP BY o.order_no, o.pay_time, o.pay_channel, o.order_status, o.refund_status"
            + " ORDER BY o.pay_time DESC LIMIT #{limit}")
    List<Map<String, Object>> settlementRows(@Param("merId") Long merId, @Param("limit") int limit);
}
