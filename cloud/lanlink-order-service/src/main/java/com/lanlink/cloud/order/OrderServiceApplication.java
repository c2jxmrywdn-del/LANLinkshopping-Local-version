package com.lanlink.cloud.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 订单微服务入口（8105）。
 */
@SpringBootApplication(scanBasePackages = {"com.lanlink.cloud.common", "com.lanlink.cloud.order"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.lanlink.cloud.api")
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
        System.out.println("==== lanlink-order-service 启动成功: http://localhost:8105 ====");
    }
}
