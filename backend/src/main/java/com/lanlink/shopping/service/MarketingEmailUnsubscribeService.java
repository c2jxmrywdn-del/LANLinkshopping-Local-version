package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.UserProfile;
import com.lanlink.shopping.mapper.UserProfileMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Signed one-click unsubscribe links for marketing email.
 * Tokens contain no email address; a token is valid only while the account's
 * current email remains unchanged. No login session is required to unsubscribe.
 */
@Service
public class MarketingEmailUnsubscribeService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final UserProfileMapper userProfileMapper;
    private final UserSettingsService userSettingsService;
    private final String secret;
    private final String publicApiBaseUrl;

    public MarketingEmailUnsubscribeService(
            UserProfileMapper userProfileMapper,
            UserSettingsService userSettingsService,
            @Value("${MARKETING_UNSUBSCRIBE_SECRET:}") String secret,
            @Value("${LANLINK_PUBLIC_API_URL:https://lanlinkshopping-production.up.railway.app/api}") String publicApiBaseUrl) {
        this.userProfileMapper = userProfileMapper;
        this.userSettingsService = userSettingsService;
        this.secret = secret == null ? "" : secret.trim();
        this.publicApiBaseUrl = publicApiBaseUrl == null ? "" : publicApiBaseUrl.trim();
    }

    public boolean isConfigured() {
        if (secret.length() < 32) return false;
        try {
            canonicalUnsubscribeUrl();
            return true;
        } catch (BusinessException ex) {
            return false;
        }
    }

    public String canonicalUnsubscribeUrl() {
        try {
            String base = publicApiBaseUrl.replaceAll("/+$", "");
            URI uri = URI.create(base);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new BusinessException("营销邮件公共 API 地址必须是有效的 HTTPS 基址");
            }
            return base + "/marketing-email/unsubscribe";
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("营销邮件公共 API 地址必须是有效的 HTTPS 基址");
        }
    }

    public String buildUnsubscribeUrl(Long userId, String email) {
        if (!isConfigured()) throw new BusinessException("营销邮件退订服务尚未配置");
        if (userId == null || email == null || email.isBlank()) {
            throw new BusinessException("无法为该收件人生成退订链接");
        }
        return canonicalUnsubscribeUrl() + "?userId=" + userId + "&token=" + sign(userId, email);
    }

    public void unsubscribe(Long userId, String token) {
        if (!isConfigured() || userId == null || token == null || token.isBlank()) {
            throw new BusinessException("退订链接无效或已失效");
        }

        UserProfile profile = userProfileMapper.selectList(
                new QueryWrapper<UserProfile>()
                        .select("user_id", "email")
                        .eq("user_id", userId))
                .stream().findFirst().orElse(null);
        if (profile == null || profile.getEmail() == null || profile.getEmail().isBlank()) {
            throw new BusinessException("退订链接无效或已失效");
        }

        String expected = sign(userId, profile.getEmail());
        boolean matches = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                token.getBytes(StandardCharsets.US_ASCII));
        if (!matches) throw new BusinessException("退订链接无效或已失效");

        Map<String, Object> groups = new LinkedHashMap<>();
        groups.put("promotion", false);
        Map<String, Object> notify = new LinkedHashMap<>();
        notify.put("groups", groups);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("notify", notify);
        userSettingsService.patch(userId, patch);
    }

    private String sign(Long userId, String email) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            String payload = userId + ":" + email.trim().toLowerCase(Locale.ROOT);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException("无法生成营销邮件退订链接");
        }
    }
}
