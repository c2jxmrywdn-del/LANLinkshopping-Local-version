package com.lanlink.shopping.common;

/**
 * 数据脱敏工具类（后端向前端安全输出治理）。
 * 遵循《个人信息保护法》与金融安全规范：
 * - 手机号：138****1234
 * - 身份证号：110101********1234
 * - 银行卡号：6222***********1234
 * - 电子邮箱：u***r@domain.com
 * - 真实姓名：张* / 李*龙 / 诸**明
 * - API 密钥 / 凭据：sk-****abcd
 * - 地址：详细门牌号后段脱敏
 * - 纳税人识别号 / 统一社会信用代码：前4后4明文，中间脱敏
 * 所有方法幂等支持：传入已脱敏字符直接原样返回，防止重复嵌套加掩码。
 */
public final class DesensitizeUtil {

    private DesensitizeUtil() {}

    /** 检查是否为已脱敏数据（含星号掩码） */
    public static boolean isMasked(String value) {
        return value != null && value.contains("*");
    }

    /** 手机号脱敏：保留前3后4，中间4位掩码 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return phone;
        if (isMasked(phone)) return phone;
        String trimmed = phone.trim();
        if (trimmed.length() == 11) {
            return trimmed.substring(0, 3) + "****" + trimmed.substring(7);
        }
        if (trimmed.length() > 7) {
            return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 4);
        }
        return "****";
    }

    /** 邮箱脱敏：保留首尾字符与域名，用户名中间掩码 */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) return email;
        if (isMasked(email)) return email;
        String trimmed = email.trim();
        int atIndex = trimmed.indexOf('@');
        if (atIndex <= 1) {
            return "***" + (atIndex >= 0 ? trimmed.substring(atIndex) : "");
        }
        String name = trimmed.substring(0, atIndex);
        String domain = trimmed.substring(atIndex);
        if (name.length() <= 2) {
            return name.charAt(0) + "*" + domain;
        }
        return name.charAt(0) + "***" + name.charAt(name.length() - 1) + domain;
    }

    /** 身份证号脱敏：18位保留前6后4，出生日期段全掩码 */
    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.isBlank()) return idCard;
        if (isMasked(idCard)) return idCard;
        String trimmed = idCard.trim();
        if (trimmed.length() == 18) {
            return trimmed.substring(0, 6) + "********" + trimmed.substring(14);
        }
        if (trimmed.length() == 15) {
            return trimmed.substring(0, 6) + "******" + trimmed.substring(12);
        }
        if (trimmed.length() > 6) {
            return trimmed.substring(0, 4) + "****" + trimmed.substring(trimmed.length() - 2);
        }
        return "****************";
    }

    /** 姓名脱敏：2字留前，3字留首尾，4字及以上留首尾 */
    public static String maskRealName(String name) {
        if (name == null || name.isBlank()) return name;
        if (isMasked(name)) return name;
        String trimmed = name.trim();
        int len = trimmed.length();
        if (len <= 1) return trimmed;
        if (len == 2) {
            return trimmed.charAt(0) + "*";
        }
        if (len == 3) {
            return trimmed.charAt(0) + "*" + trimmed.charAt(2);
        }
        return trimmed.charAt(0) + "*".repeat(len - 2) + trimmed.charAt(len - 1);
    }

    /** 银行卡号脱敏：保留前4后4 */
    public static String maskBankCard(String cardNo) {
        if (cardNo == null || cardNo.isBlank()) return cardNo;
        if (isMasked(cardNo)) return cardNo;
        String trimmed = cardNo.trim().replaceAll("\\s+", "");
        if (trimmed.length() <= 8) return trimmed;
        return trimmed.substring(0, 4) + "*".repeat(trimmed.length() - 8) + trimmed.substring(trimmed.length() - 4);
    }

    /** 敏感 API 密钥 / 令牌脱敏：保留前 3 后 4 */
    public static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return apiKey;
        if (isMasked(apiKey)) return apiKey;
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 8) return "sk-****";
        if (trimmed.startsWith("sk-")) {
            return "sk-****" + trimmed.substring(trimmed.length() - 4);
        }
        return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    /** 详细地址脱敏：保留省市区前段（前8~10字），后段门牌号房号脱敏 */
    public static String maskAddress(String address) {
        if (address == null || address.isBlank()) return address;
        if (isMasked(address)) return address;
        String trimmed = address.trim();
        if (trimmed.length() <= 8) return trimmed;
        int keep = Math.min(10, trimmed.length() / 2);
        return trimmed.substring(0, keep) + "******";
    }

    /** 统一社会信用代码 / 税务登记号脱敏：保留前4后4 */
    public static String maskCreditCode(String code) {
        if (code == null || code.isBlank()) return code;
        if (isMasked(code)) return code;
        String trimmed = code.trim();
        if (trimmed.length() <= 8) return trimmed;
        return trimmed.substring(0, 4) + "**********" + trimmed.substring(trimmed.length() - 4);
    }
}
