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

    String MINE = " oi.prod_id IN (SELECT prod_id FROM t_product WHERE mer_id = #{merId}) ";

    /** 商户商品计数（可按审核状态过滤） */
    @Select("SELECT COUNT(*) FROM t_product WHERE mer_id = #{merId} AND deleted = 0"
            + " AND (#{reviewStatus} IS NULL OR review_status = #{reviewStatus})")
    long countProducts(@Param("merId") Long merId, @Param("reviewStatus") Integer reviewStatus);

    /** 待发货订单数（已支付且 order_status IN (0, 4) 未发货或部分发货待出库，且含本商户商品，排除售后申请中且本商户商品未发完） */
    @Select("SELECT COUNT(DISTINCT o.order_no) FROM t_order o JOIN t_order_item oi ON oi.order_no = o.order_no"
            + " WHERE o.pay_status = 1 AND o.order_status IN (0, 4)"
            + " AND (o.refund_status IS NULL OR o.refund_status IN ('none', 'rejected'))"
            + " AND (oi.quantity - COALESCE(oi.shipped_quantity, 0)) > 0 AND" + MINE)
    long countPendingShip(@Param("merId") Long merId);

    /** 已发货(在途)订单数：order_status=1 或 (order_status=4 且本商户有已发货商品) */
    @Select("SELECT COUNT(DISTINCT o.order_no) FROM t_order o JOIN t_order_item oi ON oi.order_no = o.order_no"
            + " WHERE o.pay_status = 1 AND (o.order_status = 1 OR (o.order_status = 4 AND COALESCE(oi.shipped_quantity, 0) > 0)) AND" + MINE)
    long countShipping(@Param("merId") Long merId);

    /** 待处理的售后申请数（含初审 applied、买家已寄回 shipping_back、仓库质检中 received、验货异常 dispute，含本商户商品） */
    @Select("SELECT COUNT(DISTINCT o.order_no) FROM t_order o JOIN t_order_item oi ON oi.order_no = o.order_no"
            + " WHERE o.refund_status IN ('applied', 'shipping_back', 'received', 'dispute') AND" + MINE)
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

    /** 累计毛收入（历史所有已支付订单明细小计，排除未付款取消订单） */
    @Select("SELECT COALESCE(SUM(oi.subtotal),0) FROM t_order_item oi JOIN t_order o ON oi.order_no = o.order_no"
            + " WHERE o.pay_status IN (1, 2) AND o.order_status <> 3 AND o.pay_time IS NOT NULL AND" + MINE)
    BigDecimal sumIncomeTotal(@Param("merId") Long merId);

    /** 累计退款金额（全额退款 + 部分退款；排除无退款资金的纯取消账期单） */
    @Select("SELECT COALESCE(SUM("
            + " CASE WHEN o.pay_status = 2 AND o.refund_status = 'success' THEN oi.subtotal"
            + "      WHEN o.refund_amount > 0 THEN (o.refund_amount * oi.subtotal / NULLIF(o.total_amount, 0))"
            + "      ELSE 0 END), 0)"
            + " FROM t_order_item oi JOIN t_order o ON oi.order_no = o.order_no"
            + " WHERE (o.pay_status = 2 OR (o.refund_amount IS NOT NULL AND o.refund_amount > 0)) AND" + MINE)
    BigDecimal sumRefundTotal(@Param("merId") Long merId);

    /** 待结算：已支付且订单履约中（待发货 0、已发货 1、部分发货 4）的金额，订单完成（2）即转入可结算 */
    @Select("SELECT COALESCE(SUM(oi.subtotal),0) FROM t_order_item oi JOIN t_order o ON oi.order_no = o.order_no"
            + " WHERE o.pay_status = 1 AND o.order_status IN (0, 1, 4) AND" + MINE)
    BigDecimal sumPendingSettle(@Param("merId") Long merId);

    /** 结算明细：最近已支付订单（去重，含我的小计），供资金页表格 */
    @Select("SELECT o.order_no orderNo, o.pay_time payTime, o.pay_channel payChannel, o.order_status orderStatus,"
            + " o.refund_status refundStatus, SUM(oi.subtotal) myAmount, COUNT(DISTINCT oi.prod_id) myKinds"
            + " FROM t_order o JOIN t_order_item oi ON oi.order_no = o.order_no"
            + " WHERE o.pay_status IN (1, 2) AND o.order_status <> 3 AND o.pay_time IS NOT NULL AND" + MINE
            + " GROUP BY o.order_no, o.pay_time, o.pay_channel, o.order_status, o.refund_status"
            + " ORDER BY o.pay_time DESC LIMIT #{limit}")
    List<Map<String, Object>> settlementRows(@Param("merId") Long merId, @Param("limit") int limit);
}
