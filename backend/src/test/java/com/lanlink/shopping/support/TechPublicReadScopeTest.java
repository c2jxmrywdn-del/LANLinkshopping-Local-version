package com.lanlink.shopping.support;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.domain.TechAchievementAssessor;
import com.lanlink.shopping.module.techservice.entity.TechAchievement;
import com.lanlink.shopping.module.techservice.entity.TechDemand;
import com.lanlink.shopping.module.techservice.mapper.TechAchievementMapper;
import com.lanlink.shopping.module.techservice.mapper.TechDemandMapper;
import com.lanlink.shopping.module.techservice.mapper.TechOrganizationMapper;
import com.lanlink.shopping.module.techservice.mapper.TechServiceProductMapper;
import com.lanlink.shopping.module.techservice.service.TechAchievementQueryService;
import com.lanlink.shopping.module.techservice.service.TechDemandQueryService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 公开读取面的详情旁路：列表过滤了状态而详情不过滤，等于留下按 id 直读的旁路。
 * （视图默认方向的断言在 `TechPublicViewTest`，不在此重复。）
 */
class TechPublicReadScopeTest {

    // ---------- 需求详情 ----------

    private static TechDemandMapper demandMapper(String status) {
        TechDemandMapper mapper = mock(TechDemandMapper.class);
        TechDemand demand = new TechDemand();
        demand.setDemandId(9L);
        demand.setStatus(status);
        when(mapper.selectById(9L)).thenReturn(demand);
        return mapper;
    }

    @Test
    void closedDemandIsNotReadableByIdOnPublicPath() {
        TechDemandMapper mapper = demandMapper("CLOSED");
        TechDemandQueryService service = new TechDemandQueryService(mapper);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.demandDetail(9L));
        assertEquals(404, ex.getCode());
        verify(mapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void openDemandsStayReadableAndCountViews() {
        for (String status : new String[]{"MATCHING", "IN_PROGRESS"}) {
            TechDemandMapper mapper = demandMapper(status);
            TechDemandQueryService service = new TechDemandQueryService(mapper);
            assertEquals(status, service.demandDetail(9L).getStatus());
            verify(mapper).update(isNull(), any(Wrapper.class));
        }
    }

    @Test
    void unknownStatusParamDoesNotWidenThePublicList() {
        TechDemandMapper mapper = mock(TechDemandMapper.class);
        when(mapper.selectPage(any(), any())).thenReturn(new Page<>());
        TechDemandQueryService service = new TechDemandQueryService(mapper);

        // status 缺省为 null 是最常见的请求形态：List.of(...).contains(null) 会抛 NPE，
        // 曾把整个公开需求列表打成 500，只有在真实容器上打一次才会暴露
        service.demandPage(1, 10, null, null, null);
        service.demandPage(1, 10, null, null, "CLOSED");
        service.demandPage(1, 10, null, null, "GARBAGE");

        verify(mapper, times(3)).selectPage(any(), any());
    }

    @Test
    void demandWithNullStatusIsNotPubliclyReadable() {
        TechDemandMapper mapper = demandMapper(null);
        TechDemandQueryService service = new TechDemandQueryService(mapper);
        assertThrows(BusinessException.class, () -> service.demandDetail(9L));
    }

    @Test
    void achievementWithNullStatusIsNotPubliclyReadable() {
        TechAchievementMapper mapper = mock(TechAchievementMapper.class);
        TechAchievement a = new TechAchievement();
        a.setAchievementId(4L);
        when(mapper.selectById(4L)).thenReturn(a);
        TechAchievementQueryService service = new TechAchievementQueryService(mapper,
                mock(TechServiceProductMapper.class), mock(TechOrganizationMapper.class),
                mock(TechAchievementAssessor.class));
        assertThrows(BusinessException.class, () -> service.quickAssessment(4L));
    }

    // ---------- 成果详情与快筛 ----------

    private static TechAchievementQueryService achievementService(String status) {
        TechAchievementMapper mapper = mock(TechAchievementMapper.class);
        TechAchievement a = new TechAchievement();
        a.setAchievementId(4L);
        a.setStatus(status);
        when(mapper.selectById(4L)).thenReturn(a);
        return new TechAchievementQueryService(mapper, mock(TechServiceProductMapper.class),
                mock(TechOrganizationMapper.class), mock(TechAchievementAssessor.class));
    }

    @Test
    void delistedAchievementIsNotReadableOrScreenable() {
        TechAchievementQueryService service = achievementService("COMPLETED");
        assertThrows(BusinessException.class, () -> service.achievementDetail(4L));
        assertThrows(BusinessException.class, () -> service.quickAssessment(4L));
    }

    @Test
    void listedAchievementStaysReadable() {
        Map<String, Object> detail = achievementService("LISTED").achievementDetail(4L);
        assertNotNull(detail.get("achievement"));
    }
}
