package com.lanlink.shopping.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 生产配置启动校验（仅 prod profile 生效）。
 *
 * <p>背景：application.yml 为本地开发把 profile 默认值下调为 dev、Session Cookie 的
 * secure 由 true 改为 false、same-site 由 none 改为 lax。这些默认值只适合本地。
 * 若误用默认值部署到生产，会导致会话 Cookie 明文可跨站携带（CSRF/劫持风险）。</p>
 *
 * <p>application-prod.yml 已把 Cookie 默认值改回 fail-secure（secure=true / same-site=none）。
 * 本守卫在此基础上做启动期兜底：一旦在 prod 下检测到 Cookie 被显式下调为不安全，立即快速失败，
 * 而不是让不安全配置静默上线。</p>
 */
@Component
@org.springframework.context.annotation.Profile("prod")
public class ProdConfigGuard {

    private static final Logger log = LoggerFactory.getLogger(ProdConfigGuard.class);

    private final boolean cookieSecure;
    private final String sameSite;
    private final String[] activeProfiles;

    public ProdConfigGuard(
            @Value("${server.servlet.session.cookie.secure:true}") boolean cookieSecure,
            @Value("${server.servlet.session.cookie.same-site:none}") String sameSite,
            Environment env) {
        this.cookieSecure = cookieSecure;
        this.sameSite = sameSite;
        this.activeProfiles = env.getActiveProfiles();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        log.info("[prod-guard] 激活 profile={}，session cookie secure={}, same-site={}",
                Arrays.toString(activeProfiles), cookieSecure, sameSite);

        if (!cookieSecure) {
            throw new IllegalStateException(
                    "生产环境 Session Cookie 的 secure=false，会导致 Cookie 明文跨站传输。"
                            + "请通过 HTTPS 部署并移除 SESSION_COOKIE_SECURE=false 覆盖（application-prod.yml 默认已为 true）。");
        }
        // SameSite=None 必须同时 Secure，否则现代浏览器会直接丢弃该 Cookie —— 这里 secure 已保证为 true。
        if ("none".equalsIgnoreCase(sameSite) && !cookieSecure) {
            throw new IllegalStateException("SameSite=None 必须与 Secure=true 同时启用。");
        }
    }
}
