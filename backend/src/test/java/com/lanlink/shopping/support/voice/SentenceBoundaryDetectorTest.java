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
}
