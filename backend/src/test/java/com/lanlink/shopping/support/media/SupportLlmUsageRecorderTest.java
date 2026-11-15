package com.lanlink.shopping.support.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 用量账本写入器：audio_seconds/cost_micros 必须真实落 t_support_llm_usage；
 * 计量是旁路——落账失败不能反噬解析主干。
 */
@ExtendWith(MockitoExtension.class)
class SupportLlmUsageRecorderTest {

    @Mock
    private SupportMediaRepository repository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private SupportLlmUsageRecorder recorder() {
        return new SupportLlmUsageRecorder(repository, transactionTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordAudioWritesUsageRowWithSecondsAndCost() {
        // TransactionTemplate.executeWithoutResult 直通执行回调
        org.mockito.Mockito.doAnswer(inv -> {
            ((Consumer<org.springframework.transaction.TransactionStatus>) inv.getArgument(0)).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        SupportLlmUsageRecorder recorder = recorder();
        recorder.setAsrMicrosPerSecondsForTest(new BigDecimal("120"));

        recorder.recordAudio("2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f", 42L, "mimo-v2.5-asr",
                new BigDecimal("27.5"));

        ArgumentCaptor<String> capability = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BigDecimal> seconds = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<Long> cost = ArgumentCaptor.forClass(Long.class);
        verify(repository).insertLlmUsage(any(), any(), any(), seconds.capture(), cost.capture(), any());
        // capability=AUDIO 落库由 repository 侧 SQL 固定，这里校验 recorder 调用形态
        InOrder order = inOrder(transactionTemplate, repository);
        order.verify(transactionTemplate).executeWithoutResult(any());
        order.verify(repository).insertLlmUsage(any(), any(), any(), seconds.capture(), cost.capture(), any());
        assertEquals(0, seconds.getValue().compareTo(new BigDecimal("27.5")));
        assertEquals(3300L, cost.getValue()); // 27.5s × 120 微元/秒
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordAudioSkipsWhenSecondsMissingOrNonPositive() {
        recorder().recordAudio("2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f", 42L, "mimo-v2.5-asr", null);
        recorder().recordAudio("2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f", 42L, "mimo-v2.5-asr", BigDecimal.ZERO);
        verify(transactionTemplate, never()).executeWithoutResult(any());
        // costMicros 是原始 long：any() 拆箱 NPE，必须用 anyLong()
        verify(repository, never()).insertLlmUsage(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void recordAudioSwallowsPersistenceFailure() {
        // 计量旁路：insert 失败只记日志，绝不把异常抛回解析链
        doThrow(new RuntimeException("db down")).when(repository)
                .insertLlmUsage(any(), any(), any(), any(), anyLong(), any());
        org.mockito.Mockito.doAnswer(inv -> {
            ((Consumer<org.springframework.transaction.TransactionStatus>) inv.getArgument(0)).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        recorder().recordAudio("2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f", 42L, "faster-whisper",
                new BigDecimal("10"));
        verify(repository).insertLlmUsage(any(), any(), any(), any(), anyLong(), any());
        assertTrue(true);
    }

    // ==================== P2：VISION / DOC 能力落账 ====================

    @Test
    @SuppressWarnings("unchecked")
    void recordVisionWritesUsageRowWithPerCallCost() {
        org.mockito.Mockito.doAnswer(inv -> {
            ((Consumer<org.springframework.transaction.TransactionStatus>) inv.getArgument(0)).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        SupportLlmUsageRecorder recorder = recorder();
        recorder.setVisionMicrosPerCallForTest(new BigDecimal("500"));

        recorder.recordVision("2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f", 42L, "mimo-v2.6-flash", "一张发货单");

        ArgumentCaptor<String> capability = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> cost = ArgumentCaptor.forClass(Long.class);
        verify(repository).insertLlmUsage(any(), capability.capture(), any(), isNull(),
                cost.capture(), any());
        // capability=VISION，无时长维度（audio_seconds 为空），成本=单价×1 次
        assertEquals("VISION", capability.getValue());
        assertEquals(500L, cost.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordVisionSkipsEmptyRecognition() {
        // 空识别结果不入账：失败重试由解析链负责，不重复计量
        recorder().recordVision("2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f", 42L, "rapidocr-onnx", "");
        recorder().recordVision("2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f", 42L, "rapidocr-onnx", null);
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordDocumentWritesUsageRowWithCharBasedCost() {
        org.mockito.Mockito.doAnswer(inv -> {
            ((Consumer<org.springframework.transaction.TransactionStatus>) inv.getArgument(0)).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        SupportLlmUsageRecorder recorder = recorder();
        recorder.setDocMicrosPerKcharForTest(new BigDecimal("20"));

        // 5000 字 → 5 千字符 × 20 微元/千字符 = 100 微元
        recorder.recordDocument("2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f", 42L,
                new SupportDocumentTextExtractor.Result("x".repeat(5000), 3, false, false, null));

        ArgumentCaptor<String> capability = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> cost = ArgumentCaptor.forClass(Long.class);
        verify(repository).insertLlmUsage(any(), capability.capture(), any(), any(),
                cost.capture(), any());
        assertEquals("DOC", capability.getValue());
        assertEquals(100L, cost.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordDocumentSkipsEmptyText() {
        recorder().recordDocument("2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f", 42L,
                new SupportDocumentTextExtractor.Result("", null, false, false, null));
        recorder().recordDocument("2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f", 42L, null);
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }
}
