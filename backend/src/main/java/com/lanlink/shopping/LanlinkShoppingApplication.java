package com.lanlink.shopping;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@MapperScan({
        "com.lanlink.shopping.mapper",
        "com.lanlink.shopping.module.*.mapper",
        // 通知域 Mapper 直接位于 module.notify 根包（不在 mapper 子包），需显式纳入扫描
        "com.lanlink.shopping.module.notify",
        // 订单 outbox Mapper（integration.mq）
        "com.lanlink.shopping.integration.mq",
        // 支付系统：payment.mapper（支付流水）+ payment.*.mapper（钱包等子模块）
        "com.lanlink.shopping.payment.mapper",
        "com.lanlink.shopping.payment.*.mapper"
})
public class LanlinkShoppingApplication {
    public static void main(String[] args) {
        // 时区统一：容器 JVM 默认为 UTC，签到日期(t_checkin.checkin_date)与活动时间窗(effectiveNow 等
        // 基于 LocalDateTime.now() 的口径)此前整体偏移 8 小时。docker-compose 的 APP_TIMEZONE=Asia/Shanghai
        // 环境变量此前无任何代码读取，现以代码常量落地（APP_TIMEZONE 已由本行取代，仅保留向后兼容）。
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(LanlinkShoppingApplication.class, args);
        System.out.println("==== LANLinkshopping 后端启动成功: http://localhost:8080/api ====");
    }
}
