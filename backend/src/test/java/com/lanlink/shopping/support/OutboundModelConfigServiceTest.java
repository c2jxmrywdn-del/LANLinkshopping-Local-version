package com.lanlink.shopping.support;

import com.lanlink.shopping.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 出站模型密钥管理：加解密往返、脱敏回显（绝不回显明文）、空值不覆盖旧密钥、运行时读取。
 */
class OutboundModelConfigServiceTest {

    private JdbcTemplate jdbc;
    private AuditService auditService;
    private OutboundModelConfigService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        auditService = mock(AuditService.class);
        service = new OutboundModelConfigService(jdbc, auditService);
        ReflectionTestUtils.setField(service, "keySecret", "test-secret");
    }

    @Test
    void encryptDecryptRoundTrip() {
        String secret = "sk-test-abcdef123456";
        String cipher = service.encrypt(secret);
        assertNotEquals(secret, cipher, "密文不得是明文");
        assertEquals(secret, service.decrypt(cipher), "解密应还原明文");
    }

    @Test
    void maskPrefixNeverRevealsFullKey() {
        String masked = OutboundModelConfigService.maskPrefix("sk-abcdef123456789");
        assertEquals("sk-abc****", masked);
        assertFalse(masked.contains("123456789"));
    }

    @Test
    void listReturnsMaskedPrefixOnly() {
        when(jdbc.queryForList(anyString(), eq("channel"), eq("base_url"), eq("api_key_prefix"), eq("enabled")))
                .thenReturn(List.of());
        // queryForList(sql) 无参版本
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("channel", "deepseek", "base_url", "https://api.deepseek.com",
                        "api_key_prefix", "sk-abc****", "enabled", 1)));
        List<Map<String, Object>> list = service.list();
        assertEquals(1, list.size());
        assertEquals("sk-abc****", list.get(0).get("apiKeyPrefix"));
        assertFalse(list.get(0).containsKey("apiKey"), "脱敏列表不得包含密钥字段");
    }

    @Test
    void upsertRejectsUnknownChannel() {
        assertThrows(IllegalArgumentException.class,
                () -> service.upsert("hack", "https://x.com", "sk-12345678", true, 1L, null));
    }

    @Test
    void upsertEmptyKeyKeepsOldCipher() {
        // 模拟已有旧密钥
        AtomicReference<String> updatedCipher = new AtomicReference<>();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), eq("deepseek")))
                .thenAnswer(inv -> {
                    org.springframework.jdbc.core.ResultSetExtractor<String> ext = inv.getArgument(1);
                    return "old-cipher";
                });
        when(jdbc.update(contains("UPDATE t_outbound_model_config"), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    updatedCipher.set(inv.getArgument(2));
                    return 1;
                });
        service.upsert("deepseek", "https://api.deepseek.com", "", true, 1L, null);
        assertEquals("old-cipher", updatedCipher.get(), "空密钥应保留旧密文不覆盖");
    }

    @Test
    void activeChannelDecryptsAndRespectsEnabled() throws Exception {
        String cipher = service.encrypt("sk-live-12345678");
        // 模拟 ResultSet 让真实 extractor（含解密逻辑）被执行
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), eq("deepseek")))
                .thenAnswer(inv -> {
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.next()).thenReturn(true, false);
                    when(rs.getString("channel")).thenReturn("deepseek");
                    when(rs.getString("base_url")).thenReturn("https://api.deepseek.com");
                    when(rs.getString("api_key_cipher")).thenReturn(cipher);
                    when(rs.getInt("enabled")).thenReturn(1);
                    org.springframework.jdbc.core.ResultSetExtractor<?> ext = inv.getArgument(1);
                    return ext.extractData(rs);
                });
        Optional<OutboundModelConfigService.ChannelConfig> ch = service.activeChannel("deepseek");
        assertTrue(ch.isPresent());
        assertEquals("sk-live-12345678", ch.get().apiKey(), "解密后应为明文（仅内部使用）");
    }

    @Test
    void defaultModelForSupportsMimoAndBailian() {
        assertEquals("mimo-v2.6-flash", OutboundModelConfigService.defaultModelFor("mimo"));
        assertEquals("qwen-plus", OutboundModelConfigService.defaultModelFor("bailian"));
        // 渠道名大小写/空格容错
        assertEquals("mimo-v2.6-flash", OutboundModelConfigService.defaultModelFor(" MIMO "));
        assertEquals("qwen-plus", OutboundModelConfigService.defaultModelFor("BaiLian"));
    }

    @Test
    void chatCompletionsEndpointBuildsOfficialPlatformEndpoints() {
        // 小米 MiMo 开放平台（OpenAI 兼容协议）
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions",
                OutboundModelConfigService.chatCompletionsEndpoint("https://api.xiaomimimo.com/v1"));
        // 阿里百炼 DashScope 兼容模式
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                OutboundModelConfigService.chatCompletionsEndpoint(
                        "https://dashscope.aliyuncs.com/compatible-mode/v1"));
    }

    @Test
    void upsertAcceptsMimoAndBailianChannels() {
        when(jdbc.update(contains("UPDATE t_outbound_model_config"), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        assertDoesNotThrow(() -> service.upsert("mimo", "https://api.xiaomimimo.com/v1",
                "sk-mimo-1234567890", "mimo-v2.6-flash", true, 1L, null));
        assertDoesNotThrow(() -> service.upsert("bailian",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-bailian-1234567890",
                "qwen-plus", true, 1L, null));
        // 白名单外渠道仍必须被拒绝
        assertThrows(IllegalArgumentException.class,
                () -> service.upsert("mimo-hack", "https://api.xiaomimimo.com/v1", "sk-1234567890", true, 1L, null));
    }

    @Test
    void activeChannelsCoverMimoAndBailian() {
        String cipher = service.encrypt("sk-live-12345678");
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), anyString()))
                .thenAnswer(inv -> {
                    String channel = inv.getArgument(2);
                    if (!List.of("mimo", "bailian").contains(channel)) return Optional.empty();
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.next()).thenReturn(true, false);
                    when(rs.getString("channel")).thenReturn(channel);
                    when(rs.getString("base_url")).thenReturn("https://api.xiaomimimo.com/v1");
                    when(rs.getString("api_key_cipher")).thenReturn(cipher);
                    when(rs.getInt("enabled")).thenReturn(1);
                    org.springframework.jdbc.core.ResultSetExtractor<?> ext = inv.getArgument(1);
                    return ext.extractData(rs);
                });
        List<OutboundModelConfigService.ChannelConfig> channels = service.activeChannels();
        assertEquals(List.of("mimo", "bailian"),
                channels.stream().map(OutboundModelConfigService.ChannelConfig::channel).toList(),
                "新增渠道应参与备用链路降级尝试");
        assertEquals("mimo-v2.6-flash", channels.get(0).modelName(), "未配置模型名时应回退渠道默认模型");
    }

    @Test
    void testConnectionValidationAndDisabledChannelFallback() {
        // 1. 无有效 Key 且未存储时返回规范的失败格式（包含 reachable 与 statusCode）
        Map<String, Object> resNoKey = service.testConnection("deepseek", "https://api.deepseek.com", "", "deepseek-chat");
        assertEquals(Boolean.FALSE, resNoKey.get("success"));
        assertEquals(Boolean.FALSE, resNoKey.get("reachable"));
        assertEquals(400, resNoKey.get("statusCode"));
        assertTrue(String.valueOf(resNoKey.get("error")).contains("未提供 API Key"));

        // 2. 无效 Base URL
        Map<String, Object> resInvalidUrl = service.testConnection("deepseek", "ftp://invalid", "sk-123", "deepseek-chat");
        assertEquals(Boolean.FALSE, resInvalidUrl.get("success"));
        assertEquals(Boolean.FALSE, resInvalidUrl.get("reachable"));
        assertEquals(400, resInvalidUrl.get("statusCode"));
        assertTrue(String.valueOf(resInvalidUrl.get("error")).contains("Base URL"));

        // 3. 停用渠道（enabled=0）在有已保存密钥时，仍可通过 storedChannel 读取到密钥并执行探测（而非因 enabled=0 直接抛未配置密钥）
        String cipher = service.encrypt("sk-saved-secret-key");
        when(jdbc.query(contains("FROM t_outbound_model_config WHERE channel = ?"), any(org.springframework.jdbc.core.ResultSetExtractor.class), eq("deepseek")))
                .thenAnswer(inv -> {
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.next()).thenReturn(true, false);
                    when(rs.getString("channel")).thenReturn("deepseek");
                    when(rs.getString("base_url")).thenReturn("https://api.deepseek.com");
                    when(rs.getString("api_key_cipher")).thenReturn(cipher);
                    when(rs.getString("model_name")).thenReturn("deepseek-chat");
                    org.springframework.jdbc.core.ResultSetExtractor<?> ext = inv.getArgument(1);
                    return ext.extractData(rs);
                });
        // 探测本地不可达测试地址，验证走到了 HTTP 探测阶段并返回标准失败结构（包含 reachable 与 error，而非报缺失密钥）
        Map<String, Object> resWithSavedKey = service.testConnection("deepseek", "http://127.0.0.1:54321/v1", "", "");
        assertEquals(Boolean.FALSE, resWithSavedKey.get("success"));
        assertEquals(Boolean.FALSE, resWithSavedKey.get("reachable"));
        assertNotNull(resWithSavedKey.get("error"));
        assertFalse(String.valueOf(resWithSavedKey.get("error")).contains("未提供 API Key"),
                "已保存密钥的停用渠道应能成功读取密钥进行连通测试");
    }

    // ===== 连通性判定的边界用例 =====

    @Test
    void unreachableClassificationCoversNetworkBoundaries() {
        assertTrue(OutboundModelConfigService.isUnreachable(new ConnectException("refused")),
                "连接被拒绝应判定为不可达");
        assertTrue(OutboundModelConfigService.isUnreachable(new UnknownHostException("bad.invalid")),
                "域名无法解析应判定为不可达");
        assertTrue(OutboundModelConfigService.isUnreachable(new NoRouteToHostException("no route")),
                "路由不可达应判定为不可达");
        assertTrue(OutboundModelConfigService.isUnreachable(new UnresolvedAddressException()),
                "地址无法解析应判定为不可达");
        assertTrue(OutboundModelConfigService.isUnreachable(new HttpConnectTimeoutException("connect timed out")),
                "连接超时应判定为不可达");
        assertTrue(OutboundModelConfigService.isUnreachable(
                        new IOException("outer", new ConnectException("refused"))),
                "被 IOException 包装的连接拒绝仍应识别为不可达");
        assertFalse(OutboundModelConfigService.isUnreachable(new HttpTimeoutException("request timed out")),
                "响应超时不代表网络层不可达");
        assertFalse(OutboundModelConfigService.isUnreachable(new IOException("connection reset")),
                "普通 IO 异常不应直接判定为不可达");
        assertFalse(OutboundModelConfigService.isUnreachable(null), "空异常应安全返回 false");
    }

    @Test
    void timeoutClassificationCoversHttpAndFallbackMessageBoundaries() {
        assertTrue(OutboundModelConfigService.isTimeout(new HttpTimeoutException("request timed out")),
                "HttpTimeoutException 应识别为超时");
        assertTrue(OutboundModelConfigService.isTimeout(new HttpConnectTimeoutException("connect timed out")),
                "连接超时也应识别为超时");
        assertTrue(OutboundModelConfigService.isTimeout(new IOException("request timed out")),
                "仅返回 timed out 文案的异常也应识别为超时");
        assertFalse(OutboundModelConfigService.isTimeout(new ConnectException("refused")),
                "连接拒绝不应识别为超时");
        assertFalse(OutboundModelConfigService.isTimeout(new IOException("boom")),
                "无超时文案的异常不应识别为超时");
        assertFalse(OutboundModelConfigService.isTimeout(null), "空异常应安全返回 false");
    }

    // ===== 基于 Mock 数据/响应的完整连通性用例 =====

    /** 模拟数据库中的已保存渠道配置（停用渠道同样可读取用于探测）。 */
    private void mockStoredChannel(String channel, String baseUrl, String modelName, String plainKey) {
        String cipher = service.encrypt(plainKey);
        when(jdbc.query(
                contains("SELECT channel, base_url, api_key_cipher, model_name FROM t_outbound_model_config WHERE channel = ?"),
                any(org.springframework.jdbc.core.ResultSetExtractor.class), eq(channel)))
                .thenAnswer(inv -> {
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.next()).thenReturn(true, false);
                    when(rs.getString("channel")).thenReturn(channel);
                    when(rs.getString("base_url")).thenReturn(baseUrl);
                    when(rs.getString("api_key_cipher")).thenReturn(cipher);
                    when(rs.getString("model_name")).thenReturn(modelName);
                    org.springframework.jdbc.core.ResultSetExtractor<?> ext = inv.getArgument(1);
                    return ext.extractData(rs);
                });
    }

    private OutboundModelConfigService serviceWithChatCall(OutboundModelConfigService.ChatCall call) {
        OutboundModelConfigService custom = new OutboundModelConfigService(jdbc, auditService, call);
        ReflectionTestUtils.setField(custom, "keySecret", "test-secret");
        return custom;
    }

    @Test
    void testConnectionFailureBoundariesUseMockStoredChannelData() {
        mockStoredChannel("deepseek", "https://api.deepseek.com/v1", "deepseek-flash", "sk-saved-secret-key");

        record Case(Exception error, boolean reachable, boolean timeout, int statusCode) {}
        List<Case> cases = List.of(
                new Case(new ConnectException("refused"), false, false, 500),
                new Case(new UnknownHostException("bad.invalid"), false, false, 500),
                new Case(new NoRouteToHostException("no route"), false, false, 500),
                new Case(new UnresolvedAddressException(), false, false, 500),
                new Case(new HttpConnectTimeoutException("connect timed out"), false, true, 504),
                new Case(new HttpTimeoutException("request timed out"), true, true, 504),
                new Case(new IOException("connection reset"), true, false, 500),
                new Case(new IOException("outer", new ConnectException("refused")), false, false, 500)
        );

        for (Case item : cases) {
            java.util.concurrent.atomic.AtomicReference<String> seenEndpoint = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> seenKey = new java.util.concurrent.atomic.AtomicReference<>();
            OutboundModelConfigService custom = serviceWithChatCall((endpoint, key, payload, timeout) -> {
                seenEndpoint.set(endpoint);
                seenKey.set(key);
                throw item.error();
            });

            // API Key 与 Base URL 均为空：全部依赖 Mock 的已保存渠道数据
            Map<String, Object> res = custom.testConnection("deepseek", "", "", "");

            assertEquals(Boolean.FALSE, res.get("success"), item.error().getClass().getSimpleName());
            assertEquals(item.reachable(), res.get("reachable"), "reachable 判定错误: " + item.error().getClass());
            assertEquals(item.timeout(), res.get("timeout"), "timeout 判定错误: " + item.error().getClass());
            assertEquals(item.statusCode(), res.get("statusCode"), "状态码错误: " + item.error().getClass());
            assertNotNull(res.get("error"));
            assertEquals("https://api.deepseek.com/v1/chat/completions", seenEndpoint.get(),
                    "应使用 Mock 渠道数据拼接探测地址");
            assertEquals("sk-saved-secret-key", seenKey.get(), "应使用 Mock 渠道数据解密出的密钥");
        }
    }

    @Test
    void testConnectionSuccessAndRetryUseMockTransportResponses() {
        mockStoredChannel("deepseek", "https://api.deepseek.com/v1", "deepseek-flash", "sk-saved-secret-key");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        OutboundModelConfigService custom = serviceWithChatCall((endpoint, key, payload, timeout) -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                return new OutboundModelConfigService.Upstream(400,
                        "{\"error\":{\"message\":\"max_tokens unsupported\"}}");
            }
            return new OutboundModelConfigService.Upstream(200,
                    "{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}");
        });

        Map<String, Object> res = custom.testConnection("deepseek", "", "", "");

        assertEquals(Boolean.TRUE, res.get("success"));
        assertEquals(Boolean.TRUE, res.get("reachable"));
        assertEquals(200, res.get("statusCode"));
        assertEquals("deepseek-flash", res.get("model"), "应回退使用 Mock 渠道的模型名");
        assertEquals(2, calls.get(), "HTTP 400 后应使用最小请求体重试一次");
        assertTrue(String.valueOf(res.get("note")).contains("重试"));
    }

    @Test
    void testConnectionSurfacesMockUpstreamErrorBody() {
        mockStoredChannel("deepseek", "https://api.deepseek.com/v1", "deepseek-flash", "sk-saved-secret-key");
        OutboundModelConfigService custom = serviceWithChatCall((endpoint, key, payload, timeout) ->
                new OutboundModelConfigService.Upstream(401,
                        "{\"error\":{\"message\":\"invalid api key\"}}"));

        Map<String, Object> res = custom.testConnection("deepseek", "", "", "");

        assertEquals(Boolean.FALSE, res.get("success"));
        assertEquals(Boolean.FALSE, res.get("reachable"));
        assertEquals(401, res.get("statusCode"));
        assertTrue(String.valueOf(res.get("error")).contains("invalid api key"),
                "应展示 Mock 上游响应中的错误信息");
    }
}
