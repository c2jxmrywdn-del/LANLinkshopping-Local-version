package com.lanlink.shopping.social;

/**
 * 归一化后的第三方身份。
 *
 * @param provider      渠道标识（wechat/alipay/apple/google）
 * @param subject       渠道内唯一标识（微信 openid / 支付宝 user_id / Apple+Google sub）
 * @param unionId       微信开放平台 unionid（同一开放平台下多应用统一标识，可能为空）
 * @param nickname      昵称
 * @param avatar        头像地址
 * @param email         邮箱（Apple/Google 提供；微信/支付宝通常为空）
 * @param emailVerified 邮箱是否由平台验证（决定能否用于自动关联本地账号）
 */
public record SocialIdentity(
        String provider,
        String subject,
        String unionId,
        String nickname,
        String avatar,
        String email,
        boolean emailVerified
) {
    /** 展示名：昵称 → 邮箱前缀 → 平台+标识后 6 位 */
    public String displayName() {
        if (nickname != null && !nickname.isBlank()) return nickname;
        if (email != null && email.contains("@")) return email.substring(0, email.indexOf('@'));
        return provider + "-" + (subject == null ? "" : subject.substring(Math.max(0, subject.length() - 6)));
    }
}
