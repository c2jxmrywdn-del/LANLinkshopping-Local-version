package com.lanlink.cloud.design;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 设计工具对接中台入口（8107）。
 * 插件化适配器架构：DesignToolAdapter 统一抽象 + Spring 自动注册。
 */
@SpringBootApplication(scanBasePackages = {"com.lanlink.cloud.common", "com.lanlink.cloud.design"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.lanlink.cloud.api")
public class DesignServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DesignServiceApplication.class, args);
        System.out.println("==== lanlink-design-service 启动成功: http://localhost:8107 ====");
    }
}
