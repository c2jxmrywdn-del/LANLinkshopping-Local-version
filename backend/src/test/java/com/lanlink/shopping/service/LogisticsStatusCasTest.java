package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.mapper.LogisticsAlertMapper;
import com.lanlink.shopping.mapper.LogisticsProviderMapper;
import com.lanlink.shopping.mapper.LogisticsTemplateMapper;
import com.lanlink.shopping.mapper.LogisticsTraceMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillItemMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import com.lanlink.shopping.mapper.OrderItemMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 运单状态 CAS（R17）。
 *
 * <p>缺陷形态：状态机只在内存校验，落库走 {@code updateById}（WHERE 只有主键）。
 * 两个并发事务各自读到旧状态都能通过校验，后提交者无条件覆盖——
 * 已签收（DELIVERED 终态）能被迟到的轨迹回调改回「派送中」，
 * 而订单可能已是「已完成」，状态永久错乱。
 *
 * <p>修复后：写入带 {@code AND status = expected}，抢不到就拒绝/跳过。
 */
class LogisticsStatusCasTest {

    private LogisticsWaybillMapper waybillMapper;
    private LogisticsWaybillService service;

    @BeforeEach
    void setUp() {
        waybillMapper = mock(LogisticsWaybillMapper.class);
        service = new LogisticsWaybillService(
                waybillMapper,
                mock(LogisticsTraceMapper.class),
                mock(LogisticsAlertMapper.class),
                mock(LogisticsProviderMapper.class),
                mock(LogisticsTemplateMapper.class),
                mock(OrderMapper.class),
                new LogisticsFreightCalculator(),
                new LogisticsTraceParser(),
                mock(MessageService.class),
                mock(AuditService.class),
                mock(SequenceService.class),
                mock(OrderItemMapper.class),
                mock(LogisticsWaybillItemMapper.class));
        ReflectionTestUtils.setField(service, "documentMapper",
                mock(com.lanlink.shopping.mapper.LogisticsDocumentMapper.class));
        ReflectionTestUtils.setField(service, "podRecordMapper",
                mock(com.lanlink.shopping.mapper.LogisticsPodRecordMapper.class));
    }

    private LogisticsWaybill waybill(String status) {
        LogisticsWaybill wb = new LogisticsWaybill();
        wb.setWaybillId(9L);
        wb.setWaybillNo("WB-9");
        wb.setOrderNo("ORD-9");
        wb.setStatus(status);
        return wb;
    }

    @Test
    @DisplayName("CAS 抢不到（状态已被并发修改）时抛 409，不做覆盖写")
    void transitRejectsWhenStatusChangedConcurrently() {
        when(waybillMapper.selectById(9L)).thenReturn(waybill("in_transit"));
        // 模拟：另一个事务已经把状态改成 delivered，本次 CAS 影响 0 行
        when(waybillMapper.transitStatusAtomic(9L, "in_transit", "out_for_delivery")).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.transit(9L, "out_for_delivery", null, null, null));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("状态已变化"), ex.getMessage());
    }

    @Test
    @DisplayName("CAS 成功才继续写轨迹与审计")
    void transitProceedsWhenCasSucceeds() {
        when(waybillMapper.selectById(9L)).thenReturn(waybill("in_transit"));
        when(waybillMapper.transitStatusAtomic(9L, "in_transit", "out_for_delivery")).thenReturn(1);

        LogisticsWaybill result = service.transit(9L, "out_for_delivery", "已交接快递员", null, null);

        assertEquals("out_for_delivery", result.getStatus());
        verify(waybillMapper).transitStatusAtomic(9L, "in_transit", "out_for_delivery");
    }

    @Test
    @DisplayName("终态签收后不允许再流转（内存状态机先拦）")
    void deliveredIsTerminalInStateMachine() {
        when(waybillMapper.selectById(9L)).thenReturn(waybill("delivered"));

        assertThrows(BusinessException.class,
                () -> service.transit(9L, "in_transit", null, null, null));
        verify(waybillMapper, never()).transitStatusAtomic(anyLong(), anyString(), anyString());
    }
}
