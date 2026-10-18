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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AiApiKeyService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public AiApiKeyService(JdbcTemplate jdbc, AuditService audit) { this.jdbc = jdbc; this.audit = audit; }

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

    public boolean authorize(String raw, String permission) {
        if (raw == null || raw.length() < 20 || !allow(raw)) return false;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT permissions, expires_at AS expiresAt FROM t_ai_api_key WHERE key_hash=? AND status='active' LIMIT 1", hash(raw));
        if (rows.isEmpty()) return false;
        Object expiry = rows.get(0).get("expiresAt");
        if (expiry instanceof java.sql.Timestamp t && t.toLocalDateTime().isBefore(LocalDateTime.now())) return false;
        String permissions = String.valueOf(rows.get(0).get("permissions"));
        return permissions.contains("*") || permissions.split(",").length > 0 && List.of(permissions.split(",")).contains(permission);
    }

    private boolean allow(String key) {
        long now = System.currentTimeMillis();
        Window current = windows.computeIfAbsent(key, k -> new Window(now, new AtomicInteger()));
        if (now - current.started > 60_000) { windows.put(key, new Window(now, new AtomicInteger())); return true; }
        return current.calls.incrementAndGet() <= 60;
    }
    private String randomToken(int bytes) { byte[] b = new byte[bytes]; random.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private String hash(String value) { try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String clean(String value, int max) { if (value == null) return ""; value = value.trim(); return value.length() > max ? value.substring(0, max) : value; }
    private record Window(long started, AtomicInteger calls) {}
}
