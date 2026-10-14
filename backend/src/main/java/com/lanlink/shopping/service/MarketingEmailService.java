package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.dto.MarketingEmailCampaignDTO;
import com.lanlink.shopping.entity.UserProfile;
import com.lanlink.shopping.mapper.UserProfileMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Admin-triggered promotional email workflow routed through Agent Loadout.
 * Sends only to registered users whose email and promotion notifications are
 * enabled in their account preferences. Each recipient is sent separately.
 */
@Service
public class MarketingEmailService {
    private static final int MAX_RECIPIENTS = 20;

    private final AgentLoadoutMcpClient agentLoadoutMcpClient;
    private final UserProfileMapper userProfileMapper;
    private final UserSettingsService userSettingsService;
    private final MarketingEmailUnsubscribeService unsubscribeService;

    public MarketingEmailService(AgentLoadoutMcpClient agentLoadoutMcpClient,
                                 UserProfileMapper userProfileMapper,
                                 UserSettingsService userSettingsService,
                                 MarketingEmailUnsubscribeService unsubscribeService) {
        this.agentLoadoutMcpClient = agentLoadoutMcpClient;
        this.userProfileMapper = userProfileMapper;
        this.userSettingsService = userSettingsService;
        this.unsubscribeService = unsubscribeService;
    }

    public Map<String, Object> sendCampaign(MarketingEmailCampaignDTO campaign) {
        if (campaign == null || !campaign.isConsentConfirmed()) {
            throw new BusinessException("仅可向已明确订阅营销邮件的收件人发送");
        }
        if (!agentLoadoutMcpClient.isConfigured()) {
            throw new BusinessException("Agent Loadout 邮件通道尚未配置");
        }
        if (campaign.getRecipients() == null || campaign.getRecipients().isEmpty()
                || campaign.getRecipients().size() > MAX_RECIPIENTS) {
            throw new BusinessException("单次营销邮件需包含 1–20 位已订阅收件人");
        }
        validateUnsubscribeUrl(campaign.getUnsubscribeUrl());
        if (!unsubscribeService.isConfigured()
                || !unsubscribeService.canonicalUnsubscribeUrl().equals(campaign.getUnsubscribeUrl().trim())) {
            throw new BusinessException("请使用系统配置的真实退订地址，并先配置退订签名密钥");
        }

        Set<String> distinctRecipients = new HashSet<>();
        for (String recipient : campaign.getRecipients()) {
            if (recipient == null || recipient.isBlank()
                    || !distinctRecipients.add(recipient.trim().toLowerCase(Locale.ROOT))) {
                throw new BusinessException("收件人不能为空，且同一批次不能重复出现");
            }
        }

        Map<String, UserProfile> profilesByEmail = loadProfilesByEmail();
        String subject = normalizeSubject(campaign.getSubject());
        String body = campaign.getText().trim()
                + "\n\n---\n这是一封 LANLinkshopping 商业推广邮件。"
                + "\n如不希望继续接收此类邮件，请通过以下链接退订：\n"
                + campaign.getUnsubscribeUrl().trim();

        int queued = 0;
        int skippedNoConsent = 0;
        int failed = 0;
        List<Map<String, Object>> outcomes = new ArrayList<>();
        int index = 0;
        for (String recipient : campaign.getRecipients()) {
            index++;
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("recipientIndex", index);
            UserProfile profile = profilesByEmail.get(recipient.trim().toLowerCase(Locale.ROOT));
            if (profile == null || !hasMarketingConsent(profile.getUserId())) {
                skippedNoConsent++;
                outcome.put("status", "skipped_no_consent");
                outcomes.add(outcome);
                continue;
            }
            try {
                agentLoadoutMcpClient.sendMessage(
                        recipient.trim(),
                        subject,
                        body.replace(campaign.getUnsubscribeUrl().trim(),
                                unsubscribeService.buildUnsubscribeUrl(profile.getUserId(), recipient)),
                        null,
                        idempotencyKey(campaign.getIdempotencyKey(), recipient));
                queued++;
                outcome.put("status", "queued");
            } catch (RuntimeException ex) {
                // Never return recipient addresses, message content, or credentials in campaign results.
                failed++;
                outcome.put("status", "failed");
                outcome.put("reason", "MAIL_PROVIDER_ERROR");
            }
            outcomes.add(outcome);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requested", campaign.getRecipients().size());
        result.put("queued", queued);
        result.put("skippedNoConsent", skippedNoConsent);
        result.put("failed", failed);
        result.put("results", outcomes);
        result.put("message", failed == 0
                ? "营销邮件处理完成；仅向已启用营销邮件订阅的账号提交"
                : "部分邮件未能提交，请检查邮件服务配置后重试失败项");
        return result;
    }

    private Map<String, UserProfile> loadProfilesByEmail() {
        List<UserProfile> profiles = userProfileMapper.selectList(
                new QueryWrapper<UserProfile>()
                        .select("user_id", "email")
                        .isNotNull("email"));
        Map<String, UserProfile> indexed = new LinkedHashMap<>();
        for (UserProfile profile : profiles) {
            if (profile.getEmail() != null && !profile.getEmail().isBlank()) {
                indexed.putIfAbsent(profile.getEmail().trim().toLowerCase(Locale.ROOT), profile);
            }
        }
        return indexed;
    }

    private boolean hasMarketingConsent(Long userId) {
        if (userId == null) return false;
        Map<String, Object> settings = userSettingsService.get(userId);
        Map<String, Object> notify = asMap(settings.get("notify"));
        Map<String, Object> groups = asMap(notify.get("groups"));
        return Boolean.TRUE.equals(notify.get("email"))
                && Boolean.TRUE.equals(groups.get("promotion"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static String normalizeSubject(String subject) {
        String value = subject == null ? "" : subject.replace('\r', ' ').replace('\n', ' ').trim();
        if (value.isBlank()) throw new BusinessException("邮件主题不能为空");
        if (value.startsWith("【广告】") || value.startsWith("【商业推广】")
                || value.toLowerCase(Locale.ROOT).startsWith("[advertisement]")) {
            return value;
        }
        return "【商业推广】" + value;
    }

    private static void validateUnsubscribeUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new BusinessException("请提供可用的 HTTPS 退订地址");
            }
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("请提供可用的 HTTPS 退订地址");
        }
    }

    private static String idempotencyKey(String campaignKey, String recipient) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String source = campaignKey.trim() + "|" + recipient.trim().toLowerCase(Locale.ROOT);
            byte[] bytes = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }
}
