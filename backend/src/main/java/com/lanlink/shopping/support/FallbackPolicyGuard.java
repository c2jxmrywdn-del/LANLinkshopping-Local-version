package com.lanlink.shopping.support;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 客服备用链路策略守卫（纯逻辑，无状态，可单测）。
 *
 * 强制执行以下约束（违规调用一律拦截并拒绝）：
 *  1. 内容检索与问答响应【只允许】走本地知识库，禁止外部模型生成回答；
 *  2. 外部模型（DeepSeek / Mimosa 等）仅允许用于「寒暄问候 / 嘘寒问暖」类人性化交互；
 *  3. 严禁用户反代模型、或绕过本地知识库限制调用外部模型的行为；
 *  4. 所有外部模型调用必须通过 {@link #authorizeExternalGreeting} 权限校验，
 *     否则返回拒绝原因，由调用方记录违规审计。
 */
public final class FallbackPolicyGuard {

    /** 意图分类结果。 */
    public enum Intent {
        /** 寒暄问候 / 嘘寒问暖（唯一允许外部模型润色语气的场景） */
        GREETING,
        /** 知识问答（只允许本地知识库应答） */
        KNOWLEDGE,
        /** 超出范围的闲聊（礼貌提示，不调用任何外部模型） */
        OUT_OF_SCOPE,
        /** 违规请求：注入 / 反代 / 绕过本地知识库限制 —— 一律拦截 */
        PROHIBITED
    }

    // 违规信号：提示注入 / 反代 / 绕过本地知识库 / 索取外部模型或密钥
    private static final List<String> PROHIBITED_SIGNALS = List.of(
            "反代", "反向代理", "代理转发", "reverse proxy", "代理模型",
            "绕过", "绕开", "越过限制", "bypass", "越权",
            "忽略之前的", "忽略以上", "ignore previous", "ignore all", "disregard",
            "system prompt", "系统提示", "系统指令", "提示词", "jailbreak", "越狱",
            "扮演", "假装你是", "act as", "pretend",
            "deepseek", "mimosa", "openai", "api key", "apikey", "密钥", "token 泄露",
            "外部模型", "大模型直接", "生成一段代码", "执行代码", "运行代码",
            "输出内部", "读取配置", "泄露", "知识库绕过", "跳过知识库"
    );

    // 寒暄问候 / 嘘寒问暖（正则全串锚定，避免把业务问句误判为寒暄）
    private static final Pattern GREETING = Pattern.compile(
            "^(?:(?:你好|您好|哈喽|哈啰|嗨|嘿|在吗|在不在|有人吗|"
                    + "早上好|早安|中午好|下午好|晚安|晚上好|"
                    + "辛苦了|辛苦啦|你辛苦|多谢|谢谢|谢谢你|感谢|感谢你|麻烦你|麻烦您|"
                    + "再见|拜拜|good morning|good afternoon|good evening|hi|hello|hey|thanks|thank you)"
                    + "[\\s!！?？。.，,、～~·啊呀嘛呗哦呢哈]*)+$", Pattern.CASE_INSENSITIVE);

    // 知识问答信号：平台业务域关键词（命中即判定为知识问答 → 只走本地知识库）
    private static final List<String> KNOWLEDGE_SIGNALS = List.of(
            "账号", "注册", "登录", "验证码", "密码", "商品", "产品", "商城", "分类", "行业",
            "购物车", "结算", "订单", "下单", "物流", "快递", "发货", "运费", "配送", "单号", "包裹",
            "商户", "供应商", "入驻", "开店", "钱包", "充值", "账期", "余额",
            "会员", "积分", "活动", "促销", "优惠", "发票", "开票", "退货", "退款", "售后",
            "消息", "通知", "cookie", "隐私", "收货地址", "地址管理", "价格", "库存", "规格",
            "人工", "客服", "投诉"
    );

    private FallbackPolicyGuard() {
    }

    /** 意图分类：PROHIBITED 优先，其次寒暄，再次知识问答，其余为超范围闲聊。 */
    public static Intent classify(String question) {
        if (question == null || question.isBlank()) return Intent.OUT_OF_SCOPE;
        String q = question.trim().toLowerCase();
        for (String signal : PROHIBITED_SIGNALS) {
            if (q.contains(signal)) return Intent.PROHIBITED;
        }
        if (GREETING.matcher(q).matches()) return Intent.GREETING;
        for (String signal : KNOWLEDGE_SIGNALS) {
            if (q.contains(signal)) return Intent.KNOWLEDGE;
        }
        return Intent.OUT_OF_SCOPE;
    }

    /**
     * 外部模型调用权限校验（唯一放行口）。
     * 仅当：备用链路已激活 + 意图为寒暄关怀 + 未命中违规信号 时允许；
     * 其余一律返回拒绝原因，禁止任何外部模型生成。
     *
     * @return null 表示允许；非 null 为拒绝原因（调用方须记录违规审计）
     */
    public static String authorizeExternalGreeting(Intent intent, boolean backupActive) {
        if (!backupActive) return "备用链路未激活，禁止调用外部模型";
        if (intent == Intent.PROHIBITED) return "请求包含注入/反代/绕过知识库等违规信号，禁止调用外部模型";
        if (intent != Intent.GREETING) return "外部模型仅允许寒暄问候/嘘寒问暖交互，知识问答必须走本地知识库";
        return null;
    }

    /**
     * 外部模型意图分类权限校验（意图增强唯一放行口）。
     * 仅当：外部增强开关开启 + 意图为知识问答或寒暄 + 未命中违规信号 时允许；
     * 外部模型只允许输出预定义意图标签，绝不生成回答文本（与寒暄润色同等约束）。
     *
     * @return null 表示允许；非 null 为拒绝原因
     */
    public static String authorizeExternalIntent(Intent intent, boolean externalToggleOn) {
        if (!externalToggleOn) return "外部意图增强未开启，禁止调用外部模型";
        if (intent == Intent.PROHIBITED) return "请求包含注入/反代/绕过知识库等违规信号，禁止调用外部模型";
        if (intent != Intent.KNOWLEDGE && intent != Intent.GREETING) {
            return "该意图不支持外部模型辅助分类";
        }
        return null;
    }

    /** 外部意图分类输出白名单校验：只允许预定义意图标签（防输出注入/自由文本）。 */
    public static boolean isSafeIntentLabel(String label, Set<String> allowedLabels) {
        if (label == null || allowedLabels == null) return false;
        String t = label.trim();
        return t.length() <= 40 && allowedLabels.contains(t);
    }

    /**
     * 外部模型输出安全校验（寒暄场景）：
     * 只允许简短、无链接、无指令痕迹的温暖寒暄文本；不合格即丢弃，改用本地模板。
     */
    public static boolean isSafeGreetingOutput(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty() || t.length() > 120) return false;
        String lower = t.toLowerCase();
        if (lower.contains("http") || lower.contains("www.")) return false;
        for (String signal : PROHIBITED_SIGNALS) {
            if (lower.contains(signal)) return false;
        }
        return true;
    }
}
