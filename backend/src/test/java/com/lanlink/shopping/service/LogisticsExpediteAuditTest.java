package com.lanlink.shopping.service;

import com.lanlink.shopping.entity.LogisticsAlert;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.mapper.LogisticsAlertMapper;
import com.lanlink.shopping.mapper.LogisticsProviderMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 一键催单留痕。
 *
 * <p>缺陷形态：{@code expediteWaybill} 只打日志、零持久化副作用，
 * 调用方却拿到「加急催单已通知承运专席」的成功提示，事后无法追溯催办是否真的发生。
 *
 * <p>修复后：每次催办写一条 t_logistics_alert(type=expedite)，
 * 与巡检告警共用同一张表和处理流程。
 */
class LogisticsExpediteAuditTest {

    private LogisticsWaybillMapper waybillMapper;
    private LogisticsAlertMapper alertMapper;
    private LogisticsSlaWatchdogService service;

    @BeforeEach
    void setUp() {
        waybillMapper = mock(LogisticsWaybillMapper.class);
        alertMapper = mock(LogisticsAlertMapper.class);
        service = new LogisticsSlaWatchdogService(waybillMapper, alertMapper,
                mock(LogisticsProviderMapper.class));
    }

    private LogisticsWaybill waybill() {
        LogisticsWaybill wb = new LogisticsWaybill();
        wb.setWaybillId(88L);
        wb.setWaybillNo("WB-88");
        return wb;
    }

    @Test
    @DisplayName("催办必须落一条 expedite 告警，供运营在告警列表里跟进")
    void expediteWritesAuditAlert() {
        when(waybillMapper.selectById(88L)).thenReturn(waybill());

        service.expediteWaybill(88L, "客户催得急");

        ArgumentCaptor<LogisticsAlert> cap = ArgumentCaptor.forClass(LogisticsAlert.class);
        verify(alertMapper).insert(cap.capture());
        LogisticsAlert a = cap.getValue();
        assertEquals(88L, a.getWaybillId());
        assertEquals("expedite", a.getAlertType());
        assertEquals("pending", a.getStatus(), "新建催办应是待处理状态");
        assertTrue(a.getContent().contains("客户催得急"), a.getContent());
    }

    @Test
    @DisplayName("备注为空时用默认文案，不产生空内容")
    void blankNoteFallsBackToDefaultText() {
        when(waybillMapper.selectById(88L)).thenReturn(waybill());

        service.expediteWaybill(88L, "   ");

        ArgumentCaptor<LogisticsAlert> cap = ArgumentCaptor.forClass(LogisticsAlert.class);
        verify(alertMapper).insert(cap.capture());
        assertTrue(cap.getValue().getContent().contains("商户一键催派"), cap.getValue().getContent());
    }

    @Test
    @DisplayName("运单不存在时不写任何告警")
    void missingWaybillWritesNothing() {
        when(waybillMapper.selectById(404L)).thenReturn(null);

        service.expediteWaybill(404L, "x");

        verify(alertMapper, never()).insert(any(LogisticsAlert.class));
    }
}
