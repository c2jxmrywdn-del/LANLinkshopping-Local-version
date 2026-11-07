package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.entity.TechConsultation;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.entity.TechServiceProduct;
import com.lanlink.shopping.module.techservice.mapper.TechConsultationMapper;
import com.lanlink.shopping.module.techservice.mapper.TechExpertMapper;
import com.lanlink.shopping.module.techservice.mapper.TechServiceProductMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 专家 / 技术经纪人读模型（目录 + 详情 + 首页精选）。 */
@Service
public class TechExpertQueryService {

    private final TechExpertMapper expertMapper;
    private final TechServiceProductMapper serviceProductMapper;
    private final TechConsultationMapper consultationMapper;

    public TechExpertQueryService(TechExpertMapper expertMapper,
                                  TechServiceProductMapper serviceProductMapper,
                                  TechConsultationMapper consultationMapper) {
        this.expertMapper = expertMapper;
        this.serviceProductMapper = serviceProductMapper;
        this.consultationMapper = consultationMapper;
    }

    public Page<TechExpert> expertPage(long page, long size, String type, String industry, String keyword) {
        String kw = text(keyword);
        LambdaQueryWrapper<TechExpert> q = Wrappers.<TechExpert>lambdaQuery()
                .eq(TechExpert::getStatus, 1)
                .eq(text(type) != null, TechExpert::getExpertType, text(type))
                .like(text(industry) != null, TechExpert::getIndustries, text(industry))
                .and(kw != null, w -> w.like(TechExpert::getName, kw)
                        .or().like(TechExpert::getDomains, kw)
                        .or().like(TechExpert::getOrganization, kw))
                .orderByDesc(TechExpert::getSort)
                .orderByDesc(TechExpert::getServiceCount);
        return expertMapper.selectPage(TechPageable.of(page, size, TechPageable.PUBLIC_MAX_SIZE), q);
    }

    public Map<String, Object> expertDetail(Long id) {
        TechExpert expert = expertMapper.selectById(id);
        if (expert == null || !Integer.valueOf(1).equals(expert.getStatus())) {
            throw new BusinessException(404, "专家信息不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expert", expert);
        out.put("services", serviceProductMapper.selectList(Wrappers.<TechServiceProduct>lambdaQuery()
                .eq(TechServiceProduct::getStatus, 1)
                .eq(TechServiceProduct::getProviderType, "EXPERT")
                .eq(TechServiceProduct::getProviderId, id)
                .orderByAsc(TechServiceProduct::getSort)));
        out.put("completedConsultations", count(consultationMapper.selectCount(
                Wrappers.<TechConsultation>lambdaQuery()
                        .eq(TechConsultation::getExpertId, id)
                        .eq(TechConsultation::getStatus, "COMPLETED"))));
        return out;
    }

    public List<TechExpert> topExperts(int limit) {
        return expertMapper.selectList(Wrappers.<TechExpert>lambdaQuery()
                .eq(TechExpert::getStatus, 1).orderByDesc(TechExpert::getSort)
                .orderByDesc(TechExpert::getServiceCount).last("LIMIT " + Math.max(1, limit)));
    }

    public long countByType(String expertType) {
        return count(expertMapper.selectCount(Wrappers.<TechExpert>lambdaQuery()
                .eq(TechExpert::getStatus, 1).eq(TechExpert::getExpertType, expertType)));
    }

    private static long count(Long value) {
        return value == null ? 0 : value;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
