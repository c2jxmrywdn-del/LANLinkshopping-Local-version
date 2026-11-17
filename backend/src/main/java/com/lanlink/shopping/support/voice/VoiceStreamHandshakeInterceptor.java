package com.lanlink.shopping.support.voice;

import com.lanlink.shopping.support.SupportRequestGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 语音 WebSocket 全双工通道握手拦截与鉴权守卫。
 *
 * <p>浏览器原生 WebSocket 无法直接在握手请求中自定义 HTTP Header，
 * 因此除标准 X-Support-Proxy-Secret 请求头外，本拦截器额外支持通过 URL 查询参数
 * （?proxySecret=... 或 ?token=...）传递代理密钥与访客凭据。
 */
@Component
public class VoiceStreamHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(VoiceStreamHandshakeInterceptor.class);

    private final String expectedProxySecret;
    private final SupportRequestGuard guard;

    public VoiceStreamHandshakeInterceptor(Environment environment, SupportRequestGuard guard) {
        this.expectedProxySecret = environment.getProperty("support.proxy-secret", "").trim();
        this.guard = guard;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                  WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Map<String, String> queryParams = parseQueryParams(request.getURI());

        // 1. 代理密钥鉴权：头优先，查询参数兜底（适配浏览器 WebSocket）
        String headerSecret = request.getHeaders().getFirst("X-Support-Proxy-Secret");
        String secret = StringUtils.hasText(headerSecret) ? headerSecret : queryParams.get("proxySecret");

        // fail-closed：服务端未配置密钥时一律拒绝，与 SupportRequestGuard 的语义保持一致。
        //
        // <p>历史实现是 fail-open（`if (hasText(expected)) {...}`）——运维漏配
        // SUPPORT_PROXY_SECRET 时握手全部放行，等于把实时语音通道（可触发付费 ASR/TTS/LLM）
        // 完全暴露给公网。这里改为拒绝并打印可操作日志。
        if (!StringUtils.hasText(expectedProxySecret)) {
            log.error("[voice-stream-ws] 握手被拒：服务端未配置 support.proxy-secret"
                    + "（请在 .env 设置 SUPPORT_PROXY_SECRET），实时语音通道不可用");
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        if (!expectedProxySecret.equals(secret)) {
            log.warn("[voice-stream-ws] 握手被拒：代理密钥不匹配或缺失 (remote={})", request.getRemoteAddress());
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        // 2. 限流校验
        String clientIp = request.getHeaders().getFirst("X-Support-Client-IP");
        if (!StringUtils.hasText(clientIp)) {
            clientIp = request.getHeaders().getFirst("X-Forwarded-For");
            if (StringUtils.hasText(clientIp) && clientIp.contains(",")) {
                clientIp = clientIp.split(",")[0].trim();
            }
        }
        if (!StringUtils.hasText(clientIp) && request.getRemoteAddress() != null) {
            clientIp = request.getRemoteAddress().getAddress().getHostAddress();
        }
        if (clientIp == null) {
            clientIp = "127.0.0.1";
        }

        if (!guard.allowRequest(clientIp)) {
            log.warn("[voice-stream-ws] 握手被拒：IP 频率超限 (ip={})", clientIp);
            response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return false;
        }

        // 3. 会话属性绑定
        String visitorToken = queryParams.getOrDefault("visitorToken", "");
        String conversationNo = queryParams.getOrDefault("conversationNo", "");
        attributes.put("clientIp", clientIp);
        attributes.put("visitorToken", visitorToken);
        attributes.put("conversationNo", conversationNo);

        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private static Map<String, String> parseQueryParams(URI uri) {
        Map<String, String> map = new HashMap<>();
        if (uri == null || uri.getRawQuery() == null) {
            return map;
        }
        String[] pairs = uri.getRawQuery().split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(key, value);
            }
        }
        return map;
    }
}
