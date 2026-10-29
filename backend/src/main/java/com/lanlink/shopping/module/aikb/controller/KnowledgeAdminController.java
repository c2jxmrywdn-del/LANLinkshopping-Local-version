package com.lanlink.shopping.module.aikb.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.aikb.AiModelRouter;
import com.lanlink.shopping.module.aikb.EmbeddingService;
import com.lanlink.shopping.module.aikb.ExternalInfoFetchService;
import com.lanlink.shopping.module.aikb.KnowledgeIngestService;
import com.lanlink.shopping.module.aikb.KnowledgeRagService;
import com.lanlink.shopping.module.aikb.RedisVectorStore;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量知识库管理（平台运营后台）。
 *  - GET  /admin/kb/stats                 来源统计 + 嵌入提供方 + 维度 + 索引模式/缓存/检索/模型/外部抓取
 *  - POST /admin/kb/ingest?source=all     全量/按源重灌（kb/product/news）
 *  - POST /admin/kb/fetch-external?limit= 立即抓取外部 RSS 资讯并入库（刷新 news 向量源）
 *  - POST /admin/kb/ask {question}        RAG 问答测试台
 */
@RestController
@RequestMapping("/admin/kb")
@RequirePerm("admin:all")
public class KnowledgeAdminController {

    private final KnowledgeIngestService ingestService;
    private final KnowledgeRagService ragService;
    private final RedisVectorStore store;
    private final EmbeddingService embeddingService;
    private final AiModelRouter router;
    private final ExternalInfoFetchService externalInfoFetchService;

    public KnowledgeAdminController(KnowledgeIngestService ingestService, KnowledgeRagService ragService,
                                    RedisVectorStore store, EmbeddingService embeddingService,
                                    AiModelRouter router, ExternalInfoFetchService externalInfoFetchService) {
        this.ingestService = ingestService;
        this.ragService = ragService;
        this.store = store;
        this.embeddingService = embeddingService;
        this.router = router;
        this.externalInfoFetchService = externalInfoFetchService;
    }

    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        // 原有字段（键名保持不变）
        m.put("totalDocs", store.totalDocs());
        m.put("sources", store.statsBySource());
        m.put("dim", store.dim());
        m.put("embedProvider", embeddingService.getProvider());
        m.put("chatModel", embeddingService.getChatModel());
        // 性能/运行态增补（前端可容错消费）
        m.put("indexMode", store.indexMode());
        m.put("embedCache", embeddingService.cacheStats());
        m.put("search", store.searchStats());
        m.put("externalFetch", externalInfoFetchService.stats());
        m.put("models", router.modelStatus());
        return R.ok(m);
    }

    /** 立即抓取外部 RSS 资讯（手动触发；limit 为本轮最多入库条数，最终受 AI_EXTERNAL_LIMIT 限制） */
    @PostMapping("/fetch-external")
    public R<Map<String, Object>> fetchExternal(@RequestParam(defaultValue = "30") int limit) {
        Map<String, Object> stats = externalInfoFetchService.fetchNow(limit);
        return R.ok("外部资讯抓取完成", stats);
    }

    /** source: all / kb / policy / membership / subscription / category / logistics / product / news */
    @PostMapping("/ingest")
    public R<Map<String, Integer>> ingest(@RequestParam(defaultValue = "all") String source) {
        Map<String, Integer> result;
        switch (source) {
            case "kb" -> result = Map.of("kb", ingestService.ingestKnowledgeBase());
            case "policy" -> result = Map.of("policy", ingestService.ingestPolicy());
            case "membership" -> result = Map.of("membership", ingestService.ingestMembership());
            case "subscription" -> result = Map.of("subscription", ingestService.ingestSubscription());
            case "category" -> result = Map.of("category", ingestService.ingestCategory());
            case "logistics" -> result = Map.of("logistics", ingestService.ingestLogistics());
            case "product" -> result = Map.of("product", ingestService.ingestProducts());
            case "news" -> result = Map.of("news", ingestService.ingestNews());
            default -> result = ingestService.ingestAll();
        }
        return R.ok("灌装完成", result);
    }

    /** 知识条目列表（可按来源过滤），供管理端浏览与逐条维护 */
    @GetMapping("/entries")
    public R<List<Map<String, Object>>> entries(@RequestParam(required = false) String source,
                                                @RequestParam(defaultValue = "200") int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RedisVectorStore.DocInfo d : store.list(source, Math.min(Math.max(limit, 1), 1000))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id());
            m.put("source", d.source());
            m.put("refId", d.refId());
            m.put("title", d.title());
            m.put("text", d.text());
            out.add(m);
        }
        return R.ok(out);
    }

    /** 手工录入一条平台知识（默认来源 manual），返回新文档 id */
    @PostMapping("/entry")
    public R<Map<String, Object>> addEntry(@RequestBody Map<String, Object> body) {
        String source = body == null ? null : str(body.get("source"));
        String title = body == null ? null : str(body.get("title"));
        String text = body == null ? null : str(body.get("text"));
        if (text == null || text.isBlank()) return R.fail("知识内容不能为空");
        String id = ingestService.addManual(source, title, text);
        return R.ok("已录入", Map.of("id", id == null ? "" : id));
    }

    /** 按文档 id 删除一条知识 */
    @DeleteMapping("/entry/{id}")
    public R<Boolean> deleteEntry(@PathVariable String id) {
        boolean ok = ingestService.removeDoc(id);
        return R.ok(ok ? "已删除" : "未找到该条目", ok);
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    /** RAG 问答测试台（本地 qwen2.5-vl / qwen2.5:7b 互斥路由；enableTools=true 开启工具增强） */
    @PostMapping("/ask")
    public R<Map<String, Object>> ask(@RequestBody Map<String, Object> body) {
        String question = body == null ? null : (String) body.get("question");
        if (question == null || question.isBlank()) return R.fail("请输入问题");
        int topK = body.get("topK") == null ? 5 : ((Number) body.get("topK")).intValue();
        String model = body.get("model") == null ? AiModelRouter.AUTO : String.valueOf(body.get("model"));
        boolean enableTools = Boolean.TRUE.equals(body.get("enableTools"));
        return R.ok(ragService.ask(question.trim(), Math.min(Math.max(topK, 1), 10), null, model, enableTools));
    }

    /** 双模型状态：路由目标 / Ollama 已加载模型 / 文本模型 KV 上限（合并 router.modelStatus 的增补字段，旧键保留） */
    @GetMapping("/models")
    public R<Map<String, Object>> models() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("current", router.getCurrent());
        m.put("ollamaLoaded", router.loadedModels());
        m.put("textNumCtx", router.getTextNumCtx());
        m.put("gatewayModel", embeddingService.getGatewayModel());
        m.put("textModel", embeddingService.getChatModel());
        Map<String, Object> status = router.modelStatus();
        if (status != null) {
            for (Map.Entry<String, Object> entry : status.entrySet()) {
                if (entry.getKey() != null && !m.containsKey(entry.getKey())) {
                    m.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return R.ok(m);
    }

    /** 手动卸载 Ollama 文本模型（互斥策略：只保留 VL 在线） */
    @PostMapping("/models/unload-text")
    public R<Void> unloadText() {
        embeddingService.unloadOllamaTextModel();
        return R.ok("文本模型已卸载", null);
    }
}
