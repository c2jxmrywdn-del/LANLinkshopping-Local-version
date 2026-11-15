package com.lanlink.shopping.support.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.support.SupportAiAnswerService;
import com.lanlink.shopping.support.SupportAnswerService;
import com.lanlink.shopping.support.tts.SupportTtsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamVoiceWebSocketHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MimoAsrStreamService asrService;
    private SupportAnswerService answerService;
    private SupportAiAnswerService aiAnswerService;
    private SupportTtsService ttsService;
    private StreamVoiceWebSocketHandler handler;
    private WebSocketSession session;

    @BeforeEach
    void setUp() throws Exception {
        asrService = mock(MimoAsrStreamService.class);
        answerService = mock(SupportAnswerService.class);
        aiAnswerService = mock(SupportAiAnswerService.class);
        ttsService = mock(SupportTtsService.class);
        handler = new StreamVoiceWebSocketHandler(asrService, answerService, aiAnswerService, ttsService);

        session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("test-session-1");
        when(session.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(session);
    }

    @Test
    void sendsReadyEventOnConnect() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());

        JsonNode json = JSON.readTree(captor.getValue().getPayload());
        assertEquals("ready", json.path("event").asText());
        assertEquals("test-session-1", json.path("sessionId").asText());
    }

    @Test
    void handlesBinaryPcmAndAsrEnd() throws Exception {
        when(asrService.transcribePcm(any())).thenReturn(Optional.of("如何退款？"));
        when(answerService.answer("如何退款？")).thenReturn("您可以在订单页面发起退款申请。");
        when(ttsService.synthesize(anyString(), anyString()))
                .thenReturn(new SupportTtsService.TtsResult("audio-bytes", "mp3", 24000, "mimo", "茉莉"));

        // Send PCM binary chunk
        byte[] pcmChunk = new byte[1600];
        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(pcmChunk)));

        // Send asr_end
        handler.handleTextMessage(session, new TextMessage("{\"action\":\"asr_end\"}"));

        // Wait a bit for async tasks
        Thread.sleep(200);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());

        boolean sawAsrFinal = false;
        for (TextMessage msg : captor.getAllValues()) {
            JsonNode node = JSON.readTree(msg.getPayload());
            if ("asr_final".equals(node.path("event").asText()) && "如何退款？".equals(node.path("text").asText())) {
                sawAsrFinal = true;
            }
        }
        assertTrue(sawAsrFinal, "应下发 asr_final 事件及识别文本");
    }

    @Test
    void handlesInterruptBargeIn() throws Exception {
        handler.handleTextMessage(session, new TextMessage("{\"action\":\"interrupt\"}"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());

        boolean sawInterrupted = false;
        for (TextMessage msg : captor.getAllValues()) {
            JsonNode node = JSON.readTree(msg.getPayload());
            if ("interrupted".equals(node.path("event").asText())) {
                sawInterrupted = true;
            }
        }
        assertTrue(sawInterrupted, "应下发 interrupted 事件确认打断");
    }
}
