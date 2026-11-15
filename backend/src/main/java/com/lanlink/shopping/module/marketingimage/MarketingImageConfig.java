package com.lanlink.shopping.module.marketingimage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 营销图异步生成线程池。
 *
 * <p>为什么单独开池：图像生成单次最长 120s，若复用 Spring 默认的 SimpleAsyncTaskExecutor
 * 会为每次提交新建线程并可能把数据库连接池/内存打满；限制核心 1、最大 2，与上游
 * 单账号并发额度保持克制，也在队列满时用 AbortPolicy 让提交方立即得到明确失败，
 * 而不是无限堆积 PENDING 任务。
 */
@Configuration
public class MarketingImageConfig {

    public static final String EXECUTOR = "marketingImageExecutor";

    @Bean(EXECUTOR)
    public ThreadPoolTaskExecutor marketingImageExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("marketing-image-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
