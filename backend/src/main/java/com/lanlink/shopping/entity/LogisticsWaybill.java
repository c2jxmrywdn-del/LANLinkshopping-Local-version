package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 物流运单 */
@Data
@TableName("t_logistics_waybill")
public class LogisticsWaybill {
    @TableId(type = IdType.AUTO)
    private Long waybillId;
    /** 平台运单号 LL+日期+序列 */
    private String waybillNo;
    /** 关联订单号 */
    private String orderNo;
    /** 分包裹订单明细ID(逗号分隔) */
    private String orderItemIds;
    private Long providerId;
    private Long templateId;
    /** 第三方物流单号(快递单号) */
    private String logisticsNo;
    private BigDecimal freight;
    /** 计费快照明细（存储层内部格式，服务层解析留痕审计，不外发） */
    @JsonIgnore
    private String freightCalcJson;
    private BigDecimal weight;
    private BigDecimal volume;
    private String senderName;
    private String senderPhone;
    private String senderAddress;
    private String receiverName;
    private String receiverPhone;
    private String receiverProvince;
    private String receiverCity;
    private String receiverDistrict;
    private String receiverAddress;
    /** pending_ship/picked_up/in_transit/out_for_delivery/delivered/exception/returned/lost/cancelled */
    private String status;
    /** 0正常 1有活跃异常告警 */
    private Integer exceptionFlag;
    private LocalDateTime shipTime;
    private LocalDateTime deliveredTime;
    private Long createdBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 逻辑删除标记（存储层内部字段，不外发） */
    @JsonIgnore
    private Integer deleted;
}
