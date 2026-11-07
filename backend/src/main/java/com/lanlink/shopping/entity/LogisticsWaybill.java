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
    /** 包裹编号/子单号 (如 PKG-01, PKG-02) */
    private String packageNo;
    /** 包裹序号 (第几包，从 1 开始) */
    private Integer packageIndex;
    /** 总包裹数 */
    private Integer packageTotal;
    /** 运输方式：express 快递 / ltl 零担快运 / ftl 整车专线 / direct 工厂直发 / self 客户自提 */
    private String transportMode;
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
    /** 发货地经度 */
    private BigDecimal senderLng;
    /** 发货地纬度 */
    private BigDecimal senderLat;
    /** 目的地经度 */
    private BigDecimal receiverLng;
    /** 目的地纬度 */
    private BigDecimal receiverLat;
    /** 当前最新在途经度 */
    private BigDecimal currentLng;
    /** 当前最新在途纬度 */
    private BigDecimal currentLat;
    /** 当前最新在途位置 */
    private String currentLocation;
    /** pending_ship/picked_up/in_transit/out_for_delivery/delivered/exception/returned/lost/cancelled */
    private String status;
    /** 0正常 1有活跃异常告警 */
    private Integer exceptionFlag;
    private LocalDateTime shipTime;
    private LocalDateTime deliveredTime;
    private Long createdBy;
    /** 随货出厂检验报告/原厂质保书文件列表 JSON (快照) */
    private String coaDocsJson;
    /** e-POD现场签收人 */
    private String podSignedBy;
    /** e-POD现场签收人电话 */
    private String podSignedPhone;
    /** e-POD现场实际签收时间 */
    private LocalDateTime podSignedTime;
    /** e-POD公章纸质签收原件照片URL */
    private String podStampPhoto;
    /** e-POD货物外观/开箱照片列表 JSON */
    private String podCargoPhotos;
    /** e-POD审核状态：none未传 / pending待审核 / approved已验签 / rejected已驳回 */
    private String podAuditStatus;
    /** 验签审核意见 / 驳回批注 */
    private String podAuditNote;
    /** 验签审核人用户ID */
    private Long podAuditedBy;
    /** 验签审核处理时间 */
    private LocalDateTime podAuditedAt;
    /** 运单流向：forward 正向发货 / reverse 逆向退货 */
    private String waybillDirection;
    /** 关联 RMA 售后单号 (逆向退货专用) */
    private String rmaNo;
    /** 最新轨迹更新时间（用于在途 SLA 看门狗停滞判定） */
    private LocalDateTime lastTraceTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 逻辑删除标记（存储层内部字段，不外发） */
    @JsonIgnore
    private Integer deleted;
}
