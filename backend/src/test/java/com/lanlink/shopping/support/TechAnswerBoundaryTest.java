package com.lanlink.shopping.support;

import com.lanlink.shopping.module.techservice.service.TechAnswerBoundary;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 输出侧合规闸：检索到的服务目录文案会把模型带向「平台可以出具评价结论」这类越界承诺，
 * 单靠 system prompt 拦不住，必须在返回前做一次判定。
 */
class TechAnswerBoundaryTest {

    @Test
    void flagsAnswerClaimingPlatformIssuesAppraisalConclusion() {
        // 2026-11-04 容器实测到的真实越界回答，必须被拦下
        String observed = "根据现有资料，平台可以提供科技成果评价报告。平台仅以详情页为准。";
        Optional<String> violation = TechAnswerBoundary.violationOf(observed);
        assertTrue(violation.isPresent(), "把「出具评价结论」说成平台能力，正是必须禁止的越界表述");
    }

    @Test
    void flagsEndorsementVocabulary() {
        assertTrue(TechAnswerBoundary.violationOf("我们是国家级技术交易平台").isPresent());
        assertTrue(TechAnswerBoundary.violationOf("平台经政府指定出具鉴定意见").isPresent());
    }

    @Test
    void allowsCompliantAnswerThatAttributesConclusionsToInstitutions() {
        String ok = "科技成果评价报告由具备能力的第三方机构出具，平台只做材料组织与流程跟踪。"
                + "专利代理需由持牌代理机构承办，平台不代办。";
        assertTrue(TechAnswerBoundary.violationOf(ok).isEmpty(),
                "合规答案不能被误伤，否则这道闸会把有用回答全打掉");
    }

    @Test
    void allowsAnswerThatOnlyDescribesScreeningAsNonConclusion() {
        String ok = "平台提供 AI 快筛，结果不构成评估结论，仅供参考。";
        assertTrue(TechAnswerBoundary.violationOf(ok).isEmpty());
    }

    @Test
    void nullAndBlankAnswersAreNotViolations() {
        assertTrue(TechAnswerBoundary.violationOf(null).isEmpty());
        assertTrue(TechAnswerBoundary.violationOf("   ").isEmpty());
    }

    @Test
    void refusalTextItselfStaysInsideTheBoundary() {
        String refusal = TechAnswerBoundary.boundaryAnswer();
        assertTrue(TechAnswerBoundary.violationOf(refusal).isEmpty(),
                "兜底文案自己越界就没有意义了");
    }
}
