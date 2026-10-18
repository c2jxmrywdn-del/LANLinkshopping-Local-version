package com.lanlink.shopping.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.config.LocaleHintProperties;
import com.lanlink.shopping.social.SocialHttp;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语言自动识别的服务端地理提示：客户端 IP → 国家/地区 → 建议语言。
 *
 * 隐私最小化：
 *  - 只输出国家/地区码（用于语言判定），不落库、不记录明文 IP；
 *  - 结果按 IP 在内存中缓存（含 TTL），同一 IP 不重复外发；
 *  - 内网/回环地址直接跳过，不发起任何外部请求；
 *  - 查询失败或有界超时一律静默降级（返回 source=unavailable），不影响登录与页面加载。
 */
@Service
public class LocaleHintService {

    /** 中文圈国家/地区（与前端 localeDetect 的口径保持一致） */
    private static final Set<String> ZH_COUNTRIES = Set.of("CN", "HK", "TW", "MO");
    private static final long CACHE_TTL_MS = 6 * 3600_000L;
    private static final int CACHE_MAX = 512;

    private final LocaleHintProperties props;
    private final SocialHttp http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public LocaleHintService(LocaleHintProperties props, SocialHttp http) {
        this.props = props;
        this.http = http;
    }

    private record CacheEntry(String country, long at) {}

    public Map<String, Object> hint(String clientIp) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("country", null);
        out.put("locale", null);

        if (!props.getGeo().isEnabled()) {
            out.put("source", "disabled");
            return out;
        }
        if (clientIp == null || isNonRoutable(clientIp)) {
            out.put("source", "private");
            return out;
        }

        String country = fromCache(clientIp);
        if (country == null) {
            country = lookup(clientIp);
            if (country == null) {
                out.put("source", "unavailable");
                return out;
            }
            putCache(clientIp, country);
        }
        out.put("country", country);
        out.put("locale", localeOf(country));
        out.put("source", "geo");
        return out;
    }

    /** 国家/地区码 → 建议语言（中文圈 zh-CN，其余 en-US） */
    public static String localeOf(String country) {
        if (country == null) return null;
        String c = country.trim().toUpperCase();
        if (!c.matches("[A-Z]{2}")) return null;
        return ZH_COUNTRIES.contains(c) ? "zh-CN" : "en-US";
    }

    /**
     * 解析客户端真实 IP：优先取 X-Forwarded-For 首个非 unknown 项（代理场景），
     * 其次 X-Real-IP，最后 remoteAddr。
     */
    public static String clientIp(String forwardedFor, String realIp, String remoteAddr) {
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            for (String part : forwardedFor.split(",")) {
                String candidate = part.trim();
                if (!candidate.isEmpty() && !"unknown".equalsIgnoreCase(candidate)) return candidate;
            }
        }
        if (realIp != null && !realIp.isBlank() && !"unknown".equalsIgnoreCase(realIp.trim())) {
            return realIp.trim();
        }
        return remoteAddr;
    }

    /** 内网/回环/链路本地地址：无需也无法做归属地查询 */
    public static boolean isNonRoutable(String ip) {
        if (ip == null || ip.isBlank()) return true;
        String v = ip.trim();
        if ("::1".equals(v) || "0:0:0:0:0:0:0:1".equals(v)) return true;
        if (v.startsWith("127.") || v.startsWith("10.") || v.startsWith("192.168.")) return true;
        if (v.startsWith("169.254.") || v.startsWith("fc") || v.startsWith("fd") || v.startsWith("fe80:")) return true;
        if (v.startsWith("172.")) {
            String[] seg = v.split("\\.");
            if (seg.length > 1) {
                try {
                    int second = Integer.parseInt(seg[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        return false;
    }

    private String lookup(String ip) {
        String url = props.getGeo().getEndpoint().replace("{ip}", ip);
        try {
            String body = http.get(url, null, props.getGeo().getTimeoutMs());
            return extractCountry(body);
        } catch (Exception e) {
            // 网络不可达/超时/限流：静默降级，仅依赖离线信号
            return null;
        }
    }

    /** 兼容多家免费服务的字段差异：country_code / countryCode / country */
    static String extractCountry(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = new ObjectMapper().readTree(json);
            for (String field : new String[]{"country_code", "countryCode", "country"}) {
                JsonNode v = node.get(field);
                if (v != null && !v.isNull()) {
                    String code = v.asText(null);
                    if (code != null && code.trim().matches("(?i)[A-Z]{2}")) return code.trim().toUpperCase();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String fromCache(String ip) {
        CacheEntry e = cache.get(ip);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.at() > CACHE_TTL_MS) {
            cache.remove(ip);
            return null;
        }
        return e.country();
    }

    private void putCache(String ip, String country) {
        if (cache.size() >= CACHE_MAX) cache.clear();
        cache.put(ip, new CacheEntry(country, System.currentTimeMillis()));
    }

    /** 便于单测观察缓存效果 */
    int cacheSize() {
        return cache.size();
    }
}
