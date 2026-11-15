package com.lanlink.shopping.support.orchestrator;

import com.lanlink.shopping.support.media.SupportMediaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 附件接地：语音附件必须把「转写全文」（asr_text）而非 300 字截断摘要送进证据块——
 * 转写进入理解链是语音链路的核心语义，摘要压缩会丢掉用户口述的单号/金额细节。
 */
@ExtendWith(MockitoExtension.class)
class SupportMediaGroundingServiceTest {

    private static final String VISITOR = "2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f";

    @Mock
    private SupportMediaRepository repository;

    private SupportMediaRepository.Row audioRow(String mediaNo, String asrText, String summary) {
        return new SupportMediaRepository.Row(1L, mediaNo, null, null, VISITOR, "AUDIO",
                "k", "voice.webm", "audio/webm", 1000L, "sha", null, null, 27000, null,
                SupportMediaRepository.STATUS_READY, "faster-whisper", null, asrText, summary,
                null, new BigDecimal("0.8"), null, 0, null, null, null);
    }

    @Test
    void audioEvidencePrefersFullTranscriptOverTruncatedSummary() {
        String fullTranscript = "您好，我想咨询一下订单的问题。我昨天下午下了一单商品，订单号是 SM2026110988，"
                + "到现在物流一直没有更新，麻烦帮我查一下，这单我付了一百二十八块五。";
        String truncatedSummary = "您好，我想咨询一下订单的问题。我昨天下午下了一单商品，订单";
        when(repository.findByMediaNosAndVisitor(List.of("SM1"), VISITOR))
                .thenReturn(List.of(audioRow("SM1", fullTranscript, truncatedSummary)));

        SupportMediaGroundingService.Grounding grounding =
                new SupportMediaGroundingService(repository).build(List.of("SM1"), VISITOR);

        assertTrue(grounding.text().contains("SM2026110988"));
        assertTrue(grounding.text().contains("一百二十八块五"));
        assertTrue(grounding.text().contains("[M1]"));
        assertFalse(grounding.pending());
    }

    @Test
    void audioEvidenceTruncatesVeryLongTranscriptAtEightHundredChars() {
        String longText = "长".repeat(2000);
        when(repository.findByMediaNosAndVisitor(List.of("SM1"), VISITOR))
                .thenReturn(List.of(audioRow("SM1", longText, null)));

        SupportMediaGroundingService.Grounding grounding =
                new SupportMediaGroundingService(repository).build(List.of("SM1"), VISITOR);

        assertTrue(grounding.text().length() < 1000);
        assertTrue(grounding.text().endsWith("…"));
    }

    @Test
    void audioFallsBackToSummaryWhenTranscriptMissing() {
        when(repository.findByMediaNosAndVisitor(List.of("SM1"), VISITOR))
                .thenReturn(List.of(audioRow("SM1", "  ", "语音摘要内容")));

        SupportMediaGroundingService.Grounding grounding =
                new SupportMediaGroundingService(repository).build(List.of("SM1"), VISITOR);

        assertTrue(grounding.text().contains("语音摘要内容"));
    }

    @Test
    void pendingWhenAudioStillParsing() {
        when(repository.findByMediaNosAndVisitor(List.of("SM1"), VISITOR))
                .thenReturn(List.of());

        SupportMediaGroundingService.Grounding grounding =
                new SupportMediaGroundingService(repository).build(List.of("SM1"), VISITOR);

        assertEquals("", grounding.text());
        assertTrue(grounding.pending());
    }

    // ==================== P2：partial 显式化与 32K 证据预算 ====================

    private SupportMediaRepository.Row docRow(String mediaNo, String summary, String entitiesJson) {
        return new SupportMediaRepository.Row(2L, mediaNo, null, null, VISITOR, "DOCUMENT",
                "k", "sample.pdf", "application/pdf", 2000L, "sha", null, null, null, 3,
                SupportMediaRepository.STATUS_READY, "pdfbox", null, null, summary,
                entitiesJson, new BigDecimal("0.8"), null, 0, null, null, null);
    }

    @Test
    void partialEntityAddsExplicitTruncationNoteToEvidence() {
        String entities = "{\"partial\":true,\"partialReason\":\"压缩包超过 20 个条目，仅解析前 20 个\"}";
        when(repository.findByMediaNosAndVisitor(List.of("SM2"), VISITOR))
                .thenReturn(List.of(docRow("SM2", "压缩包解析摘要", entities)));

        SupportMediaGroundingService.Grounding grounding =
                new SupportMediaGroundingService(repository).build(List.of("SM2"), VISITOR);

        // partial 说明必须出现在证据块内，让模型知道证据不完整（消灭静默丢弃）
        assertTrue(grounding.text().contains("仅解析了部分内容"));
        assertTrue(grounding.text().contains("仅解析前 20 个"));
    }

    @Test
    void totalEvidenceBudgetCappedFor32kContext() {
        // 3 份 800 字上限文档：总预算 10_000 字符内应全部装下且不触发截断说明
        List<SupportMediaRepository.Row> rows = List.of(
                docRow("SM1", "文".repeat(800), null),
                docRow("SM2", "档".repeat(800), null),
                docRow("SM3", "证".repeat(800), null));
        when(repository.findByMediaNosAndVisitor(List.of("SM1", "SM2", "SM3"), VISITOR))
                .thenReturn(rows);

        SupportMediaGroundingService.Grounding grounding =
                new SupportMediaGroundingService(repository)
                        .build(List.of("SM1", "SM2", "SM3"), VISITOR);

        assertTrue(grounding.text().contains("[M1]"));
        assertTrue(grounding.text().contains("[M3]"));
        assertFalse(grounding.text().contains("长度上限截断"));
    }

    @Test
    void evidenceBatchCappedAtTenAttachments() {
        // MAX_MEDIA=10：一次提问最多带 10 份附件进证据块（规划口径），多出的单号忽略
        java.util.List<String> mediaNos = new java.util.ArrayList<>();
        java.util.List<SupportMediaRepository.Row> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 14; i++) {
            String no = "SM" + i;
            mediaNos.add(no);
            rows.add(docRow(no, "文档" + i, null));
        }
        when(repository.findByMediaNosAndVisitor(mediaNos.subList(0, 10), VISITOR)).thenReturn(rows);

        SupportMediaGroundingService.Grounding grounding =
                new SupportMediaGroundingService(repository).build(mediaNos, VISITOR);

        // 传入 14 个单号，repository 只收到前 10 个；证据块最多编到 [M10]
        assertTrue(grounding.text().contains("[M10]"));
        assertFalse(grounding.text().contains("[M11]"));
        // 前 10 个单号全部 READY：不误报 pending
        assertFalse(grounding.pending());
    }
}
