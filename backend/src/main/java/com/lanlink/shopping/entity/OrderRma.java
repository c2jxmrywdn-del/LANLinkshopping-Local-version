package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * RMA 售后退货退款主实体 (P2 阶段逆向物流闭环)。
 */
@Data
@TableName("t_order_rma")
public class OrderRma {

    @TableId(type = IdType.AUTO)
    private Long rmaId;

    /** RMA 业务单号 (RMA+年月日时分秒+6位随机数) */
    private String rmaNo;

    /** 关联主订单号 */
    private String orderNo;

    /** 申请买家用户ID */
    private Long userId;

    /** 归属商户ID */
    private Long merId;

    /** 售后类型：refund_only 仅退款 / return_goods 退货退款 */
    private String rmaType;

    /**
     * 售后状态：
     * buyer_applied (待商户初审)
     * merchant_approved (商户同意，下发退货仓库地址或待放款)
     * shipping_back (买家已寄回，逆向物流中)
     * received (商户仓库已收货)
     * inspected (验货质检完成)
     * dispute (货损争议协商)
     * processing (渠道出款中)
     * success (退款成功结案)
     * rejected (已驳回)
     * cancelled (买家已撤销)
     */
    private String status;

    /** 申请退款金额 */
    private BigDecimal applyAmount;

    /** 实际结算退款金额 */
    private BigDecimal actualRefundAmount;

    /** 原因分类：quality/damage/wrong_spec/negotiated/other */
    private String reasonCategory;

    /** 详细原因描述 */
    private String applyReason;

    /** 买家上传凭证图片JSON列表 */
    private String proofImages;

    /** 商户初审时间 */
    private LocalDateTime merchantAuditTime;

    /** 商户初审说明/指导 */
    private String merchantAuditNote;

    /** 商户退货收件人姓名 */
    private String returnReceiverName;

    /** 商户退货收件人电话 */
    private String returnReceiverPhone;

    /** 商户退货仓库地址 */
    private String returnWarehouseAddr;

    /** 逆向物流承运商ID */
    private Long returnProviderId;

    /** 承运商名称 (顺丰速运/德邦物流等) */
    private String returnCarrierName;

    /** 退货物流运单号 */
    private String returnWaybillNo;

    /** 系统生成的逆向运单ID */
    private Long returnWaybillId;

    /** 买家寄出时间 */
    private LocalDateTime returnShipTime;

    /** 买家寄出物流备注 */
    private String returnShipRemark;

    /** 仓库验货质检时间 */
    private LocalDateTime inspectTime;

    /** 验货操作员用户ID */
    private Long inspectorId;

    /** 质检状态：passed 合格 / damaged 破损货损 */
    private String inspectionStatus;

    /** 质检结果详细说明 */
    private String inspectionResult;

    /** 质检拍照凭证JSON */
    private String inspectionPhotos;

    /** 是否已执行原子恢复库存：0否 1是 */
    private Integer isRestocked;

    /** 商户发起渠道退款唯一请求号 (RF...) */
    private String outRefundNo;

    /** 支付通道官方退款流水号 */
    private String channelRefundId;

    /** 退款最终完成到账时间 */
    private LocalDateTime refundTime;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @JsonIgnore
    private Integer deleted;
}
