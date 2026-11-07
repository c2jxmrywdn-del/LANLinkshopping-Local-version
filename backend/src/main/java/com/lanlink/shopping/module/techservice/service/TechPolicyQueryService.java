package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.module.techservice.entity.TechPolicy;
import com.lanlink.shopping.module.techservice.mapper.TechPolicyMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 政策读模型。 */
@Service
public class TechPolicyQueryService {

    private final TechPolicyMapper policyMapper;

    public TechPolicyQueryService(TechPolicyMapper policyMapper) {
        this.policyMapper = policyMapper;
    }

    public List<TechPolicy> policyList(String region, String category) {
        String r = text(region);
        String c = text(category);
        return policyMapper.selectList(Wrappers.<TechPolicy>lambdaQuery()
                .eq(TechPolicy::getStatus, 1)
                .eq(r != null, TechPolicy::getRegion, r)
                .eq(c != null, TechPolicy::getCategory, c)
                .orderByAsc(TechPolicy::getSort)
                .orderByDesc(TechPolicy::getPublishDate));
    }

    public List<TechPolicy> topPolicies(int limit) {
        return policyMapper.selectList(Wrappers.<TechPolicy>lambdaQuery()
                .eq(TechPolicy::getStatus, 1).orderByAsc(TechPolicy::getSort)
                .orderByDesc(TechPolicy::getPublishDate).last("LIMIT " + Math.max(1, limit)));
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
