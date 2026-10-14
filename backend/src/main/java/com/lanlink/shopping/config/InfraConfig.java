package com.lanlink.shopping.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 开启缓存（服务端热点数据缓存，降 DB 压力）与异步（非实时任务异步化，提高吞吐）。
 * 默认使用 Spring 内置 ConcurrentMap 缓存；生产可切换 Redis 多级缓存。
 */
@Configuration
@EnableCaching
@EnableAsync
public class InfraConfig {
}
