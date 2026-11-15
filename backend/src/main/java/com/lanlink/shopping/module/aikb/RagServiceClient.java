package com.lanlink.shopping.module.aikb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG-Anything 服务客户端（多模态知识检索）。
 *
 * <p>与 {@link KnowledgeRagService} 的分工：
 * <ul>
 *   <li>{@code KnowledgeRagService} = 现有文本 RAG（Redis HNSW {@code aikb-idx} + bge-m3），
 *       面向客服问答，链路稳定，**不替换**；</li>
 *   <li>本类 = 新增的多模态 RAG（RAG-Anything / LightRAG），独立存储，
 *       用于图片/表格/公式等富文档的检索。</li>
 * </ul>
 *
 * <p><b>降级语义</b>：rag-service 不可用（未启动、超时、返回非 2xx）时，
 * 所有方法返回 null / 空，调用方据此回退到 {@code KnowledgeRagService}。
 * 绝不抛异常穿透到 Controller——多模态检索是增强能力，不能拖垮主问答链路。
 */
@Service
public class RagServiceClient {

    private static final Logger log = LoggerFactory.getLogger(RagServiceClient.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** 服务基址：容器内走服务名，本机调试可用 127.0.0.1:8093 */
    private final String baseUrl;
    /** 内部调用密钥，与 rag-service 的 RAG_INTERNAL_SECRET 一致 */
    private final String secret;
    /** 索引写入可能较慢（LLM 实体抽取），单独给长超时 */
    private final int ingestTimeoutSeconds;
    /** 查询超时：embedding + LLM 生成 */
    private final int queryTimeoutSeconds;

    public RagServiceClient(
            @Value("${RAG_SERVICE_BASE_URL:http://lanlink-rag-service:8093}") String baseUrl,
            @Value("${RAG_INTERNAL_SECRET:lanlink-rag-internal}") String secret,
            @Value("${RAG_INGEST_TIMEOUT_SECONDS:300}") int ingestTimeoutSeconds,
            @Value("${RAG_QUERY_TIMEOUT_SECONDS:120}") int queryTimeoutSeconds) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.ingestTimeoutSeconds = ingestTimeoutSeconds;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /** 服务是否已配置基址（未配置时所有调用直接短路，避免无意义的连接尝试） */
    public boolean configured() {
        return StringUtils.hasText(baseUrl);
    }

    /**
     * 健康检查。返回 null 表示不可用。
     */
    public Map<String, Object> health() {
        if (!configured()) return null;
        try {
            HttpResponse<String> resp = httpClient.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                            .timeout(Duration.ofSeconds(8))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return null;
            return objectMapper.readValue(resp.body(), Map.class);
        } catch (Exception e) {
            log.debug("[rag] 健康检查失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 写入一篇文档。
     *
     * @param docId       业务侧唯一标识（订单号/商品ID/文件名等）
     * @param contentList RAG-Anything 原生 content_list：
     *                    <pre>[{"type":"text","text":"...","page_idx":0}]</pre>
     * @param filePath    可选，用于检索结果里的来源显示
     * @return 成功返回 {"docId":..,"chunks":N}；失败返回 null
     */
    public Map<String, Object> ingest(String docId, List<Map<String, Object>> contentList, String filePath) {
        if (!configured() || !StringUtils.hasText(docId) || contentList == null || contentList.isEmpty()) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docId", docId);
        body.put("contentList", contentList);
        if (StringUtils.hasText(filePath)) body.put("filePath", filePath);
        JsonNode node = post("/ingest", body, ingestTimeoutSeconds);
        if (node == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docId", node.path("docId").asText(docId));
        out.put("chunks", node.path("chunks").asInt(0));
        return out;
    }

    /**
     * 检索并生成答案。
     *
     * @param question 用户问题
     * @param mode     LightRAG 检索模式。**当前实测只有 naive 可靠**：
     *                 hybrid/local/global 依赖实体抽取，而本地 qwen2.5-7b 无法稳定
     *                 输出 LightRAG 要求的 `entity<|#|>...` 格式，导致 graph 为空。
     * @param topK     召回数量
     * @return 答案文本；失败返回 null（调用方回退现有 RAG）
     */
    public String query(String question, String mode, Integer topK) {
        if (!configured() || !StringUtils.hasText(question)) return null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("mode", StringUtils.hasText(mode) ? mode : "naive");
        if (topK != null) body.put("topK", topK);
        JsonNode node = post("/query", body, queryTimeoutSeconds);
        if (node == null) return null;
        String answer = node.path("answer").asText("");
        return StringUtils.hasText(answer) ? answer : null;
    }

    /** 构造纯文本 content_list（最常用形态，省去调用方拼 JSON） */
    public static List<Map<String, Object>> textContent(String... paragraphs) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (paragraphs == null) return list;
        for (String p : paragraphs) {
            if (!StringUtils.hasText(p)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "text");
            item.put("text", p);
            item.put("page_idx", 0);
            list.add(item);
        }
        return list;
    }

    private JsonNode post(String path, Map<String, Object> body, int timeoutSeconds) {
        try {
            String json = objectMapper.writeValueAsString(body);
            /*
             * 用 ofByteArray 而不是 ofString：
             * 两者都会自动设置正确的 Content-Length，但 ofByteArray 明确按 UTF-8
             * 字节发送，避免中文在默认字符集下被二次编码。
             *
             * **不要手动加 `Content-Length` 头**：JDK HttpClient 把它列为 restricted header，
             * 手动设置会抛 `restricted header name: "Content-Length"`，
             * 表现为所有请求直接失败（曾导致 /ingest 与 /query 全 503）。
             */
            byte[] payload = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Secret", secret)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                // 只记 warn，不抛：调用方按 null 回退
                log.warn("[rag] {} 返回 {}: {}", path, resp.statusCode(),
                        truncate(resp.body(), 200));
                return null;
            }
            return objectMapper.readTree(resp.body());
        } catch (Exception e) {
            log.warn("[rag] {} 调用失败: {}", path, e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
