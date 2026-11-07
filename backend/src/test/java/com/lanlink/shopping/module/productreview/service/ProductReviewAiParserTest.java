package com.lanlink.shopping.module.productreview.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 输出解析白名单单测：合法 JSON 通过，Markdown 包裹容忍，越界枚举/缺字段/非 JSON 一律拒绝。
 */
class ProductReviewAiParserTest {

    @Test
    void parsesValidTextReviewWithMarkdownFence() {
        String content = "```json\n{\"decision\":\"PASS\",\"risk\":\"LOW\",\"score\":91,"
                + "\"issues\":[]}\n```";
        var r = ProductReviewAiService.parseReview(content, "deepseek:deepseek-flash");
        assertTrue(r.isPresent());
        assertEquals("PASS", r.get().decision());
        assertEquals("LOW", r.get().risk());
        assertEquals(91, r.get().score());
    }

    @Test
    void rejectsUnknownDecisionAndOutOfRangeScore() {
        assertTrue(ProductReviewAiService.parseReview(
                "{\"decision\":\"MAYBE\",\"risk\":\"LOW\",\"score\":80}", "m").isEmpty());
        assertTrue(ProductReviewAiService.parseReview(
                "{\"decision\":\"PASS\",\"risk\":\"LOW\",\"score\":101}", "m").isEmpty());
        assertTrue(ProductReviewAiService.parseReview("not-json", "m").isEmpty());
    }

    @Test
    void normalizesUnknownIssueCodeToOther() {
        var r = ProductReviewAiService.parseReview(
                "{\"decision\":\"REVIEW\",\"risk\":\"MEDIUM\",\"score\":70,"
                        + "\"issues\":[{\"code\":\"CUSTOM\",\"message\":\"需人工确认\"}]}", "m");
        assertTrue(r.isPresent());
        assertEquals("OTHER", r.get().issues().get(0).code());
    }

    @Test
    void parsesCompactTextFormat() {
        var r = ProductReviewAiService.parseReview("PASS|LOW|98|NONE", "local-7b");
        assertTrue(r.isPresent());
        assertEquals("PASS", r.get().decision());
        assertEquals(98, r.get().score());
        assertTrue(r.get().issues().isEmpty());

        var reject = ProductReviewAiService.parseReview(
                "REVIEW|MEDIUM|70|CATEGORY_MISMATCH: 类目不符, CONTACT_INFO: 含微信", "local");
        assertTrue(reject.isPresent());
        assertEquals(2, reject.get().issues().size());
        assertEquals("CATEGORY_MISMATCH", reject.get().issues().get(0).code());
    }

    @Test
    void extractsVerdictFromLabeledProse() {
        var r = ProductReviewAiService.parseReview(
                "**Decision**: PASS\n**Risk**: LOW\n**Score**: 95\n**Issues**: None", "local-7b");
        assertTrue(r.isPresent());
        assertEquals("PASS", r.get().decision());
        assertEquals("LOW", r.get().risk());
        assertEquals(95, r.get().score());
    }

    @Test
    void parsesCompactVisionFormat() {
        var r = ProductReviewVisionService.parseVision("MATCH|LOW|NONE", "qwen2.5-vl");
        assertTrue(r.isPresent());
        assertTrue(r.get().match());
        assertEquals("LOW", r.get().risk());
        assertTrue(r.get().issues().isEmpty());

        var bad = ProductReviewVisionService.parseVision("MISMATCH|HIGH|二维码、与标题不符", "vl");
        assertTrue(bad.isPresent());
        assertFalse(bad.get().match());
        assertEquals(2, bad.get().issues().size());
    }

    @Test
    void parsesVisionReviewAndRejectsInvalidRisk() {
        var ok = ProductReviewVisionService.parseVision(
                "{\"match\":true,\"risk\":\"LOW\",\"issues\":[]}", "qwen2.5-vl");
        assertTrue(ok.isPresent());
        assertTrue(ok.get().match());

        assertTrue(ProductReviewVisionService.parseVision(
                "{\"match\":false,\"risk\":\"UNKNOWN\",\"issues\":[\"二维码\"]}", "vl").isEmpty());
        assertTrue(ProductReviewVisionService.parseVision("no-json", "vl").isEmpty());
    }
}
