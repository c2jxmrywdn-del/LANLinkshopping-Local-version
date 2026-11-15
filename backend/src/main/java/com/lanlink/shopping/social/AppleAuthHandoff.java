package com.lanlink.shopping.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.config.SocialLoginProperties;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Sign in with Apple 的 form_post 中转。
 *
 * <p>请求了 name、email 时，Apple 只接受 response_mode=form_post，并且这是从
 * appleid.apple.com 发来的跨站 POST。SameSite=Lax 的登录会话不会跟过来，
 * 所以 state / nonce 先放在服务端短时缓存里：Apple 把 code 和一次性姓名 POST 到
 * 后端，这里再 302 回前端登录页，由前端带着原来的会话完成换票。
 */
@Component
public class AppleAuthHandoff {

    static final long TTL_MS = 10 * 60_000L;
    private static final int MAX_PENDING = 10_000;
    private static final int MAX_CODE_LENGTH = 2048;
    private static final int MAX_USER_JSON = 4096;
    private static final int MAX_NAME_LENGTH = 64;
    private static final Pattern STATE = Pattern.compile("^[A-Za-z0-9]{16,128}$");
    private static final Pattern ERROR = Pattern.compile("^[a-z0-9_]{1,64}$");

    private final SocialLoginProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    public AppleAuthHandoff(SocialLoginProperties props) {
        this.props = props;
    }

    /** 发起授权时记下一次性 nonce。只接受本服务生成的 state。 */
    public void remember(String state, String nonce) {
        if (!validState(state) || nonce == null || !STATE.matcher(nonce).matches()) {
            throw new BusinessException("授权状态校验失败，请重新发起授权");
        }
        evictExpired();
        if (pending.size() >= MAX_PENDING) {
            throw new BusinessException("Apple 登录排队过多，请稍后再试");
        }
        pending.put(state, new Pending(nonce, System.currentTimeMillis()));
    }

    /**
     * 消费并取回 nonce 与首次授权时的姓名。过期、未知或已用过都返回 null。
     */
    public Claim consume(String state) {
        if (!validState(state)) return null;
        Pending found = pending.remove(state);
        if (found == null || found.expired(System.currentTimeMillis())) return null;
        return new Claim(found.nonce, found.displayName);
    }

    /**
     * Apple 的 form_post 落点：校验 state 后把浏览器送回前端登录页。
     * 姓名留在服务端，不放进回跳 URL。
     */
    public String relay(String code, String state, String userJson, String error) {
        if (error != null && !error.isBlank()) {
            return loginUrl(Map.of("error", sanitizeError(error)));
        }
        if (!validState(state)) return loginUrl(Map.of("error", "apple_state"));
        Pending found = pending.get(state);
        if (found == null || found.expired(System.currentTimeMillis())) {
            return loginUrl(Map.of("error", "apple_state"));
        }
        found.attachName(displayName(userJson));
        if (code == null || code.isBlank() || code.length() > MAX_CODE_LENGTH) {
            return loginUrl(Map.of("error", "apple_code"));
        }
        return loginUrl(Map.of("code", code, "state", state));
    }

    private String displayName(String userJson) {
        if (userJson == null || userJson.isBlank() || userJson.length() > MAX_USER_JSON) return null;
        try {
            JsonNode root = mapper.readTree(userJson);
            JsonNode name = root.path("name");
            String first = name.path("firstName").asText("");
            String last = name.path("lastName").asText("");
            String joined = (first + " " + last).trim().replaceAll("\\s+", " ");
            joined = joined.replaceAll("\\p{Cntrl}", "");
            if (joined.isEmpty()) return null;
            return joined.length() > MAX_NAME_LENGTH ? joined.substring(0, MAX_NAME_LENGTH) : joined;
        } catch (Exception ex) {
            return null;
        }
    }

    private String loginUrl(Map<String, String> params) {
        String base = props.getRedirectBase() == null ? "" : props.getRedirectBase().trim();
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            throw new BusinessException("社交登录回跳地址配置非法");
        }
        StringBuilder sb = new StringBuilder(base);
        sb.append(base.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return sb.toString();
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(entry -> entry.getValue().expired(now));
    }

    private static boolean validState(String state) {
        return state != null && STATE.matcher(state).matches();
    }

    private static String sanitizeError(String error) {
        String trimmed = error.trim().toLowerCase();
        return ERROR.matcher(trimmed).matches() ? trimmed : "apple_denied";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    public record Claim(String nonce, String displayName) {}

    private static final class Pending {
        private final String nonce;
        private final long createdAt;
        private volatile String displayName;
        private volatile boolean nameAttached;

        private Pending(String nonce, long createdAt) {
            this.nonce = nonce;
            this.createdAt = createdAt;
        }

        private boolean expired(long now) {
            return now - createdAt > TTL_MS;
        }

        /** 第一次带回的姓名生效，后面的覆盖请求忽略。 */
        private synchronized void attachName(String name) {
            if (nameAttached) return;
            displayName = name;
            nameAttached = true;
        }
    }
}
