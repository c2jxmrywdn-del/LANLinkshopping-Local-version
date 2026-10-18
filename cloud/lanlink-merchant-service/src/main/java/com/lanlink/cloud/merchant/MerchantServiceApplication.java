package com.lanlink.cloud.merchant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 商户微服务入口（8102）。
 */
@SpringBootApplication(scanBasePackages = {"com.lanlink.cloud.common", "com.lanlink.cloud.merchant"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.lanlink.cloud.api")
public class MerchantServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MerchantServiceApplication.class, args);
        System.out.println("==== lanlink-merchant-service 启动成功: http://localhost:8102 ====");
    }
}
