package com.lanlink.shopping.dto;

import lombok.Data;
import java.util.List;

/**
 * 结算可用券试算请求（金额与行业由服务端按购物车重算，不信任前端金额）
 */
@Data
public class CouponQuoteDTO {
    /** 结算的购物车行ID列表（空则按"已勾选"口径试算） */
    private List<Long> cartIds;
}
