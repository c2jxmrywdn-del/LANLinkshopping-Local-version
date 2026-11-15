package com.lanlink.shopping.support.tts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TTS 网关基址归一化。
 *
 * <p>回归缺陷：历史实现对 `lanlink-ai-gateway` 做 InetAddress 探测，失败时替换成
 * `127.0.0.1`。但后端本身运行在容器内，`127.0.0.1:8090` 指向后端自己，
 * 于是所有 TTS 请求都 502（前端表现为「朗读功能异常」）。
 */
class SupportTtsGatewayUrlTest {

    @Test
    void keepsComposeServiceNameUntouched() {
        assertEquals("http://lanlink-ai-gateway:8090/v1",
                SupportTtsService.normalizeGatewayUrl("http://lanlink-ai-gateway:8090/v1"),
                "容器内必须保留 compose 服务名，不能被改写成 127.0.0.1");
    }

    @Test
    void stripsTrailingSlashOnly() {
        assertEquals("http://lanlink-ai-gateway:8090/v1",
                SupportTtsService.normalizeGatewayUrl("http://lanlink-ai-gateway:8090/v1///"));
        assertEquals("http://127.0.0.1:8090/v1",
                SupportTtsService.normalizeGatewayUrl("http://127.0.0.1:8090/v1/"),
                "宿主机本地开发显式指定的地址原样保留");
    }

    @Test
    void blankFallsBackToComposeService() {
        assertEquals("http://lanlink-ai-gateway:8090/v1", SupportTtsService.normalizeGatewayUrl(null));
        assertEquals("http://lanlink-ai-gateway:8090/v1", SupportTtsService.normalizeGatewayUrl(""));
        assertEquals("http://lanlink-ai-gateway:8090/v1", SupportTtsService.normalizeGatewayUrl("   "));
    }

    // ==================== 音色 / 风格乱码兜底 ====================

    /**
     * 回归缺陷：`.env` 用 GBK 写入中文，docker 传入后变成「閼煎甯€」，
     * MiMo 判定为非法 voice 返回 HTTP 400 → 前端「朗读功能异常」。
     */
    @Test
    void mojibakeVoiceFallsBackToDefault() {
        assertEquals("茉莉", SupportTtsService.sanitizeVoiceOrFallback("茉莉", "茉莉"),
                "合法中文音色必须保留（保持可配置能力）");
        assertEquals("茉莉", SupportTtsService.sanitizeVoiceOrFallback("", "茉莉"));
        assertEquals("茉莉", SupportTtsService.sanitizeVoiceOrFallback(null, "茉莉"));
        // 乱码特征：问号占位 / 替换符
        assertEquals("茉莉", SupportTtsService.sanitizeVoiceOrFallback("閼煎甯€?", "茉莉"));
        assertEquals("茉莉", SupportTtsService.sanitizeVoiceOrFallback("voice\ufffd", "茉莉"));
        // 超长乱码串
        assertEquals("茉莉", SupportTtsService.sanitizeVoiceOrFallback("閼煎甯€閼煎甯€閼煎甯€閼煎甯€", "茉莉"));
    }

    @Test
    void mojibakeStyleFallsBackToDefault() {
        String def = SupportTtsService.DEFAULT_STYLE;
        assertEquals(def, SupportTtsService.sanitizeStyleOrFallback(null, def));
        assertEquals(def, SupportTtsService.sanitizeStyleOrFallback("  ", def));
        assertEquals(def, SupportTtsService.sanitizeStyleOrFallback("閹存劗鍟涘▽澶屒旈惃鍕叀?", def),
                "乱码风格必须回退，否则 MiMo 可能 400");
        assertEquals("语速稍快", SupportTtsService.sanitizeStyleOrFallback("语速稍快", def),
                "合法风格必须保留");
    }
}
