package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.module.techservice.entity.TechAchievement;
import com.lanlink.shopping.module.techservice.mapper.TechAchievementMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 开放许可专区读模型（P1）：只读取已上架且许可方式为开放许可的成果。
 * 平台提供声明要点、材料模板与流程陪跑，备案由合作机构代办。
 */
@Service
public class OpenLicenseQueryService {

    private final TechAchievementMapper achievementMapper;

    public OpenLicenseQueryService(TechAchievementMapper achievementMapper) {
        this.achievementMapper = achievementMapper;
    }

    public List<TechAchievement> openLicenseAchievements() {
        return achievementMapper.selectList(Wrappers.<TechAchievement>lambdaQuery()
                .eq(TechAchievement::getLicensingMode, "OPEN_LICENSE")
                .in(TechAchievement::getStatus, "LISTED", "MATCHING", "IN_DEAL")
                .orderByDesc(TechAchievement::getVerified)
                .orderByDesc(TechAchievement::getCreateTime)
                .last("LIMIT 50"));
    }
}
