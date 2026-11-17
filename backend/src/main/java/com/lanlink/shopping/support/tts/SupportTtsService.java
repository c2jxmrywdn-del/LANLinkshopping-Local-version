package com.lanlink.shopping.support.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lanlink.shopping.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 客服回答语音合成核心服务：把文本转为音频 Base64（默认 MP3）。
 *
 * <p>特性：
 * <ul>
 *   <li>音色锁定：统一知性御姐声线，叠加前置风格锚点与自然语言指令；</li>
 *   <li>内存 LRU 缓存：按 sha256(text+voice+format) 缓存，避免重复付费与算力开销；</li>
 *   <li>支持控制器单次合成与流式 WebSocket 分句 Pipeline 并发合成复用。</li>
 * </ul>
 */
@Service
public class SupportTtsService {

    private static final Logger log = LoggerFactory.getLogger(SupportTtsService.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    /**
     * 网关合成超时。
     *
     * <p>实测 MiMo 合成速率约 45–50ms/字：258 字 ≈ 14.8s、400 字 ≈ 20s、500 字 ≈ 48s。
     * 原值 30s 会让 400 字以上的回答在网关仍在合成时被客户端中断，前端只看到
     * `code:503`，配合 `SupportChat.vue` 的静默 catch 表现为「点朗读没反应」。
     * 90s 给足 500 字上限 + 网络抖动余量，且仍小于 nginx/Vite 的 600s 代理超时。
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);
    private static final int MAX_TEXT_LENGTH = 500;
    private static final int CACHE_CAPACITY = 200;
    /**
     * 缓存字节上限（约 48MB 的 base64 字符）。
     *
     * <p>按条数限流不够：500 字回答的 base64 约 2MB，200 条最坏可达 400MB，
     * 足够把 2g 堆的容器打爆。这里同时按字符量封顶，超出即淘汰最旧条目。
     */
    private static final long CACHE_MAX_CHARS = 48L * 1024 * 1024;
    public static final String SUPPORTED_FORMAT = "mp3";

    public static final String DEFAULT_VOICE = "茉莉";
    public static final String STYLE_TAG = "(御姐音 醇雅 干练 平静)";
    public static final String DEFAULT_STYLE =
            "成熟沉稳的知性御姐，语调优雅从容，语速适中平稳，音色醇雅有磁性，避免甜腻、尖锐或稚嫩；"
                    + "句间停顿自然，重点词略加重，保持客服的专业与亲和。";

    private final String gatewayBaseUrl;
    private final String gatewayApiKey;
    private final String voice;
    private final String style;

    /** 缓存当前占用的 base64 字符总量（配合 CACHE_MAX_CHARS 做字节级封顶）。 */
    private long cacheChars = 0L;

    private final Map<String, TtsResult> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, TtsResult> eldest) {
                    if (size() <= CACHE_CAPACITY && cacheChars <= CACHE_MAX_CHARS) {
                        return false;
                    }
                    String dropped = eldest.getValue().audioBase64();
                    cacheChars -= dropped == null ? 0 : dropped.length();
                    return true;
                }
            });

    @Autowired
    public SupportTtsService(
            @Value("${AI_GATEWAY_BASE_URL:http://lanlink-ai-gateway:8090/v1}") String gatewayBaseUrl,
            @Value("${AI_GATEWAY_API_KEY:lanlink-local-ai-gateway}") String gatewayApiKey,
            @Value("${SUPPORT_TTS_VOICE:茉莉}") String voice,
            @Value("${SUPPORT_TTS_STYLE:" + DEFAULT_STYLE + "}") String style) {
        this.gatewayBaseUrl = normalizeGatewayUrl(gatewayBaseUrl);
        this.gatewayApiKey = gatewayApiKey;
        // 白名单收敛：只接受实测可用的 MiMo 音色，其余（晓晓/云希/Molly 等）一律回退「茉莉」，
        // 避免上游 400 导致整条朗读链路 100% 失败。
        this.voice = sanitizeVoiceByWhitelist(voice, DEFAULT_VOICE);
        // 风格同样做乱码兜底：GBK 写坏的长串会命中「?」/替换符/超长，回退内置常量
        this.style = sanitizeStyleOrFallback(style, DEFAULT_STYLE);
    }

    /** 风格指令净化：乱码/超长回退内置常量（理由同 {@link #sanitizeVoiceOrFallback}）。 */
    static String sanitizeStyleOrFallback(String style, String fallback) {
        if (style == null) return fallback;
        String s = style.trim();
        if (s.isEmpty()) return fallback;
        if (s.contains("?") || s.contains("\ufffd")) return fallback;
        if (containsMojibakeMarker(s)) return fallback;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) return fallback;
        }
        // 正常风格描述远短于 200 字；乱码串常因字节膨胀而超长
        return s.length() > 200 ? fallback : s;
    }

    private static String sanitizeVoice(String voice) {
        return sanitizeVoiceOrFallback(voice, DEFAULT_VOICE);
    }

    /**
     * 音色名净化：非法/乱码一律回退到安全默认值。
     *
     * <p>回归缺陷：`.env` 用 GBK 写入中文音色（「茉莉」→「閼煎甯€」），
     * 服务端原样透传给 MiMo，上游判定为非法 voice 返回 HTTP 400，
     * 前端表现为「朗读功能异常」。这里把「非中文/字母数字」的音色名视为脏数据，
     * 直接使用源码里的常量（编译期 UTF-8，不受运行时环境变量编码影响）。
     *
     * <p>判定规则：允许 CJK、字母、数字、空格、连字符；出现问号/替换符/控制字符即视为乱码。
     * 合法中文音色（如「茉莉」）仍会通过，保持可配置能力。
     */
    static String sanitizeVoiceOrFallback(String voice, String fallback) {
        if (voice == null) return fallback;
        String v = voice.trim();
        if (v.isEmpty()) return fallback;
        // 乱码特征：问号（GBK 无法映射时的占位）、U+FFFD 替换符、控制字符
        if (v.contains("?") || v.contains("\ufffd")) return fallback;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isISOControl(c)) return fallback;
        }
        // GBK→UTF-8 乱码的强特征：混入「鑼/帢/閼/煎/甯/閹/劗」这类生僻字。
        // 正常音色名（茉莉/晓晓/云希）不会出现这些字，命中即判定为乱码。
        if (containsMojibakeMarker(v)) return fallback;
        // 长度兜底：正常音色名不会超过 16 字符（乱码串通常很长）
        return v.length() > 16 ? fallback : v;
    }

    /**
     * GBK 字节被按 UTF-8 解读后产生的典型字符。
     *
     * <p>这些字在正常中文音色名/风格描述里几乎不会出现（生僻字或非常用组合），
     * 用作乱码强特征，避免「4 个字的乱码」因长度达标而漏网（实测 `閼煎甯€` 只有 4 字）。
     */
    private static final String MOJIBAKE_MARKERS = "鑼帢閼煎甯閹劗閺鍟鍕鍨鎷欒鍙鍞鍜鍝";

    /**
     * MiMo TTS 合法音色白名单（实测唯一可用值）。
     *
     * <p>实测：`茉莉` 返回 200；`晓晓`/`云希`/`女声`/`Molly`/`male`/`female`/`alloy`
     * 全部返回 502（上游 400）。原实现只过滤乱码、不校验合法性，
     * 一旦运维把 `SUPPORT_TTS_VOICE` 填成常见音色名，整条朗读链路 100% 失败且
     * 前端静默——所以这里显式收敛到白名单，非法值直接回退默认音色。
     */
    private static final java.util.Set<String> ALLOWED_VOICES = java.util.Set.of("茉莉");

    static String sanitizeVoiceByWhitelist(String voice, String fallback) {
        String sanitized = sanitizeVoiceOrFallback(voice, fallback);
        return ALLOWED_VOICES.contains(sanitized) ? sanitized : fallback;
    }

    static boolean containsMojibakeMarker(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < MOJIBAKE_MARKERS.length(); i++) {
            if (text.indexOf(MOJIBAKE_MARKERS.charAt(i)) >= 0) return true;
        }
        return false;
    }

    public record TtsResult(String audioBase64, String format, int sampleRate, String engine, String voice) {
    }

    public TtsResult synthesize(String text, String format) {
        return synthesize(text, this.voice, format, this.style);
    }

    public TtsResult synthesize(String text, String requestedVoice, String requestedFormat, String customStyle) {
        String cleanText = text == null ? "" : text.trim();
        if (cleanText.isEmpty()) {
            throw new BusinessException(400, "文本不能为空");
        }
        if (cleanText.length() > MAX_TEXT_LENGTH) {
            throw new BusinessException(400, "文本长度不能超过 " + MAX_TEXT_LENGTH + " 字");
        }
        String format = (requestedFormat == null || requestedFormat.isBlank())
                ? SUPPORTED_FORMAT
                : requestedFormat.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_FORMAT.equals(format)) {
            throw new BusinessException(400, "仅支持 mp3 格式");
        }

        // 统一强制使用服务端知性御姐声线
        String effectiveVoice = this.voice;
        String effectiveStyle = (customStyle == null || customStyle.isBlank()) ? this.style : customStyle.trim();
        String taggedText = cleanText.startsWith(STYLE_TAG) ? cleanText : STYLE_TAG + cleanText;

        String cacheKey = cacheKey(taggedText, format);
        TtsResult cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        TtsResult result = requestGateway(taggedText, effectiveVoice, format, effectiveStyle);
        // 字节级封顶：按「新值 - 旧值」增量记账，同 key 覆盖不会重复计数
        // （removeEldestEntry 里按被淘汰条目的长度回扣）。
        String audio = result.audioBase64();
        long incoming = audio == null ? 0 : audio.length();
        synchronized (cache) {
            TtsResult previous = cache.get(cacheKey);
            long outgoing = previous == null || previous.audioBase64() == null
                    ? 0 : previous.audioBase64().length();
            cacheChars += incoming - outgoing;
            cache.put(cacheKey, result);
        }
        return result;
    }

    private TtsResult requestGateway(String taggedText, String effectiveVoice, String format, String effectiveStyle) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("text", taggedText);
        payload.put("voice", effectiveVoice);
        payload.put("style", effectiveStyle);
        payload.put("format", format);

        URI uri = URI.create(gatewayBaseUrl + "/tts");
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + gatewayApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HTTP.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[support-tts] 网关返回异常 status={}", response.statusCode());
                throw new BusinessException(503, "语音合成暂不可用");
            }
            JsonNode root = JSON.readTree(response.body());
            String audioBase64 = root.path("audioBase64").asText("");
            if (audioBase64.isBlank()) {
                log.warn("[support-tts] 网关响应缺少 audioBase64");
                throw new BusinessException(503, "语音合成暂不可用");
            }
            return new TtsResult(audioBase64,
                    root.path("format").asText(format),
                    root.path("sampleRate").asInt(24000),
                    root.path("engine").asText(""),
                    root.path("voice").asText(effectiveVoice));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[support-tts] 网关调用被中断");
            throw new BusinessException(503, "语音合成暂不可用");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[support-tts] 网关调用失败: {}: {}", e.getClass().getSimpleName(), e.getMessage());
            throw new BusinessException(503, "语音合成暂不可用");
        }
    }

    /**
     * 归一化网关基址（仅去尾部斜杠）。
     *
     * <p>历史缺陷：这里曾把 `lanlink-ai-gateway` 主机名「探测失败」时替换成 `127.0.0.1`，
     * 但后端本身跑在容器里——`127.0.0.1:8090` 指向的是后端容器自己，TTS 必然 502。
     * 而 `InetAddress.getByName` 对容器 DNS 别名偶发解析失败，就把好地址改成了坏地址。
     *
     * <p>正确做法：容器内始终使用 compose 服务名（由 Docker DNS 解析，实测 172.19.0.14），
     * 不做任何主机名替换；宿主机本地开发用 env 显式指定 127.0.0.1 即可。
     */
    static String normalizeGatewayUrl(String url) {
        if (url == null || url.isBlank()) return "http://lanlink-ai-gateway:8090/v1";
        return trimSlash(url);
    }

    private static String cacheKey(String taggedText, String format) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((taggedText + "|" + format).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0x0f, 16));
                sb.append(Character.forDigit(b & 0x0f, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return taggedText + "|" + format;
        }
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
