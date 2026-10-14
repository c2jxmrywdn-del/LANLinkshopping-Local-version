package com.lanlink.cloud.api;

import com.lanlink.cloud.api.dto.OrderDTO;
import com.lanlink.cloud.common.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

/**
 * 订单契约：提供方 lanlink-order-service（支付取金额、支付成功回写状态）。
 */
@FeignClient(name = "lanlink-order-service", path = "/orders")
public interface OrderApi {

    @GetMapping("/{orderNo}")
    R<OrderDTO> getByOrderNo(@PathVariable("orderNo") String orderNo);

    /** 支付成功回写（payment-service 调用） */
    @PutMapping("/{orderNo}/paid")
    R<Void> markPaid(@PathVariable("orderNo") String orderNo,
                     @RequestParam("channel") String channel,
                     @RequestParam("amount") BigDecimal amount);
}
