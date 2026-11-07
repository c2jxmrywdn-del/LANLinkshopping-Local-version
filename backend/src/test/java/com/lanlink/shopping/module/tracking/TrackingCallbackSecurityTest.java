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
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回调安全矩阵单测（out/logistics-tracking-plan.md G1-3 的可离线部分）：
 * ①无 token → 401；②伪造 token → 401 且 callback_log 新增 FAIL 行；
 * ③FAIL 行 body_raw 不匹配手机号正则（脱敏红线，夹具故意携带手机号/地址）；
 * ④合法 Mock token → 200 且 syncTrace 收到解析节点；⑤快递100 MD5(param+salt) 正负例。
 * （第 121 次 POST → 429 的集成侧断言依赖容器/数据库，见 TrackingRateLimitRegistrationTest 说明。）
 */
class TrackingCallbackSecurityTest {

    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    private static final String TOKEN = "dev-callback-token";

    private TrackingEventMapper eventMapper;
    private TrackingCallbackLogMapper callbackLogMapper;
    private LogisticsWaybillMapper waybillMapper;
    private LogisticsWaybillService waybillService;
    private TrackingIngestService ingest;

    /** 夹具故意携带手机号/地址（红线断言的数据源） */
    private static final String BODY = """
            {"eventId":"EVT-1001","logisticsNo":"SF123456789",
             "receiverPhone":"13812345678",
             "receiverAddress":"江苏省苏州市工业园区星湖街328号",
             "nodes":[
               {"time":"2026-11-06 10:00:00","location":"苏州转运中心","description":"包裹已到达苏州转运中心"},
               {"time":"2026-11-06 12:30:00","location":"无锡","description":"包裹滞留于无锡分拨中心，已超24小时"}
             ]}
            """;

    @BeforeEach
    void setUp() {
        TrackingProperties properties = new TrackingProperties();
        properties.setCallbackUrlToken(TOKEN);
        properties.getKuaidi100().setSalt("unit-salt");

        eventMapper = mock(TrackingEventMapper.class);
        callbackLogMapper = mock(TrackingCallbackLogMapper.class);
        waybillMapper = mock(LogisticsWaybillMapper.class);
        waybillService = mock(LogisticsWaybillService.class);

        when(eventMapper.insert(any(TrackingEvent.class))).thenAnswer(inv -> {
            inv.getArgument(0, TrackingEvent.class).setEventId(1L);
            return 1;
        });
        LogisticsWaybill wb = new LogisticsWaybill();
        wb.setWaybillId(77L);
        wb.setLogisticsNo("SF123456789");
        when(waybillMapper.selectOne(any())).thenReturn(wb);
        when(waybillService.syncTrace(eq(77L), anyList())).thenReturn(Map.of("imported", 2));

        MockCarrierAdapter mockAdapter = new MockCarrierAdapter();
        ingest = new TrackingIngestService(
                new WebhookVerifierRegistry(List.of(
                        new UrlTokenVerifier(properties),
                        new Kuaidi100Verifier(properties))),
                new CarrierAdapterRegistry(List.of(mockAdapter), properties),
                eventMapper, callbackLogMapper, waybillMapper, waybillService,
                new CallbackDesensitizer());
    }

    @Test
    @DisplayName("无 token 回调 → 401 SIGN_INVALID，落 FAIL 审计行且不进幂等/解析链路")
    void missingTokenRejectedWith401() {
        TrackingIngestService.CallbackAck ack = ingest.handle(
                "mock", BODY, Map.of(), "/logistics/callback/mock", null, "10.0.0.1");

        assertEquals(401, ack.httpStatus());
        assertEquals("SIGN_INVALID", ack.body().get("errorCode"));

        ArgumentCaptor<TrackingCallbackLog> captor = ArgumentCaptor.forClass(TrackingCallbackLog.class);
        verify(callbackLogMapper).insert(captor.capture());
        TrackingCallbackLog row = captor.getValue();
        assertEquals(TrackingCallbackLog.VERIFY_FAIL, row.getVerifyResult());
        assertEquals(401, row.getHttpStatus());
        assertNotNull(row.getBodyRaw());
        // 脱敏红线（G1-3 断言③）：FAIL 行原文不得残留手机号
        assertFalse(PHONE.matcher(row.getBodyRaw()).find(),
                "callback_log.body_raw 残留手机号：" + row.getBodyRaw());
        assertTrue(row.getBodyRaw().contains("138****5678"));

        verify(eventMapper, never()).insert(any(TrackingEvent.class));
        verify(waybillService, never()).syncTrace(any(), anyList());
    }

    @Test
    @DisplayName("伪造 token 回调 → 401 且 FAIL 原因记录 mismatch")
    void forgedTokenRejectedWith401() {
        TrackingIngestService.CallbackAck ack = ingest.handle(
                "mock", BODY, Map.of(), "/logistics/callback/mock/wrong-token", "wrong-token", "10.0.0.2");

        assertEquals(401, ack.httpStatus());
        ArgumentCaptor<TrackingCallbackLog> captor = ArgumentCaptor.forClass(TrackingCallbackLog.class);
        verify(callbackLogMapper).insert(captor.capture());
        assertEquals(TrackingCallbackLog.VERIFY_FAIL, captor.getValue().getVerifyResult());
        assertEquals("callback url token mismatch", captor.getValue().getFailReason());
        assertFalse(PHONE.matcher(captor.getValue().getBodyRaw()).find());
        verify(waybillService, never()).syncTrace(any(), anyList());
    }

    @Test
    @DisplayName("合法 Mock token → 200 成功应答，syncTrace 收到脱敏前解析的 2 个节点")
    void validMockTokenAccepted() {
        TrackingIngestService.CallbackAck ack = ingest.handle(
                "mock", BODY, Map.of(),
                "/logistics/callback/mock/" + TOKEN, TOKEN, "10.0.0.3");

        assertEquals(200, ack.httpStatus());
        assertEquals(Boolean.TRUE, ack.body().get("success"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> nodesCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(waybillService).syncTrace(eq(77L), nodesCaptor.capture());
        List<Map<String, Object>> nodes = nodesCaptor.getValue();
        assertEquals(2, nodes.size());
        assertEquals("包裹已到达苏州转运中心", nodes.get(0).get("description"));
        assertTrue(String.valueOf(nodes.get(1).get("description")).contains("滞留"));

        // PASS 审计行 + 事件落档（received → normalized 由 updateById 完成）
        ArgumentCaptor<TrackingCallbackLog> logCaptor = ArgumentCaptor.forClass(TrackingCallbackLog.class);
        verify(callbackLogMapper).insert(logCaptor.capture());
        assertEquals(TrackingCallbackLog.VERIFY_PASS, logCaptor.getValue().getVerifyResult());
        verify(eventMapper).insert(any(TrackingEvent.class));
        verify(eventMapper).updateById(any(TrackingEvent.class));
    }

    @Test
    @DisplayName("快递100 验签：MD5(param+salt) 32 位大写匹配通过，篡改/缺盐/缺签名均拒绝")
    void kuaidi100Md5SignVerification() throws Exception {
        TrackingProperties properties = new TrackingProperties();
        properties.getKuaidi100().setSalt("unit-salt");
        Kuaidi100Verifier verifier = new Kuaidi100Verifier(properties);

        String param = "{\"status\":\"polling\",\"lastTime\":\"2026-11-06 12:00:00\",\"data\":[]}";
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                .digest((param + "unit-salt").getBytes(StandardCharsets.UTF_8))).toUpperCase();
        String formBody = "param=" + java.net.URLEncoder.encode(param, StandardCharsets.UTF_8) + "&sign=" + expected;

        // 独立于实现重算 MD5：32 位大写比对通过
        WebhookVerifier.VerifyResult pass = verifier.verify(new WebhookVerifier.VerifyRequest(
                "kuaidi100", formBody, Map.of(), "/logistics/callback/kuaidi100", null));
        assertTrue(pass.pass(), "合法 MD5(param+salt) 应通过: " + pass.reason());

        // 篡改 param 后签名不匹配 → 拒绝
        String tampered = formBody.replace("polling", "aborting");
        assertFalse(verifier.verify(new WebhookVerifier.VerifyRequest(
                "kuaidi100", tampered, Map.of(), "/logistics/callback/kuaidi100", null)).pass());

        // 缺签名 → 拒绝
        assertFalse(verifier.verify(new WebhookVerifier.VerifyRequest(
                "kuaidi100", "param=" + param, Map.of(), "/logistics/callback/kuaidi100", null)).pass());

        // 盐未配置 → fail-closed
        properties.getKuaidi100().setSalt("");
        assertFalse(verifier.verify(new WebhookVerifier.VerifyRequest(
                "kuaidi100", formBody, Map.of(), "/logistics/callback/kuaidi100", null)).pass());
    }

    @Test
    @DisplayName("unknown 渠道无 verifier → 404，不进入幂等链路")
    void unknownProviderReturns404() {
        TrackingIngestService.CallbackAck ack = ingest.handle(
                "nosuch", BODY, Map.of(), "/logistics/callback/nosuch", null, "10.0.0.4");
        assertEquals(404, ack.httpStatus());
        verify(eventMapper, never()).insert(any(TrackingEvent.class));
    }
}
