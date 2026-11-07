package com.lanlink.shopping.module.aikb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真机契约探针：只有显式给出 {@code LANLINK_MCP_LIVE_URL}（+ {@code AI_GATEWAY_API_KEY}）才运行，
 * 平时随 CI 跳过。它证明 KbMcpClient 解析的不是我自己写的假响应，而是网关真实产出的
 * structuredContent（同一份 ai-gateway/MCP_KB_SPEC.md 契约）。
 *
 * <p>本地跑法（临时网关容器，不动线上 lanlink-ai-gateway）：
 * <pre>
 * LANLINK_MCP_LIVE_URL=http://127.0.0.1:8199/mcp LANLINK_MCP_LIVE_KEY=&lt;该容器的网关 Key&gt; \
 *   mvn -Dtest=KbMcpLiveProbeTest test
 * </pre>
 * Key 必须显式给：shell 里常挂着线上 AI_GATEWAY_API_KEY，静默复用它只会得到一个误导性的 401。
 */
@EnabledIfEnvironmentVariable(named = "LANLINK_MCP_LIVE_URL", matches = ".+")
class KbMcpLiveProbeTest {

    @Test
    void readsRealVectorIndexThroughMcp() {
        String url = System.getenv("LANLINK_MCP_LIVE_URL");
        String key = System.getenv().getOrDefault("LANLINK_MCP_LIVE_KEY", "");
        KbMcpClient client = new KbMcpClient(url, key, true);
        assertTrue(client.isConfigured(), "探针必须显式给出 URL 与 Key 才算已配置");

        // 网关的密集向量路依赖嵌入服务，冷启动或嵌入超时那一次会回退 TF-IDF；
        // 客户端按契约把它判为"非向量命中"并交回直连检索，这是正确行为，不能算探针失败。
        KbMcpClient.Outcome out = KbMcpClient.Outcome.unavailable("never-ran");
        for (int i = 0; i < 4 && !out.servedByVectorIndex(); i++) {
            out = client.search("企业采购的账期与授信规则是什么", 3, 0.3);
            System.out.println("[mcp-live] attempt=" + i + " backend=" + out.backend()
                    + " servedByVectorIndex=" + out.servedByVectorIndex() + " hits=" + out.hits().size());
        }

        assertTrue(out.servedByVectorIndex(),
                "多次重试后网关仍未用向量索引服务本次检索，说明嵌入服务或 aikb-idx 不可用，"
                        + "此时应验的是回退路径而不是本探针: backend=" + out.backend());
        assertFalse(out.hits().isEmpty(), "真实索引里应有账期资料；空结果说明契约或鉴权变了");
        List<RedisVectorStore.Hit> hits = out.hits();
        for (RedisVectorStore.Hit h : hits) {
            assertFalse(h.title().isBlank(), "命中必须带标题");
            assertFalse(h.text().isBlank(), "命中必须带正文片段");
            assertTrue(h.score() >= 0.3, "服务端 minScore 已在相关度分上过滤: " + h.score());
        }
        assertTrue(hits.stream().anyMatch(h -> "kb".equals(h.source()) || "policy".equals(h.source())),
                "账期问题应命中平台帮助或政策来源，实际来源: "
                        + hits.stream().map(RedisVectorStore.Hit::source).toList());
    }
}
