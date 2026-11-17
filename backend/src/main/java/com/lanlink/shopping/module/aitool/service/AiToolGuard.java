package com.lanlink.shopping.module.aitool.service;

import com.lanlink.shopping.support.AiApiKeyService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * AI 工具网关安全与鉴权守卫。
 *
 * <p>鉴权分两层：先确认调用方身份（内部服务密钥或平台 API Key），再按工具 scope 授权。
 * 兼容历史 {@code tools:call} 只读密钥，但写工具必须显式持有 {@code cart:write}。</p>
 */
@Component
public class AiToolGuard {

    private static final Logger log = LoggerFactory.getLogger(AiToolGuard.class);
    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_REQUESTS_PER_MIN = 180;
    private static final String DEFAULT_INTERNAL_SECRET = "lanlink-internal-dev-secret";
    private static final Pattern PHONE_PATTERN = Pattern.compile("(\\d{3})\\d{4}(\\d{4})");
    private static final Pattern ROOM_PATTERN = Pattern.compile("(\\d+[-号室幢栋楼单元层])");
    private static final Set<String> READ_PERMISSIONS = Set.of(
            "tools:read", "knowledge:read", "products:read", "orders:read", "logistics:monitor");

    /**
     * 内部 secret 通道的来源白名单，默认只允许 Docker `shopping_default` 网段。
     *
     * <p>为什么不能只靠 secret：secret 是静态共享凭据，一旦泄露（日志、脚本、
     * 容器环境变量）本机任意进程都能拿到；而网关与后端之间的合法调用在
     * Docker 网络里是稳定的容器网段。这里用 {@code request.getRemoteAddr()}
     * **而不是** X-Forwarded-For——后者由客户端可控，伪造一个 172.19.x.x 就绕过了。
     *
     * <p>不要默许 loopback：backend 只映射到 127.0.0.1:8080，宿主机脚本/Host 进程
     * 恰好就是从 loopback 进来的，若把 127.0.0.1 放进默认白名单，这项加固对
     * 「本机已泄露 secret」的场景完全无效。需要本地调试时用
     * {@code AI_INTERNAL_ALLOWED_CIDRS} 显式追加。
     */
    private static final String DEFAULT_ALLOWED_INTERNAL_CIDRS = "172.19.0.0/16";

    /**
     * Docker bridge 的网关地址（宿主机侧进入容器的源地址）。
     *
     * <p>`172.19.0.0/16` 覆盖整个 compose 网段，其中 `172.19.0.1` 是宿主机的
     * bridge 地址：从宿主机 `127.0.0.1:8080` 访问时，Tomcat 看到的 remoteAddr
     * 正是它。若不单独排除，本机任意进程仍可用泄露的 secret 调写工具，
     * 「容器网段白名单」就只挡了外网、没挡本机。默认排除，确需本机调试时
     * 通过 `ai.internal.denied-cidrs` 显式放开。
     */
    private static final String DEFAULT_DENIED_INTERNAL_CIDRS = "172.19.0.1/32";

    /**
     * 写工具权限前缀：只有**写通道**（独立的 write secret）可以调用。
     *
     * <p>安全背景：内部 secret 通道原先 `hasPermission()` 直接 `return true`，
     * 等于「一个 64 字符共享密钥 = 全部工具 + 全部租户」，实测可建采购草稿、
     * 财务过审、签发 approvalToken。而网关刚做的角色校验对这条路径完全不生效
     * （网关自己就是用这把 secret 调后端的）。这把钥匙还同时是客服代理密钥
     * （`SUPPORT_PROXY_SECRET` 是 fallback），影响面进一步放大。
     *
     * <p>现在按 secret 分成两档：
     * <ul>
     *   <li>只配一把 secret（向后兼容）→ 仍全放开，但启动期打 WARN；</li>
     *   <li>配了 {@code AI_INTERNAL_WRITE_SECRET} → 该 secret 只能调写工具，
     *       {@code AI_INTERNAL_SECRET} 只能调只读工具，互不越界。</li>
     * </ul>
     */
    //: 必须与 `AiToolsInternalController.permissionForTool()` 的写工具分支一一对应，
    //: 否则「写 secret」会因 scope 名对不上而调不通自己该调的写工具。
    //: 写工具专用 scope，与 `AiToolsInternalController.permissionForTool()` 一一对应。
    private static final Set<String> WRITE_ONLY_SCOPES = Set.of(
            "cart:write",              // lanlink_create_cart_draft
            "procurement:audit",       // lanlink_audit_procurement_draft
            "procurement:approve");    // lanlink_approve_procurement_flow

    /**
     * 写 secret 的完整权限集：**只读 + 写**。
     *
     * <p>写通道通常要「先查再改」（查商品→建草稿、查草稿→过审），
     * 若只给写 scope，它连 `lanlink_search_products` 都调不通。
     * 而只读 secret 只给 {@link #READ_PERMISSIONS}，永远够不到写工具——
     * 这才是「客服代理密钥不能建草稿」的关键。
     */
    private static final Set<String> WRITE_PERMISSIONS;

    static {
        Set<String> all = new java.util.LinkedHashSet<>(READ_PERMISSIONS);
        all.addAll(WRITE_ONLY_SCOPES);
        all.add("tools:call");
        WRITE_PERMISSIONS = Set.copyOf(all);
    }

    private final String internalSecret;
    /** 写通道独立密钥；为空表示未启用读写分离（保持兼容，单 secret 全放开）。 */
    private final String internalWriteSecret;
    private final List<CidrRange> allowedInternalCidrs;
    private final List<CidrRange> deniedInternalCidrs;
    private final Environment environment;
    private final AiApiKeyService apiKeyService;
    private final java.util.LinkedHashMap<String, WindowCounter> rateLimiters =
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, WindowCounter> eldest) {
                    return size() > 5000;
                }
            };

    @Autowired
    public AiToolGuard(Environment env, @Autowired(required = false) AiApiKeyService apiKeyService) {
        String sec = env.getProperty("ai.internal-secret", "");
        // 不再回退到 `support.proxy-secret`。
        //
        // 历史缺陷：客服代理密钥（SUPPORT_PROXY_SECRET）会顺带获得 AI 工具全权限，
        // 而客服链路只需要只读（甚至只要能调 /support/ask）。两条通道混用一把 secret，
        // 一旦客服代理密钥外泄（前端容器曾注入、日志、共享脚本），写工具直接可达。
        // 现在必须显式配 `ai.internal-secret`（或 INTERNAL_GATEWAY_SECRET）；
        // 都不配则内部通道关闭，fail-closed。
        if (!StringUtils.hasText(sec)) {
            sec = env.getProperty("INTERNAL_GATEWAY_SECRET", "");
        }
        this.internalSecret = sec.trim();
        this.internalWriteSecret = env.getProperty("ai.internal-write-secret", "").trim();
        String cidrs = env.getProperty("ai.internal.allowed-cidrs",
                env.getProperty("AI_INTERNAL_ALLOWED_CIDRS", DEFAULT_ALLOWED_INTERNAL_CIDRS));
        this.allowedInternalCidrs = parseCidrs(cidrs);
        String denied = env.getProperty("ai.internal.denied-cidrs",
                env.getProperty("AI_INTERNAL_DENIED_CIDRS", DEFAULT_DENIED_INTERNAL_CIDRS));
        this.deniedInternalCidrs = parseCidrs(denied);
        // 构造期诊断：只打印长度与是否分离，不打印密钥本身。
        // 放在构造器而非 @PostConstruct，保证无论 Bean 何时初始化都能看到实际生效值。
        log.info("[ai-tool-guard] init: read_secret_len={} write_secret_len={} split={} allowed_cidrs={} denied_cidrs={}",
                this.internalSecret.length(), this.internalWriteSecret.length(),
                StringUtils.hasText(this.internalWriteSecret), cidrs, denied);
        this.environment = env;
        this.apiKeyService = apiKeyService;
    }

    @PostConstruct
    public void warnInsecureDefaults() {
        if (!StringUtils.hasText(internalSecret)) {
            log.warn("[ai-tool-guard] 未配置内部密钥（AI_INTERNAL_SECRET / INTERNAL_GATEWAY_SECRET / "
                    + "SUPPORT_PROXY_SECRET），/internal/ai/tools/** 的内部通道已关闭（fail-closed）；"
                    + "仅 API Key 方式可调用");
            return;
        }
        if (DEFAULT_INTERNAL_SECRET.equals(internalSecret) || internalSecret.contains(DEFAULT_INTERNAL_SECRET)) {
            List<String> profiles = Arrays.asList(environment.getActiveProfiles());
            if (profiles.contains("prod")) {
                throw new IllegalStateException("AI 工具内部密钥仍为默认值；生产环境必须配置 AI_INTERNAL_SECRET/INTERNAL_GATEWAY_SECRET");
            }
            log.warn("[ai-tool-guard] 使用默认内部密钥，仅限本地演示；生产请配置专用 AI_INTERNAL_SECRET");
        }
        // 读写分离自检：未配置写 secret 时，单一 secret 等价于全权限。
        // 这里只告警不阻断，避免打挂现有部署；生产应配 AI_INTERNAL_WRITE_SECRET。
        if (!StringUtils.hasText(internalWriteSecret)) {
            log.warn("[ai-tool-guard] 未配置 AI_INTERNAL_WRITE_SECRET：内部通道仍是**单一密钥全权限**"
                    + "（可建草稿/过审/签发审批）。生产建议拆分读写 secret，"
                    + "使只读代理（如客服）无法触达写工具");
        } else if (internalWriteSecret.equals(internalSecret)) {
            log.error("[ai-tool-guard] AI_INTERNAL_WRITE_SECRET 与 AI_INTERNAL_SECRET 相同，"
                    + "读写分离实际未生效");
        }
    }

    public enum AuthType {
        NONE,
        INTERNAL_SECRET,
        API_KEY
    }

    public record AuthContext(AuthType type, boolean internal, Set<String> permissions,
                              Long tenantId, String keyPrefix, String rateClientId) {
        public boolean allowed() {
            return type != AuthType.NONE;
        }

        public boolean hasPermission(String permission) {
            if (internal) {
                // 内部通道按 secret 档位判断，不再一律放行。
                //
                // `permissions` 由 authenticateContext 按匹配到的 secret 填入：
                //   * 只配一把 secret（兼容模式）→ Set.of("*")
                //   * 写 secret → WRITE_PERMISSIONS
                //   * 只读 secret → READ_PERMISSIONS
                // 这样「只读代理持有只读 secret」时调写工具会被这里拒掉，
                // 而不是像历史实现那样无条件 true。
                if (permissions == null || permission == null) return false;
                if (permissions.contains("*")) return true;
                return permissions.contains(permission);
            }
            if (permissions == null || permission == null) return false;
            if (permissions.contains("*") || permissions.contains(permission)) return true;
            return READ_PERMISSIONS.contains(permission)
                    && (permissions.contains("tools:call") || permissions.contains("tools:*"));
        }
    }

    public AuthType authenticate(HttpServletRequest request) {
        return authenticateContext(request).type();
    }

    /** 一次查询得到身份、scope、租户和限流主体，避免同一请求反复访问 API Key 表。 */
    public AuthContext authenticateContext(HttpServletRequest request) {
        if (request == null) return new AuthContext(AuthType.NONE, false, Set.of(), null, "anonymous", "anonymous");

        boolean internalSourceAllowed = isInternalSourceAllowed(request.getRemoteAddr());
        String suppliedSecret = request.getHeader("X-Internal-Secret");
        // 过渡期兼容：仍接受 `X-Support-Proxy-Secret`，但**只授予只读**。
        //
        // 历史缺陷：客服代理密钥经这条 fallback 直接拿到 internal 身份，而
        // `hasPermission` 对 internal 无条件 true → 客服代理密钥可建草稿/过审。
        // 现在保留这条通路（避免打挂现有客服链路）但降级为只读：
        // 真正的写能力必须走 `X-Internal-Secret` + AI_INTERNAL_WRITE_SECRET。
        boolean viaSupportProxy = false;
        if (!StringUtils.hasText(suppliedSecret)) {
            suppliedSecret = request.getHeader("X-Support-Proxy-Secret");
            viaSupportProxy = StringUtils.hasText(suppliedSecret);
        }
        if (StringUtils.hasText(suppliedSecret) && StringUtils.hasText(internalSecret)) {
            byte[] suppBytes = suppliedSecret.trim().getBytes(StandardCharsets.UTF_8);
            if (!internalSourceAllowed) {
                // secret 正确也不算数：来源不在容器网段内，按未认证处理并留下
                // `internal-denied-ip` 前缀，审计里能直接看出这是「有钥匙但来源非法」。
                log.warn("[ai-tool-guard] 拒绝非容器网段的内部 secret 调用: remote={}",
                        request.getRemoteAddr());
                return new AuthContext(AuthType.NONE, false, Set.of(), null,
                        "internal-denied-ip", request.getRemoteAddr());
            }
            // 客服代理密钥通道：只给只读，且**永不**比对写 secret。
            if (viaSupportProxy) {
                for (String sec : internalSecret.split(",")) {
                    if (StringUtils.hasText(sec) && MessageDigest.isEqual(
                            sec.trim().getBytes(StandardCharsets.UTF_8), suppBytes)) {
                        return new AuthContext(AuthType.INTERNAL_SECRET, true, READ_PERMISSIONS, null,
                                "support-proxy-read", request.getRemoteAddr());
                    }
                }
                return new AuthContext(AuthType.NONE, false, Set.of(), null,
                        "support-proxy-denied", "anonymous");
            }
            // 先比对**写** secret：配了独立写密钥时它只授予写工具，不授予只读。
            if (StringUtils.hasText(internalWriteSecret)) {
                for (String sec : internalWriteSecret.split(",")) {
                    if (StringUtils.hasText(sec) && MessageDigest.isEqual(
                            sec.trim().getBytes(StandardCharsets.UTF_8), suppBytes)) {
                        return new AuthContext(AuthType.INTERNAL_SECRET, true, WRITE_PERMISSIONS, null,
                                "internal-write", request.getRemoteAddr());
                    }
                }
            }
            // 再比对只读 secret。
            for (String sec : internalSecret.split(",")) {
                if (StringUtils.hasText(sec) && MessageDigest.isEqual(
                        sec.trim().getBytes(StandardCharsets.UTF_8), suppBytes)) {
                    // 未配置写 secret 时保持原语义（全放开）；配了则只给只读。
                    Set<String> granted = StringUtils.hasText(internalWriteSecret)
                            ? READ_PERMISSIONS : Set.of("*");
                    String label = StringUtils.hasText(internalWriteSecret)
                            ? "internal-read" : "internal-secret";
                    return new AuthContext(AuthType.INTERNAL_SECRET, true, granted, null,
                            label, request.getRemoteAddr());
                }
            }
        }

        String apiKey = extractApiKey(request);
        if (StringUtils.hasText(apiKey) && apiKeyService != null) {
            AiApiKeyService.Principal principal = apiKeyService.authenticate(apiKey);
            if (principal != null) {
                String clientId = StringUtils.hasText(principal.keyPrefix()) ? principal.keyPrefix() : "api-key";
                return new AuthContext(AuthType.API_KEY, false, principal.permissions(),
                        principal.tenantId(), clientId, clientId);
            }
        }

        return new AuthContext(AuthType.NONE, false, Set.of(), null,
                StringUtils.hasText(apiKey) ? maskedClientKey(request) : maskedClientKey(request),
                maskedClientKey(request));
    }

    /**
     * 判断内部通道来源是否在允许网段内。
     *
     * <p>严格使用 {@code remoteAddr}：Tomcat 在未配置 trusted proxy 时给的是 TCP
     * 对端地址；`X-Forwarded-For` / `X-Real-IP` 可以被任意客户端伪造，不能作为
     * 授权依据。IP 解析失败时 fail-closed。
     */
    private boolean isInternalSourceAllowed(String remoteAddr) {
        if (!StringUtils.hasText(remoteAddr)) return false;
        try {
            InetAddress address = InetAddress.getByName(remoteAddr.trim());
            for (CidrRange range : deniedInternalCidrs) {
                if (range.contains(address)) return false;
            }
            for (CidrRange range : allowedInternalCidrs) {
                if (range.contains(address)) return true;
            }
        } catch (UnknownHostException ex) {
            log.warn("[ai-tool-guard] 无法解析内部通道来源地址: {}", remoteAddr);
        }
        return false;
    }

    /** 解析逗号分隔的 CIDR / 单 IP；非法条目跳过并告警，不阻断启动。 */
    private static List<CidrRange> parseCidrs(String raw) {
        List<CidrRange> out = new ArrayList<>();
        if (!StringUtils.hasText(raw)) return List.of();
        for (String item : raw.split(",")) {
            String entry = item.trim();
            if (!entry.isEmpty()) {
                try {
                    out.add(CidrRange.parse(entry));
                } catch (IllegalArgumentException ex) {
                    log.warn("[ai-tool-guard] 忽略非法 CIDR 配置: {} ({})", entry, ex.getMessage());
                }
            }
        }
        return List.copyOf(out);
    }

    /** IPv4/IPv6 CIDR 匹配器；只依赖 JDK，避免为一条白名单引入额外依赖。 */
    private record CidrRange(byte[] network, int prefixBits) {
        static CidrRange parse(String value) {
            String[] parts = value.split("/", -1);
            if (parts.length > 2) throw new IllegalArgumentException("格式应为 ip 或 ip/prefix");
            try {
                InetAddress address = InetAddress.getByName(parts[0]);
                byte[] bytes = address.getAddress();
                int prefix = parts.length == 2
                        ? Integer.parseInt(parts[1])
                        : bytes.length * 8;
                if (prefix < 0 || prefix > bytes.length * 8) {
                    throw new IllegalArgumentException("prefix 超出范围");
                }
                byte[] network = bytes.clone();
                int fullBytes = prefix / 8;
                int remainder = prefix % 8;
                if (remainder != 0) {
                    network[fullBytes] &= (byte) (0xFF << (8 - remainder));
                }
                for (int i = fullBytes + (remainder == 0 ? 0 : 1); i < network.length; i++) {
                    network[i] = 0;
                }
                return new CidrRange(network, prefix);
            } catch (UnknownHostException ex) {
                throw new IllegalArgumentException("非法 IP: " + parts[0]);
            }
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) return false;
            for (int i = 0; i < network.length; i++) {
                int bits = Math.min(8, Math.max(0, prefixBits - i * 8));
                if (bits == 0) break;
                int mask = 0xFF << (8 - bits) & 0xFF;
                if ((candidate[i] & mask) != (network[i] & mask)) return false;
            }
            return true;
        }
    }

    public boolean hasPermission(HttpServletRequest request, String permission) {
        return authenticateContext(request).hasPermission(permission);
    }

    public Long tenantId(HttpServletRequest request) {
        return authenticateContext(request).tenantId();
    }

    public String keyPrefix(HttpServletRequest request) {
        return authenticateContext(request).keyPrefix();
    }

    private static String extractApiKey(HttpServletRequest request) {
        String apiKey = request.getHeader("X-API-Key");
        if (StringUtils.hasText(apiKey)) return apiKey.trim();
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }
        return null;
    }

    private static String maskedClientKey(HttpServletRequest request) {
        String apiKey = extractApiKey(request);
        if (StringUtils.hasText(apiKey)) {
            return apiKey.startsWith("llk_") && apiKey.length() > 12 ? apiKey.substring(0, 12) + "****" : "external-bearer";
        }
        return request.getRemoteAddr();
    }

    /**
     * 限流校验：防止外部单 Key 或单 IP 刷爆数据库或向量检索连接池。
     */
    public boolean checkRateLimit(String clientIdentifier) {
        if (!StringUtils.hasText(clientIdentifier)) clientIdentifier = "anonymous";
        long now = System.currentTimeMillis();
        String key = clientIdentifier.trim();
        synchronized (rateLimiters) {
            WindowCounter counter = rateLimiters.get(key);
            if (counter == null || now - counter.windowStart > WINDOW_MS) {
                counter = new WindowCounter(now);
                rateLimiters.put(key, counter);
            }
            counter.requests++;
            if (counter.requests > MAX_REQUESTS_PER_MIN) {
                log.warn("[ai-tool-guard] rate limit exceeded for client: {}, count={}", key, counter.requests);
                return false;
            }
            return true;
        }
    }

    // ==================== PII 动态脱敏脱敏器 ====================

    /** 手机号脱敏：13812345678 -> 138****5678 */
    public static String maskPhone(String phone) {
        if (!StringUtils.hasText(phone)) return "";
        String trimmed = phone.trim();
        if (trimmed.length() == 11) {
            return PHONE_PATTERN.matcher(trimmed).replaceAll("$1****$2");
        }
        if (trimmed.length() > 6) {
            return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 2);
        }
        return "***";
    }

    /** 姓名脱敏：张三 -> 张*；诸葛孔明 -> 诸**明 */
    public static String maskName(String name) {
        if (!StringUtils.hasText(name)) return "";
        String trimmed = name.trim();
        if (trimmed.length() <= 1) return trimmed + "*";
        if (trimmed.length() == 2) return trimmed.charAt(0) + "*";
        if (trimmed.length() == 3) return trimmed.charAt(0) + "*" + trimmed.charAt(2);
        return trimmed.charAt(0) + "**" + trimmed.charAt(trimmed.length() - 1);
    }

    /** 地址脱敏：保留省市区道路产业园，截断具体楼幢室号 */
    public static String maskAddress(String address) {
        if (!StringUtils.hasText(address)) return "";
        return ROOM_PATTERN.matcher(address).replaceAll("***");
    }

    private static class WindowCounter {
        final long windowStart;
        int requests;

        WindowCounter(long windowStart) {
            this.windowStart = windowStart;
            this.requests = 0;
        }
    }
}
