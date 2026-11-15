package com.lanlink.shopping.support.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配额服务：Lua 返回值语义、Redis 故障的 fail-closed 行为、键名不泄露原始标识。
 */
@ExtendWith(MockitoExtension.class)
class SupportMediaQuotaServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private final SupportMediaProperties props = new SupportMediaProperties();

    private SupportMediaQuotaService service() {
        return new SupportMediaQuotaService(redis, props);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reserveReturnsTrueWhenLuaAllows() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);
        when(redis.opsForValue()).thenReturn(valueOps);

        assertTrue(service().reserve("SM1", "2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f",
                "10.0.0.8", "conv-1", "msg-1"));
        verify(valueOps).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reserveReturnsFalseWhenAnyDimensionExceeded() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0L);

        assertFalse(service().reserve("2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f",
                "10.0.0.8", "conv-1", "msg-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsClosedByDefaultWhenRedisThrows() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("redis down"));

        assertFalse(service().reserve("2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f",
                "10.0.0.8", "conv-1", "msg-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void canOptOutOfFailClosedForDemoEnvironment() {
        props.setRedisFailClosed(false);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("redis down"));

        assertTrue(service().reserve("2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f",
                "10.0.0.8", "conv-1", "msg-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void quotaKeysNeverContainRawVisitorOrIp() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);
        when(redis.opsForValue()).thenReturn(valueOps);

        String visitor = "2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f";
        String ip = "203.0.113.9";
        service().reserve("SM1", visitor, ip, "conv-1", "msg-1");

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keysCaptor.capture(),
                any(), any(), any(), any(), any(), any());

        List<String> keys = keysCaptor.getValue();
        assertEquals(3, keys.size());
        for (String key : keys) {
            assertFalse(key.contains(visitor), "配额键不应包含访客令牌明文");
            assertFalse(key.contains(ip), "配额键不应包含 IP 明文");
        }
    }
}
