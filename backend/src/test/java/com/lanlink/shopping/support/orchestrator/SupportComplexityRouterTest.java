package com.lanlink.shopping.support.orchestrator;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 智能客服复杂度分流测试：验证三层漏斗分流决策的准确性与模型推荐逻辑。
 */
class SupportComplexityRouterTest {

    private final SupportComplexityRouter router = new SupportComplexityRouter();

    @Test
    void routesExactKbMatchToTier1() {
        var decision = router.evaluate("如何修改收货地址", Collections.emptyList(), "", true, "kb-addr-001");
        assertEquals(SupportComplexityRouter.Tier.TIER_1_EXACT_KB, decision.tier());
        assertFalse(decision.isComplexDiverted());
        assertEquals("local:kb", decision.recommendedModel());
    }

    @Test
    void routesNormalProductQuestionToTier2() {
        var decision = router.evaluate("这款无线耳机有现货吗？", Collections.emptyList(), "", true, null);
        assertEquals(SupportComplexityRouter.Tier.TIER_2_LOCAL_RAG, decision.tier());
        assertFalse(decision.isComplexDiverted());
        assertEquals("local:qwen2.5-7b", decision.recommendedModel());
    }

    @Test
    void routesMultipleImagesToTier3() {
        var decision = router.evaluate("帮我看看这两个商品", List.of("img1", "img2"), "图片详情", true, null);
        assertEquals(SupportComplexityRouter.Tier.TIER_3_EXTERNAL_EMBEDDED, decision.tier());
        assertTrue(decision.isComplexDiverted());
        assertEquals("mimo-v2.6-flash", decision.recommendedModel());
    }

    @Test
    void routesVideoAttachmentToTier3() {
        var decision = router.evaluate("机器开不了机", List.of("vid1"), "【视频附件】开箱测试视频", true, null);
        assertEquals(SupportComplexityRouter.Tier.TIER_3_EXTERNAL_EMBEDDED, decision.tier());
        assertTrue(decision.isComplexDiverted());
        assertEquals("mimo-v2.6-flash", decision.recommendedModel());
    }

    @Test
    void routesDefectSignalToTier3() {
        var decision = router.evaluate("收到的包裹破损很严重，里面的杯子碎了", List.of("img1"), "快递箱外观", true, null);
        assertEquals(SupportComplexityRouter.Tier.TIER_3_EXTERNAL_EMBEDDED, decision.tier());
        assertTrue(decision.isComplexDiverted());
        assertEquals("mimo-v2.6-flash", decision.recommendedModel());
    }

    @Test
    void routesDisputeSignalToTier3Pro() {
        var decision = router.evaluate("商家拒绝退款，运费险不赔，我要投诉消协维权", Collections.emptyList(), "", true, null);
        assertEquals(SupportComplexityRouter.Tier.TIER_3_EXTERNAL_EMBEDDED, decision.tier());
        assertTrue(decision.isComplexDiverted());
        assertEquals("mimo-v2.6-pro", decision.recommendedModel(), "深度纠纷应推荐 mimo-v2.6-pro 旗舰推理模型");
    }

    @Test
    void routesComparisonSignalToTier3Flash() {
        var decision = router.evaluate("对比一下这两款机械键盘的区别，哪款更划算", Collections.emptyList(), "", true, null);
        assertEquals(SupportComplexityRouter.Tier.TIER_3_EXTERNAL_EMBEDDED, decision.tier());
        assertTrue(decision.isComplexDiverted());
        assertEquals("mimo-v2.6-flash", decision.recommendedModel());
    }
}
