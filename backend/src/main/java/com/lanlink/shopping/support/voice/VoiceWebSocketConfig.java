package com.lanlink.shopping.support.voice;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 客服实时语音流 WebSocket 配置类。
 *
 * <p>端点路径：/support/stream-voice 与 /api/support/stream-voice
 * 挂载 {@link VoiceStreamHandshakeInterceptor} 实施可信反代密钥与 IP 频控鉴权。
 */
@Configuration
@EnableWebSocket
public class VoiceWebSocketConfig implements WebSocketConfigurer {

    private final StreamVoiceWebSocketHandler voiceHandler;
    private final VoiceStreamHandshakeInterceptor handshakeInterceptor;

    public VoiceWebSocketConfig(
            StreamVoiceWebSocketHandler voiceHandler,
            VoiceStreamHandshakeInterceptor handshakeInterceptor) {
        this.voiceHandler = voiceHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(voiceHandler, "/support/stream-voice", "/api/support/stream-voice")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
