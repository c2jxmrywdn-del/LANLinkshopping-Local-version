package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.module.techservice.entity.TechOrganization;
import com.lanlink.shopping.module.techservice.entity.TechServiceProduct;
import com.lanlink.shopping.module.techservice.mapper.TechOrganizationMapper;
import com.lanlink.shopping.module.techservice.mapper.TechServiceProductMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 目录读模型：生态机构与产品化服务。 */
@Service
public class TechDirectoryQueryService {

    private final TechOrganizationMapper organizationMapper;
    private final TechServiceProductMapper serviceProductMapper;

    public TechDirectoryQueryService(TechOrganizationMapper organizationMapper,
                                     TechServiceProductMapper serviceProductMapper) {
        this.organizationMapper = organizationMapper;
        this.serviceProductMapper = serviceProductMapper;
    }

    public List<TechOrganization> organizationList(String type) {
        return organizationMapper.selectList(Wrappers.<TechOrganization>lambdaQuery()
                .eq(TechOrganization::getStatus, 1)
                .eq(text(type) != null, TechOrganization::getOrgType, text(type))
                .orderByAsc(TechOrganization::getSort));
    }

    public List<TechServiceProduct> serviceList(String category) {
        return serviceProductMapper.selectList(Wrappers.<TechServiceProduct>lambdaQuery()
                .eq(TechServiceProduct::getStatus, 1)
                .eq(text(category) != null, TechServiceProduct::getCategory, text(category))
                .orderByAsc(TechServiceProduct::getSort));
    }

    public List<TechServiceProduct> allServices() {
        return serviceList(null);
    }

    public List<TechOrganization> topOrganizations(int limit) {
        return organizationMapper.selectList(Wrappers.<TechOrganization>lambdaQuery()
                .eq(TechOrganization::getStatus, 1).orderByAsc(TechOrganization::getSort)
                .last("LIMIT " + Math.max(1, limit)));
    }

    public long countOrganizations() {
        Long value = organizationMapper.selectCount(Wrappers.<TechOrganization>lambdaQuery()
                .eq(TechOrganization::getStatus, 1));
        return value == null ? 0 : value;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
