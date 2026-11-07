package com.lanlink.shopping.support;

import com.lanlink.shopping.module.techservice.domain.TechAchievementAssessor;
import com.lanlink.shopping.module.techservice.entity.TechAchievement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 成果快筛评分器：确定性、四维评分、免责声明与沉睡提示。 */
class TechAchievementAssessorTest {

    private final TechAchievementAssessor assessor = new TechAchievementAssessor();

    private TechAchievement achievement(String legalStatus, int trl, String licensing, int dormant) {
        TechAchievement a = new TechAchievement();
        a.setAchievementId(1L);
        a.setTitle("面向精密装配的视觉引导机器人标定方法");
        a.setPatentType("INVENTION");
        a.setLegalStatus(legalStatus);
        a.setVerified(1);
        a.setMaturityLevel(trl);
        a.setExpectedPrice(new BigDecimal("850000"));
        a.setLicensingMode(licensing);
        a.setDormant(dormant);
        return a;
    }

    @Test
    void producesDeterministicFourDimensionScore() {
        Map<String, Object> first = assessor.assess(achievement("有效", 7, "LICENSE", 0));
        Map<String, Object> second = assessor.assess(achievement("有效", 7, "LICENSE", 0));
        assertEquals(first.get("composite"), second.get("composite"));
        assertEquals(first.get("level"), second.get("level"));

        Map<?, ?> scores = (Map<?, ?>) first.get("scores");
        assertEquals(4, scores.size(), "法律/技术/经济/成熟度四维");
        int composite = (Integer) first.get("composite");
        assertTrue(composite >= 55 && composite <= 98);
        assertTrue(List.of("A", "B", "C", "D").contains(first.get("level")));
    }

    @Test
    void invalidLegalStatusLowersLegalScore() {
        Map<?, ?> valid = (Map<?, ?>) assessor.assess(achievement("有效", 7, "LICENSE", 0)).get("scores");
        Map<?, ?> expired = (Map<?, ?>) assessor.assess(achievement("失效", 7, "LICENSE", 0)).get("scores");
        assertTrue((Integer) expired.get("legal") < (Integer) valid.get("legal"));
    }

    @Test
    void dormantPatentAddsActivationAdvice() {
        Map<String, Object> result = assessor.assess(achievement("有效", 5, "OPEN_LICENSE", 1));
        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) result.get("reasons");
        assertTrue(reasons.stream().anyMatch(r -> r.contains("沉睡专利")));
    }

    @Test
    void outputCarriesStandardAndDisclaimer() {
        Map<String, Object> result = assessor.assess(achievement("审中", 4, "ASSIGN", 0));
        assertTrue(String.valueOf(result.get("standard")).contains("GB/T 44731-2024"));
        assertTrue(String.valueOf(result.get("disclaimer")).contains("不构成评估结论"));
    }
}
