package com.lanlink.shopping;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan({
        "com.lanlink.shopping.mapper",
        "com.lanlink.shopping.module.*.mapper",
        // 支付系统：payment.mapper（支付流水）+ payment.*.mapper（钱包等子模块）
        "com.lanlink.shopping.payment.mapper",
        "com.lanlink.shopping.payment.*.mapper"
})
public class LanlinkShoppingApplication {
    public static void main(String[] args) {
        SpringApplication.run(LanlinkShoppingApplication.class, args);
        System.out.println("==== LANLinkshopping 后端启动成功: http://localhost:8080/api ====");
    }
}
