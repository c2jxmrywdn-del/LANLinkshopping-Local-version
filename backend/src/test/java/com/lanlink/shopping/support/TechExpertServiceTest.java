package com.lanlink.shopping.support;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.dto.TechExpertApplyRequest;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.mapper.TechExpertMapper;
import com.lanlink.shopping.module.techservice.service.TechComplianceGuard;
import com.lanlink.shopping.module.techservice.service.TechExpertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 专家资源池写入口：主动申请进入待审核、定向邀请即时上架、审核状态机。 */
class TechExpertServiceTest {

    private TechExpertMapper expertMapper;
    private TechExpertService service;

    @BeforeEach
    void setUp() {
        expertMapper = mock(TechExpertMapper.class);
        service = new TechExpertService(expertMapper, new TechComplianceGuard());
    }

    private TechExpertApplyRequest request(String name, String type) {
        TechExpertApplyRequest req = new TechExpertApplyRequest();
        req.setName(name);
        req.setExpertType(type);
        req.setOrganization("上海某高校（演示）");
        req.setDomains("机器视觉");
        req.setBio("长期从事机器人视觉研究。");
        req.setConsultFee(new BigDecimal("1000"));
        return req;
    }

    @Test
    void applyCreatesPendingRecordForReview() {
        TechExpert created = service.applyExpert(1L, request("周启明", "EXPERT"));
        assertEquals(0, created.getStatus(), "主动申请必须进入待审核队列");
        assertEquals("APPLIED", created.getInviteStatus());
        assertEquals(0, created.getVerified());
        assertEquals(1L, created.getUserId());
        verify(expertMapper).insert(any(TechExpert.class));
    }

    @Test
    void invitePublishesImmediatelyAsVerified() {
        TechExpert created = service.inviteExpert(request("王思远", "BROKER"));
        assertEquals(1, created.getStatus());
        assertEquals("INVITED", created.getInviteStatus());
        assertEquals(1, created.getVerified());
        assertEquals("BROKER", created.getExpertType());
    }

    @Test
    void applyRejectsForbiddenEndorsementClaim() {
        TechExpertApplyRequest req = request("李某", "EXPERT");
        req.setBio("本平台为政府指定评价机构，保证通过。");
        assertThrows(BusinessException.class, () -> service.applyExpert(1L, req));
        verify(expertMapper, never()).insert(any(TechExpert.class));
    }

    @Test
    void reviewTogglesStatus() {
        TechExpert expert = new TechExpert();
        expert.setExpertId(9L);
        expert.setStatus(0);
        when(expertMapper.selectById(9L)).thenReturn(expert);

        TechExpert approved = service.reviewExpert(9L, "approve", "材料齐备");
        assertEquals(1, approved.getStatus());
        assertEquals(1, approved.getVerified());
        assertEquals("ACTIVE", approved.getInviteStatus());

        TechExpert rejected = service.reviewExpert(9L, "reject", "资质材料缺少执业许可证");
        assertEquals(-1, rejected.getStatus());
        assertEquals(0, rejected.getVerified());
        assertEquals("REJECTED", rejected.getInviteStatus());
    }

    @Test
    void reviewRejectsUnknownDecision() {
        TechExpert expert = new TechExpert();
        expert.setExpertId(9L);
        when(expertMapper.selectById(9L)).thenReturn(expert);
        assertThrows(BusinessException.class, () -> service.reviewExpert(9L, "maybe", "x"));
    }

    @Test
    void rejectWithoutReasonIsRefusedAndNotPersisted() {
        TechExpert expert = new TechExpert();
        expert.setExpertId(9L);
        expert.setStatus(0);
        when(expertMapper.selectById(9L)).thenReturn(expert);

        assertThrows(BusinessException.class, () -> service.reviewExpert(9L, "reject", null));
        assertThrows(BusinessException.class, () -> service.reviewExpert(9L, "reject", "   "));
        verify(expertMapper, never()).updateById(any(TechExpert.class));
        assertEquals(0, expert.getStatus(), "被拒的驳回不应改动原状态");
    }

    @Test
    void reasonForAuditCollapsesNewlinesAndCapsLength() {
        assertEquals("材料不齐 需补许可证", TechExpertService.reviewReasonForAudit("reject", "材料不齐\n需补许可证"));
        assertEquals("未填写原因", TechExpertService.reviewReasonForAudit("approve", "  "));

        String long255 = TechExpertService.reviewReasonForAudit("reject", "长".repeat(400));
        assertEquals(255, long255.length(), "审计 detail 的列宽是 255，超长必须截断而不是让整条留痕写失败");
    }
}
