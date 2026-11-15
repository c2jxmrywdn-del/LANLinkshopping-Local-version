package com.lanlink.shopping.support.voice;

import com.lanlink.shopping.support.media.SupportMediaPerceptionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * 语音识别（ASR）流式中继与转写服务。
 *
 * <p>输入口径：前端 AudioWorklet 采集的 16kHz 16-bit Mono 小端 PCM 原始流。
 * 本服务负责将 PCM 封装为规范的 WAV 格式，并调用感知客户端转发 MiMo ASR / 本地 faster-whisper 双层兜底。
 */
@Service
public class MimoAsrStreamService {

    private static final Logger log = LoggerFactory.getLogger(MimoAsrStreamService.class);
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNELS = 1;
    private static final int BITS_PER_SAMPLE = 16;

    private final SupportMediaPerceptionClient perceptionClient;

    public MimoAsrStreamService(SupportMediaPerceptionClient perceptionClient) {
        this.perceptionClient = perceptionClient;
    }

    /**
     * 将 16kHz 16-bit Mono PCM 字节流转写为文本。
     *
     * @param pcmBytes 原始 PCM 字节（小端）
     * @return 识别出的文本（识别失败或无语音时返回空 Optional）
     */
    public Optional<String> transcribePcm(byte[] pcmBytes) {
        if (pcmBytes == null || pcmBytes.length < 320) { // 少于 10ms 的帧直接忽略
            return Optional.empty();
        }
        byte[] wavBytes = pcm16ToWav(pcmBytes, SAMPLE_RATE, CHANNELS);
        try {
            return perceptionClient.asr(wavBytes, "audio/wav")
                    .map(SupportMediaPerceptionClient.AsrResult::text)
                    .map(String::trim)
                    .filter(t -> !t.isEmpty());
        } catch (Exception e) {
            log.warn("[voice-stream-asr] ASR 转写异常: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 为 16-bit PCM 裸流添加标准 44 字节 RIFF/WAVE 文件头。
     */
    public static byte[] pcm16ToWav(byte[] pcm, int sampleRate, int channels) {
        int byteRate = sampleRate * channels * (BITS_PER_SAMPLE / 8);
        int totalDataLen = pcm.length + 36;
        int blockAlign = channels * (BITS_PER_SAMPLE / 8);

        byte[] header = new byte[44];
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);

        // RIFF chunk descriptor
        buf.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        buf.putInt(totalDataLen);
        buf.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');

        // "fmt " sub-chunk
        buf.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        buf.putInt(16); // 16 for PCM
        buf.putShort((short) 1); // PCM = 1
        buf.putShort((short) channels);
        buf.putInt(sampleRate);
        buf.putInt(byteRate);
        buf.putShort((short) blockAlign);
        buf.putShort((short) BITS_PER_SAMPLE);

        // "data" sub-chunk
        buf.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        buf.putInt(pcm.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        try {
            out.write(header);
            out.write(pcm);
        } catch (IOException ignored) {}

        return out.toByteArray();
    }
}
