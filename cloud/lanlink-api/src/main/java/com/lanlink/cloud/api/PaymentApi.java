package com.lanlink.cloud.api;

import com.lanlink.cloud.api.dto.PaymentCreateDTO;
import com.lanlink.cloud.api.dto.PaymentDTO;
import com.lanlink.cloud.common.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 支付契约：提供方 lanlink-payment-service。
 */
@FeignClient(name = "lanlink-payment-service", path = "/payments")
public interface PaymentApi {

    @PostMapping
    R<PaymentDTO> create(@RequestBody PaymentCreateDTO dto);
}
