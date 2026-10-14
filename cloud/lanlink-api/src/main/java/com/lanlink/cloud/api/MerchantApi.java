package com.lanlink.cloud.api;

import com.lanlink.cloud.api.dto.MerchantDTO;
import com.lanlink.cloud.common.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 商户契约：提供方 lanlink-merchant-service。
 */
@FeignClient(name = "lanlink-merchant-service", path = "/merchants")
public interface MerchantApi {

    @GetMapping("/{id}")
    R<MerchantDTO> getById(@PathVariable("id") Long id);
}
