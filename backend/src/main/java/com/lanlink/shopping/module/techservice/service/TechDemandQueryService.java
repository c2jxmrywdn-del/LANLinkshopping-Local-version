package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.entity.TechDemand;
import com.lanlink.shopping.module.techservice.mapper.TechDemandMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 需求大厅读模型（目录 + 详情 + 首页精选）。 */
@Service
public class TechDemandQueryService {

    /** 公开可见的需求状态；CLOSED 只在本人与后台可见 */
    private static final List<String> PUBLIC_STATUSES = List.of("MATCHING", "IN_PROGRESS");

    /** `List.of(...).contains(null)` 会抛 NPE（不可变集合拒绝 null），必须先挡空 */
    private static boolean isPublic(String status) {
        return status != null && PUBLIC_STATUSES.contains(status);
    }

    private final TechDemandMapper demandMapper;

    public TechDemandQueryService(TechDemandMapper demandMapper) {
        this.demandMapper = demandMapper;
    }

    public Page<TechDemand> demandPage(long page, long size, String industry, String keyword, String status) {
        String kw = text(keyword);
        // 只接受公开状态值：原样透传 status 会让 ?status=CLOSED 绕过默认过滤，把已关闭需求整页捞出来
        String requested = text(status);
        String st = isPublic(requested) ? requested : null;
        LambdaQueryWrapper<TechDemand> q = Wrappers.<TechDemand>lambdaQuery()
                .eq(st != null, TechDemand::getStatus, st)
                .in(st == null, TechDemand::getStatus, "MATCHING", "IN_PROGRESS")
                .eq(text(industry) != null, TechDemand::getIndustry, text(industry))
                .and(kw != null, w -> w.like(TechDemand::getTitle, kw)
                        .or().like(TechDemand::getTechField, kw)
                        .or().like(TechDemand::getDescription, kw))
                .orderByDesc(TechDemand::getCreateTime);
        return demandMapper.selectPage(TechPageable.of(page, size, TechPageable.PUBLIC_MAX_SIZE), q);
    }

    public TechDemand demandDetail(Long id) {
        TechDemand demand = demandMapper.selectById(id);
        if (demand == null || !isPublic(demand.getStatus())) {
            // 已关闭的需求对外按「不存在」处理：列表过滤了状态，详情不过滤等于留了一个按 id 直读的旁路
            throw new BusinessException(404, "需求不存在");
        }
        demandMapper.update(null, Wrappers.<TechDemand>lambdaUpdate()
                .eq(TechDemand::getDemandId, id).setSql("view_count = view_count + 1"));
        demand.setViewCount((demand.getViewCount() == null ? 0 : demand.getViewCount()) + 1);
        return demand;
    }

    public List<TechDemand> topDemands(int limit) {
        return demandMapper.selectList(Wrappers.<TechDemand>lambdaQuery()
                .in(TechDemand::getStatus, "MATCHING", "IN_PROGRESS")
                .orderByDesc(TechDemand::getCreateTime).last("LIMIT " + Math.max(1, limit)));
    }

    public long countOpen() {
        Long value = demandMapper.selectCount(Wrappers.<TechDemand>lambdaQuery()
                .ne(TechDemand::getStatus, "CLOSED"));
        return value == null ? 0 : value;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
