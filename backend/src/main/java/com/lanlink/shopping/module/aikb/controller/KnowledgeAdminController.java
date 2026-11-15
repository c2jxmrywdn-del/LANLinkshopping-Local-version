package com.lanlink.shopping.module.aikb.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.aikb.AiModelRouter;
import com.lanlink.shopping.module.aikb.EmbeddingService;
import com.lanlink.shopping.module.aikb.ExternalInfoFetchService;
import com.lanlink.shopping.module.aikb.KnowledgeIngestService;
import com.lanlink.shopping.module.aikb.KnowledgeRagService;
import com.lanlink.shopping.module.aikb.RedisVectorStore;
import com.lanlink.shopping.module.aikb.RagServiceClient;
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
    private final RagServiceClient ragServiceClient;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lanlink.shopping.module.aikb.KbOutboxService kbOutboxService;

    public KnowledgeAdminController(KnowledgeIngestService ingestService, KnowledgeRagService ragService,
                                    RedisVectorStore store, EmbeddingService embeddingService,
                                    AiModelRouter router, ExternalInfoFetchService externalInfoFetchService,
                                    RagServiceClient ragServiceClient) {
        this.ingestService = ingestService;
        this.ragService = ragService;
        this.store = store;
        this.embeddingService = embeddingService;
        this.router = router;
        this.externalInfoFetchService = externalInfoFetchService;
        this.ragServiceClient = ragServiceClient;
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
        m.put("kbOutbox", kbOutboxService == null ? Map.of() : kbOutboxService.stats());
        m.put("models", router.modelStatus());
        // 多模态 RAG（RAG-Anything）状态：null 表示服务不可用，前端据此隐藏入口
        m.put("multimodalRag", ragServiceClient.health());
        return R.ok(m);
    }

    /** 立即抓取外部 RSS 资讯（手动触发；limit 为本轮最多入库条数，最终受 AI_EXTERNAL_LIMIT 限制） */
    @PostMapping("/fetch-external")
    public R<Map<String, Object>> fetchExternal(@RequestParam(defaultValue = "30") int limit) {
        Map<String, Object> stats = externalInfoFetchService.fetchNow(limit);
        return R.ok("外部资讯抓取完成", stats);
    }

    /** source: all / kb / policy / membership / subscription / category / logistics / product / news / tech */
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
            case "tech" -> result = Map.of("tech", ingestService.ingestTech());
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
        if (id == null) return R.fail("向量索引写入失败，请检查嵌入模型与 AI_EMBED_DIM 维度配置");
        return R.ok("已录入", Map.of("id", id));
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

    // ===== 多模态 RAG（RAG-Anything，独立索引）=====

    /**
     * 多模态 RAG 健康状态。
     *
     * <p>返回 null 表示 rag-service 未启动或不可达；前端据此隐藏入口，
     * 不影响现有文本 RAG。
     */
    @GetMapping("/multimodal/health")
    public R<Map<String, Object>> multimodalHealth() {
        Map<String, Object> h = ragServiceClient.health();
        if (h == null) {
            return R.ok("多模态 RAG 服务不可用", null);
        }
        return R.ok(h);
    }

    /**
     * 写入多模态 RAG 索引。
     *
     * <p>请求体：
     * <pre>
     * {
     *   "docId": "order-20261116-001",
     *   "filePath": "合同扫描件.pdf",          // 可选，仅用于来源显示
     *   "paragraphs": ["第一段...", "第二段..."]  // 纯文本快捷方式
     *   // 或 "contentList": [{"type":"text","text":"...","page_idx":0}]
     * }
     * </pre>
     *
     * <p>为什么同时支持 paragraphs 与 contentList：绝大多数调用方只有纯文本，
     * 让它拼 RAG-Anything 的原生结构属于无谓负担；需要图片/表格时再用 contentList。
     */
    @PostMapping("/multimodal/ingest")
    @SuppressWarnings("unchecked")
    public R<Map<String, Object>> multimodalIngest(@RequestBody Map<String, Object> body) {
        String docId = body.get("docId") instanceof String s ? s.trim() : null;
        if (docId == null || docId.isBlank()) {
            return R.fail(400, "docId 不能为空");
        }
        List<Map<String, Object>> contentList;
        Object raw = body.get("contentList");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            contentList = (List<Map<String, Object>>) list;
        } else if (body.get("paragraphs") instanceof List<?> paras && !paras.isEmpty()) {
            List<String> texts = new ArrayList<>();
            for (Object p : paras) {
                if (p != null) texts.add(String.valueOf(p));
            }
            contentList = RagServiceClient.textContent(texts.toArray(new String[0]));
        } else {
            return R.fail(400, "contentList 或 paragraphs 至少提供一个");
        }
        String filePath = body.get("filePath") instanceof String s ? s : null;
        Map<String, Object> out = ragServiceClient.ingest(docId, contentList, filePath);
        if (out == null) {
            return R.fail(503, "多模态 RAG 服务不可用或索引失败");
        }
        return R.ok("已写入多模态索引", out);
    }

    /**
     * 多模态 RAG 检索问答。
     *
     * <p><b>mode 默认 naive</b>：实测 hybrid/local/global 依赖实体抽取，
     * 而本地 qwen2.5-7b 无法稳定输出 LightRAG 的实体格式，graph 为空。
     * 保留 mode 参数是为了换更大模型后可直接切换，无需改代码。
     */
    @PostMapping("/multimodal/query")
    public R<Map<String, Object>> multimodalQuery(@RequestBody Map<String, Object> body) {
        String question = body.get("question") instanceof String s ? s.trim() : null;
        if (question == null || question.isBlank()) {
            return R.fail(400, "question 不能为空");
        }
        String mode = body.get("mode") instanceof String s && !s.isBlank() ? s.trim() : "naive";
        Integer topK = body.get("topK") instanceof Number n ? n.intValue() : null;
        String answer = ragServiceClient.query(question, mode, topK);
        if (answer == null) {
            return R.fail(503, "多模态 RAG 服务不可用或检索失败");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answer", answer);
        out.put("mode", mode);
        return R.ok(out);
    }
}
