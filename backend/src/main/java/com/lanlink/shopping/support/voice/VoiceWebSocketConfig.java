package com.lanlink.shopping.support.voice;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
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

    /**
     * WebSocket 容器缓冲区上限。
     *
     * <p>回归缺陷：Tomcat 默认 text 缓冲仅 8KB，而语音链路单条下行消息是
     * `tts_chunk`——内含一整句的 MP3 base64（实测单句 25KB~70KB）。
     * 超过 8KB 时容器直接以 `CloseStatus[code=1009, reason=No async message support and
     * buffer too small. Buffer size: [8,192]]` 断开连接，前端表现为
     * 「客服正在回答…」之后突然静音、对讲失效。
     *
     * <p>取值依据：TTS 单句上限 = 500 字 → 约 2MB base64（实测 500 字音频 1.99MB），
     * 加上 JSON 包装与其它字段，4MB 足够；binary 侧是 16kHz PCM 帧（640B/20ms），
     * 1MB 已远超任何合理单帧。
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(4 * 1024 * 1024);
        container.setMaxBinaryMessageBufferSize(1024 * 1024);
        return container;
    }
}
