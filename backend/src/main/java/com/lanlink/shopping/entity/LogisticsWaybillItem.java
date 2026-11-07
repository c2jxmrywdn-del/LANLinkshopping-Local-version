package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 运单发货商品明细关联实体（多包裹分批拆单发货）
 */
@Data
@TableName("t_logistics_waybill_item")
public class LogisticsWaybillItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 运单ID */
    private Long waybillId;

    /** 运单号 */
    private String waybillNo;

    /** 关联订单号 */
    private String orderNo;

    /** 订单明细ID (t_order_item.item_id) */
    private Long orderItemId;

    /** 商品ID */
    private Long prodId;

    /** 商品名称快照 */
    private String prodName;

    /** 本次包裹实发数量 */
    private Integer shippedQty;

    /** 创建时间 */
    private LocalDateTime createTime;
}
