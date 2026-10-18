package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.Order;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

public interface OrderMapper extends BaseMapper<Order> {

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
     * 退款状态原子流转（需求 2：防重复退款申请/并发篡改退款金额）。
     * 仅当订单当前退款状态为 none/rejected/空 时允许置为 applied，返回 0 表示重复申请。
     */
    @Update("UPDATE t_order SET refund_status = 'applied', refund_reason = #{reason}, " +
            "refund_apply_time = NOW(), refund_reject_reason = NULL, update_time = NOW() " +
            "WHERE order_no = #{orderNo} " +
            "AND (refund_status IS NULL OR refund_status IN ('none','rejected'))")
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
