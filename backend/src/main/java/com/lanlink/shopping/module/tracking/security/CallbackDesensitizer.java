package com.lanlink.shopping.module.tracking.security;

import com.lanlink.shopping.common.DesensitizeUtil;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回调链路脱敏（out/logistics-tracking-plan.md §4.4 红线）：
 * 任何落库（tracking_event.raw_body / tracking_callback_log.body_raw）与日志输出之前执行。
 * 复用既有 common/DesensitizeUtil 方法集（手机号/邮箱/身份证/银行卡自由文本掩码），
 * 叠加 JSON 结构化字段的地址/姓名掩码；方法幂等，重复调用不叠加掩码。
 */
@Component
public class CallbackDesensitizer {

    /** JSON 字符串字段级掩码：只动 value，不破坏 JSON 结构 */
    private static final Pattern ADDRESS_FIELD =
            Pattern.compile("\"([A-Za-z_]*[Aa]ddress[A-Za-z_]*)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NAME_FIELD =
            Pattern.compile("\"(receiver_?name|sender_?name|consignee|contact_?name|name)\"\\s*:\\s*\"([^\"]*)\"");

    /** 自由文本 PII（手机号/邮箱/身份证/银行卡）+ JSON 地址/姓名字段 */
    public String desensitize(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String out = DesensitizeUtil.maskPiiInText(raw);
        out = maskJsonStringFields(ADDRESS_FIELD, out, DesensitizeUtil::maskAddress);
        out = maskJsonStringFields(NAME_FIELD, out, DesensitizeUtil::maskRealName);
        return out;
    }

    /** 脱敏后截断（成功回调日志只存前 maxChars 摘要，§4.3） */
    public String summarize(String raw, int maxChars) {
        String masked = desensitize(raw);
        if (masked == null) return null;
        return masked.length() <= maxChars ? masked : masked.substring(0, maxChars);
    }

    private String maskJsonStringFields(Pattern pattern, String text, java.util.function.UnaryOperator<String> masker) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (matcher.find()) {
            String masked = masker.apply(matcher.group(2));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    "\"" + matcher.group(1) + "\":\"" + (masked == null ? "" : masked) + "\""));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
