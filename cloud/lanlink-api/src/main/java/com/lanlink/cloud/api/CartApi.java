package com.lanlink.cloud.api;

import com.lanlink.cloud.api.dto.CartItemDTO;
import com.lanlink.cloud.common.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 购物车契约：提供方 lanlink-cart-service（下单后清购物车用）。
 */
@FeignClient(name = "lanlink-cart-service", path = "/carts")
public interface CartApi {

    @GetMapping("/{userId}/items")
    R<List<CartItemDTO>> items(@PathVariable("userId") Long userId);

    /** 下单成功后移除已购购物车项 */
    @PostMapping("/{userId}/remove")
    R<Void> removeItems(@PathVariable("userId") Long userId, @RequestBody List<Long> cartIds);
}
