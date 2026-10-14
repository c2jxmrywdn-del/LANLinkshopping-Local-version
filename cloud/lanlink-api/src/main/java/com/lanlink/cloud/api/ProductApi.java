package com.lanlink.cloud.api;

import com.lanlink.cloud.api.dto.ProductDTO;
import com.lanlink.cloud.common.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 商品契约：提供方 lanlink-product-service（下单计价依据）。
 */
@FeignClient(name = "lanlink-product-service", path = "/products")
public interface ProductApi {

    @GetMapping("/{id}")
    R<ProductDTO> getById(@PathVariable("id") Long id);
}
