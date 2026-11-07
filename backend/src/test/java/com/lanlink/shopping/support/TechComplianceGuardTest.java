package com.lanlink.shopping.support;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.service.TechComplianceGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 技术服务业务边界：禁用表述拦截 + 边界清单完整性。
 */
class TechComplianceGuardTest {

    private TechComplianceGuard guard;

    @BeforeEach
    void setUp() {
        guard = new TechComplianceGuard();
    }

    @Test
    void rejectsGovernmentEndorsementClaims() {
        assertThrows(BusinessException.class, () -> guard.validate("需求描述", "我们是政府指定平台，保证成交"));
        assertThrows(BusinessException.class, () -> guard.validate("标题", "国家级技术交易平台"));
        assertThrows(BusinessException.class, () -> guard.validate("正文", "官方背书，包授权包下证"));
    }

    @Test
    void acceptsPlainObjectiveDescription() {
        assertDoesNotThrow(() -> guard.validate("需求描述", "希望寻找耐高温导热材料，预算 30-80 万，周期三个月。"));
        assertDoesNotThrow(() -> guard.validate("标题", null));
        assertDoesNotThrow(() -> guard.validate("标题", "   "));
    }

    @Test
    void rejectsIllegalServiceAndBlackIndustryTerms() {
        assertThrows(BusinessException.class, () -> guard.validate("需求描述", "需要一批接码平台账号用于注册"));
        assertThrows(BusinessException.class, () -> guard.validate("正文", "可代做刷单与网络水军"));
        assertThrows(BusinessException.class, () -> guard.validate("托管范围", "提供窃听器材定制方案"));
        assertThrows(BusinessException.class, () -> guard.validate("需求标题", "拖库撞库数据收购"));
    }

    @Test
    void riskControlDemandMentionsAntiMoneyLaundering() {
        // 「反洗钱」是正当技术方向，词面与黑产词重合，误拒会让真实需求发不出来
        assertDoesNotThrow(() -> guard.validate("需求描述", "希望引入反洗钱模型与交易洗钱监测能力"));
        assertThrows(BusinessException.class, () -> guard.validate("需求描述", "提供洗钱通道与跑分套现"));
    }

    @Test
    void boundaryExposesBothBlocklists() {
        Map<String, Object> boundary = guard.boundary();
        assertTrue(((List<?>) boundary.get("sensitiveTerms")).size() >= 15,
                "内容审核最小集必须成清单暴露给运营，不能只藏在校验里");
        assertTrue(((List<?>) boundary.get("forbiddenClaims")).contains("国家级"));
        assertTrue(((List<?>) boundary.get("sensitiveTerms")).contains("接码平台"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void boundaryCoversAllServiceLines() {
        Map<String, Object> boundary = guard.boundary();
        List<Map<String, Object>> items = (List<Map<String, Object>>) boundary.get("items");
        assertEquals(8, items.size(), "八大服务线都必须有明确的合规边界");

        List<String> modules = items.stream().map(i -> String.valueOf(i.get("module"))).toList();
        assertTrue(modules.contains("科技成果评价"));
        assertTrue(modules.contains("知识产权服务机构托管"));
        assertTrue(modules.contains("技术收储-托管-交易"));

        // 评价服务必须写明「不得以平台名义出具结论」
        Map<String, Object> appraisal = items.stream()
                .filter(i -> "科技成果评价".equals(i.get("module"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(appraisal.get("forbidden")).contains("出具评价结论"));

        assertNotNull(boundary.get("notice"));
        assertTrue(((List<?>) boundary.get("forbiddenClaims")).size() >= 10);
    }
}
