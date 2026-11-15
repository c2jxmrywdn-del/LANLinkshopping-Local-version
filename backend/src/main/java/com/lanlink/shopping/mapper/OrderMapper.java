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

    /** 按订单号反查订单（security-hardening-plan.md P1-6.1）：MQ 消费侧校验载荷真伪用 */
    @Select("SELECT * FROM t_order WHERE order_no = #{orderNo}")
    Order selectByOrderNo(@Param("orderNo") String orderNo);

    /** 商户视角 WHERE 片段：订单包含本商户商品（order_item→product 关联；即使商品后续软删除，历史订单仍属本店） */
    String MINE_EXISTS =
            "EXISTS(SELECT 1 FROM t_order_item oi JOIN t_product p ON oi.prod_id = p.prod_id "
            + "WHERE oi.order_no = o.order_no AND p.mer_id = #{merId})";

    /**
     * 商户订单分页：仅返回包含本商户商品的订单，可按支付状态/订单状态/退款/关键词过滤。
     * 关键词匹配订单号或本商户商品名。
     */
    @Select("<script>SELECT o.* FROM t_order o WHERE " + MINE_EXISTS
            + "<if test='payStatus != null and payStatus == 0'> AND o.pay_status = 0 AND o.order_status = 0</if>"
            + "<if test='payStatus != null and payStatus != 0'> AND o.pay_status = #{payStatus}</if>"
            + "<if test='orderStatus != null and orderStatus == 0'> AND o.order_status IN (0, 4)</if>"
            + "<if test='orderStatus != null and orderStatus == 1'> AND o.order_status IN (1, 4)</if>"
            + "<if test='orderStatus != null and orderStatus != 0 and orderStatus != 1'> AND o.order_status = #{orderStatus}</if>"
            + "<if test=\"refundFlag == 'applied'\"> AND o.refund_status = 'applied'</if>"
            + "<if test=\"refundFlag == 'pending_audit'\"> AND o.refund_status IN ('applied', 'shipping_back', 'received', 'dispute')</if>"
            + "<if test=\"refundFlag == 'any'\"> AND o.refund_status IS NOT NULL AND o.refund_status != 'none'</if>"
            + "<if test='keyword != null and keyword != \"\"'> AND (o.order_no LIKE CONCAT('%',#{keyword},'%')"
            + " OR EXISTS(SELECT 1 FROM t_order_item oi2 JOIN t_product p2 ON oi2.prod_id = p2.prod_id"
            + " WHERE oi2.order_no = o.order_no AND p2.mer_id = #{merId}"
            + " AND oi2.prod_name LIKE CONCAT('%',#{keyword},'%')))</if>"
            + " ORDER BY o.create_time DESC</script>")
    Page<Order> merchantPage(Page<Order> page, @Param("merId") Long merId,
                             @Param("payStatus") Integer payStatus, @Param("orderStatus") Integer orderStatus,
                             @Param("refundFlag") String refundFlag, @Param("keyword") String keyword);

    /** 本商户商品在指定订单中的明细（用于「我的金额」小计与订单详情） */
    @Select("<script>SELECT oi.* FROM t_order_item oi JOIN t_product p ON oi.prod_id = p.prod_id"
            + " WHERE p.mer_id = #{merId} AND oi.order_no IN"
            + " <foreach collection='orderNos' item='n' open='(' separator=',' close=')'>#{n}</foreach>"
            + " ORDER BY oi.item_id</script>")
    List<OrderItem> merchantItems(@Param("merId") Long merId, @Param("orderNos") List<String> orderNos);

    /** 含指定商户商品的全部订单号（运单列表按商户隔离用） */
    @Select("SELECT DISTINCT oi.order_no FROM t_order_item oi JOIN t_product p ON oi.prod_id = p.prod_id "
            + "WHERE p.mer_id = #{merId}")
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
            "WHERE order_no = #{orderNo} AND pay_status = 0 AND order_status = 0")
    int markPaidAtomic(@Param("orderNo") String orderNo);

    /**
     * 超时关单的条件流转：只允许「未支付 + 待发货 + 非账期」的订单被系统关闭。
     *
     * <p>不能复用 {@link #cancelAtomic}：后者只判 order_status&lt;&gt;3，会把已支付订单也关掉；
     * 账期单的 pay_status 语义是"占用授信"（由 cancelAtomic 的 IF 置 2），不属于超时未付，
     * 必须排除。与 markPaidAtomic 互为对方的否定条件，保证「已取消」和「已支付」不会同时为真。
     */
    @Update("UPDATE t_order SET order_status = 3, update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND pay_status = 0 AND order_status = 0 " +
            "AND (pay_type IS NULL OR pay_type <> 'term')")
    int closeUnpaidAtomic(@Param("orderNo") String orderNo);

    /**
     * 回填渠道与渠道流水号（只对已支付订单生效）。
     *
     * <p>拆成独立语句的原因：markPaidAtomic 用一条条件更新完成「未支付→已支付」的竞态裁决，
     * 渠道信息只有裁决成功的那一方才该写入；把渠道字段塞进同一条 SQL 会让失败方也覆盖渠道。
     */
    @Update("UPDATE t_order SET pay_channel = #{channel}, " +
            "transaction_id = COALESCE(#{transactionId}, transaction_id), update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND pay_status = 1")
    int bindPaidChannel(@Param("orderNo") String orderNo,
                        @Param("channel") String channel,
                        @Param("transactionId") String transactionId);

    /**
     * 发货状态原子流转（防并发重复发货/多次创建运单）。
     * 仅当订单已支付(pay_status=1)且处于待发货(order_status=0)时置为已发货(order_status=1)，
     * 返回 1 表示发货成功，返回 0 表示已被并发处理或状态不符。
     */
    @Update("UPDATE t_order SET order_status = 1, update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND order_status = 0 AND pay_status = 1")
    int markShippedAtomic(@Param("orderNo") String orderNo);

    /**
     * 拆单发货-部分发货原子流转：仅当订单已支付(pay_status=1)且处于待发货(0)或部分发货(4)时置为部分发货(4)。
     */
    @Update("UPDATE t_order SET order_status = 4, update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND order_status IN (0, 4) AND pay_status = 1")
    int markPartialShippedAtomic(@Param("orderNo") String orderNo);

    /**
     * 拆单发货-全部发完原子流转：仅当订单已支付(pay_status=1)且处于待发货(0)或部分发货(4)时置为已发货(1)。
     */
    @Update("UPDATE t_order SET order_status = 1, update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND order_status IN (0, 4) AND pay_status = 1")
    int markFullyShippedAtomic(@Param("orderNo") String orderNo);

    /**
     * 订单完成原子流转（确认收货闭环）：仅已发货(order_status=1)或部分发货(order_status=4)时置为已完成(order_status=2)。
     * 买家确认收货 / 运单签收联动 / 超时自动确认共用；返回 0 表示状态不符或已被并发处理。
     */
    @Update("UPDATE t_order SET order_status = 2, finish_time = NOW(), update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND order_status IN (1, 4)")
    int markCompletedAtomic(@Param("orderNo") String orderNo);

    /** 超时自动确认收货：已发货超过 N 天（按运单发货时间）仍未完成的订单批量置为已完成 */
    @Update("UPDATE t_order o JOIN t_logistics_waybill w "
            + "ON w.order_no COLLATE utf8mb4_unicode_ci = o.order_no COLLATE utf8mb4_unicode_ci "
            + "SET o.order_status = 2, o.finish_time = NOW(), o.update_time = NOW() "
            + "WHERE o.order_status = 1 AND o.pay_status = 1 AND w.ship_time < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int autoCompleteShipped(@Param("days") int days);

    /**
     * 退款出款「处理中」原子占位（P0-C 退款出款收口，防并发双退）。
     *
     * <p><b>守卫矩阵（状态 × 角色 × 入口）</b>：
     * <ul>
     *   <li>角色：仅平台运营（{@code admin:all}）可触发「出款」，商户/买家侧永远只改状态不碰钱；</li>
     *   <li>入口：{@code POST /payment/refund} 与 {@code POST /admin/refund/review/**} 的出款分支；
     *       两者都必须先有 t_refund_approval 中未消费的 PLATFORM/APPROVE 复核行
     *       （校验与消费在 {@code PaymentService#refund}，本 SQL 只做第二道状态闸）；</li>
     *   <li>允许状态：仅「商户已初审通过 / 退货已寄回 / 已收货 / 已有部分退款到账」这些
     *       “钱已经具备出款条件”的状态 → processing；</li>
     *   <li>永久禁止：{@code rejected}（驳回是终态，出款不得推翻驳回；异议走售后工单申诉后由平台
     *       {@code reopenForAppeal} 重开复核）、{@code success}（已全额退）、{@code processing}
     *       （并发在途）、{@code applied}（仅用户申请，商户未初审）、{@code none}/NULL
     *       （无退款依据）——这些状态一律返回 0 行，调用方抛 403/400。</li>
     * </ul>
     *
     * <p><b>为什么把 {@code rejected}/{@code none}/{@code applied} 从旧白名单摘掉</b>：旧语句
     * {@code IN ('applied','partial','none','rejected','merchant_approved')} 让“已驳回”和
     * “未初审”的订单也能被平台退款接口占位后直接出款——前者等于用出款推翻驳回（可绕过审批链），
     * 后者等于跳过商户初审。收口后“出款”只认已经走完审批链的状态，且平台复核行是另一把锁。
     *
     * @return 1 = 本次拿到出款权；0 = 状态不允许或已被并发处理（调用方禁止继续调用渠道）
     */
    @Update("UPDATE t_order SET refund_status = 'processing', update_time = NOW() " +
            "WHERE order_no = #{orderNo} " +
            "AND refund_status IN ('merchant_approved', 'shipping_back', 'received', 'partial')")
    int markRefundProcessingAtomic(@Param("orderNo") String orderNo);

    /**
     * 退款状态原子流转（需求 2：防重复退款申请/并发篡改退款金额）。
     * 仅当订单当前退款状态为 none/rejected/partial/空 时允许置为 applied，
     * 返回 0 表示重复申请（partial = 部分退款已到账，剩余金额可再次申请）。
     */
    @Update("UPDATE t_order SET refund_status = 'applied', refund_reason = #{reason}, " +
            "refund_evidences = #{evidences}, " +
            "refund_apply_time = NOW(), refund_reject_reason = NULL, update_time = NOW() " +
            "WHERE order_no = #{orderNo} " +
            "AND (refund_status IS NULL OR refund_status IN ('none','rejected','partial'))")
    int applyRefundAtomic(@Param("orderNo") String orderNo, @Param("reason") String reason,
                          @Param("evidences") String evidences);

    /**
     * 订单取消原子流转（防并发重复取消 / 先读后改竞态覆盖）。
     * 仅当订单既未取消（order_status&lt;&gt;3）也未全额退款（order_status&lt;&gt;5）时置为已取消(order_status=3)；
     * 账期订单(pay_type='term')在同一语句内同步置 pay_status=2（与原 Java 侧 term 置 2 口径一致）。
     * 返回 1 表示本次完成取消，返回 0 表示已被并发取消/已全额退款/状态已变化，
     * 调用方须抛业务异常整体回滚事务（避免库存已恢复而订单未取消的半取消状态）。
     *
     * <p>P1-3 修复：原条件只写 {@code order_status <> 3}，会把已全额退款（order_status=5）的订单再取消一次，
     * 触发 {@code restoreStock} 二次回补造成库存虚增；故收紧为 {@code NOT IN (3,5)}。
     */
    @Update("UPDATE t_order SET order_status = 3, " +
            "pay_status = IF(pay_type = 'term', 2, pay_status), update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND order_status NOT IN (3, 5)")
    int cancelAtomic(@Param("orderNo") String orderNo);

    /**
     * 平台退款驳回原子流转（防并发竞态覆盖）。
     * 当订单当前为申请中或商户初审通过（refund_status IN ('applied', 'merchant_approved')）时置为 rejected 并记录驳回原因，
     * 返回 0 表示并发请求已抢先处理（状态已被并行操作变更），不得覆盖其结果。
     */
    @Update("UPDATE t_order SET refund_status = 'rejected', refund_reject_reason = #{reason}, " +
            "update_time = NOW() WHERE order_no = #{orderNo} AND refund_status IN ('applied', 'merchant_approved')")
    int rejectRefundAtomic(@Param("orderNo") String orderNo, @Param("reason") String reason);

    /**
     * 商户退款初审通过原子流转：仅当订单退款状态为 applied 时置为 merchant_approved。
     */
    @Update("UPDATE t_order SET refund_status = 'merchant_approved', update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND refund_status = 'applied'")
    int merchantApproveRefundAtomic(@Param("orderNo") String orderNo);

    /**
     * 商户退款初审驳回原子流转：仅当订单退款状态为 applied 时置为 rejected 并记录驳回原因。
     */
    @Update("UPDATE t_order SET refund_status = 'rejected', refund_reject_reason = #{reason}, update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND refund_status = 'applied'")
    int merchantRejectRefundAtomic(@Param("orderNo") String orderNo, @Param("reason") String reason);

    /**
     * 买家回填退货物流单号原子流转：仅当商户初审通过 (merchant_approved) 时置为 shipping_back。
     */
    @Update("UPDATE t_order SET refund_status = 'shipping_back', return_carrier_name = #{carrier}, " +
            "return_waybill_no = #{waybillNo}, update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND refund_status = 'merchant_approved'")
    int shipBackAtomic(@Param("orderNo") String orderNo,
                       @Param("carrier") String carrier,
                       @Param("waybillNo") String waybillNo);

    /**
     * 记录质检验货结果
     */
    @Update("UPDATE t_order SET inspect_status = #{status}, inspect_result = #{result}, " +
            "inspect_time = NOW(), update_time = NOW() WHERE order_no = #{orderNo}")
    int updateInspectResult(@Param("orderNo") String orderNo,
                            @Param("status") String status,
                            @Param("result") String result);

    /**
     * 平台或系统看门狗超时卡单查询：扫描 processing 状态超 N 分钟的订单
     */
    @Select("SELECT * FROM t_order WHERE refund_status = 'processing' " +
            "AND update_time < DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE) " +
            "ORDER BY update_time ASC LIMIT #{limit}")
    List<Order> listStaleProcessingRefunds(@Param("minutes") int minutes, @Param("limit") int limit);
}
