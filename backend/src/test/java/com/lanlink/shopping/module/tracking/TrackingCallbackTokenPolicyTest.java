package com.lanlink.shopping.module.tracking;

import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import com.lanlink.shopping.module.tracking.adapter.CarrierAdapterRegistry;
import com.lanlink.shopping.module.tracking.adapter.MockCarrierAdapter;
import com.lanlink.shopping.module.tracking.config.TrackingProperties;
import com.lanlink.shopping.module.tracking.domain.TrackingCallbackLog;
import com.lanlink.shopping.module.tracking.domain.TrackingEvent;
import com.lanlink.shopping.module.tracking.mapper.TrackingCallbackLogMapper;
import com.lanlink.shopping.module.tracking.mapper.TrackingEventMapper;
import com.lanlink.shopping.module.tracking.security.CallbackDesensitizer;
import com.lanlink.shopping.module.tracking.security.Kuaidi100Verifier;
import com.lanlink.shopping.module.tracking.security.UrlTokenVerifier;
import com.lanlink.shopping.module.tracking.security.WebhookVerifier;
import com.lanlink.shopping.module.tracking.security.WebhookVerifierRegistry;
import com.lanlink.shopping.module.tracking.service.TrackingIngestService;
import com.lanlink.shopping.service.LogisticsWaybillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回调 URL token 弱口令策略单测（审计 P1-3）。
 *
 * <p>缺陷背景：`application.yml` 曾把 `tracking.callback-url-token` 默认成
 * `dev-callback-token`，compose 又没注入 `TRACKING_*`，导致容器内实际使用该默认值，
 * 任意人可 `POST /logistics/callback/track17/dev-callback-token` 拿到 200 并写入
 * `tracking_callback_log(verify_result=PASS)`。
 *
 * <p>本测试锁定三点：
 * ① 空 / 历史默认值 / 短 token 一律验签失败（与 profile 无关）；
 * ② 未实现适配器的渠道（track17/kdniao/aftership）验签通过也返回 404，且不写 PASS 审计；
 * ③ 合法长 token 的 mock 回调仍然 200，保证演示链路不被误伤。
 */
class TrackingCallbackTokenPolicyTest {

    private static final String STRONG_TOKEN = "unit-callback-token-0123456789abcdef";
    private static final String BODY =
            "{\"eventId\":\"EVT-POLICY-1\",\"logisticsNo\":\"SF123456789\","
                    + "\"nodes\":[{\"time\":\"2026-11-06 10:00:00\",\"location\":\"苏州\","
                    + "\"description\":\"包裹已到达苏州转运中心\"}]}";

    private TrackingProperties properties;
    private TrackingEventMapper eventMapper;
    private TrackingCallbackLogMapper callbackLogMapper;
    private LogisticsWaybillMapper waybillMapper;
    private LogisticsWaybillService waybillService;

    @BeforeEach
    void setUp() {
        properties = new TrackingProperties();
        properties.setCallbackUrlToken(STRONG_TOKEN);

        eventMapper = mock(TrackingEventMapper.class);
        callbackLogMapper = mock(TrackingCallbackLogMapper.class);
        waybillMapper = mock(LogisticsWaybillMapper.class);
        waybillService = mock(LogisticsWaybillService.class);

        when(eventMapper.insert(any(TrackingEvent.class))).thenAnswer(inv -> {
            inv.getArgument(0, TrackingEvent.class).setEventId(1L);
            return 1;
        });
        LogisticsWaybill waybill = new LogisticsWaybill();
        waybill.setWaybillId(77L);
        waybill.setLogisticsNo("SF123456789");
        when(waybillMapper.selectOne(any())).thenReturn(waybill);
        when(waybillService.syncTrace(any(), anyList())).thenReturn(Map.of("imported", 1));
    }

    @ParameterizedTest(name = "弱 token [{0}] 应被判定为弱")
    @ValueSource(strings = {"", "   ", "dev-callback-token", "DEV-CALLBACK-TOKEN", "short", "0123456789abcde",
            "your_callback_url_token_here", "CHANGEME-0123456789abcdef"})
    @DisplayName("isWeakCallbackToken：空/历史默认值/短于 16 位/模板占位符均为弱")
    void weakTokenDetection(String token) {
        assertTrue(TrackingProperties.isWeakCallbackToken(token));
    }

    @Test
    @DisplayName("isWeakCallbackToken：16 位以上且非默认值视为合格")
    void strongTokenAccepted() {
        assertFalse(TrackingProperties.isWeakCallbackToken(STRONG_TOKEN));
        assertFalse(TrackingProperties.isWeakCallbackToken("0123456789abcdef"));
    }

    @ParameterizedTest(name = "弱 token [{0}] 验签失败")
    @ValueSource(strings = {"", "dev-callback-token", "short"})
    @DisplayName("UrlTokenVerifier：弱配置一律 fail-closed（不区分 profile）")
    void urlTokenVerifierRejectsWeakConfig(String configured) {
        TrackingProperties weak = new TrackingProperties();
        weak.setCallbackUrlToken(configured);
        UrlTokenVerifier verifier = new UrlTokenVerifier(weak);

        WebhookVerifier.VerifyResult result = verifier.verify(new WebhookVerifier.VerifyRequest(
                "mock", BODY, Map.of(), "/logistics/callback/mock/" + configured, configured));
        assertFalse(result.pass(), "弱 token 不应通过验签: " + configured);
        assertTrue(result.reason().contains("not configured"));
    }

    @Test
    @DisplayName("历史默认值即使请求侧完全匹配也拒绝（曾经可 200 的伪造路径）")
    void legacyDefaultTokenNoLongerAccepted() {
        TrackingProperties legacy = new TrackingProperties();
        legacy.setCallbackUrlToken(TrackingProperties.LEGACY_DEV_TOKEN);
        UrlTokenVerifier verifier = new UrlTokenVerifier(legacy);

        WebhookVerifier.VerifyResult result = verifier.verify(new WebhookVerifier.VerifyRequest(
                "track17", BODY, Map.of(),
                "/logistics/callback/track17/" + TrackingProperties.LEGACY_DEV_TOKEN,
                TrackingProperties.LEGACY_DEV_TOKEN));
        assertFalse(result.pass());
    }

    @Test
    @DisplayName("合法长 token → mock 回调 200，syncTrace 收到节点")
    void strongTokenCallbackStillWorks() {
        TrackingIngestService ingest = newIngest();
        TrackingIngestService.CallbackAck ack = ingest.handle(
                "mock", BODY, Map.of(),
                "/logistics/callback/mock/" + STRONG_TOKEN, STRONG_TOKEN, "10.0.0.5");

        assertEquals(200, ack.httpStatus());
        assertEquals(Boolean.TRUE, ack.body().get("success"));
        verify(waybillService).syncTrace(any(), anyList());
    }

    @Test
    @DisplayName("track17 适配器未实现 → 404 CHANNEL_NOT_IMPLEMENTED，且不写 PASS 审计")
    void unimplementedChannelRejectedWithoutPassAudit() {
        TrackingIngestService ingest = newIngest();
        TrackingIngestService.CallbackAck ack = ingest.handle(
                "track17", BODY, Map.of(),
                "/logistics/callback/track17/" + STRONG_TOKEN, STRONG_TOKEN, "10.0.0.6");

        assertEquals(404, ack.httpStatus());
        assertEquals("CHANNEL_NOT_IMPLEMENTED", ack.body().get("errorCode"));

        ArgumentCaptor<TrackingCallbackLog> captor = ArgumentCaptor.forClass(TrackingCallbackLog.class);
        verify(callbackLogMapper).insert(captor.capture());
        TrackingCallbackLog row = captor.getValue();
        assertEquals(TrackingCallbackLog.VERIFY_FAIL, row.getVerifyResult(),
                "未实现渠道绝不能留下 PASS 审计（审计 P1-3 的核心回归点）");
        assertEquals(404, row.getHttpStatus());
        verify(eventMapper, never()).insert(any(TrackingEvent.class));
        verify(waybillService, never()).syncTrace(any(), anyList());
    }

    private TrackingIngestService newIngest() {
        return new TrackingIngestService(
                new WebhookVerifierRegistry(List.of(
                        new UrlTokenVerifier(properties),
                        new Kuaidi100Verifier(properties))),
                new CarrierAdapterRegistry(List.of(new MockCarrierAdapter()), properties),
                eventMapper, callbackLogMapper, waybillMapper,
                waybillService, new CallbackDesensitizer());
    }
}
