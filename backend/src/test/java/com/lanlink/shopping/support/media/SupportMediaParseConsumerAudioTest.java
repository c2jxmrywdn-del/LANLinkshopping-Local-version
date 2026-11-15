package com.lanlink.shopping.support.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语音消息解析链：上传不再止步于存储——转写必须进入理解链（asr_text 落库），
 * 且转写时长必须落计量账本（t_support_llm_usage.audio_seconds）。
 */
@ExtendWith(MockitoExtension.class)
class SupportMediaParseConsumerAudioTest {

    private static final String VISITOR = "2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f";

    @Mock
    private SupportMediaRepository repository;

    @Mock
    private SupportDocumentTextExtractor extractor;

    @Mock
    private SupportMediaPerceptionClient perception;

    @Mock
    private SupportLlmUsageRecorder usageRecorder;

    @Mock
    private S3Client s3;

    private SupportMediaParseConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SupportMediaParseConsumer(repository, extractor, perception, usageRecorder,
                new StorageProperties(), new ObjectMapper(), s3);
    }

    /** 消费链先读对象存储再解析：未 stub 的 s3.getObject 返回 null 会让 readObject 直接 NPE */
    private void stubObjectStorage() {
        ResponseInputStream<GetObjectResponse> in = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(
                        new ByteArrayInputStream("fake-audio-bytes".getBytes(StandardCharsets.UTF_8))));
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(in);
    }

    private SupportMediaRepository.Row audioRow() {
        return new SupportMediaRepository.Row(9L, "SM1", null, null, VISITOR, "AUDIO",
                "k", "voice.webm", "audio/webm", 1000L, "sha", null, null, null, null,
                SupportMediaRepository.STATUS_UPLOADED, null, null, null, null,
                null, null, null, 0, null, null, null);
    }

    @Test
    void audioTranscriptionEntersUnderstandingChainAndUsageLedger() {
        SupportMediaRepository.Row row = audioRow();
        lenient().when(repository.findById(9L)).thenReturn(Optional.of(row));
        when(repository.markParsing(9L)).thenReturn(1);
        stubObjectStorage();
        // audioRow 无 durationMs：走 cloud usage.seconds 落账
        when(perception.asr(any(), anyString())).thenReturn(Optional.of(
                new SupportMediaPerceptionClient.AsrResult("帮我查一下订单 SM123", "faster-whisper",
                        "medium", new BigDecimal("27.50"))));
        when(repository.markReady(eq(9L), isNull(), eq("帮我查一下订单 SM123"), anyString(),
                eq("faster-whisper"), any(), isNull(), isNull(), isNull())).thenReturn(1);

        consumer.consume("{\"mediaId\":9,\"mediaNo\":\"SM1\",\"kind\":\"AUDIO\",\"objectKey\":\"k\"}");

        ArgumentCaptor<BigDecimal> seconds = ArgumentCaptor.forClass(BigDecimal.class);
        verify(usageRecorder).recordAudio(eq(VISITOR), eq(9L), eq("medium"), seconds.capture());
        assertEquals(0, seconds.getValue().compareTo(new BigDecimal("27.50")));
        // 转写全文落 asr_text 列（理解链经 grounding 读取）
        verify(repository).markReady(eq(9L), isNull(), eq("帮我查一下订单 SM123"), anyString(),
                eq("faster-whisper"), any(), isNull(), isNull(), isNull());
    }

    @Test
    void audioFallsBackToRowDurationWhenUsageMissing() {
        SupportMediaRepository.Row row = new SupportMediaRepository.Row(9L, "SM1", null, null, VISITOR,
                "AUDIO", "k", "voice.webm", "audio/webm", 1000L, "sha", null, null, 27000, null,
                SupportMediaRepository.STATUS_UPLOADED, null, null, null, null,
                null, null, null, 0, null, null, null);
        lenient().when(repository.findById(9L)).thenReturn(Optional.of(row));
        when(repository.markParsing(9L)).thenReturn(1);
        stubObjectStorage();
        // 云端 usage 缺失（兜底链旧版响应）：按行时长 durationMs=27000ms 兜底
        when(perception.asr(any(), anyString())).thenReturn(Optional.of(
                new SupportMediaPerceptionClient.AsrResult("文本", "mimo", "mimo-v2.5-asr", null)));
        when(repository.markReady(eq(9L), isNull(), eq("文本"), anyString(), eq("mimo"),
                any(), isNull(), isNull(), isNull())).thenReturn(1);

        consumer.consume("{\"mediaId\":9,\"mediaNo\":\"SM1\",\"kind\":\"AUDIO\",\"objectKey\":\"k\"}");

        ArgumentCaptor<BigDecimal> seconds = ArgumentCaptor.forClass(BigDecimal.class);
        verify(usageRecorder).recordAudio(eq(VISITOR), eq(9L), eq("mimo-v2.5-asr"), seconds.capture());
        assertEquals(0, seconds.getValue().compareTo(new BigDecimal("27.000")));
    }

    @Test
    void audioWithoutTranscriptionFailsParse() {
        SupportMediaRepository.Row row = audioRow();
        lenient().when(repository.findById(9L)).thenReturn(Optional.of(row));
        when(repository.markParsing(9L)).thenReturn(1);
        stubObjectStorage();
        when(perception.asr(any(), anyString())).thenReturn(Optional.empty());
        when(repository.markRetry(eq(9L), eq(1), anyString())).thenReturn(1);
        doNothing().when(repository).insertOutbox(9L);

        // 转写服务不可用：handleFailure 写完重试状态后按约定把 RuntimeException 抛回容器触发重试
        assertThrows(BusinessException.class,
                () -> consumer.consume("{\"mediaId\":9,\"mediaNo\":\"SM1\",\"kind\":\"AUDIO\",\"objectKey\":\"k\"}"));

        // 不落账、不置 READY，走重试
        verify(usageRecorder, never()).recordAudio(anyString(), anyLong(), anyString(), any());
        verify(repository, never()).markReady(anyLong(), isNull(), any(), any(), any(), any(), isNull(), isNull(), isNull());
        verify(repository).markRetry(eq(9L), eq(1), anyString());
    }
}
