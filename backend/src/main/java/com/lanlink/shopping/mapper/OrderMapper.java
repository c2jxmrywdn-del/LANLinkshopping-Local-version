package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.OrderItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

public interface OrderMapper extends BaseMapper<Order> {

    /** 商户视角 WHERE 片段：订单包含本商户商品（order_item→product 关联） */
    String MINE_EXISTS =
            "EXISTS(SELECT 1 FROM t_order_item oi JOIN t_product p ON oi.prod_id = p.prod_id "
            + "WHERE oi.order_no = o.order_no AND p.mer_id = #{merId} AND p.deleted = 0)";

    /**
     * 商户订单分页：仅返回包含本商户商品的订单，可按支付状态/订单状态/退款/关键词过滤。
     * 关键词匹配订单号或本商户商品名。
     */
    @Select("<script>SELECT o.* FROM t_order o WHERE " + MINE_EXISTS
            + "<if test='payStatus != null'> AND o.pay_status = #{payStatus}</if>"
            + "<if test='orderStatus != null'> AND o.order_status = #{orderStatus}</if>"
            + "<if test=\"refundFlag == 'applied'\"> AND o.refund_status = 'applied'</if>"
            + "<if test=\"refundFlag == 'any'\"> AND o.refund_status IS NOT NULL AND o.refund_status != 'none'</if>"
            + "<if test='keyword != null and keyword != \"\"'> AND (o.order_no LIKE CONCAT('%',#{keyword},'%')"
            + " OR EXISTS(SELECT 1 FROM t_order_item oi2 JOIN t_product p2 ON oi2.prod_id = p2.prod_id"
            + " WHERE oi2.order_no = o.order_no AND p2.mer_id = #{merId} AND p2.deleted = 0"
            + " AND oi2.prod_name LIKE CONCAT('%',#{keyword},'%')))</if>"
            + " ORDER BY o.create_time DESC</script>")
    Page<Order> merchantPage(Page<Order> page, @Param("merId") Long merId,
                             @Param("payStatus") Integer payStatus, @Param("orderStatus") Integer orderStatus,
                             @Param("refundFlag") String refundFlag, @Param("keyword") String keyword);

    /** 本商户商品在指定订单中的明细（用于「我的金额」小计与订单详情） */
    @Select("<script>SELECT oi.* FROM t_order_item oi JOIN t_product p ON oi.prod_id = p.prod_id"
            + " WHERE p.mer_id = #{merId} AND p.deleted = 0 AND oi.order_no IN"
            + " <foreach collection='orderNos' item='n' open='(' separator=',' close=')'>#{n}</foreach>"
            + " ORDER BY oi.item_id</script>")
    List<OrderItem> merchantItems(@Param("merId") Long merId, @Param("orderNos") List<String> orderNos);

    /** 含指定商户商品的全部订单号（运单列表按商户隔离用） */
    @Select("SELECT DISTINCT oi.order_no FROM t_order_item oi JOIN t_product p ON oi.prod_id = p.prod_id "
            + "WHERE p.mer_id = #{merId} AND p.deleted = 0")
    List<String> merchantOrderNos(@Param("merId") Long merId);

    /** 指定买家本人的全部订单号（运单列表按买家隔离用） */
    @Select("SELECT order_no FROM t_order WHERE user_id = #{userId}")
    List<String> buyerOrderNos(@Param("userId") Long userId);

    /** 用户累计已支付订单金额（pay_status=1） */
    @Select("SELECT COALESCE(SUM(total_amount), 0) FROM t_order " +
            "WHERE user_id = #{userId} AND pay_status = 1")
    BigDecimal sumPaidAmount(@Param("userId") Long userId);

    /**
     * 支付状态原子流转（需求 2：防并发重复支付/防状态篡改）。
     * 仅当订单当前为未支付（pay_status=0）时置为已支付，返回 1 表示本次完成支付、
     * 0 表示并发请求已抢先支付或订单状态不允许支付。
     */
    @Update("UPDATE t_order SET pay_status = 1, pay_time = NOW(), update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND pay_status = 0")
    int markPaidAtomic(@Param("orderNo") String orderNo);

    /**
     * 发货状态原子流转（防并发重复发货/多次创建运单）。
     * 仅当订单已支付(pay_status=1)且处于待发货(order_status=0)时置为已发货(order_status=1)，
     * 返回 1 表示发货成功，返回 0 表示已被并发处理或状态不符。
     */
    @Update("UPDATE t_order SET order_status = 1, update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND order_status = 0 AND pay_status = 1")
    int markShippedAtomic(@Param("orderNo") String orderNo);

    /**
     * 订单完成原子流转（确认收货闭环）：仅已发货(order_status=1)时置为已完成(order_status=2)。
     * 买家确认收货 / 运单签收联动 / 超时自动确认共用；返回 0 表示状态不符或已被并发处理。
     */
    @Update("UPDATE t_order SET order_status = 2, update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND order_status = 1")
    int markCompletedAtomic(@Param("orderNo") String orderNo);

    /** 超时自动确认收货：已发货超过 N 天（按运单发货时间）仍未完成的订单批量置为已完成 */
    @Update("UPDATE t_order o JOIN t_logistics_waybill w ON w.order_no = o.order_no " +
            "SET o.order_status = 2, o.update_time = NOW() " +
            "WHERE o.order_status = 1 AND o.pay_status = 1 AND w.ship_time < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int autoCompleteShipped(@Param("days") int days);

    /** 退款处理中原子流转（防并发双退）：applied → processing，返回 0 表示已被并发处理 */
    @Update("UPDATE t_order SET refund_status = 'processing', update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND refund_status = 'applied'")
    int markRefundProcessingAtomic(@Param("orderNo") String orderNo);

    /**
     * 退款状态原子流转（需求 2：防重复退款申请/并发篡改退款金额）。
     * 仅当订单当前退款状态为 none/rejected/partial/空 时允许置为 applied，
     * 返回 0 表示重复申请（partial = 部分退款已到账，剩余金额可再次申请）。
     */
    @Update("UPDATE t_order SET refund_status = 'applied', refund_reason = #{reason}, " +
            "refund_apply_time = NOW(), refund_reject_reason = NULL, update_time = NOW() " +
            "WHERE order_no = #{orderNo} " +
            "AND (refund_status IS NULL OR refund_status IN ('none','rejected','partial'))")
    int applyRefundAtomic(@Param("orderNo") String orderNo, @Param("reason") String reason);

    /**
     * 退款驳回原子流转（防并发竞态覆盖）。
     * 仅当订单当前为申请中（refund_status='applied'）时置为 rejected 并记录驳回原因，
     * 返回 0 表示并发请求已抢先处理（状态已被并行操作变更），不得覆盖其结果。
     */
    @Update("UPDATE t_order SET refund_status = 'rejected', refund_reject_reason = #{reason}, " +
            "update_time = NOW() WHERE order_no = #{orderNo} AND refund_status = 'applied'")
    int rejectRefundAtomic(@Param("orderNo") String orderNo, @Param("reason") String reason);
}
