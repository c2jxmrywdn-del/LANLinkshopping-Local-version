package com.lanlink.shopping.support.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lanlink.shopping.support.SupportAiAnswerService;
import com.lanlink.shopping.support.SupportAnswerService;
import com.lanlink.shopping.support.tts.SupportTtsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客服实时双向语音全双工 WebSocket 处理器。
 *
 * <p>端到端架构：
 * <ul>
 *   <li>上行：接收浏览器 AudioWorklet 分发的 16kHz 16-bit Mono 小端 PCM 帧，实时累积并转写（ASR）；</li>
 *   <li>中枢：LLM 流式生成与流式分句检测（SBD），首句 6~8 字符立即切句；</li>
 *   <li>下行：并发 Pipeline TTS 合成 MP3 语音分片，携带 sequenceId 下发前端保序连续播放；</li>
 *   <li>即时打断（Barge-in）：收到 interrupt 指令或检测到新用户发言时，立即中止当前轮次 TTS 并重置 epoch。</li>
 * </ul>
 */
@Component
public class StreamVoiceWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamVoiceWebSocketHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PCM_BUFFER_BYTES = 10 * 1024 * 1024; // 10MB 防溢出

    private final MimoAsrStreamService asrService;
    private final SupportAnswerService answerService;
    private final SupportAiAnswerService aiAnswerService;
    private final SupportTtsService ttsService;
    /**
     * TTS 合成线程池：有界队列 + AbortPolicy（拒绝时由调用方降级为纯文本）。
     *
     * <p>ASR/LLM/TTS 都是阻塞调用，历史实现用无界队列的 fixedThreadPool(16)，
     * 超过 16 并发就无限排队、内存与延迟双爆。
     */
    private final ExecutorService ttsExecutor = new java.util.concurrent.ThreadPoolExecutor(
            16, 16, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(64),
            new java.util.concurrent.ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicInteger seq =
                        new java.util.concurrent.atomic.AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "support-tts-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();

    public StreamVoiceWebSocketHandler(
            MimoAsrStreamService asrService,
            SupportAnswerService answerService,
            SupportAiAnswerService aiAnswerService,
            SupportTtsService ttsService) {
        this.asrService = asrService;
        this.answerService = answerService;
        this.aiAnswerService = aiAnswerService;
        this.ttsService = ttsService;
    }

    private static class SessionContext {
        final String sessionId;
        final ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
        final AtomicInteger currentEpoch = new AtomicInteger(0);
        final AtomicBoolean isProcessing = new AtomicBoolean(false);
        final List<CompletableFuture<?>> activeTasks = new CopyOnWriteArrayList<>();

        SessionContext(String sessionId) {
            this.sessionId = sessionId;
        }

        void clearPcm() {
            synchronized (pcmBuffer) {
                pcmBuffer.reset();
            }
        }

        byte[] snapshotAndClearPcm() {
            synchronized (pcmBuffer) {
                byte[] data = pcmBuffer.toByteArray();
                pcmBuffer.reset();
                return data;
            }
        }

        void appendPcm(byte[] bytes) {
            synchronized (pcmBuffer) {
                if (pcmBuffer.size() + bytes.length <= MAX_PCM_BUFFER_BYTES) {
                    pcmBuffer.write(bytes, 0, bytes.length);
                }
            }
        }

        void interrupt() {
            currentEpoch.incrementAndGet();
            for (CompletableFuture<?> task : activeTasks) {
                if (!task.isDone()) {
                    task.cancel(true);
                }
            }
            activeTasks.clear();
            isProcessing.set(false);
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        SessionContext ctx = new SessionContext(session.getId());
        sessions.put(session.getId(), ctx);

        ObjectNode readyMsg = JSON.createObjectNode();
        readyMsg.put("event", "ready");
        readyMsg.put("sessionId", session.getId());
        sendSafe(session, readyMsg);
        log.info("[voice-stream-ws] 客户端已连接 sessionId={}", session.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SessionContext ctx = sessions.get(session.getId());
        if (ctx == null) return;

        ByteBuffer payload = message.getPayload();
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        ctx.appendPcm(bytes);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        SessionContext ctx = sessions.get(session.getId());
        if (ctx == null) return;

        String payload = message.getPayload();
        JsonNode root;
        try {
            root = JSON.readTree(payload);
        } catch (Exception e) {
            log.warn("[voice-stream-ws] 非法 JSON 消息: {}", payload);
            return;
        }

        String action = root.path("action").asText("");
        switch (action) {
            case "start" -> {
                ctx.interrupt();
                ctx.clearPcm();
                ObjectNode node = JSON.createObjectNode();
                node.put("event", "ready");
                node.put("sessionId", session.getId());
                sendSafe(session, node);
            }
            case "asr_end" -> handleAsrEnd(session, ctx);
            case "text" -> {
                String text = root.path("text").asText("").trim();
                if (!text.isEmpty()) {
                    processQuestion(session, ctx, text);
                }
            }
            case "interrupt" -> {
                ctx.interrupt();
                ObjectNode node = JSON.createObjectNode();
                node.put("event", "interrupted");
                node.put("epoch", ctx.currentEpoch.get());
                sendSafe(session, node);
                log.info("[voice-stream-ws] 收到用户即时打断 (Barge-in) sessionId={}", session.getId());
            }
            case "ping" -> {
                ObjectNode node = JSON.createObjectNode();
                node.put("event", "pong");
                sendSafe(session, node);
            }
            default -> log.debug("[voice-stream-ws] 收到未处理的 action={}", action);
        }
    }

    private void handleAsrEnd(WebSocketSession session, SessionContext ctx) {
        byte[] pcm = ctx.snapshotAndClearPcm();
        if (pcm.length < 320) {
            ObjectNode node = JSON.createObjectNode();
            node.put("event", "asr_final");
            node.put("text", "");
            sendSafe(session, node);
            ObjectNode warnNode = JSON.createObjectNode();
            warnNode.put("event", "warning");
            warnNode.put("message", "没有听到声音，请按住说话后再点说完了");
            sendSafe(session, warnNode);
            return;
        }

        ObjectNode startNode = JSON.createObjectNode();
        startNode.put("event", "asr_start");
        sendSafe(session, startNode);

        // 线程池饱和（有界队列 + AbortPolicy）时降级为纯文本提示，不让 WebSocket 直接断开
        java.util.concurrent.CompletableFuture<java.util.Optional<String>> asrFuture;
        try {
            asrFuture = CompletableFuture.supplyAsync(() -> asrService.transcribePcm(pcm), ttsExecutor);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            ObjectNode busy = JSON.createObjectNode();
            busy.put("event", "error");
            busy.put("message", "语音通道繁忙，请稍后重试或改用文字提问");
            sendSafe(session, busy);
            ctx.isProcessing.set(false);
            return;
        }
        asrFuture
                .thenAccept(optText -> {
                    String text = optText.orElse("").trim();
                    ObjectNode finalNode = JSON.createObjectNode();
                    finalNode.put("event", "asr_final");
                    finalNode.put("text", text);
                    sendSafe(session, finalNode);

                    if (StringUtils.hasText(text)) {
                        processQuestion(session, ctx, text);
                    } else {
                        ObjectNode warnNode = JSON.createObjectNode();
                        warnNode.put("event", "warning");
                        warnNode.put("message", "未识别到清晰语音，请重试");
                        sendSafe(session, warnNode);
                    }
                })
                .exceptionally(ex -> {
                    log.error("[voice-stream-ws] ASR 处理失败", ex);
                    ObjectNode errNode = JSON.createObjectNode();
                    errNode.put("event", "error");
                    errNode.put("message", "语音转写异常，请重试");
                    sendSafe(session, errNode);
                    return null;
                });
    }

    private void processQuestion(WebSocketSession session, SessionContext ctx, String question) {
        ctx.interrupt();
        int epoch = ctx.currentEpoch.get();
        ctx.isProcessing.set(true);

        ObjectNode llmStart = JSON.createObjectNode();
        llmStart.put("event", "llm_start");
        llmStart.put("question", question);
        llmStart.put("epoch", epoch);
        sendSafe(session, llmStart);

        // 池饱和时降级为文本提示（有界队列 + AbortPolicy 会抛拒绝异常）
        try {
            CompletableFuture.runAsync(() -> {
            try {
                if (ctx.currentEpoch.get() != epoch) return;

                // 优先知识库或 AI 回答
                String fullAnswer = answerService.answer(question);
                if (!StringUtils.hasText(fullAnswer) && aiAnswerService.available()) {
                    fullAnswer = aiAnswerService.answer(question, "").orElse(null);
                }
                if (!StringUtils.hasText(fullAnswer)) {
                    fullAnswer = answerService.fallback();
                }

                if (ctx.currentEpoch.get() != epoch) return;

                // 使用流式分句检测器 (SBD) 将完整回答拆解为实时分句进行 Pipeline TTS
                SentenceBoundaryDetector detector = new SentenceBoundaryDetector();
                List<String> sentences = detector.append(fullAnswer);
                String tail = detector.finish();
                if (tail != null && !tail.isEmpty()) {
                    sentences.add(tail);
                }

                AtomicInteger seqCounter = new AtomicInteger(0);
                List<CompletableFuture<Void>> ttsFutures = new CopyOnWriteArrayList<>();

                for (String sentence : sentences) {
                    if (ctx.currentEpoch.get() != epoch) break;
                    int seqId = seqCounter.incrementAndGet();

                    CompletableFuture<Void> ttsTask = CompletableFuture.runAsync(() -> {
                        if (ctx.currentEpoch.get() != epoch) return;
                        try {
                            SupportTtsService.TtsResult ttsResult = ttsService.synthesize(sentence, "mp3");
                            if (ctx.currentEpoch.get() != epoch) return;

                            ObjectNode chunk = JSON.createObjectNode();
                            chunk.put("event", "tts_chunk");
                            chunk.put("sequenceId", seqId);
                            chunk.put("sentence", sentence);
                            chunk.put("audioBase64", ttsResult.audioBase64());
                            chunk.put("format", "mp3");
                            chunk.put("epoch", epoch);
                            sendSafe(session, chunk);
                        } catch (Exception e) {
                            log.warn("[voice-stream-ws] 分句 TTS 失败: sentence={} err={}", sentence, e.getMessage());
                        }
                    }, ttsExecutor);

                    ttsFutures.add(ttsTask);
                    ctx.activeTasks.add(ttsTask);
                }

                final String finalAnswer = fullAnswer;
                CompletableFuture.allOf(ttsFutures.toArray(new CompletableFuture[0]))
                        .whenComplete((v, ex) -> {
                            if (ctx.currentEpoch.get() == epoch) {
                                ObjectNode streamEnd = JSON.createObjectNode();
                                streamEnd.put("event", "stream_end");
                                streamEnd.put("epoch", epoch);
                                streamEnd.put("totalSentences", seqCounter.get());
                                streamEnd.put("fullText", finalAnswer);
                                sendSafe(session, streamEnd);
                                ctx.isProcessing.set(false);
                            }
                        });

            } catch (Exception e) {
                log.error("[voice-stream-ws] 问答链路异常", e);
                if (ctx.currentEpoch.get() == epoch) {
                    ObjectNode errNode = JSON.createObjectNode();
                    errNode.put("event", "error");
                    errNode.put("message", "处理失败，请稍后重试");
                    sendSafe(session, errNode);
                    ctx.isProcessing.set(false);
                }
            }
            }, ttsExecutor);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            ObjectNode busy = JSON.createObjectNode();
            busy.put("event", "error");
            busy.put("message", "语音通道繁忙，请稍后重试或改用文字提问");
            sendSafe(session, busy);
            ctx.isProcessing.set(false);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionContext ctx = sessions.remove(session.getId());
        if (ctx != null) {
            ctx.interrupt();
            ctx.clearPcm();
        }
        log.info("[voice-stream-ws] 客户端已断开 sessionId={} status={}", session.getId(), status);
    }

    private void sendSafe(WebSocketSession session, JsonNode json) {
        if (!session.isOpen()) return;
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(JSON.writeValueAsString(json)));
                }
            }
        } catch (IOException e) {
            log.debug("[voice-stream-ws] 消息发送失败: {}", e.getMessage());
        }
    }
}
