package com.lanlink.shopping.module.ab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A/B 实验平台（最小版）。
 *
 * <p>分流：FNV-1a 哈希(expKey + bucketId) % 100 &lt; traffic_pct 进实验；
 * 变体 = variants[hash(expKey + bucketId + "#v") % variants.length]——同桶恒定同变体，纯内存可重算，
 * DB 故障时降级返回 null（默认分支），不阻塞业务。
 * 指标：expose/convert 落 t_ab_log；统计在 AbStatService 做两比例 z 检验 / Welch t 检验。
 * 非个性化场景（如广告排序实验）不依赖 analytics 授权——分流与 Cookie 授权体系解耦。
 */
@Service
public class AbTestService {

    private static final Logger log = LoggerFactory.getLogger(AbTestService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 实验定义 30s 缓存：分流是读多写少的热路径，避免每次查库 */
    private final Map<String, CachedExp> cache = new ConcurrentHashMap<>();
    private static final long CACHE_MS = 30_000L;

    public AbTestService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record CachedExp(String expKey, int trafficPct, List<String> variants, long loadedAt) {}

    /** 分流结果：null = 不在实验（回默认分支） */
    public record Assignment(String expKey, String variant) {}

    /**
     * 分流。bucketId 优先 userId（稳定），匿名用 sessionId（跨请求不稳定，结果标注为估算）。
     */
    public Assignment assign(String expKey, Long userId, String sessionId) {
        CachedExp exp = loadExperiment(expKey);
        if (exp == null) return null;
        String bucketId = userId != null ? "u" + userId
                : (sessionId != null && !sessionId.isBlank() ? "s" + sessionId : null);
        if (bucketId == null) return null;
        int bucket = Math.floorMod(fnv1a32(expKey + bucketId), 100);
        if (bucket >= exp.trafficPct()) return null;
        int idx = Math.floorMod(fnv1a32(expKey + bucketId + "#v"), exp.variants().size());
        return new Assignment(expKey, exp.variants().get(idx));
    }

    /** 记录曝光（同用户同变体同日去重，防日志膨胀） */
    public void expose(String expKey, String variant, Long userId, String sessionId) {
        insertLog(expKey, variant, userId, sessionId, "expose", null,
                " AND DATE(create_time)=CURDATE() ");
    }

    /** 记录转化（value=金额，gmv 指标用；ctr/cvr 指标 value 传 null） */
    public void convert(String expKey, String variant, Long userId, String sessionId, java.math.BigDecimal value) {
        insertLog(expKey, variant, userId, sessionId, "convert", value, "");
    }

    private void insertLog(String expKey, String variant, Long userId, String sessionId,
                           String eventType, java.math.BigDecimal value, String dedupSuffix) {
        try {
            if (userId != null) {
                int dup = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_ab_log WHERE exp_key=? AND variant=? AND user_id=? AND event_type=?" + dedupSuffix,
                        Integer.class, expKey, variant, userId, eventType);
                if (dup > 0) return;
            }
            jdbc.update("INSERT INTO t_ab_log(exp_key, variant, user_id, session_id, event_type, value) VALUES(?,?,?,?,?,?)",
                    expKey, variant, userId, sessionId, eventType, value);
        } catch (Exception e) {
            log.warn("[ab] log failed (ignored): exp={} err={}", expKey, e.getMessage());
        }
    }

    private CachedExp loadExperiment(String expKey) {
        CachedExp hit = cache.get(expKey);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.loadedAt() < CACHE_MS) {
            return hit; // 非运行态以 trafficPct=0 缓存，assign 自然返回 null
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT exp_key, status, traffic_pct, variants_json FROM t_ab_experiment WHERE exp_key=?", expKey);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        if (!"running".equals(String.valueOf(r.get("status")))) {
            cache.put(expKey, new CachedExp(expKey, 0, List.of(), now)); // 非运行态：0 流量缓存，免反复查库
            return null;
        }
        List<String> variants = new ArrayList<>();
        try {
            JsonNode arr = objectMapper.readTree(String.valueOf(r.get("variants_json")));
            arr.forEach(n -> variants.add(n.asText()));
        } catch (Exception e) {
            throw new BusinessException("实验变体配置非法");
        }
        if (variants.isEmpty()) return null;
        int pct = ((Number) r.get("traffic_pct")).intValue();
        CachedExp exp = new CachedExp(expKey, Math.min(Math.max(pct, 0), 100), variants, now);
        cache.put(expKey, exp);
        return exp;
    }

    /** FNV-1a 32 位：分布均匀、无依赖、可重算 */
    static int fnv1a32(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return h;
    }
}
