package com.lanlink.cloud.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 商品微服务入口（8103）。
 */
@SpringBootApplication(scanBasePackages = {"com.lanlink.cloud.common", "com.lanlink.cloud.product"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.lanlink.cloud.api")
public class ProductServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
        System.out.println("==== lanlink-product-service 启动成功: http://localhost:8103 ====");
    }
}
