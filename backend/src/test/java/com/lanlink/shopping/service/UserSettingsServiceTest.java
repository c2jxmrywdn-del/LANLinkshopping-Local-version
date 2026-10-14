package com.lanlink.shopping.service;

import com.lanlink.shopping.entity.UserSettings;
import com.lanlink.shopping.mapper.UserSettingsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 系统设置服务：默认值、部分 patch 深合并、整存往返、恢复默认
 */
class UserSettingsServiceTest {

    private UserSettingsMapper mapper;
    private UserSettingsService service;

    @BeforeEach
    void setUp() {
        mapper = mock(UserSettingsMapper.class);
        service = new UserSettingsService(mapper);
    }

    @Test
    void defaultSettingsWhenNoRow() {
        when(mapper.selectById(1L)).thenReturn(null);
        Map<String, Object> s = service.get(1L);
        assertEquals("system", ((Map<?, ?>) s.get("appearance")).get("theme"));
        assertEquals("zh-CN", ((Map<?, ?>) s.get("appearance")).get("language"));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) s.get("notify")).get("sms"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) s.get("privacy")).get("recommend"));
    }

    @Test
    void patchMergesDeeplyAndPersistsFullJson() {
        when(mapper.selectById(1L)).thenReturn(null);

        Map<String, Object> merged = service.patch(1L, Map.of("notify", Map.of("sms", true)));

        // 深合并：patch 字段生效，其余保持默认
        assertEquals(Boolean.TRUE, ((Map<?, ?>) merged.get("notify")).get("sms"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) merged.get("notify")).get("email"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) ((Map<?, ?>) merged.get("notify")).get("groups")).get("order"));
        assertEquals("standard", ((Map<?, ?>) merged.get("appearance")).get("fontSize"));

        // 落库：整存完整 JSON（含默认字段），可读回
        ArgumentCaptor<UserSettings> captor = ArgumentCaptor.forClass(UserSettings.class);
        verify(mapper).insert(captor.capture());
        String json = captor.getValue().getSettingsJson();
        assertTrue(json.contains("\"sms\":true"));
        assertTrue(json.contains("\"groups\""));

        when(mapper.selectById(1L)).thenReturn(captor.getValue());
        Map<String, Object> reread = service.get(1L);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) reread.get("notify")).get("sms"));
    }

    @Test
    void nestedPatchOverridesOnlyGivenLeaf() {
        Map<String, Object> s = service.patch(1L, Map.of("appearance", Map.of("theme", "dark")));
        Map<?, ?> appearance = (Map<?, ?>) s.get("appearance");
        assertEquals("dark", appearance.get("theme"));
        assertEquals("zh-CN", appearance.get("language"), "未改动的叶子字段应保留");
    }

    @Test
    void resetReturnsDefaults() {
        Map<String, Object> def = service.reset(1L);
        assertEquals(Boolean.FALSE, ((Map<?, ?>) def.get("notify")).get("sms"));
        assertEquals("system", ((Map<?, ?>) def.get("appearance")).get("theme"));
        verify(mapper, times(1)).insert(any(UserSettings.class));
    }
}