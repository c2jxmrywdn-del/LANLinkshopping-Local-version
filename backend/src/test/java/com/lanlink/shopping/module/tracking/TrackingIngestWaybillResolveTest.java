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
import com.lanlink.shopping.module.tracking.security.UrlTokenVerifier;
import com.lanlink.shopping.module.tracking.security.WebhookVerifierRegistry;
import com.lanlink.shopping.module.tracking.service.TrackingIngestService;
import com.lanlink.shopping.service.LogisticsWaybillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回调运单解析单测（W3 链路贯通修复）：
 * 渠道推送携带平台运单号（LL 前缀）而发货未传 logisticsNo 时 t_logistics_waybill.logistics_no 为 NULL，
 * 仅按 logistics_no 等值匹配会 waybill==null → 全部推送 failEvent、轨迹零落库。
 * 解析必须两级匹配：logistics_no 优先、waybill_no 回退。
 */
class TrackingIngestWaybillResolveTest {

    private static final String TOKEN = "dev-callback-token";
    private static final String BODY =
            "{\"eventId\":\"EVT-RESOLVE-1\",\"logisticsNo\":\"LL202611060001\","
                    + "\"nodes\":[{\"time\":\"2026-11-06 12:30:00\",\"location\":\"苏州\","
                    + "\"description\":\"包裹已到达苏州转运中心\"}]}";

    private TrackingEventMapper eventMapper;
    private LogisticsWaybillMapper waybillMapper;
    private LogisticsWaybillService waybillService;
    private TrackingIngestService ingest;

    @BeforeEach
    void setUp() {
        TrackingProperties properties = new TrackingProperties();
        properties.setCallbackUrlToken(TOKEN);

        eventMapper = mock(TrackingEventMapper.class);
        TrackingCallbackLogMapper callbackLogMapper = mock(TrackingCallbackLogMapper.class);
        waybillMapper = mock(LogisticsWaybillMapper.class);
        waybillService = mock(LogisticsWaybillService.class);

        when(eventMapper.insert(any(TrackingEvent.class)))
                .thenAnswer(inv -> {
                    inv.getArgument(0, TrackingEvent.class).setEventId(11L);
                    return 1;
                });

        ingest = new TrackingIngestService(
                new WebhookVerifierRegistry(List.of(new UrlTokenVerifier(properties))),
                new CarrierAdapterRegistry(List.of(new MockCarrierAdapter()), properties),
                eventMapper, callbackLogMapper, waybillMapper, waybillService,
                new CallbackDesensitizer());
    }

    @Test
    @DisplayName("logistics_no 未命中按 waybill_no 回退：轨迹落库到真实运单（syncTrace 用回退到的运单 ID）")
    void waybillNoFallbackResolvesWhenLogisticsNoMissing() {
        LogisticsWaybill wb = new LogisticsWaybill();
        wb.setWaybillId(88L);
        wb.setWaybillNo("LL202611060001");
        wb.setLogisticsNo(null); // 发货未传 logisticsNo 的现网形态
        when(waybillMapper.selectOne(any())).thenReturn(null, wb); // 第1次 logistics_no 未命中，第2次 waybill_no 命中
        when(waybillService.syncTrace(anyLong(), anyList())).thenReturn(Map.of("imported", 1));

        TrackingIngestService.CallbackAck ack = ingest.handle(
                "mock", BODY, Map.of(), "/logistics/callback/mock/" + TOKEN, TOKEN, "10.1.0.1");

        assertEquals(200, ack.httpStatus());
        assertEquals(Boolean.TRUE, ack.body().get("success"));
        verify(waybillService).syncTrace(eq(88L), anyList());

        ArgumentCaptor<TrackingEvent> updated = ArgumentCaptor.forClass(TrackingEvent.class);
        verify(eventMapper).updateById(updated.capture());
        assertEquals(TrackingEvent.STATUS_NORMALIZED, updated.getValue().getStatus());
        assertEquals(88L, updated.getValue().getWaybillId());
    }

    @Test
    @DisplayName("两级匹配均未命中：event=failed + 渠道成功应答（§4.5 永久错误不重推，补拉兜底）")
    void unknownWaybillNoMarksEventFailedWithChannelSuccessAck() {
        when(waybillMapper.selectOne(any())).thenReturn(null, null);

        TrackingIngestService.CallbackAck ack = ingest.handle(
                "mock", BODY, Map.of(), "/logistics/callback/mock/" + TOKEN, TOKEN, "10.1.0.1");

        assertEquals(200, ack.httpStatus());
        assertEquals(Boolean.TRUE, ack.body().get("success"));
        verify(waybillService, never()).syncTrace(anyLong(), anyList());

        ArgumentCaptor<TrackingEvent> updated = ArgumentCaptor.forClass(TrackingEvent.class);
        verify(eventMapper).updateById(updated.capture());
        assertEquals(TrackingEvent.STATUS_FAILED, updated.getValue().getStatus());
        assertNotNull(updated.getValue().getErrorMsg());
    }
}
