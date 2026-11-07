package com.lanlink.shopping.common.storage;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

/**
 * object key ↔ URL 归一（out/storage-mq-plan.md 任务 A2/A4）。
 *
 * <p>库内只存短 key（255 长度红线），出站统一拼公开绝对 URL。静态 holder 让
 * {@link StorageUrlSerializer} 在非 Spring 托管路径（Jackson 反射构造）下也能读到
 * enabled / public-base-url；由 {@link StorageConfig} 装配侧调用 {@link #init} 注入。
 *
 * <p>归一边界（与规划 A2 一致并按实际基建校准）：
 * <ul>
 *   <li>本存储域绝对 URL → 截回 key（商户手填回显 URL、绝对 URL 入库场景）；</li>
 *   <li>遗留相对路径 /products/{id}.webp → 桶根 key {id}.webp（minio-init 镜像保留
 *       frontend/public/products 相对路径布局，键名一一对应）；</li>
 *   <li>/api/uploads/**（头像/证照等本地盘域，规划明确不迁）与外部任意 URL → 原样；</li>
 *   <li>裸 key（如 1001.webp、products/prod_ts.webp）→ 原样。</li>
 * </ul>
 */
public final class StorageUrls {

    private StorageUrls() {
    }

    /** 遗留相对路径前缀：静态种子图在前端 public 目录与 MinIO 桶根各存一份（同名 key） */
    static final String LEGACY_WEB_PREFIX = "/products/";
    /** 上传对象入桶前缀（ProductController 上传 key 形如 products/prod_{ts}.{kind}） */
    static final String UPLOAD_KEY_PREFIX = "products/";
    /** 本地盘域（规划 Q3/P2-A9 范围，本期不迁）：出站原样。公开给退款证据校验等需要与 URL 形态对齐的契约常量 */
    public static final String LOCAL_UPLOAD_PREFIX = "/api/uploads/";
    static final String LOCAL_UPLOAD_ALT_PREFIX = "/uploads/";

    private static volatile boolean enabled = false;
    private static volatile String baseUrl = "";
    /** public-base-url 的路径段（path-style 下为桶路径，如 "/products"）；截 key 时需剥离 */
    private static volatile String basePath = "";
    private static volatile Set<String> ownHosts = Set.of();

    /**
     * 装配侧注入生效配置（幂等可重复调用）。未调用时保持"禁用+原样"兜底，
     * 保证纯 Jackson 序列化路径不会拼出错误 URL。
     */
    public static void init(StorageProperties props) {
        if (props == null) {
            return;
        }
        enabled = props.isEnabled();
        String base = props.getPublicBaseUrl();
        if (base != null && base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        baseUrl = base == null ? "" : base;
        basePath = "";
        if (baseUrl != null && !baseUrl.isEmpty()) {
            try {
                URI uri = URI.create(baseUrl);
                String path = uri.getRawPath();
                if (path != null && path.length() > 1) {
                    basePath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                }
            } catch (IllegalArgumentException ignore) {
                // 非法 URL 配置交由 S3Client 构建时报错，这里仅按无路径段处理
            }
        }
        Set<String> hosts = new HashSet<>();
        collectHost(hosts, props.getEndpoint());
        collectHost(hosts, props.getPublicBaseUrl());
        ownHosts = Set.copyOf(hosts);
    }

    /**
     * 写入端归一：把可识别为本存储的值截成 key；外部 URL、本地盘域路径原样放行
     * （规划 A3：商户手填的本存储域绝对 URL 截 key，外域长 URL 仍受 255 校验约束）。
     */
    public static String toKey(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String v = value.trim();
        if (isAbsoluteHttp(v)) {
            String key = keyFromOwnUrl(v);
            return key != null ? key : v;
        }
        if (v.startsWith(LEGACY_WEB_PREFIX) && v.length() > LEGACY_WEB_PREFIX.length()) {
            return v.substring(LEGACY_WEB_PREFIX.length());
        }
        return v;
    }

    /**
     * 出站渲染（规划 A4 兼容层，存量 DB 不直改）：
     * enabled=true → key 拼公开绝对 URL（publicBaseUrl + "/" + key）；
     * enabled=false → 原样输出（规划 G12 回退口径：出站原样，历史相对路径由前端解析层兜底）；
     * 外部绝对 URL / 本地盘域 /api/uploads/** → 原样（本地盘域不迁，头像/证照仍走 /api/uploads/**）。
     */
    public static String publicUrlOf(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String v = value.trim();
        if (!enabled) {
            return v;
        }
        if (isAbsoluteHttp(v)) {
            String key = keyFromOwnUrl(v);
            return key != null ? baseUrl + "/" + key : v;
        }
        if (v.startsWith("/")) {
            if (v.startsWith(LOCAL_UPLOAD_PREFIX) || v.startsWith(LOCAL_UPLOAD_ALT_PREFIX)) {
                return v;
            }
            if (v.startsWith(LEGACY_WEB_PREFIX) && v.length() > LEGACY_WEB_PREFIX.length()) {
                return baseUrl + "/" + v.substring(LEGACY_WEB_PREFIX.length());
            }
            return v;
        }
        return isBareKeyShape(v) ? baseUrl + "/" + v : v;
    }

    /**
     * 视觉审核回源判定（规划 A6）：值是"裸 key"或"本存储域绝对 URL"时返回对应 key，
     * 否则返回 null（交回调用方原有分支：本地盘 / 前端静态资源 / 外部 URL SSRF 白名单）。
     */
    public static String storageKeyOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (isBareKeyShape(v)) {
            return v;
        }
        if (isAbsoluteHttp(v)) {
            return keyFromOwnUrl(v);
        }
        return null;
    }

    /** 裸 key 形态：无 scheme、无根相对前导斜杠、无空白、字符集限于 key 安全字符、无 ".." 段 */
    static boolean isBareKeyShape(String v) {
        if (v.isEmpty() || v.startsWith("/") || isAbsoluteHttp(v)) {
            return false;
        }
        for (String segment : v.split("/")) {
            if ("..".equals(segment)) {
                return false;
            }
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_' || c == '/';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAbsoluteHttp(String v) {
        String low = v.toLowerCase();
        return low.startsWith("http://") || low.startsWith("https://");
    }

    /**
     * 本存储域（endpoint 或 public-base-url 的 host:port）绝对 URL → 截 key；非本域返回 null。
     * path-style 下 public-base-url 含桶路径段（如 /products），须先剥离再取 key：
     * http://localhost:9000/products/1001.webp → 1001.webp；
     * 无路径段的虚拟主机式域名（COS/OSS）则 URL 路径即 key。
     */
    private static String keyFromOwnUrl(String v) {
        try {
            URI uri = URI.create(v);
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            int port = uri.getPort();
            String hostPort = port > 0 ? host + ":" + port : host;
            if (!ownHosts.contains(hostPort)) {
                return null;
            }
            String path = uri.getRawPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                return null;
            }
            String key;
            if (!basePath.isEmpty() && path.startsWith(basePath + "/")) {
                key = path.substring(basePath.length() + 1);
            } else if (!basePath.isEmpty() && path.equals(basePath)) {
                return null;
            } else {
                key = path.startsWith("/") ? path.substring(1) : path;
            }
            return key.isEmpty() ? null : key;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void collectHost(Set<String> hosts, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            if (host == null) {
                return;
            }
            int port = uri.getPort();
            hosts.add(port > 0 ? host + ":" + port : host);
        } catch (IllegalArgumentException ignore) {
            // 非法 URL 配置交由 S3Client 构建时报错，这里仅跳过
        }
    }
}
