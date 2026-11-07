package com.lanlink.shopping.module.techservice.config;

import com.lanlink.shopping.module.techservice.domain.TechAchievementAssessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 技术服务模块装配：纯领域组件通过 @Bean 注册，避免在 domain 包引入 Spring 注解。
 */
@Configuration
public class TechServiceConfig {

    @Bean
    public TechAchievementAssessor techAchievementAssessor() {
        return new TechAchievementAssessor();
    }
}
