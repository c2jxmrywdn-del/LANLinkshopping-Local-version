package com.lanlink.shopping.vo;

import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.OrderItem;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商户视角订单 VO：订单本体 + 仅本商户商品的明细子集 + 我方金额小计。
 * 列表场景 waybills 为 null；详情场景附带关联运单。
 */
@Data
public class MerchantOrderVO {
    private Order order;
    /** 仅包含本商户商品的订单明细 */
    private List<OrderItem> myItems;
    /** 本商户商品在该订单中的金额小计（不含运费/积分分摊，按明细小计求和） */
    private BigDecimal myAmount;
    /** 该订单关联的物流运单（详情接口返回） */
    private List<LogisticsWaybill> waybills;
}
