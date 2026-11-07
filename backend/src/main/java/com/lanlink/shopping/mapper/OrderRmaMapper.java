package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.OrderRma;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

public interface OrderRmaMapper extends BaseMapper<OrderRma> {

    /** 根据订单号查询最新一条未取消的 RMA 单 */
    @Select("SELECT * FROM t_order_rma WHERE order_no = #{orderNo} AND deleted = 0 ORDER BY rma_id DESC LIMIT 1")
    OrderRma findLatestByOrderNo(@Param("orderNo") String orderNo);

    /** 商户初审通过原子流转：仅当状态为 buyer_applied 时更新 */
    @Update("UPDATE t_order_rma SET status = 'merchant_approved', merchant_audit_time = NOW(), " +
            "return_receiver_name = #{receiver}, return_receiver_phone = #{phone}, " +
            "return_warehouse_addr = #{addr}, merchant_audit_note = #{note}, update_time = NOW() " +
            "WHERE rma_no = #{rmaNo} AND status = 'buyer_applied' AND deleted = 0")
    int merchantApproveAtomic(@Param("rmaNo") String rmaNo,
                              @Param("receiver") String receiver,
                              @Param("phone") String phone,
                              @Param("addr") String addr,
                              @Param("note") String note);

    /** 买家填报退货物流原子流转：仅当状态为 merchant_approved 时更新为 shipping_back */
    @Update("UPDATE t_order_rma SET status = 'shipping_back', return_carrier_name = #{carrier}, " +
            "return_waybill_no = #{waybillNo}, return_waybill_id = #{waybillId}, " +
            "return_ship_time = NOW(), return_ship_remark = #{remark}, update_time = NOW() " +
            "WHERE rma_no = #{rmaNo} AND status = 'merchant_approved' AND deleted = 0")
    int shipBackAtomic(@Param("rmaNo") String rmaNo,
                       @Param("carrier") String carrier,
                       @Param("waybillNo") String waybillNo,
                       @Param("waybillId") Long waybillId,
                       @Param("remark") String remark);

    /** 商户质检验货原子流转：仅当状态处于 shipping_back 或 received 时更新 */
    @Update("UPDATE t_order_rma SET status = #{status}, inspect_time = NOW(), inspector_id = #{inspectorId}, " +
            "inspection_status = #{inspectStatus}, inspection_result = #{result}, " +
            "is_restocked = #{isRestocked}, update_time = NOW() " +
            "WHERE rma_no = #{rmaNo} AND status IN ('shipping_back', 'received') AND deleted = 0")
    int inspectAtomic(@Param("rmaNo") String rmaNo,
                      @Param("status") String status,
                      @Param("inspectorId") Long inspectorId,
                      @Param("inspectStatus") String inspectStatus,
                      @Param("result") String result,
                      @Param("isRestocked") int isRestocked);

    /** 退款结算完成原子流转 */
    @Update("UPDATE t_order_rma SET status = 'success', actual_refund_amount = #{actualAmount}, " +
            "out_refund_no = #{outRefundNo}, channel_refund_id = #{channelRefundId}, " +
            "refund_time = NOW(), update_time = NOW() " +
            "WHERE rma_no = #{rmaNo} AND status <> 'success' AND deleted = 0")
    int settleSuccessAtomic(@Param("rmaNo") String rmaNo,
                            @Param("actualAmount") BigDecimal actualAmount,
                            @Param("outRefundNo") String outRefundNo,
                            @Param("channelRefundId") String channelRefundId);

    /** 驳回 RMA 申请 */
    @Update("UPDATE t_order_rma SET status = 'rejected', merchant_audit_note = #{reason}, " +
            "merchant_audit_time = NOW(), update_time = NOW() " +
            "WHERE rma_no = #{rmaNo} AND status IN ('buyer_applied', 'dispute') AND deleted = 0")
    int rejectAtomic(@Param("rmaNo") String rmaNo, @Param("reason") String reason);
}
