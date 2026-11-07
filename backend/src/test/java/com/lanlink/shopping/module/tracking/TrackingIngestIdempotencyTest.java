package com.lanlink.shopping.module.tracking;

import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import com.lanlink.shopping.module.tracking.adapter.CarrierAdapterRegistry;
import com.lanlink.shopping.module.tracking.adapter.MockCarrierAdapter;
import com.lanlink.shopping.module.tracking.config.TrackingProperties;
import com.lanlink.shopping.module.tracking.domain.TrackingEvent;
import com.lanlink.shopping.module.tracking.mapper.TrackingCallbackLogMapper;
import com.lanlink.shopping.module.tracking.mapper.TrackingEventMapper;
import com.lanlink.shopping.module.tracking.security.CallbackDesensitizer;
import com.lanlink.shopping.module.tracking.security.Kuaidi100Verifier;
import com.lanlink.shopping.module.tracking.security.UrlTokenVerifier;
import com.lanlink.shopping.module.tracking.security.WebhookVerifierRegistry;
import com.lanlink.shopping.module.tracking.service.TrackingIngestService;
import com.lanlink.shopping.service.LogisticsWaybillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回调幂等单测（out/logistics-tracking-plan.md §3.2 幂等分层第 1 层 / G2-2 离线部分）：
 * 同 (provider_code, external_event_id) 重复推送，uk_event INSERT 冲突 →
 * 渠道语义成功应答 + 解析/syncTrace 零重复（tracking_event 恰 1 行由库内唯一键保证，
 * 本测以 insert 第二次抛 DuplicateKeyException 模拟该约束）。
 */
class TrackingIngestIdempotencyTest {

    private static final String TOKEN = "dev-callback-token";
    private static final String BODY =
            "{\"eventId\":\"EVT-DUP-1\",\"logisticsNo\":\"SF123456789\","
                    + "\"nodes\":[{\"time\":\"2026-11-06 10:00:00\",\"location\":\"苏州\","
                    + "\"description\":\"包裹已到达苏州转运中心\"}]}";

    private TrackingEventMapper eventMapper;
    private LogisticsWaybillService waybillService;
    private TrackingIngestService ingest;

    @BeforeEach
    void setUp() {
        TrackingProperties properties = new TrackingProperties();
        properties.setCallbackUrlToken(TOKEN);

        eventMapper = mock(TrackingEventMapper.class);
        TrackingCallbackLogMapper callbackLogMapper = mock(TrackingCallbackLogMapper.class);
        LogisticsWaybillMapper waybillMapper = mock(LogisticsWaybillMapper.class);
        waybillService = mock(LogisticsWaybillService.class);

        when(eventMapper.insert(any(TrackingEvent.class)))
                .thenAnswer(inv -> {
                    inv.getArgument(0, TrackingEvent.class).setEventId(1L);
                    return 1;
                })
                .thenThrow(new DuplicateKeyException("uk_event"));
        LogisticsWaybill wb = new LogisticsWaybill();
        wb.setWaybillId(77L);
        wb.setLogisticsNo("SF123456789");
        when(waybillMapper.selectOne(any())).thenReturn(wb);
        when(waybillService.syncTrace(anyLong(), anyList())).thenReturn(Map.of("imported", 1));

        ingest = new TrackingIngestService(
                new WebhookVerifierRegistry(List.of(new UrlTokenVerifier(properties))),
                new CarrierAdapterRegistry(List.of(new MockCarrierAdapter()), properties),
                eventMapper, callbackLogMapper, waybillMapper, waybillService,
                new CallbackDesensitizer());
    }

    @Test
    @DisplayName("同事件推送 2 次：两次均成功应答，tracking_event 插入尝试 2 次（第 2 次冲突）但 syncTrace 恰 1 次")
    void duplicatePushRunsSyncTraceExactlyOnce() {
        TrackingIngestService.CallbackAck first = ingest.handle(
                "mock", BODY, Map.of(), "/logistics/callback/mock/" + TOKEN, TOKEN, "10.1.0.1");
        TrackingIngestService.CallbackAck second = ingest.handle(
                "mock", BODY, Map.of(), "/logistics/callback/mock/" + TOKEN, TOKEN, "10.1.0.1");

        assertEquals(200, first.httpStatus());
        assertEquals(200, second.httpStatus());
        assertEquals(Boolean.TRUE, second.body().get("success"));

        verify(eventMapper, times(2)).insert(any(TrackingEvent.class));
        verify(waybillService, times(1)).syncTrace(anyLong(), anyList());
    }
}
