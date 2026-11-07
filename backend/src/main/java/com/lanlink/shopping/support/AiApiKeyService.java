package com.lanlink.shopping.support;

import com.lanlink.shopping.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AiApiKeyService {

    private static final int WINDOW_MAX = 5000;

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    /** 按访问顺序淘汰，避免攻击者用大量不同 Bearer 原文制造无界限流 Map。 */
    private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
            return size() > WINDOW_MAX;
        }
    };

    public AiApiKeyService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record Principal(String keyPrefix, Set<String> permissions, Long tenantId) {
        public boolean has(String permission) {
            return permission != null && !permission.isBlank()
                    && (permissions.contains("*") || permissions.contains(permission));
        }
    }

    public Map<String, Object> create(String name, String provider, String permissions, LocalDateTime expiresAt,
                                       Long userId, HttpServletRequest request) {
        String raw = "llk_" + randomToken(32);
        String prefix = raw.substring(0, 12);
        jdbc.update("INSERT INTO t_ai_api_key(name, key_prefix, key_hash, provider, permissions, status, expires_at, created_by, created_at) VALUES(?,?,?,?,?,'active',?,?,NOW())",
                clean(name, 80), prefix, hash(raw), clean(provider, 40), clean(permissions, 500), expiresAt, userId);
        audit.record(userId, "AI_API_KEY_CREATED", "创建 AI 提供商密钥 " + prefix, request);
        return Map.of("name", clean(name, 80), "provider", clean(provider, 40), "key", raw, "keyPrefix", prefix,
                "permissions", clean(permissions, 500), "expiresAt", expiresAt == null ? "" : expiresAt.toString());
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT id, name, key_prefix AS keyPrefix, provider, permissions, status, expires_at AS expiresAt, created_by AS createdBy, created_at AS createdAt, disabled_at AS disabledAt FROM t_ai_api_key ORDER BY id DESC");
    }

    public void disable(long id, Long userId, HttpServletRequest request) {
        int changed = jdbc.update("UPDATE t_ai_api_key SET status='disabled', disabled_at=NOW() WHERE id=? AND status='active'", id);
        if (changed == 0) throw new IllegalArgumentException("密钥不存在或已禁用");
        audit.record(userId, "AI_API_KEY_DISABLED", "禁用 AI 提供商密钥 #" + id, request);
    }

    /** 单次验证 API Key，并返回 scope/租户；无效或未命中格式一律 null。 */
    public Principal authenticate(String raw) {
        if (raw == null || raw.length() < 20 || !raw.startsWith("llk_") || !allow(raw)) return null;
        Map<String, Object> row = activeKeyRow(raw);
        if (row == null) return null;
        Set<String> permissions = parsePermissions(String.valueOf(row.get("permissions")));
        Long tenantId = parseTenantId(permissions);
        String prefix = row.get("keyPrefix") == null ? raw.substring(0, Math.min(12, raw.length()))
                : String.valueOf(row.get("keyPrefix"));
        return new Principal(prefix + "****", Set.copyOf(permissions), tenantId);
    }

    public boolean isValid(String raw) {
        return authenticate(raw) != null;
    }

    public boolean hasPermission(String raw, String permission) {
        Principal principal = authenticate(raw);
        return principal != null && principal.has(permission);
    }

    public boolean authorize(String raw, String permission) {
        return hasPermission(raw, permission);
    }

    public Long tenantId(String raw) {
        Principal principal = authenticate(raw);
        return principal == null ? null : principal.tenantId();
    }

    private Map<String, Object> activeKeyRow(String raw) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT permissions, key_prefix AS keyPrefix, expires_at AS expiresAt FROM t_ai_api_key WHERE key_hash=? AND status='active' LIMIT 1",
                hash(raw));
        if (rows.isEmpty()) return null;
        Object expiry = rows.get(0).get("expiresAt");
        if (expiry instanceof java.sql.Timestamp t && t.toLocalDateTime().isBefore(LocalDateTime.now())) return null;
        return rows.get(0);
    }

    private static Set<String> parsePermissions(String permissions) {
        Set<String> out = new LinkedHashSet<>();
        if (permissions == null || permissions.isBlank()) return out;
        for (String token : permissions.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    private static Long parseTenantId(Set<String> permissions) {
        for (String token : permissions) {
            if (token.startsWith("tenant:")) {
                try {
                    return Long.valueOf(token.substring("tenant:".length()));
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private boolean allow(String key) {
        String windowKey = hash(key);
        long now = System.currentTimeMillis();
        synchronized (windows) {
            Window current = windows.get(windowKey);
            if (current == null || now - current.started > 60_000) {
                windows.put(windowKey, new Window(now, new AtomicInteger(1)));
                return true;
            }
            return current.calls.incrementAndGet() <= 60;
        }
    }

    private String randomToken(int bytes) { byte[] b = new byte[bytes]; random.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private String hash(String value) { try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String clean(String value, int max) { if (value == null) return ""; value = value.trim(); return value.length() > max ? value.substring(0, max) : value; }
    private record Window(long started, AtomicInteger calls) {}
}
