package com.lanlink.shopping.module.aikb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.module.pricemonitor.entity.MarketNews;
import com.lanlink.shopping.module.pricemonitor.service.MarketNewsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 网关资讯管线编排（runGatewayPipeline）单测：HTTP 层用子类覆盖（不发真实请求），
 * 验证入库放行矩阵与回退行为。
 *
 * <p>放行矩阵（与网关 flags.stored 单一口径）：verdict∈{unique, unavailable} 且
 * flags.stored=true → existsByTitle 快查 → toMarketNews（填 originUrl/fingerprint）
 * → saveExternal → gatewayProcessed++；needs_review → 不调 saveExternal、gatewayReview++；
 * exact/near_duplicate → 不调 saveExternal、gatewayDropped++；网关失败 → gatewayFailed++
 * 且 FALLBACK=true 回退 fetchNow。
 */
class ExternalInfoFetchServiceGatewayPipelineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 组装 service：灰度开关经 ReflectionTestUtils 注入（真实字段为 @Value 字段注入）。 */
    private static ExternalInfoFetchService newService(MarketNewsService newsService,
                                                       KnowledgeIngestService ingestService,
                                                       boolean pipelineEnabled,
                                                       boolean fallback) {
        ExternalInfoFetchService service = new ExternalInfoFetchService(
                newsService, ingestService, null, true,
                "https://www.ithome.com/rss/", 30, false, false);
        ReflectionTestUtils.setField(service, "newsPipelineEnabled", pipelineEnabled);
        ReflectionTestUtils.setField(service, "newsPipelineFallback", fallback);
        return service;
    }

    /** 网关单条 results 元素（与 /v1/news/extract 响应 data 形状一致的关键字段）。 */
    private static String gatewayItem(String verdict, boolean stored, String title,
                                      String bodySha, String originUrl) {
        return """
                {"ok": true, "data": {"article": {"headline": "%s",
                    "url": "https://www.ithome.com/0/1.htm", "originUrl": "%s",
                    "publishedAt": "2026-11-17T08:00:00+08:00",
                    "body": "螺纹钢现货报价上调，贸易商出货节奏加快，社会库存去化提速明显。"},
                  "source": {"originSite": "www.ithome.com", "redirectChain": []},
                  "robots": {"checked": [], "allowed": true},
                  "fingerprint": {"bodySha256": "%s", "titleSha256": "abc", "simhash": "0123456789abcdef",
                     "hammingHits": null},
                  "noise": {"extractor": "trafilatura", "noiseStripped": true, "rawChars": 3000, "bodyChars": 2800},
                  "dedup": {"verdict": "%s", "matchedId": null, "relatedHint": null, "windowHours": 72},
                  "flags": {"needsReview": false, "degraded": false, "stored": %s},
                  "aiGateway": {"elapsedMs": 1200, "degraded": false, "errors": []}}}
                """.formatted(title, originUrl == null ? "https://www.ithome.com/0/1.htm" : originUrl,
                bodySha, verdict, stored).replace("\n", " ");
    }

    /** 从 results 元素 JSON 里取出 data 节点（persistGatewayArticle 直调时的入参形状）。 */
    private static JsonNode itemJson(String item) {
        try {
            return MAPPER.readTree(item).path("data");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== 放行矩阵 ====================

    @Test
    void uniqueStoredTrueSavesWithOriginUrlAndFingerprint() {
        MarketNewsService newsService = mock(MarketNewsService.class);
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        when(newsService.existsByTitle(anyString())).thenReturn(false);
        when(newsService.saveExternal(any())).thenReturn(true);
        when(ingest.ingestNews()).thenReturn(1);

        ExternalInfoFetchService service = newService(newsService, ingest, true, true);
        GatewayStub.stub(service, List.of(
                gatewayItem("unique", true, "螺纹钢现货报价上调", "aa".repeat(32), null)));
        Map<String, Object> stats = service.runGatewayPipeline();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MarketNews> captor = ArgumentCaptor.forClass(MarketNews.class);
        verify(newsService, times(1)).saveExternal(captor.capture());
        MarketNews saved = captor.getValue();
        assertEquals("螺纹钢现货报价上调", saved.getTitle());
        assertEquals("https://www.ithome.com/0/1.htm", saved.getUrl());
        // originUrl/fingerprint 必须实写（uk_news_fingerprint 兜底生效的前提）
        assertEquals("https://www.ithome.com/0/1.htm", saved.getOriginUrl());
        assertEquals("aa".repeat(32), saved.getFingerprint());
        assertEquals(1, stats.get("gatewayProcessed"));
        assertEquals(0, stats.get("gatewayDropped"));
        assertEquals(0, stats.get("gatewayReview"));
    }

    @Test
    void unavailableStoredTrueStillSavesAndDuplicateTitleBlocked() {
        MarketNewsService newsService = mock(MarketNewsService.class);
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        when(newsService.saveExternal(any())).thenReturn(true);
        // 同题重发：第一次 existsByTitle 未命中放行，第二次命中（Redis 挂时 Java 侧兜底）
        when(newsService.existsByTitle("螺纹钢现货报价上调")).thenReturn(false, true);

        ExternalInfoFetchService service = newService(newsService, ingest, true, true);
        GatewayStub.stub(service, List.of(
                gatewayItem("unavailable", true, "螺纹钢现货报价上调", "bb".repeat(32), null)));
        service.runGatewayPipeline();
        verify(newsService, times(1)).saveExternal(any());

        // 同题重发（模拟 Redis 挂时的第二同题原始 URL）：existsByTitle 兜底拦住，不产生第二行
        boolean saved = service.persistGatewayArticle(
                itemJson(gatewayItem("unavailable", true, "螺纹钢现货报价上调", "bb".repeat(32), null)));
        assertFalse(saved, "同题重发必须被 existsByTitle 兜底拦住");
        verify(newsService, times(1)).saveExternal(any());
    }

    @Test
    void needsReviewDoesNotSaveAndIncrementsReview() {
        MarketNewsService newsService = mock(MarketNewsService.class);
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        ExternalInfoFetchService service = newService(newsService, ingest, true, true);
        GatewayStub.stub(service, List.of(
                gatewayItem("needs_review", false, "水泥价格连续三周上行", "cc".repeat(32), null)));
        Map<String, Object> stats = service.runGatewayPipeline();
        verify(newsService, never()).saveExternal(any());
        assertEquals(0, stats.get("gatewayProcessed"));
        assertEquals(1, stats.get("gatewayReview"));
    }

    @Test
    void exactAndNearDuplicateDoNotSaveAndIncrementDropped() {
        MarketNewsService newsService = mock(MarketNewsService.class);
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        ExternalInfoFetchService service = newService(newsService, ingest, true, true);
        GatewayStub.stub(service, List.of(
                gatewayItem("exact_duplicate", false, "重复文一", "dd".repeat(32), null),
                gatewayItem("near_duplicate", false, "重复文二", "ee".repeat(32), null)));
        Map<String, Object> stats = service.runGatewayPipeline();
        verify(newsService, never()).saveExternal(any());
        assertEquals(2, stats.get("gatewayDropped"));
        assertEquals(0, stats.get("gatewayProcessed"));
        assertEquals(0, stats.get("gatewayReview"));
    }

    // ==================== 回退与开关 ====================

    @Test
    void gatewayFailureFallsBackToLegacyFetchNow() {
        MarketNewsService newsService = mock(MarketNewsService.class);
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        // 回退走的 fetchNow 会对真实 RSS 源做直抓入库（本单测环境 relevanceFilter 关、
        // 词表空直通），RSS 抓取结果随日历/网络漂移，不可断言其入库次数——只断言
        // 「发生了回退」：gatewayFailed=1 且 stats 带 notice 之外的 fetchNow 形状。
        when(newsService.saveExternal(any())).thenReturn(true);
        ExternalInfoFetchService service = newService(newsService, ingest, true, true);
        GatewayStub.stub(service, List.of(), true);
        Map<String, Object> stats = service.runGatewayPipeline();
        assertEquals(1, stats.get("gatewayFailed"));
        assertTrue(stats.containsKey("fetched"), "回退后应返回 fetchNow 形状的 stats");
    }

    @Test
    void gatewayFailureWithoutFallbackSkipsRun() {
        MarketNewsService newsService = mock(MarketNewsService.class);
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        ExternalInfoFetchService service = newService(newsService, ingest, true, false);
        GatewayStub.stub(service, List.of(), true);
        Map<String, Object> stats = service.runGatewayPipeline();
        assertEquals(1, stats.get("gatewayFailed"));
        // FALLBACK=false 不回退：lastFetched 保持 0（回退时 fetchNow 会有 feeds 维度写 lastFetched）
        assertEquals(0, stats.get("fetched"), "FALLBACK=false 不回退，fetched 保持 0");
        assertEquals(0, stats.get("gatewayProcessed"));
    }

    @Test
    void pipelineDisabledGoesStraightToLegacyFetch() {
        MarketNewsService newsService = mock(MarketNewsService.class);
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        ExternalInfoFetchService service = newService(newsService, ingest, false, true);
        Map<String, Object> stats = service.runGatewayPipeline();
        // 开关关闭：方法内直接 fetchNow（无候选收集，stats 恒为 fetchNow 形状）
        assertTrue(stats.containsKey("fetched"), "开关关闭直走 fetchNow 既有链路");
        assertEquals(0, stats.get("gatewayFailed"));
    }

    @Test
    void sameFingerprintSecondInsertReturnsFalseViaSaveExternal() {
        MarketNewsService newsService = mock(MarketNewsService.class);
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        // saveExternal 内部唯一键冲突返回 false（MarketNewsService :155 语义）
        when(newsService.saveExternal(any())).thenReturn(true, false);
        when(newsService.existsByTitle(anyString())).thenReturn(false);

        ExternalInfoFetchService service = newService(newsService, ingest, true, true);
        GatewayStub.stub(service, List.of(
                gatewayItem("unique", true, "同指纹文A", "ff".repeat(32), null),
                gatewayItem("unique", true, "同指纹文B", "ff".repeat(32), null)));
        Map<String, Object> stats = service.runGatewayPipeline();
        assertEquals(1, stats.get("gatewayProcessed"), "第二条唯一键冲突 → dropped");
        assertEquals(1, stats.get("gatewayDropped"));
    }

    // ==================== 测试桩 ====================

    /**
     * GatewayStub：把 postGatewayBatch 覆盖为「返回注入的 results JSON」（batchFail=true 时
     * 返回 null 模拟网关连接失败/5xx/超时），候选收集固定 1 条 URL——全部不发真实 HTTP。
     */
    private static final class GatewayStub {

        static void stub(ExternalInfoFetchService service, List<String> resultsJson) {
            stub(service, resultsJson, false);
        }

        static void stub(ExternalInfoFetchService service, List<String> resultsJson,
                         boolean batchFail) {
            ReflectionTestUtils.setField(service, "stubResults", resultsJson);
            ReflectionTestUtils.setField(service, "stubBatchFail", batchFail);
            ReflectionTestUtils.setField(service, "stubCandidates",
                    List.of("https://www.ithome.com/0/1.htm"));
        }
    }
}
