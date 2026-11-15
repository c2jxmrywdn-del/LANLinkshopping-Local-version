package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;

@Data
@TableName("t_order_item")
public class OrderItem {
    @TableId(type = IdType.AUTO)
    private Long itemId;
    private String orderNo;
    private Long prodId;
    private String prodName;
    private BigDecimal price;
    private Integer quantity;
    /** 行小计（成交单价 × 数量；已含阶梯价/会员取优后的行价，未分摊订单级优惠） */
    private BigDecimal subtotal;
    /**
     * 本行分摊的订单级优惠（促销/会员/券/积分合计）= subtotal − paidAmount。
     * 下单时一次固化（OrderAmounts.allocate），读时不再重算，保证退款/对账可审计。
     */
    private BigDecimal allocatedDiscount;
    /** 本行实付货款 = subtotal − allocatedDiscount；Σpaid_amount 必须等于 t_order.goods_amount。 */
    private BigDecimal paidAmount;
    /** 本行分摊运费；Σfreight_amount 必须等于 t_order.freight_amount。 */
    private BigDecimal freightAmount;
    private String coverUrl;
    /** 已发货数量（多包裹分批拆单发货累计） */
    private Integer shippedQuantity;
    /** 发货状态：pending 未发货 / partial 部分发货 / shipped 已发完 */
    private String shippingStatus;
    /** 已退款商品数量 */
    private Integer refundedQuantity;
    /** 已退金额（含行级优惠分摊） */
    private BigDecimal refundAmount;
    /** 行级退款状态：none 未退款 / applied 申请中 / refunded 已退款 */
    private String refundStatus;
}
