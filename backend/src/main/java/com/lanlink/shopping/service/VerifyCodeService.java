package com.lanlink.shopping.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lanlink.shopping.common.BusinessException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 验证码服务：内存存储，5 分钟过期 + 60 秒冷却 + 单次使用。
 * 邮件通道支持 Agent Loadout MCP、HTTPS 邮件 API（Resend）和 SMTP。
 * 生产环境缺少完整配置时拒绝发送且不回显验证码。
 */
@Service
public class VerifyCodeService {

    private static final Logger log = LoggerFactory.getLogger(VerifyCodeService.class);
    private static final long TTL_MS = 5 * 60 * 1000L;
    private static final long COOLDOWN_MS = 60 * 1000L;
    private static final String MAIL_SUBJECT = "【LANLink 商城】邮箱验证码";
    private static final String DEFAULT_RESEND_API_URL = "https://api.resend.com/emails";
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("auto", "smtp", "resend", "agent-loadout");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static class CodeBox {
        String code;
        long exp;
        long lastSendAt;
    }

    private final Map<String, CodeBox> store = new ConcurrentHashMap<>();
    private final JavaMailSender mailSender;
    private final String mailHost;
    private final String mailUsername;
    private final String mailFrom;
    private final boolean smtpConfigured;
    private final String mailProvider;
    private final String resendApiKey;
    private final URI resendApiUri;
    private final boolean demoMode;
    private final AgentLoadoutMcpClient agentLoadoutMcpClient;

    @Autowired
    public VerifyCodeService(
            @Autowired(required = false) JavaMailSender mailSender,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${spring.mail.username:}") String mailUsername,
            @Value("${MAIL_FROM:}") String configuredMailFrom,
            @Value("${spring.mail.password:}") String mailPassword,
            @Value("${MAIL_PROVIDER:auto}") String mailProvider,
            @Value("${RESEND_API_KEY:}") String resendApiKey,
            @Value("${lanlink.mail.resend.api-url:https://api.resend.com/emails}") String resendApiUrl,
            @Value("${lanlink.verify-code.demo-mode:false}") boolean demoMode,
            AgentLoadoutMcpClient agentLoadoutMcpClient) {
        this.mailSender = mailSender;
        this.mailHost = normalize(mailHost);
        this.mailUsername = normalize(mailUsername);
        String explicitFrom = normalize(configuredMailFrom);
        this.mailFrom = explicitFrom.isBlank() ? this.mailUsername : explicitFrom;
        this.smtpConfigured = mailSender != null
                && !this.mailHost.isBlank()
                && !this.mailUsername.isBlank()
                && mailPassword != null
                && !mailPassword.isBlank()
                && !this.mailFrom.isBlank();

        String provider = normalize(mailProvider).toLowerCase(Locale.ROOT);
        if (provider.isBlank()) {
            provider = "auto";
        }
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("MAIL_PROVIDER must be auto, smtp, resend, or agent-loadout");
        }
        this.mailProvider = provider;
        this.resendApiKey = normalize(resendApiKey);
        this.resendApiUri = validateResendEndpoint(resendApiUrl);
        this.demoMode = demoMode;
        this.agentLoadoutMcpClient = agentLoadoutMcpClient;
    }

    /** Compatibility constructor for existing unit tests and direct instantiation. */
    VerifyCodeService(JavaMailSender mailSender, String mailHost, String mailUsername,
                      String configuredMailFrom, String mailPassword, String mailProvider,
                      String resendApiKey, String resendApiUrl, boolean demoMode) {
        this(mailSender, mailHost, mailUsername, configuredMailFrom, mailPassword, mailProvider,
                resendApiKey, resendApiUrl, demoMode, null);
    }

    /** Compatibility constructor for unit tests and simple direct instantiation. */
    VerifyCodeService(JavaMailSender mailSender, String mailHost, String mailUsername,
                      String mailPassword, boolean demoMode) {
        this(mailSender, mailHost, mailUsername, "", mailPassword, "auto", "",
                DEFAULT_RESEND_API_URL, demoMode);
    }

    /**
     * 发送验证码。开发演示回显必须显式开启 demo-mode。
     * 已配置真实通道发生发送错误时，不会回退为演示验证码。
     */
    public String send(Long userId, String type, String target) {
        if (target == null || target.isBlank()) {
            throw new BusinessException("验证码接收方不能为空");
        }
        if (!"email".equals(type) && !"phone".equals(type)) {
            throw new BusinessException("不支持的验证码类型");
        }

        String key = key(userId, type, target);
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(entry -> entry.getValue().exp < now);

        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        CodeBox nb = new CodeBox();
        nb.code = code;
        nb.exp = now + TTL_MS;
        nb.lastSendAt = now;

        store.compute(key, (k, current) -> {
            if (current != null && now - current.lastSendAt < COOLDOWN_MS
                    && current.exp >= now) {
                throw new BusinessException("验证码发送过于频繁，请稍后再试");
            }
            return nb;
        });

        if ("email".equals(type)) {
            try {
                if (sendEmailCode(target, code)) {
                    return null; // 已真实发送，不回显验证码
                }
                if (demoMode) {
                    log.warn("邮件通道未完整配置，当前为开发验证码演示模式");
                    return code;
                }
                store.remove(key, nb);
                throw new BusinessException("邮箱验证码服务暂未配置");
            } catch (BusinessException ex) {
                store.remove(key, nb);
                throw ex;
            }
        }

        if (!demoMode) {
            store.remove(key, nb);
            throw new BusinessException("短信验证码服务暂未配置");
        }
        return code; // 仅开发演示模式回显
    }

    /** 校验验证码；成功后立即失效（单次使用） */
    public void verify(Long userId, String type, String target, String code) {
        String key = key(userId, type, target);
        CodeBox box = store.get(key);
        if (box == null) {
            throw new BusinessException("验证码已过期，请重新获取");
        }
        if (box.exp < System.currentTimeMillis()) {
            store.remove(key, box);
            throw new BusinessException("验证码已过期，请重新获取");
        }
        if (!box.code.equals(code)) {
            throw new BusinessException("验证码错误");
        }
        if (!store.remove(key, box)) {
            throw new BusinessException("验证码已使用，请重新获取");
        }
    }

    /** 根据配置选用 Agent Loadout、HTTPS 邮件 API 或 SMTP；返回 false 只代表必要凭据不完整。 */
    private boolean sendEmailCode(String to, String code) {
        String provider = resolveEmailProvider();
        if (provider == null) {
            return false;
        }
        if ("agent-loadout".equals(provider)) {
            if (agentLoadoutMcpClient == null) {
                throw new BusinessException("Agent Loadout 邮件通道尚未配置");
            }
            String text = "LANLink 商城\n\n您的邮箱验证码为：" + code
                    + "\n验证码 5 分钟内有效，请勿泄露给他人。若非本人操作，请忽略此邮件。";
            agentLoadoutMcpClient.sendMessage(to, MAIL_SUBJECT, text, buildHtmlBody(code), java.util.UUID.randomUUID().toString());
            log.info("验证码邮件发送成功，provider=agent-loadout");
            return true;
        }
        if ("resend".equals(provider)) {
            sendViaResend(to, code);
            log.info("验证码邮件发送成功，provider=resend");
            return true;
        }
        sendViaSmtp(to, code);
        log.info("验证码邮件发送成功，provider=smtp");
        return true;
    }

    private String resolveEmailProvider() {
        if ("agent-loadout".equals(mailProvider)) {
            return agentLoadoutMcpClient != null && agentLoadoutMcpClient.isConfigured() ? "agent-loadout" : null;
        }
        if ("resend".equals(mailProvider)) {
            return isResendConfigured() ? "resend" : null;
        }
        if ("smtp".equals(mailProvider)) {
            return smtpConfigured ? "smtp" : null;
        }
        // Agent Loadout was explicitly requested as the preferred provider when configured.
        if (agentLoadoutMcpClient != null && agentLoadoutMcpClient.isConfigured()) {
            return "agent-loadout";
        }
        // Railway 若限制 SMTP 出站，优先使用 HTTPS 邮件 API。
        if (isResendConfigured()) {
            return "resend";
        }
        return smtpConfigured ? "smtp" : null;
    }

    private boolean isResendConfigured() {
        return !resendApiKey.isBlank() && !mailFrom.isBlank();
    }

    private void sendViaSmtp(String to, String code) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(mailFrom);
            helper.setTo(to);
            helper.setSubject(MAIL_SUBJECT);
            helper.setText(buildHtmlBody(code), true);
            mailSender.send(msg);
        } catch (jakarta.mail.MessagingException | org.springframework.mail.MailException ex) {
            log.error("验证码邮件 SMTP 发送失败，host={}", mailHost, ex);
            throw new BusinessException("邮件发送失败，请检查 SMTP 配置或网络权限后重试");
        }
    }

    private void sendViaResend(String to, String code) {
        try {
            ObjectNode payload = JSON.createObjectNode();
            payload.put("from", mailFrom);
            payload.putArray("to").add(to);
            payload.put("subject", MAIL_SUBJECT);
            payload.put("html", buildHtmlBody(code));

            HttpRequest request = HttpRequest.newBuilder(resendApiUri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + resendApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            JSON.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("邮件 HTTPS API 返回失败状态，provider=resend status={}", response.statusCode());
                throw new BusinessException("邮件发送失败，请检查 API 密钥、发件地址及邮件服务配额");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("邮件发送被中断，请稍后重试");
        } catch (IOException | RuntimeException ex) {
            // 不记录请求头、API 密钥、收件人或验证码正文。
            log.error("验证码邮件 HTTPS API 请求失败，provider=resend");
            throw new BusinessException("邮件服务暂时不可用，请稍后重试");
        }
    }

    private static URI validateResendEndpoint(String endpoint) {
        String value = normalize(endpoint);
        URI uri;
        try {
            uri = URI.create(value.isBlank() ? DEFAULT_RESEND_API_URL : value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid Resend API URL", ex);
        }

        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && isLoopbackAddress(uri.getHost());
        if (uri.getHost() == null || (!https && !loopbackHttp)) {
            throw new IllegalArgumentException(
                    "Resend API URL must use HTTPS (HTTP is allowed only for loopback tests)");
        }
        return uri;
    }

    private static boolean isLoopbackAddress(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException ex) {
            return false;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String buildHtmlBody(String code) {
        return "<div style=\"font-family:'Microsoft YaHei',Arial,sans-serif;max-width:520px;margin:0 auto;"
                + "padding:28px;border:1px solid #e5e7eb;border-radius:12px\">"
                + "<h2 style=\"margin:0 0 16px;color:#1f3864\">LANLink 商城</h2>"
                + "<p style=\"color:#333;font-size:14px\">您的邮箱验证码为：</p>"
                + "<p style=\"font-size:32px;font-weight:700;letter-spacing:6px;color:#1e6eb8;margin:8px 0 20px\">"
                + code + "</p>"
                + "<p style=\"color:#666;font-size:13px\">验证码 5 分钟内有效，请勿泄露给他人。"
                + "若非本人操作，请忽略此邮件。</p>"
                + "</div>";
    }

    private String key(Long userId, String type, String target) {
        return userId + ":" + type + ":" + target;
    }
}
