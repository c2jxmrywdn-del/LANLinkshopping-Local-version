package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.domain.TechAchievementAssessor;
import com.lanlink.shopping.module.techservice.entity.TechAchievement;
import com.lanlink.shopping.module.techservice.entity.TechOrganization;
import com.lanlink.shopping.module.techservice.entity.TechServiceProduct;
import com.lanlink.shopping.module.techservice.mapper.TechAchievementMapper;
import com.lanlink.shopping.module.techservice.mapper.TechOrganizationMapper;
import com.lanlink.shopping.module.techservice.mapper.TechServiceProductMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 成果 / 专利读模型（目录 + 详情 + 快筛 + 我的成果）。 */
@Service
public class TechAchievementQueryService {

    /** 公开可见的成果状态；列表与详情/快筛共用同一份，避免两处口径漂移 */
    private static final List<String> PUBLIC_STATUSES = List.of("LISTED", "MATCHING", "IN_DEAL");

    private final TechAchievementMapper achievementMapper;
    private final TechServiceProductMapper serviceProductMapper;
    private final TechOrganizationMapper organizationMapper;
    private final TechAchievementAssessor assessor;

    public TechAchievementQueryService(TechAchievementMapper achievementMapper,
                                       TechServiceProductMapper serviceProductMapper,
                                       TechOrganizationMapper organizationMapper,
                                       TechAchievementAssessor assessor) {
        this.achievementMapper = achievementMapper;
        this.serviceProductMapper = serviceProductMapper;
        this.organizationMapper = organizationMapper;
        this.assessor = assessor;
    }

    public Page<TechAchievement> achievementPage(long page, long size, String industry, String type,
                                                 Integer dormant, String keyword) {
        String kw = text(keyword);
        LambdaQueryWrapper<TechAchievement> q = Wrappers.<TechAchievement>lambdaQuery()
                .in(TechAchievement::getStatus, PUBLIC_STATUSES)
                .eq(text(industry) != null, TechAchievement::getIndustry, text(industry))
                .eq(text(type) != null, TechAchievement::getAchievementType, text(type))
                .eq(dormant != null, TechAchievement::getDormant, dormant)
                .and(kw != null, w -> w.like(TechAchievement::getTitle, kw)
                        .or().like(TechAchievement::getTechField, kw)
                        .or().like(TechAchievement::getSummary, kw))
                .orderByDesc(TechAchievement::getDormant)
                .orderByDesc(TechAchievement::getCreateTime);
        return achievementMapper.selectPage(TechPageable.of(page, size, TechPageable.PUBLIC_MAX_SIZE), q);
    }

    public Map<String, Object> achievementDetail(Long id) {
        TechAchievement achievement = load(id);
        achievementMapper.update(null, Wrappers.<TechAchievement>lambdaUpdate()
                .eq(TechAchievement::getAchievementId, id).setSql("view_count = view_count + 1"));
        achievement.setViewCount(nvl(achievement.getViewCount()) + 1);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("achievement", achievement);
        out.put("assessment", assessor.assess(achievement));
        out.put("services", serviceProductMapper.selectList(Wrappers.<TechServiceProduct>lambdaQuery()
                .eq(TechServiceProduct::getStatus, 1)
                .in(TechServiceProduct::getCategory, "PATENT_HOSTING", "TRANSACTION")
                .orderByAsc(TechServiceProduct::getSort)));
        out.put("institutions", organizationMapper.selectList(Wrappers.<TechOrganization>lambdaQuery()
                .eq(TechOrganization::getStatus, 1)
                .in(TechOrganization::getOrgType, "EVALUATION", "EXCHANGE")
                .orderByAsc(TechOrganization::getSort)));
        return out;
    }

    public Map<String, Object> quickAssessment(Long id) {
        return assessor.assess(load(id));
    }

    public List<TechAchievement> myAchievements(Long userId) {
        return achievementMapper.selectList(Wrappers.<TechAchievement>lambdaQuery()
                .eq(TechAchievement::getOwnerUserId, userId).orderByDesc(TechAchievement::getCreateTime));
    }

    public List<TechAchievement> topAchievements(int limit) {
        return achievementMapper.selectList(Wrappers.<TechAchievement>lambdaQuery()
                .in(TechAchievement::getStatus, PUBLIC_STATUSES)
                .orderByDesc(TechAchievement::getDormant).orderByDesc(TechAchievement::getCreateTime)
                .last("LIMIT " + Math.max(1, limit)));
    }

    public long countVisible() {
        // 成果没有草稿态（DRAFT 只属于合同），这里的可见性与列表口径一致：全量已上账成果
        return count(achievementMapper.selectCount(Wrappers.<TechAchievement>lambdaQuery()));
    }

    public long countDormant() {
        return count(achievementMapper.selectCount(Wrappers.<TechAchievement>lambdaQuery()
                .eq(TechAchievement::getDormant, 1).ne(TechAchievement::getStatus, "COMPLETED")));
    }

    private TechAchievement load(Long id) {
        TechAchievement achievement = achievementMapper.selectById(id);
        // List.of(...).contains(null) 会抛 NPE，状态为空一律按不可见处理
        if (achievement == null || achievement.getStatus() == null
                || !PUBLIC_STATUSES.contains(achievement.getStatus())) {
            // 详情与快筛共用本方法：列表按状态过滤过，这里不过就会留下按 id 直读已下架成果的旁路
            throw new BusinessException(404, "技术成果不存在");
        }
        return achievement;
    }

    private static long count(Long value) {
        return value == null ? 0 : value;
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
