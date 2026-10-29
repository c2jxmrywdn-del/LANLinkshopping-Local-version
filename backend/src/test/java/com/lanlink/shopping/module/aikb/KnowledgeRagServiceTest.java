package com.lanlink.shopping.module.aikb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RAG 混合检索单测（mock 嵌入/向量库/路由器，不触达 Redis/Ollama/网络）：
 *   - lexicalScore 中文 char-bigram / 英文词元包含度；
 *   - searchHybrid 0.6*向量 + 0.4*词面 重排（词面相关候选可反超高向量分候选）；
 *   - 候选为空 / 向量召回异常时安全回退、不抛错；
 *   - buildContext 的 minScore 过滤与英文语料优先逻辑；
 *   - search() 走 hybrid 排序。
 */
class KnowledgeRagServiceTest {

    private EmbeddingService embeddingService;
    private RedisVectorStore store;
    private AiModelRouter router;
    private KnowledgeRagService service;

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        store = mock(RedisVectorStore.class);
        router = mock(AiModelRouter.class);
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(embeddingService.getProvider()).thenReturn("ollama:nomic-embed-text");
        service = new KnowledgeRagService(embeddingService, store, router);
    }

    private static RedisVectorStore.Hit hit(String id, String source, String title, String text, double score) {
        return new RedisVectorStore.Hit(id, source, "ref-" + id, title, text, score);
    }

    // ===== 中文词面分 =====

    @Test
    void lexicalScoreHighWhenChineseBigramsOverlap() {
        double s = KnowledgeRagService.lexicalScore("企业账期怎么申请", "企业账期授信", "企业账期申请流程与还款方式");
        // query 双字组：企业/业账/账期/期怎/怎么/么申/申请 = 7，候选命中 4 → 4/7
        assertEquals(4.0 / 7.0, s, 1e-9, "与标题/正文共享中文双字组应得高分: " + s);
    }

    @Test
    void lexicalScoreZeroWhenUnrelated() {
        double s = KnowledgeRagService.lexicalScore("企业账期怎么申请", "购物车结算", "购物车结算与订单支付管理");
        assertEquals(0.0, s, 1e-9, "完全不相关的中文文本词面分应为 0");
    }

    @Test
    void lexicalScoreCountsEnglishAndDigitsTokens() {
        double s = KnowledgeRagService.lexicalScore("Enterprise Payment terms", "Enterprise payment", "Terms and conditions for payment");
        assertEquals(1.0, s, 1e-9, "英文词元应大小写不敏感、按词包含匹配");
        assertTrue(KnowledgeRagService.lexicalScore("B2B 账期", "企业 B2B 授信", "B2B 账期说明") > 0.5,
                "数字/字母混合词元也应参与匹配");
    }

    @Test
    void lexicalScoreHandlesNullAndBlank() {
        assertEquals(0.0, KnowledgeRagService.lexicalScore(null, "标题", "正文"), 1e-9);
        assertEquals(0.0, KnowledgeRagService.lexicalScore("   ", "标题", "正文"), 1e-9);
        assertEquals(0.0, KnowledgeRagService.lexicalScore("企业账期", null, null), 1e-9);
        assertEquals(0.0, KnowledgeRagService.lexicalScore("。。。!!", "标题", "正文"), 1e-9);
    }

    @Test
    void lexicalTokensSplitsCjkBigramsAndAsciiWords() {
        List<String> tokens = KnowledgeRagService.lexicalTokens("企业账期 a1 流程");
        assertTrue(tokens.containsAll(List.of("企业", "业账", "账期", "a1", "流程")), "词元切分: " + tokens);
    }

    // ===== hybrid 重排 =====

    @Test
    void searchHybridReranksLexicallyRelevantAboveHigherVectorScore() {
        RedisVectorStore.Hit unrelated = hit("id-a", "kb", "购物车结算", "购物车结算与订单支付", 0.90);
        RedisVectorStore.Hit relevant = hit("id-b", "kb", "企业账期授信", "企业账期申请流程与还款方式", 0.85);
        RedisVectorStore.Hit other = hit("id-c", "news", "行业资讯", "本周行业新闻汇总", 0.70);
        when(store.search(any(float[].class), anyInt(), any()))
                .thenReturn(List.of(unrelated, relevant, other));

        List<RedisVectorStore.Hit> out = service.searchHybrid("企业账期怎么申请", 2, null);

        assertEquals(2, out.size());
        assertEquals("id-b", out.get(0).id(), "词面相关候选应被重排到 top1");
        assertEquals("id-a", out.get(1).id());
        // 0.6*0.85 + 0.4*(4/7) = 0.738571… → 四舍五入 4 位
        assertEquals(0.7386, out.get(0).score(), 1e-9);
        assertEquals(0.54, out.get(1).score(), 1e-9);
        // 其它字段保持不变
        assertEquals("企业账期授信", out.get(0).title());
        assertEquals("企业账期申请流程与还款方式", out.get(0).text());
        assertEquals("ref-id-b", out.get(0).refId());
        assertEquals("kb", out.get(0).source());
        // 向量召回倍数：topK=2、无来源过滤 → recall=min(2*6,60)=12（过滤 intent 语料需更大候选池）
        verify(store).search(any(float[].class), eq(12), any());
    }

    @Test
    void searchHybridExcludesIntentCorpusFromGeneralSearch() {
        RedisVectorStore.Hit intent = hit("id-intent", "intent", "退款与售后", "退款什么时候到账", 0.99);
        RedisVectorStore.Hit policy = hit("id-policy", "policy", "退款政策", "退款到账时间说明", 0.70);
        when(store.search(any(float[].class), anyInt(), any()))
                .thenReturn(List.of(intent, policy));

        List<RedisVectorStore.Hit> out = service.searchHybrid("退款多久到账", 3, null);

        assertEquals(1, out.size(), "无来源过滤时应排除 intent 示例语料");
        assertEquals("id-policy", out.get(0).id());
        // 显式 source=intent 时仍可检索到（意图分类链路不走这里，但保持接口语义）
        when(store.search(any(float[].class), anyInt(), eq("intent")))
                .thenReturn(List.of(intent));
        assertEquals(1, service.searchHybrid("退款多久到账", 3, "intent").size());
    }

    @Test
    void plainSearchUsesHybridOrdering() {
        RedisVectorStore.Hit unrelated = hit("id-a", "kb", "购物车结算", "购物车结算与订单支付", 0.90);
        RedisVectorStore.Hit relevant = hit("id-b", "kb", "企业账期授信", "企业账期申请流程与还款方式", 0.85);
        when(store.search(any(float[].class), anyInt(), any()))
                .thenReturn(List.of(unrelated, relevant));

        List<RedisVectorStore.Hit> out = service.search("企业账期怎么申请", 2, null);
        assertEquals(2, out.size());
        assertEquals("id-b", out.get(0).id(), "/kb/search 等现有链路应使用 hybrid 排序");
    }

    @Test
    void searchHybridPassesSourceFilterThrough() {
        when(store.search(any(float[].class), anyInt(), eq("policy")))
                .thenReturn(List.of(hit("id-p", "policy", "企业账期", "企业账期申请流程", 0.8)));
        List<RedisVectorStore.Hit> out = service.searchHybrid("企业账期怎么申请", 3, "policy");
        assertEquals(1, out.size());
        assertEquals("id-p", out.get(0).id());
    }

    // ===== 回退与异常安全 =====

    @Test
    void searchHybridReturnsEmptyWhenNoCandidates() {
        when(store.search(any(float[].class), anyInt(), any())).thenReturn(List.of());
        List<RedisVectorStore.Hit> out = assertDoesNotThrow(() -> service.searchHybrid("企业账期怎么申请", 5, null));
        assertTrue(out.isEmpty());
    }

    @Test
    void searchHybridDoesNotThrowWhenStoreFails() {
        when(store.search(any(float[].class), anyInt(), any()))
                .thenThrow(new RuntimeException("redis down"));
        List<RedisVectorStore.Hit> out = assertDoesNotThrow(() -> service.searchHybrid("企业账期怎么申请", 5, null));
        assertTrue(out.isEmpty(), "召回与回退都失败时返回空结果，不向上抛异常");
    }

    @Test
    void searchHybridFallsBackToPlainTopKWhenRecallFails() {
        RedisVectorStore.Hit plain = hit("id-plain", "kb", "企业账期", "企业账期申请流程", 0.33);
        when(store.search(any(float[].class), anyInt(), any()))
                .thenThrow(new RuntimeException("recall boom"))
                .thenReturn(List.of(plain));

        List<RedisVectorStore.Hit> out = assertDoesNotThrow(() -> service.searchHybrid("企业账期怎么申请", 5, null));
        assertEquals(1, out.size());
        assertEquals("id-plain", out.get(0).id());
        assertEquals(0.33, out.get(0).score(), 1e-9, "回退路径应返回原向量结果（不再重排）");
    }

    // ===== buildContext =====

    @Test
    void buildContextFiltersByMinScoreAndUsesHybridOrdering() {
        RedisVectorStore.Hit relevant = hit("id-b", "kb", "企业账期", "企业账期申请流程与还款方式", 1.0);
        RedisVectorStore.Hit unrelated = hit("id-a", "kb", "购物车", "购物车结算与订单支付", 0.0);
        when(store.search(any(float[].class), anyInt(), any()))
                .thenReturn(List.of(relevant, unrelated));

        KnowledgeRagService.RagContext ctx = service.buildContext("企业账期怎么申请", 5, 0.5);

        assertEquals(1, ctx.hits(), "低于 minScore 的候选应被过滤");
        assertTrue(ctx.text().contains("企业账期申请流程"), "上下文应包含命中的资料块: " + ctx.text());
        assertFalse(ctx.text().contains("购物车结算"), "无关资料不应进入上下文");
        // 0.6*1.0 + 0.4*(4/7) = 0.828571… → 0.8286
        assertEquals(0.8286, ctx.topScore(), 1e-9);
        // buildContext 加大召回：先按 max(5*4, 20)=20 取 hybrid 结果；searchHybrid 内部再 6 倍/封顶 60 向量召回
        verify(store).search(any(float[].class), eq(60), any());
    }

    @Test
    void buildContextReturnsEmptyWhenBelowMinScore() {
        when(store.search(any(float[].class), anyInt(), any()))
                .thenReturn(List.of(hit("id-x", "kb", "购物车", "购物车结算", 0.4)));
        KnowledgeRagService.RagContext ctx = service.buildContext("企业账期怎么申请", 3, 0.9);
        assertEquals(0, ctx.hits());
        assertTrue(ctx.text().isEmpty());
        assertTrue(ctx.topScore() > 0);
        assertFalse(ctx.usable());
    }

    @Test
    void buildContextPrefersLatinBlocksForEnglishQuestions() {
        RedisVectorStore.Hit zh = hit("id-zh", "kb", "企业账期", "企业账期申请流程与还款方式", 0.95);
        RedisVectorStore.Hit en = hit("id-en", "kb", "Enterprise payment", "terms and conditions for enterprise payment", 0.70);
        when(store.search(any(float[].class), anyInt(), any())).thenReturn(List.of(zh, en));

        KnowledgeRagService.RagContext ctx = service.buildContext("enterprise payment terms", 5, 0.0, true);

        assertEquals(1, ctx.hits(), "英文提问应优先使用拉丁语料块");
        assertTrue(ctx.text().contains("Enterprise payment"), "应保留英文资料块: " + ctx.text());
        assertFalse(ctx.text().contains("企业账期"), "中文块在英文提问下应被排除");
        assertEquals(0.82, ctx.topScore(), 1e-9);
    }

    @Test
    void buildContextUsesAllBlocksWhenNoLatinAvailable() {
        RedisVectorStore.Hit zh = hit("id-zh", "kb", "企业账期", "企业账期申请流程与还款方式", 1.0);
        when(store.search(any(float[].class), anyInt(), any())).thenReturn(List.of(zh));
        KnowledgeRagService.RagContext ctx = service.buildContext("enterprise payment terms", 5, 0.0, true);
        assertEquals(1, ctx.hits(), "无拉丁语料时应退回全部命中块");
        assertTrue(ctx.text().contains("企业账期申请流程"));
    }

    // ===== 来源权威度权重（news 降权，避免 WPS/京东PLUS 之类资讯混入事实问答） =====

    @Test
    void sourceWeightsMatchPolicy() {
        assertEquals(1.0, KnowledgeRagService.sourceWeight("kb"), 1e-9);
        assertEquals(1.0, KnowledgeRagService.sourceWeight("policy"), 1e-9);
        assertEquals(1.0, KnowledgeRagService.sourceWeight("product"), 1e-9);
        assertEquals(1.0, KnowledgeRagService.sourceWeight("membership"), 1e-9);
        assertEquals(1.0, KnowledgeRagService.sourceWeight("subscription"), 1e-9);
        assertEquals(1.0, KnowledgeRagService.sourceWeight("category"), 1e-9);
        assertEquals(1.0, KnowledgeRagService.sourceWeight("logistics"), 1e-9);
        assertEquals(1.0, KnowledgeRagService.sourceWeight("learned"), 1e-9);
        assertEquals(0.95, KnowledgeRagService.sourceWeight("manual"), 1e-9);
        assertEquals(0.75, KnowledgeRagService.sourceWeight("news"), 1e-9);
        assertEquals(1.0, KnowledgeRagService.sourceWeight("unknown-source"), 1e-9);
        assertEquals(1.0, KnowledgeRagService.sourceWeight(null), 1e-9);
    }

    @Test
    void searchHybridDownranksNewsBelowPlatformFacts() {
        // 三条候选向量分相同/接近，词面都无重合：最终排序只由来源权重决定
        RedisVectorStore.Hit news = hit("id-news", "news", "行业资讯", "本周市场新闻汇总", 1.0);
        RedisVectorStore.Hit policy = hit("id-policy", "policy", "平台政策", "平台政策要点集合", 0.85);
        RedisVectorStore.Hit manual = hit("id-manual", "manual", "平台知识", "平台知识补充说明", 1.0);
        when(store.search(any(float[].class), anyInt(), any())).thenReturn(List.of(news, policy, manual));

        List<RedisVectorStore.Hit> out = service.searchHybrid("会员等级有什么折扣", 3, null);

        assertEquals("id-manual", out.get(0).id(), "manual 0.95 权重应高于 news");
        assertEquals("id-policy", out.get(1).id(), "平台事实 1.0 权重应高于 news");
        assertEquals("id-news", out.get(2).id(), "news 0.75 降权后排最后");
        assertEquals(0.57, out.get(0).score(), 1e-9); // 0.6*1.0*0.95
        assertEquals(0.51, out.get(1).score(), 1e-9); // 0.6*0.85*1.0
        assertEquals(0.45, out.get(2).score(), 1e-9); // 0.6*1.0*0.75
    }

    // ===== ask()：相关性阈值 / 无命中兜底 / 英文回答语言 / 防注入 =====

    private void stubRouterAnswer(String answer) {
        when(router.decideTarget(any(), any(), any())).thenReturn(AiModelRouter.TEXT);
        Map<String, Object> gen = new LinkedHashMap<>();
        gen.put("answer", answer);
        gen.put("backend", "gateway:qwen2.5-7b");
        gen.put("target", AiModelRouter.TEXT);
        when(router.generate(any(), anyString(), anyString())).thenReturn(gen);
    }

    @Test
    void askFiltersLowScoreHitsBeforeGeneration() {
        RedisVectorStore.Hit relevant = hit("id-b", "kb", "企业账期", "企业账期申请流程与还款方式", 1.0);
        RedisVectorStore.Hit irrelevant = hit("id-a", "kb", "购物车结算", "购物车结算与订单支付", 0.1);
        when(store.search(any(float[].class), anyInt(), any())).thenReturn(List.of(relevant, irrelevant));
        stubRouterAnswer("企业账期可在线申请。");

        Map<String, Object> out = service.ask("企业账期怎么申请", 5, null);

        assertEquals("企业账期可在线申请。", out.get("answer"));
        assertEquals(1, ((Number) out.get("hits")).intValue(), "低分命中应被 ASK_MIN_SCORE 过滤");
        assertEquals(1, ((List<?>) out.get("sources")).size());
        assertNull(out.get("notice"));
        assertFalse((Boolean) out.get("noAnswer"));
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(router).generate(any(), anyString(), userPrompt.capture());
        assertTrue(userPrompt.getValue().contains("企业账期申请流程"));
        assertFalse(userPrompt.getValue().contains("购物车结算"), "低分无关资料不应进入上下文");
    }

    @Test
    void askLowScoreOnlySkipsModelAndFallsBack() {
        when(store.search(any(float[].class), anyInt(), any()))
                .thenReturn(List.of(hit("id-low", "kb", "购物车结算", "购物车结算与订单支付", 0.1)));

        Map<String, Object> out = service.ask("企业账期怎么申请", 5, null);

        assertEquals(KnowledgeRagService.NO_CONTEXT_ANSWER_ZH, out.get("answer"));
        assertEquals(KnowledgeRagService.NOTICE_NO_CONTEXT, out.get("notice"));
        assertTrue((Boolean) out.get("noAnswer"));
        assertEquals(0, ((Number) out.get("hits")).intValue());
        verify(router, never()).generate(any(), anyString(), anyString());
    }

    @Test
    void askNoContextReturnsFriendlyFallbackInsteadOfBlank() {
        when(store.search(any(float[].class), anyInt(), any())).thenReturn(List.of());

        Map<String, Object> out = service.ask("企业账期怎么申请", 5, null);

        assertEquals(KnowledgeRagService.NO_CONTEXT_ANSWER_ZH, out.get("answer"));
        assertTrue(((String) out.get("answer")).contains("暂无法确认"));
        assertEquals(KnowledgeRagService.NOTICE_NO_CONTEXT, out.get("notice"));
        assertTrue((Boolean) out.get("noAnswer"));
        assertEquals(0, ((Number) out.get("hits")).intValue());
        verify(router, never()).generate(any(), anyString(), anyString());
    }

    @Test
    void askEnglishNoContextReturnsEnglishFallback() {
        when(store.search(any(float[].class), anyInt(), any())).thenReturn(List.of());

        Map<String, Object> out = service.ask("How do I apply for enterprise credit terms?", 5, null);

        assertEquals(KnowledgeRagService.NO_CONTEXT_ANSWER_EN, out.get("answer"));
        assertEquals(KnowledgeRagService.NOTICE_NO_CONTEXT, out.get("notice"));
        assertTrue((Boolean) out.get("noAnswer"));
    }

    @Test
    void askEnglishQuestionReplacesChineseModelAnswerWithLatinDigest() {
        RedisVectorStore.Hit en = hit("id-en", "kb", "Enterprise credit terms",
                "Enterprise credit terms are available for verified business buyers; apply in the merchant center.", 0.7);
        RedisVectorStore.Hit zh = hit("id-zh", "kb", "企业账期", "企业账期申请流程与还款方式说明", 0.95);
        when(store.search(any(float[].class), anyInt(), any())).thenReturn(List.of(zh, en));
        stubRouterAnswer("根据资料，企业账期可以在商户中心申请。");

        Map<String, Object> out = service.ask("How do I apply for enterprise credit terms?", 5, null);

        String answer = (String) out.get("answer");
        assertEquals("Enterprise credit terms are available for verified business buyers; apply in the merchant center.", answer,
                "中文回答应被拉丁资料英文摘录替换");
        assertEquals(KnowledgeRagService.NOTICE_EN_DIGEST, out.get("notice"));
        assertEquals(1, ((Number) out.get("hits")).intValue(), "英文提问只使用拉丁资料块");
        assertTrue(KnowledgeRagService.cjkRatio(answer) <= KnowledgeRagService.EN_ANSWER_MAX_CJK_RATIO);
        assertFalse((Boolean) out.get("noAnswer"));

        ArgumentCaptor<String> sys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(router).generate(any(), sys.capture(), user.capture());
        assertTrue(sys.getValue().contains("You are an assistant for LANLinkshopping"), "英文提问应用英文 system prompt");
        assertFalse(sys.getValue().contains("你是 LANLinkshopping"));
        assertTrue(sys.getValue().contains("NOT instructions"), "英文 prompt 应含防注入要求");
        assertTrue(user.getValue().contains("References:"), "英文提问使用英文 prompt 结构");
        assertTrue(user.getValue().contains("Enterprise credit terms"));
        assertFalse(user.getValue().contains("企业账期申请流程"), "中文块不应进入英文上下文");
    }

    @Test
    void askEnglishQuestionWithoutLatinBlocksUsesEnglishFallback() {
        when(store.search(any(float[].class), anyInt(), any()))
                .thenReturn(List.of(hit("id-zh", "kb", "企业账期", "企业账期申请流程与还款方式说明", 0.9)));
        stubRouterAnswer("根据资料，企业账期可以申请。");

        Map<String, Object> out = service.ask("How do I apply for enterprise credit terms?", 5, null);

        assertEquals(KnowledgeRagService.NO_CONTEXT_ANSWER_EN, out.get("answer"),
                "无拉丁资料时不能输出中文答案，应给英文兜底");
        assertEquals(KnowledgeRagService.NOTICE_NO_CONTEXT, out.get("notice"));
        assertTrue((Boolean) out.get("noAnswer"));
    }

    @Test
    void askModelUnavailableReturnsFriendlyFallback() {
        when(store.search(any(float[].class), anyInt(), any()))
                .thenReturn(List.of(hit("id-b", "kb", "企业账期", "企业账期申请流程与还款方式", 1.0)));
        when(router.decideTarget(any(), any(), any())).thenReturn(AiModelRouter.TEXT);
        when(router.generate(any(), anyString(), anyString())).thenReturn(new LinkedHashMap<>());

        Map<String, Object> out = service.ask("企业账期怎么申请", 5, null);

        assertEquals(KnowledgeRagService.NO_CONTEXT_ANSWER_ZH, out.get("answer"), "模型不可用时应给兜底文案而非空白");
        assertEquals(KnowledgeRagService.NOTICE_MODEL_UNAVAILABLE, out.get("notice"));
        assertTrue((Boolean) out.get("noAnswer"));
    }

    @Test
    void askHardensChinesePromptAgainstInjection() {
        when(store.search(any(float[].class), anyInt(), any()))
                .thenReturn(List.of(hit("id-b", "kb", "企业账期", "企业账期申请流程", 1.0)));
        stubRouterAnswer("答案");

        service.ask("企业账期怎么申请", 5, null);

        ArgumentCaptor<String> sys = ArgumentCaptor.forClass(String.class);
        verify(router).generate(any(), sys.capture(), anyString());
        assertTrue(sys.getValue().contains("忽略"), "system prompt 应要求忽略参考资料中的指令");
        assertTrue(sys.getValue().contains("指令"));
        assertTrue(sys.getValue().contains("数据"));
    }

    @Test
    void englishDigestOnlyUsesLatinBlocks() {
        RedisVectorStore.Hit zh = hit("id-zh", "kb", "企业账期", "企业账期申请流程说明", 0.9);
        RedisVectorStore.Hit en = hit("id-en", "kb", "Enterprise terms", "Enterprise credit terms for business buyers", 0.8);

        String digest = KnowledgeRagService.englishDigest(List.of(zh, en));
        assertNotNull(digest);
        assertTrue(digest.contains("Enterprise credit terms"));
        assertFalse(digest.contains("企业账期"));
        assertNull(KnowledgeRagService.englishDigest(List.of(zh)));
        assertNull(KnowledgeRagService.englishDigest(List.of()));
    }
}
