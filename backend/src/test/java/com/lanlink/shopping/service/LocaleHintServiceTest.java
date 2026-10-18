package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.config.LocaleHintProperties;
import com.lanlink.shopping.social.SocialHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 语言识别的服务端地理提示单测。
 *
 * 覆盖：国家 → 语言映射、XFF 取真实 IP、内网地址跳过外发、查询失败静默降级、
 * 缓存避免重复外发、开关关闭时不外发，以及多家服务响应字段的兼容解析。
 */
class LocaleHintServiceTest {

    private LocaleHintProperties props;
    private SocialHttp http;
    private LocaleHintService service;

    @BeforeEach
    void setUp() {
        props = new LocaleHintProperties();
        http = mock(SocialHttp.class);
        service = new LocaleHintService(props, http);
    }

    private Map<String, Object> hint(String ip) {
        return service.hint(ip);
    }

    /* ---------------- 正常路径 ---------------- */

    @Test
    void publicIpIsLookedUpAndChineseCountryMapsToZh() {
        when(http.get(contains("1.2.3.4"), any(), anyLong())).thenReturn("{\"country_code\":\"CN\"}");
        Map<String, Object> out = hint("1.2.3.4");
        assertEquals("CN", out.get("country"));
        assertEquals("zh-CN", out.get("locale"));
        assertEquals("geo", out.get("source"));
    }

    @Test
    void foreignCountryMapsToEnglish() {
        when(http.get(anyString(), any(), anyLong())).thenReturn("{\"countryCode\":\"DE\"}");
        Map<String, Object> out = hint("8.8.8.8");
        assertEquals("en-US", out.get("locale"));
    }

    @Test
    void extractCountrySupportsProviderFieldVariants() {
        assertEquals("JP", LocaleHintService.extractCountry("{\"country_code\":\"JP\"}"));
        assertEquals("JP", LocaleHintService.extractCountry("{\"countryCode\":\"jp\"}"));
        assertEquals("JP", LocaleHintService.extractCountry("{\"country\":\"JP\"}"));
        assertNull(LocaleHintService.extractCountry("{\"city\":\"Tokyo\"}"));
        assertNull(LocaleHintService.extractCountry("not-json"));
        assertNull(LocaleHintService.extractCountry(null));
    }

    @Test
    void cachePreventsRepeatedOutboundCalls() {
        when(http.get(anyString(), any(), anyLong())).thenReturn("{\"country_code\":\"CN\"}");
        hint("1.2.3.4");
        hint("1.2.3.4");
        verify(http, times(1)).get(anyString(), any(), anyLong());
        assertEquals(1, service.cacheSize());
    }

    /* ---------------- 边界与异常 ---------------- */

    @Test
    void privateAndLoopbackAddressesSkipOutboundEntirely() {
        assertEquals("private", hint("127.0.0.1").get("source"));
        assertEquals("private", hint("192.168.1.20").get("source"));
        assertEquals("private", hint("10.0.0.5").get("source"));
        assertEquals("private", hint("172.20.3.1").get("source"));
        assertEquals("private", hint("::1").get("source"));
        assertEquals("private", hint(null).get("source"));
        verifyNoInteractions(http);
    }

    @Test
    void routablePublicIpIsNotTreatedAsPrivate() {
        when(http.get(anyString(), any(), anyLong())).thenReturn("{\"country_code\":\"US\"}");
        assertEquals("geo", hint("172.32.0.1").get("source"), "172.32.x 不在私网段内");
        assertEquals("geo", hint("11.0.0.1").get("source"));
    }

    @Test
    void lookupFailureDegradesSilently() {
        when(http.get(anyString(), any(), anyLong())).thenThrow(new BusinessException("无法访问"));
        Map<String, Object> out = hint("1.2.3.4");
        assertEquals("unavailable", out.get("source"));
        assertNull(out.get("locale"), "查询失败必须静默降级，不得臆断语言");
    }

    @Test
    void unparsableResponseDegradesSilently() {
        when(http.get(anyString(), any(), anyLong())).thenReturn("<html>captcha</html>");
        assertEquals("unavailable", hint("1.2.3.4").get("source"));
    }

    @Test
    void disabledSwitchAvoidsOutbound() {
        props.getGeo().setEnabled(false);
        assertEquals("disabled", hint("1.2.3.4").get("source"));
        verifyNoInteractions(http);
    }

    @Test
    void timeoutIsPassedFromConfig() {
        props.getGeo().setTimeoutMs(350L);
        when(http.get(anyString(), any(), anyLong())).thenReturn("{\"country_code\":\"CN\"}");
        hint("1.2.3.4");
        verify(http).get(anyString(), any(), eq(350L));
    }

    /* ---------------- 客户端 IP 解析 ---------------- */

    @Test
    void clientIpPrefersFirstForwardedEntry() {
        assertEquals("203.0.113.9", LocaleHintService.clientIp("203.0.113.9, 10.0.0.1", null, "127.0.0.1"));
        assertEquals("203.0.113.9", LocaleHintService.clientIp("unknown, 203.0.113.9", null, "127.0.0.1"));
        assertEquals("198.51.100.7", LocaleHintService.clientIp(null, "198.51.100.7", "127.0.0.1"));
        assertEquals("127.0.0.1", LocaleHintService.clientIp(null, null, "127.0.0.1"));
        assertEquals("127.0.0.1", LocaleHintService.clientIp("", "unknown", "127.0.0.1"));
    }

    @Test
    void localeOfHandlesInvalidInput() {
        assertEquals("zh-CN", LocaleHintService.localeOf("TW"));
        assertEquals("en-US", LocaleHintService.localeOf("FR"));
        assertNull(LocaleHintService.localeOf("CHN"));
        assertNull(LocaleHintService.localeOf(null));
    }
}
