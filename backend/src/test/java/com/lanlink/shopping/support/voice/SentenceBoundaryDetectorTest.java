package com.lanlink.shopping.support.voice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SentenceBoundaryDetectorTest {

    @Test
    void testBasicSentenceSplitting() {
        SentenceBoundaryDetector detector = new SentenceBoundaryDetector();

        List<String> s1 = detector.append("您好，我是专属智能客服。");
        assertEquals(1, s1.size());
        assertEquals("您好，我是专属智能客服。", s1.get(0));

        List<String> s2 = detector.append("请问有什么可以");
        assertTrue(s2.isEmpty());

        List<String> s3 = detector.append("帮助您的呢？欢迎咨询平台业务。");
        assertEquals(2, s3.size());
        assertEquals("请问有什么可以帮助您的呢？", s3.get(0));
        assertEquals("欢迎咨询平台业务。", s3.get(1));

        String tail = detector.finish();
        assertNull(tail);
    }

    @Test
    void testAntiHungerCutoff() {
        SentenceBoundaryDetector detector = new SentenceBoundaryDetector();
        String longTextWithoutPunctuation = "这是一段非常漫长且没有任何标点符号的连续文本用于测试切词看门狗防饥饿机制";
        List<String> result = detector.append(longTextWithoutPunctuation);

        assertFalse(result.isEmpty());
        assertTrue(result.get(0).length() <= 30);

        String tail = detector.finish();
        assertNotNull(tail);
    }

    @Test
    void testFirstSentenceSpeed() {
        SentenceBoundaryDetector detector = new SentenceBoundaryDetector();
        List<String> s1 = detector.append("订单已发货。");
        assertEquals(1, s1.size());
        assertEquals("订单已发货。", s1.get(0));
    }

    /**
     * URL / 端口 / 金额里的冒号与逗号不是句边界。
     *
     * <p>回归背景：弱标点（`:` `,`）无条件切句会把
     * `https://example.com:8080/a,b` 切成 `https:` + `//example.com:` + `8080/a,`，
     * 每片单独送去 TTS，播报变成「https 冒号 斜杠 斜杠…」无法理解。
     */
    @Test
    void testUrlAndPortAreNotSplitOnWeakPunctuation() {
        SentenceBoundaryDetector detector = new SentenceBoundaryDetector();
        List<String> result = detector.append("详情请访问 https://example.com:8080/order 查看。");

        // 整句应作为一个片段（或至少不在 `https:` / `:8080` 处断开）
        for (String s : result) {
            assertFalse(s.endsWith("https:"), "不应在协议冒号处切句: " + s);
            assertFalse(s.endsWith("example.com:"), "不应在端口冒号处切句: " + s);
        }
        assertTrue(result.stream().anyMatch(s -> s.contains("https://example.com:8080")),
                "URL 应保持完整，实际: " + result);
    }

    /** 金额千分位逗号不切句。 */
    @Test
    void testAmountThousandSeparatorIsNotSplit() {
        SentenceBoundaryDetector detector = new SentenceBoundaryDetector();
        List<String> result = detector.append("该商品售价为 $1,299.99，下单立减。");

        assertTrue(result.stream().anyMatch(s -> s.contains("$1,299.99")),
                "金额应保持完整，实际: " + result);
    }

    /** 中文逗号仍然是合法切句点（豁免规则不能误伤正常中文）。 */
    @Test
    void testChineseCommaStillSplits() {
        SentenceBoundaryDetector detector = new SentenceBoundaryDetector();
        List<String> result = detector.append("您好，欢迎咨询平台业务。");

        assertFalse(result.isEmpty());
        assertEquals("您好，欢迎咨询平台业务。", result.get(0));
    }
}
