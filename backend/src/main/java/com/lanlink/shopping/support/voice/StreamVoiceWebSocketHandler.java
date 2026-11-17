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

    /**
     * ASR / LLM 阻塞链路线程池，与 TTS 池物理隔离。
     *
     * <p>历史缺陷：ASR、LLM 流式读、TTS 合成共用一个 16 线程池。一轮对话会同时占用
     * 「1 个 LLM 读线程 + N 个 TTS 线程」，多用户并发时 TTS 分片把队列占满，
     * 新的 ASR 请求直接 RejectedExecutionException → 前端「语音通道繁忙」。
     * 拆分后 ASR/LLM 永远有独立线程可用，TTS 饱和只影响播报速度。
     */
    private final ExecutorService workExecutor = new java.util.concurrent.ThreadPoolExecutor(
            8, 8, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(64),
            new java.util.concurrent.ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicInteger seq =
                        new java.util.concurrent.atomic.AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "support-voice-work-" + seq.incrementAndGet());
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
            // 竞态宽限：前端停止录音与 asr_end 几乎同刻发出，AudioWorklet 最后一帧
            // 可能还在投递途中。若此刻立刻判定「没听到声音」，会先弹 warning 退出思考态，
            // 紧接着真正的转写结果才到达（实测 3/3 复现）。
            // 这里等一个短宽限再取一次快照，仍为空才判定静音。
            retryAsrEndAfterGrace(session, ctx);
            return;
        }

        continueAsr(session, ctx, pcm);
    }

    /** 把一段 PCM 提交给 ASR 并把识别结果/错误推给前端。 */
    private void continueAsr(WebSocketSession session, SessionContext ctx, byte[] pcm) {
        ObjectNode startNode = JSON.createObjectNode();
        startNode.put("event", "asr_start");
        sendSafe(session, startNode);

        // 线程池饱和（有界队列 + AbortPolicy）时降级为纯文本提示，不让 WebSocket 直接断开
        java.util.concurrent.CompletableFuture<java.util.Optional<String>> asrFuture;
        try {
            // ASR 走独立工作池：与 TTS 池隔离，避免播报高峰把识别请求挤成「语音通道繁忙」
            asrFuture = CompletableFuture.supplyAsync(() -> asrService.transcribePcm(pcm), workExecutor);
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

    /**
     * 静音判定的宽限重试：给最后一帧 PCM 一点到达时间再判定。
     *
     * <p>竞态背景：浏览器侧 `stopListening()` 先 `cleanupRecordNodes()`（停采集）
     * 再发 `{"action":"asr_end"}`；但 AudioWorklet 的 postMessage 是异步的，
     * 最后一帧可能仍在消息队列中。服务端若在文本帧到达的同一刻取快照，
     * 会拿到 0 字节，于是先发 `asr_final('')` + `warning` 让前端退出思考态，
     * 随后真正的识别结果才到达——用户看到的是「明明说了话，却提示没听到」。
     *
     * <p>宽限 120ms（约 6 个 20ms 帧）足够覆盖本地事件循环抖动，又不会让
     * 真正的静音场景明显变慢。
     */
    private void retryAsrEndAfterGrace(WebSocketSession session, SessionContext ctx) {
        final long graceMs = 120L;
        CompletableFuture.delayedExecutor(graceMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> {
                    byte[] late = ctx.snapshotAndClearPcm();
                    if (late.length >= 320) {
                        // 迟到的音频到了：按正常路径继续识别
                        continueAsr(session, ctx, late);
                        return;
                    }
                    ObjectNode node = JSON.createObjectNode();
                    node.put("event", "asr_final");
                    node.put("text", "");
                    sendSafe(session, node);
                    ObjectNode warnNode = JSON.createObjectNode();
                    warnNode.put("event", "warning");
                    warnNode.put("message", "没有听到声音，请按住说话后再点说完了");
                    sendSafe(session, warnNode);
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
            CompletableFuture.runAsync(() -> runStreamingAnswer(session, ctx, question, epoch), workExecutor);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            ObjectNode busy = JSON.createObjectNode();
            busy.put("event", "error");
            busy.put("message", "语音通道繁忙，请稍后重试或改用文字提问");
            sendSafe(session, busy);
            ctx.isProcessing.set(false);
        }
    }

    /**
     * 流式作答主流程：LLM 逐 token → 句边界检测 → 立即并发 TTS → 保序下发。
     *
     * <p>历史实现先 `resolveAnswer()` 拿**整段**回答，再整体切句合成，首句延迟 =
     * LLM 完整生成时间 + 首次 TTS 时间（实测 8-20s）。改为逐 token 消费后，
     * 首个句号出现即可开始合成，首句延迟降到约「首个句号 + 单句 TTS」。
     *
     * <p>降级路径（知识库精确命中 / 模型不可用）走 {@link #emitWholeAnswer}，语义与原先一致。
     */
    private void runStreamingAnswer(WebSocketSession session, SessionContext ctx, String question, int epoch) {
        try {
            if (ctx.currentEpoch.get() != epoch) return;

            String kbEntryId = answerService.matchedEntryId(question);
            boolean kbHit = StringUtils.hasText(kbEntryId);

            AtomicInteger seqCounter = new AtomicInteger(0);
            List<CompletableFuture<Void>> ttsFutures = new CopyOnWriteArrayList<>();
            SentenceBoundaryDetector detector = new SentenceBoundaryDetector();

            // 句边界一旦确定就立刻投递 TTS，不等待后续 token
            java.util.function.Consumer<String> enqueueTts = sentence -> {
                if (ctx.currentEpoch.get() != epoch) return;
                String clean = sentence == null ? "" : sentence.trim();
                if (clean.isEmpty()) return;
                int seqId = seqCounter.incrementAndGet();
                CompletableFuture<Void> task;
                try {
                    task = CompletableFuture.runAsync(() -> synthesizeAndSend(session, ctx, epoch, seqId, clean),
                            ttsExecutor);
                } catch (java.util.concurrent.RejectedExecutionException rejected) {
                    log.warn("[voice-stream-ws] TTS 队列已满，跳过本句播报: {}", clean);
                    return;
                }
                ttsFutures.add(task);
                ctx.activeTasks.add(task);
            };

            String fullAnswer;
            if (kbHit) {
                // 知识库精确命中：无生成延迟，直接整段切句播报
                String kbAnswer = answerService.answer(question);
                fullAnswer = StringUtils.hasText(kbAnswer) ? kbAnswer : answerService.fallback();
                for (String s : detector.append(fullAnswer)) enqueueTts.accept(s);
            } else {
                StringBuilder collected = new StringBuilder();
                // 语音场景强制关思考：MiMo thinking=true 首 token 实测 ~16.6s，
                // thinking=false ~5.6s；等 16 秒才出声对语音对话不可接受。
                fullAnswer = aiAnswerService.answerStreamTokens(question, "",
                        delta -> {
                            if (ctx.currentEpoch.get() != epoch) return;
                            collected.append(delta);
                            for (String s : detector.append(delta)) enqueueTts.accept(s);
                        },
                        () -> ctx.currentEpoch.get() != epoch,
                        false);

                // 模型不可用 / 空回答：走知识库兜底（与 resolveAnswer 同语义）
                if (!StringUtils.hasText(fullAnswer) || !StringUtils.hasText(collected.toString())) {
                    fullAnswer = resolveAnswer(question);
                    for (String s : detector.append(fullAnswer)) enqueueTts.accept(s);
                } else {
                    fullAnswer = collected.toString();
                }
            }

            String tail = detector.finish();
            if (tail != null && !tail.isEmpty()) {
                enqueueTts.accept(tail);
            }

            final String finalAnswer = fullAnswer;
            CompletableFuture.allOf(ttsFutures.toArray(new CompletableFuture[0]))
                    .whenComplete((v, ex) -> {
                        if (ctx.currentEpoch.get() != epoch) return;
                        ObjectNode streamEnd = JSON.createObjectNode();
                        streamEnd.put("event", "stream_end");
                        streamEnd.put("epoch", epoch);
                        streamEnd.put("totalSentences", seqCounter.get());
                        streamEnd.put("fullText", finalAnswer);
                        sendSafe(session, streamEnd);
                        ctx.isProcessing.set(false);
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
    }

    /** 单句 TTS 合成并下发；epoch 变化（用户打断）时静默丢弃。 */
    private void synthesizeAndSend(WebSocketSession session, SessionContext ctx,
                                   int epoch, int seqId, String sentence) {
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

    /**
     * 语音问答的答案解析：知识库精确命中 → 大模型（MiMo）生成 → 兜底话术。
     *
     * <p>回归缺陷：历史实现是
     * <pre>
     *   String fullAnswer = answerService.answer(question);
     *   if (!StringUtils.hasText(fullAnswer) &amp;&amp; aiAnswerService.available()) { ... }
     * </pre>
     * 但 {@code SupportAnswerService.answer()} 在未命中知识库时返回的是**非空的兜底文案**，
     * 因此 {@code !hasText(...)} 恒为 false，MiMo 大模型分支永远进不去——语音对话实际
     * 只会念「抱歉，当前客服仅能解答…」。这里改为先判断是否真正命中知识库条目
     * （{@code matchedEntryId} 非空），未命中才交给大模型；两者都失败才用兜底文案。
     */
    String resolveAnswer(String question) {
        String kbEntryId = answerService.matchedEntryId(question);
        if (StringUtils.hasText(kbEntryId)) {
            String kbAnswer = answerService.answer(question);
            if (StringUtils.hasText(kbAnswer)) {
                return kbAnswer;
            }
        }
        if (aiAnswerService.available()) {
            String kbAnswer = answerService.answer(question);
            Optional<String> aiAnswer = aiAnswerService.answer(question, kbAnswer);
            if (aiAnswer.isPresent() && StringUtils.hasText(aiAnswer.get())) {
                return aiAnswer.get();
            }
        }
        String kbAnswer = answerService.answer(question);
        return StringUtils.hasText(kbAnswer) ? kbAnswer : answerService.fallback();
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
