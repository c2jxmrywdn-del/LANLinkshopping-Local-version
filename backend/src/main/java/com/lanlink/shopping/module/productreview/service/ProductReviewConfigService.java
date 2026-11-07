package com.lanlink.shopping.module.productreview.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 商品自动审核配置：开关与阈值存 t_product_review_config，缺表/异常时回落到安全默认值。
 * 默认策略：自动审核开启、AI 与视觉开启、自动通过阈值 85、每日 AI 预算 300。
 */
@Service
public class ProductReviewConfigService {

    private static final Logger log = LoggerFactory.getLogger(ProductReviewConfigService.class);

    public static final String KEY_AUTO_ENABLED = "auto_enabled";
    public static final String KEY_AI_ENABLED = "ai_enabled";
    public static final String KEY_VISION_ENABLED = "vision_enabled";
    public static final String KEY_AUTO_SCORE = "auto_approve_score";
    public static final String KEY_DAILY_BUDGET = "daily_ai_budget";

    private static final Set<String> EDITABLE = Set.of(
            KEY_AUTO_ENABLED, KEY_AI_ENABLED, KEY_VISION_ENABLED, KEY_AUTO_SCORE, KEY_DAILY_BUDGET);

    private final JdbcTemplate jdbcTemplate;

    public ProductReviewConfigService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean autoEnabled() {
        return bool(KEY_AUTO_ENABLED, true);
    }

    public boolean aiEnabled() {
        return bool(KEY_AI_ENABLED, true);
    }

    public boolean visionEnabled() {
        return bool(KEY_VISION_ENABLED, true);
    }

    public int autoApproveScore() {
        return integer(KEY_AUTO_SCORE, 85, 60, 100);
    }

    public int dailyAiBudget() {
        return integer(KEY_DAILY_BUDGET, 300, 0, 5000);
    }

    public Map<String, Object> view() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(KEY_AUTO_ENABLED, autoEnabled());
        out.put(KEY_AI_ENABLED, aiEnabled());
        out.put(KEY_VISION_ENABLED, visionEnabled());
        out.put(KEY_AUTO_SCORE, autoApproveScore());
        out.put(KEY_DAILY_BUDGET, dailyAiBudget());
        return out;
    }

    /** 管理端更新：仅允许白名单键，布尔值按 true/false 落库，阈值做边界收敛。 */
    public Map<String, Object> update(Map<String, Object> body) {
        if (body != null) {
            for (Map.Entry<String, Object> e : body.entrySet()) {
                String key = e.getKey();
                if (!EDITABLE.contains(key)) continue;
                Object raw = e.getValue();
                String value;
                if (KEY_AUTO_SCORE.equals(key)) {
                    value = String.valueOf(clamp(toInt(raw, autoApproveScore()), 60, 100));
                } else if (KEY_DAILY_BUDGET.equals(key)) {
                    value = String.valueOf(clamp(toInt(raw, dailyAiBudget()), 0, 5000));
                } else {
                    value = Boolean.toString(toBool(raw, true));
                }
                upsert(key, value);
            }
        }
        return view();
    }

    private boolean bool(String key, boolean def) {
        String v = raw(key);
        if (v == null || v.isBlank()) return def;
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }

    private int integer(String key, int def, int min, int max) {
        String v = raw(key);
        if (v == null || v.isBlank()) return def;
        try {
            return clamp(Integer.parseInt(v.trim()), min, max);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private String raw(String key) {
        try {
            return jdbcTemplate.query("SELECT config_value FROM t_product_review_config WHERE config_key = ?",
                    rs -> rs.next() ? rs.getString(1) : null, key);
        } catch (Exception e) {
            log.debug("[product-review-config] read {} failed: {}", key, e.getMessage());
            return null;
        }
    }

    private void upsert(String key, String value) {
        int updated = jdbcTemplate.update(
                "UPDATE t_product_review_config SET config_value = ? WHERE config_key = ?", value, key);
        if (updated == 0) {
            try {
                jdbcTemplate.update("INSERT INTO t_product_review_config (config_key, config_value) VALUES (?, ?)",
                        key, value);
            } catch (Exception ignore) {
                jdbcTemplate.update("UPDATE t_product_review_config SET config_value = ? WHERE config_key = ?",
                        value, key);
            }
        }
    }

    private static int toInt(Object raw, int def) {
        try {
            return raw == null ? def : Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean toBool(Object raw, boolean def) {
        if (raw == null) return def;
        if (raw instanceof Boolean b) return b;
        String s = String.valueOf(raw).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
