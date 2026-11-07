package com.lanlink.shopping.module.tracking;

import com.lanlink.shopping.module.tracking.adapter.Kuaidi100Adapter;
import com.lanlink.shopping.module.tracking.adapter.NormalizedTraceNode;
import com.lanlink.shopping.module.tracking.adapter.TrackingChannel;
import com.lanlink.shopping.module.tracking.config.TrackingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快递100 适配器离线单测：推送原文解析与 abort 应答语义（§4.5）。
 * subscribe/poll 的真实 HTTP 调用依赖企业版账号（openQuestion #1），联调时另行覆盖。
 */
class TrackingKuaidi100AdapterTest {

    private final TrackingProperties properties = new TrackingProperties();
    private final Kuaidi100Adapter adapter = new Kuaidi100Adapter(properties);

    @Test
    @DisplayName("parse：polling 推送 data 数组逐节点解析，ftime 优先于 time")
    void parsesPollingPayload() {
        String body = """
                {"status":"polling","lastTime":"2026-11-06 12:00:00","data":[
                  {"ftime":"2026-11-06 10:00:00","context":"【苏州市】快件已到达苏州转运中心"},
                  {"time":"2026-11-06 09:00:00","ftime":"2026-11-06 11:30:00","context":"快件已揽收"}
                ]}
                """;
        List<NormalizedTraceNode> nodes = adapter.parse(body);
        assertEquals(2, nodes.size());
        assertEquals("【苏州市】快件已到达苏州转运中心", nodes.get(0).getDescription());
        assertEquals("快件已揽收", nodes.get(1).getDescription());
    }

    @Test
    @DisplayName("parse：abort（3 天无记录/60 天无变化）返回空节点——上层仍回成功应答终止渠道重推")
    void abortReturnsEmptyNodes() {
        assertTrue(adapter.parse("{\"status\":\"abort\",\"message\":\"3天无记录\"}").isEmpty());
    }

    @Test
    @DisplayName("configured：key/customer 任一缺失即 false（未配置走补拉顺延，不抛异常）")
    void configuredRequiresCredentials() {
        assertFalse(adapter.configured());
        properties.getKuaidi100().setKey("k1");
        assertFalse(adapter.configured());
        properties.getKuaidi100().setCustomer("c1");
        assertTrue(adapter.configured());
    }

    @Test
    @DisplayName("channel=KUAIDI100；SHA256 请求端签名可生成（HMAC-SHA256 大写 hex 64 位）")
    void channelAndSha256Sign() {
        assertEquals(TrackingChannel.KUAIDI100, adapter.channel());
        String sign = Kuaidi100Adapter.hmacSha256Upper("secret-key", "param+key+customer");
        assertEquals(64, sign.length());
        assertEquals(sign, sign.toUpperCase());
    }
}
