package com.lanlink.shopping.support;

import com.lanlink.shopping.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

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
}
