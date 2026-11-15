package com.lanlink.shopping.support.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能客服实时流式分句检测器 (Sentence Boundary Detector).
 *
 * <p>核心设计指标：
 * <ul>
 *   <li>首句延迟最小化：累积文本达到强标点（≥8 字符）或弱标点（≥14 字符）立即切句，下发 TTS；</li>
 *   <li>防饥饿截断：当连续生成超过 30 字符且无标点时，强制在最近的词边界切断，避免音频播报断流；</li>
 *   <li>幂等与有序：严格保持字符顺序与剩余缓冲区管理；在流结束时调用 finish() 刷出尾句。</li>
 * </ul>
 */
public class SentenceBoundaryDetector {

    private static final int MIN_STRONG_CHARS = 8;
    private static final int MIN_WEAK_CHARS = 14;
    private static final int MAX_HUNGER_CHARS = 30;

    private final StringBuilder buffer = new StringBuilder();
    private boolean isFirstSentence = true;

    /**
     * 追加流式文本增量 (Delta) 并返回已切分的就绪句子列表。
     *
     * @param delta LLM 流式输出文本片段
     * @return 本批次检测出的完整句子列表
     */
    public synchronized List<String> append(String delta) {
        List<String> sentences = new ArrayList<>();
        if (delta == null || delta.isEmpty()) {
            return sentences;
        }

        buffer.append(delta);

        while (true) {
            int splitIdx = findSplitIndex();
            if (splitIdx < 0) {
                break;
            }

            String sentence = buffer.substring(0, splitIdx).trim();
            buffer.delete(0, splitIdx);

            if (!sentence.isEmpty()) {
                sentences.add(sentence);
                isFirstSentence = false;
            }
        }

        return sentences;
    }

    /**
     * 流结束时刷出缓冲区中剩余的最后一段文本。
     *
     * @return 最终尾句；若无剩余内容则返回 null
     */
    public synchronized String finish() {
        if (buffer.length() == 0) {
            return null;
        }
        String trailing = buffer.toString().trim();
        buffer.setLength(0);
        return trailing.isEmpty() ? null : trailing;
    }

    /**
     * 寻找当前缓冲区内的最佳切句点索引 (1-based substring end index)。
     */
    private int findSplitIndex() {
        int len = buffer.length();
        if (len == 0) {
            return -1;
        }

        int strongThreshold = isFirstSentence ? 6 : MIN_STRONG_CHARS;
        int weakThreshold = isFirstSentence ? 10 : MIN_WEAK_CHARS;

        for (int i = 0; i < len; i++) {
            char c = buffer.charAt(i);

            // 强标点：句号、叹号、问号、换行
            if (isStrongPunctuation(c)) {
                if (i + 1 >= strongThreshold) {
                    return i + 1;
                }
            }
            // 弱标点：逗号、分号
            else if (isWeakPunctuation(c)) {
                if (i + 1 >= weakThreshold) {
                    return i + 1;
                }
            }
        }

        // 防饥饿强制截断
        if (len >= MAX_HUNGER_CHARS) {
            // 优先在最近的空格或符号处截断
            for (int i = len - 1; i >= strongThreshold; i--) {
                char c = buffer.charAt(i);
                if (Character.isWhitespace(c) || isAnyPunctuation(c)) {
                    return i + 1;
                }
            }
            // 无边界时硬截断
            return MAX_HUNGER_CHARS;
        }

        return -1;
    }

    private static boolean isStrongPunctuation(char c) {
        return c == '。' || c == '！' || c == '!' || c == '？' || c == '?' || c == '\n';
    }

    private static boolean isWeakPunctuation(char c) {
        return c == '，' || c == ',' || c == '；' || c == ';' || c == '：' || c == ':';
    }

    private static boolean isAnyPunctuation(char c) {
        return isStrongPunctuation(c) || isWeakPunctuation(c) || c == '、';
    }
}
