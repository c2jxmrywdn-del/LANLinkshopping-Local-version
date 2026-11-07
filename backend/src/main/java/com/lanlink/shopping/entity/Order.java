package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_order")
public class Order {
    @TableId      // 主键为订单号(字符串)，非自增
    private String orderNo;
    private Long userId;
    private Long entId;
    private BigDecimal totalAmount;
    private String payType;
    /** 积分抵扣金额（元，整元） */
    private BigDecimal pointDeductAmount;
    /** 积分抵扣消耗积分数（1000 积分 = 1 元） */
    private Integer pointDeductPoints;
    /** 促销/会员最优优惠金额（结算自动应用，互斥取优） */
    private BigDecimal promoDiscount;
    /** 生效促销标题快照（取 quote.appliedTitle；会员折扣生效或无促销时为 null） */
    private String promoTitle;
    /** 生效来源快照：promotion 促销 / member 会员折扣 */
    private String promoSource;
    /** 优惠券优惠金额（用户持券核销） */
    private BigDecimal couponDiscount;
    /** 使用的用户券ID（t_user_coupon.id，取消/退款回退依据） */
    private Long couponId;
    /** 下单归因直播间ID（t_live_room.room_id，直播带货复盘依据；非直播单为 null） */
    private Long liveId;
    private Integer payStatus;
    private Integer orderStatus;
    private String receiver;
    private String phone;
    private String address;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
    /** 支付渠道：wechat | alipay | mock */
    private String payChannel;
    /** 第三方渠道交易号 */
    private String transactionId;
    /** 微信预下单号 */
    private String prepayId;
    /** 退款状态：none | applied(用户已申请待平台处理) | rejected(申请被驳回) | processing | partial(部分退款已到账) | success */
    private String refundStatus;
    /** 渠道退款单号 */
    private String refundId;
    /** 已退金额 */
    private BigDecimal refundAmount;
    private LocalDateTime refundTime;
    /** 退款申请原因（用户填写） */
    private String refundReason;
    /** 退款证据 JSON [{type,url,name}]（链A简单退款独占；链B证据权威存 t_order_rma.proof_images） */
    private String refundEvidences;
    /** 退款申请时间 */
    private LocalDateTime refundApplyTime;
    /** 退款申请驳回原因（平台填写，回显给用户） */
    private String refundRejectReason;
    /** 当前活跃RMA单号 */
    private String currentRmaNo;
    /** 最近一次渠道退款请求单号（商户对账单号） */
    private String outRefundNo;
    /** 售后形态：refund_only 仅退款 / return_goods 退货退款 */
    private String refundType;
    /** 退货物流运单号 */
    private String returnWaybillNo;
    /** 退货承运商名称 */
    private String returnCarrierName;
    /** 退货收件人姓名 */
    private String returnReceiverName;
    /** 退货收件人电话 */
    private String returnReceiverPhone;
    /** 退货仓库地址 */
    private String returnWarehouseAddr;
    /** 质检验货状态：passed 合格入库 / damaged 货损异常 */
    private String inspectStatus;
    /** 质检验货说明 */
    private String inspectResult;
    /** 质检验货时间 */
    private LocalDateTime inspectTime;
    private LocalDateTime updateTime;
}
