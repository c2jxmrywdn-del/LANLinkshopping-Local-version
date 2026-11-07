package com.lanlink.shopping.module.pricemonitor.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 分析链内容安全过滤器（有害信息拦截 + 提示注入净化）。
 *
 * <p>作用面：
 * <ul>
 *   <li>网络检索结果进入五维因子分析前——外部信息不可信，有害内容整条丢弃；</li>
 *   <li>商户可编辑的商品文本（标题/详情）进入 AI 上下文前——只净化注入模式，不阻断商品本身
 *       （有害商品应走商户治理/风控，而非静默丢弃导致分析缺数据）。</li>
 * </ul>
 *
 * <p>词表为演示平台的起步集，正式环境应接入内容安全服务并按法规扩充。
 */
@Service
public class AnalysisSafetyFilter {

    /** 有害内容关键词（命中即整条丢弃）：毒品/赌博/色情/暴恐/违禁交易 */
    private static final String[] HARMFUL_KEYWORDS = {
            "冰毒", "海洛因", "摇头丸", "大麻", "可卡因", "迷奸", "致幻剂",
            "赌博", "博彩", "赌场", "棋牌娱乐城", "六合彩",
            "色情", "淫秽", "裸聊", "援交",
            "枪支弹药", "制枪", "爆炸物制作", "雷管", "炸药配方",
            "代办证件", "发票代开", "洗钱", "传销", "刷单兼职",
            "自杀方法", "自残教程",
    };

    /**
     * 提示注入模式（净化为无害占位）：防止外部文本改写 AI 的系统指令。
     * 前六条为指令式注入语句（输出侧回声检测同源复用，见 {@link #ECHO_PATTERNS}）；
     * 后两条为商户字段易漏审的二次注入载体：行首 Markdown 标题 / 行首角色前缀（system:/assistant:），
     * 行首锚定（MULTILINE）限定误伤面——正文中间的"system: Android"这类规格描述不受影响。
     * 注意：后两条不参与 {@link #containsInjectionEcho}——模型正常回答允许用 Markdown 分节。
     */
    private static final Pattern[] INJECTION_PATTERNS = {
            Pattern.compile("(?i)(?:ignore|disregard|forget|skip|override)\\s+(?:all\\s+)?(?:previous|prior|above|existing)\\s+(?:instructions|prompts?|rules?|directives?)"),
            Pattern.compile("(?i)disregard\\s+(all\\s+)?(previous|above)"),
            Pattern.compile("(?i)(?:你|请|现在)?(?:忽略|无视|忘记|跳过|覆盖)(?:上面|之前|以上|先前|已有)的?(?:所有)?(?:指令|提示|要求|设定|规则)"),
            Pattern.compile("(?i)system\\s*prompt|系统提示词|初始指令|developer\\s*mode|开发者模式|jailbreak|越狱模式"),
            Pattern.compile("(?i)</?(?:system|instructions|assistant|think|developer|prompt)[^>]{0,30}>"),
            Pattern.compile("(?i)(?:you\\s+are\\s+now|act\\s+as|pretend\\s+to\\s+be|from\\s+now\\s+on\\s+you\\s+are)\\s+"),
            Pattern.compile("(?i)(?:从现在开始你是|假装你是|请扮演|切换至.*模式)"),
            Pattern.compile("(?im)^[ \\t]{0,8}#{1,6}[ \\t]\\S"),
            Pattern.compile("(?im)^[ \\t]{0,8}(system|assistant|developer)[ \\t]*[:：]"),
    };

    /**
     * 输出侧回声特征：只含指令式注入语句（INJECTION_PATTERNS 中的指令特征），
     * 不含行首 Markdown/角色前缀——"### 标题"是模型回答的合法排版，不能据此降级。
     */
    private static final Pattern[] ECHO_PATTERNS = {
            INJECTION_PATTERNS[0], INJECTION_PATTERNS[1], INJECTION_PATTERNS[2],
            INJECTION_PATTERNS[3], INJECTION_PATTERNS[4], INJECTION_PATTERNS[5],
            INJECTION_PATTERNS[6]
    };

    /** 过滤判定结果 */
    public record SafetyVerdict(boolean clean, String reason) {}

    /** 网络检索等不可信内容：有害即拒绝 */
    public SafetyVerdict checkText(String text) {
        if (!StringUtils.hasText(text)) return new SafetyVerdict(true, "");
        String lower = text.toLowerCase();
        for (String kw : HARMFUL_KEYWORDS) {
            if (lower.contains(kw)) return new SafetyVerdict(false, "命中有害关键词：" + kw);
        }
        return new SafetyVerdict(true, "");
    }

    /** 注入净化：把注入模式替换为占位符，文本本身保留（商品信息不丢） */
    public String sanitizeInjection(String text) {
        return sanitize(text);
    }

    /**
     * 注入净化（静态版）：供无法便捷注入本 Bean 的链路使用（商品入库、向量知识库灌装、客服应答等）。
     * 把注入模式替换为占位符，文本本身保留（商品信息不丢）。
     */
    public static String sanitize(String text) {
        if (!StringUtils.hasText(text)) return text == null ? "" : text;
        // 清理零宽空格与隐蔽逃逸控制符 (\u200B, \u200C, \u200D, \uFEFF)
        String out = text.replaceAll("[\u200B\u200C\u200D\uFEFF]", "");
        for (Pattern p : INJECTION_PATTERNS) {
            out = p.matcher(out).replaceAll("[已过滤指令式内容]");
        }
        return out;
    }

    /**
     * 输出侧注入回声检测（静态版）：模型回答中出现注入指令特征（"忽略之前的指令" /
     * "system prompt" / 角色改写要求等）说明上下文注入已部分生效，调用方应丢弃该回答并降级兜底。
     * 词表用 {@link #ECHO_PATTERNS}（不含行首 Markdown/角色前缀，避免误杀正常排版回答）。
     */
    public static boolean containsInjectionEcho(String text) {
        if (!StringUtils.hasText(text)) return false;
        for (Pattern p : ECHO_PATTERNS) {
            if (p.matcher(text).find()) return true;
        }
        return false;
    }

    /** 商户商品文本（标题/详情）进入 AI 上下文前：净化注入 + 标记有害 */
    public Map<String, Object> screenProductText(String title, String detail) {
        Map<String, Object> out = new LinkedHashMap<>();
        String merged = (title == null ? "" : title) + "\n" + (detail == null ? "" : detail);
        SafetyVerdict v = checkText(merged);
        out.put("clean", v.clean());
        out.put("reason", v.reason());
        out.put("title", sanitizeInjection(title == null ? "" : title));
        out.put("detail", sanitizeInjection(detail == null ? "" : detail));
        return out;
    }
}
