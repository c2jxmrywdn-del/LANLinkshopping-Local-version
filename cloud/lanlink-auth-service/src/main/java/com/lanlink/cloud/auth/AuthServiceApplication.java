package com.lanlink.cloud.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 认证/用户微服务入口（8101）。
 */
@SpringBootApplication(scanBasePackages = {"com.lanlink.cloud.common", "com.lanlink.cloud.auth"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.lanlink.cloud.api")
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
        System.out.println("==== lanlink-auth-service 启动成功: http://localhost:8101 ====");
    }
}
