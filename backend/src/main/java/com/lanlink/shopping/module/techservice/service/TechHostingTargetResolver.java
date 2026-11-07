package com.lanlink.shopping.module.techservice.service;

import com.lanlink.shopping.module.techservice.entity.TechAchievement;
import com.lanlink.shopping.module.techservice.entity.TechServiceProduct;
import com.lanlink.shopping.module.techservice.mapper.TechAchievementMapper;
import com.lanlink.shopping.module.techservice.mapper.TechServiceProductMapper;
import org.springframework.stereotype.Component;

/**
 * 托管标的名称解析：按托管类型从成果库或服务产品库补齐标的名称。
 * 独立为一个组件，避免托管聚合直接依赖两个无关 Mapper。
 */
@Component
public class TechHostingTargetResolver {

    private final TechAchievementMapper achievementMapper;
    private final TechServiceProductMapper serviceProductMapper;

    public TechHostingTargetResolver(TechAchievementMapper achievementMapper,
                                     TechServiceProductMapper serviceProductMapper) {
        this.achievementMapper = achievementMapper;
        this.serviceProductMapper = serviceProductMapper;
    }

    public String resolve(String hostingType, Long targetId, String providedName) {
        if (providedName != null && !providedName.isBlank()) return providedName.trim();
        if (targetId != null) {
            if ("PATENT".equals(hostingType)) {
                TechAchievement achievement = achievementMapper.selectById(targetId);
                if (achievement != null) return achievement.getTitle();
            } else if ("SAAS".equals(hostingType)) {
                TechServiceProduct product = serviceProductMapper.selectById(targetId);
                if (product != null) return product.getTitle();
            }
        }
        return switch (hostingType) {
            case "PATENT" -> "专利托管标的";
            case "AGENCY" -> "知识产权服务机构托管";
            case "SAAS" -> "SaaS 工具托管";
            default -> "托管标的";
        };
    }
}
