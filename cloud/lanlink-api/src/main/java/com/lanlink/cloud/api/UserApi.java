package com.lanlink.cloud.api;

import com.lanlink.cloud.api.dto.UserDTO;
import com.lanlink.cloud.common.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 用户契约：提供方 lanlink-auth-service。
 */
@FeignClient(name = "lanlink-auth-service", path = "/users")
public interface UserApi {

    @GetMapping("/{id}")
    R<UserDTO> getById(@PathVariable("id") Long id);
}
