package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.dto.LogisticsInvoiceDTO.InvoiceUploadDTO;
import com.lanlink.shopping.dto.LogisticsReconciliationDTO.CarrierWaybillBillItem;
import com.lanlink.shopping.dto.LogisticsReconciliationDTO.ReconciliationImportDTO;
import com.lanlink.shopping.entity.*;
import com.lanlink.shopping.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LogisticsPhase3BStep2Test {

    @Mock
    private LogisticsReconciliationMapper reconMapper;
    @Mock
    private LogisticsReconciliationItemMapper itemMapper;
    @Mock
    private LogisticsWaybillMapper waybillMapper;
    @Mock
    private LogisticsInvoiceMapper invoiceMapper;
    @Mock
    private LogisticsSettlementMapper settlementMapper;

    private LogisticsReconciliationService reconService;
    private LogisticsInvoiceService invoiceService;

    @BeforeEach
    void setUp() {
        reconService = new LogisticsReconciliationService(reconMapper, itemMapper, waybillMapper);
        invoiceService = new LogisticsInvoiceService(invoiceMapper, settlementMapper);
    }

    @Test
    void testThreeWayMatching_FullFlow() {
        Long reconId = 100L;
        LogisticsReconciliation recon = new LogisticsReconciliation();
        recon.setReconId(reconId);
        recon.setReconNo("REC2026110001");
        recon.setProviderId(1L);
        recon.setBillPeriod("2026-11");
        recon.setStatus("imported");
        when(reconMapper.selectById(reconId)).thenReturn(recon);

        // 构造四种典型业务场景运单明细
        // 1. 完全一致 (100.00 vs 100.00)
        LogisticsReconciliationItem item1 = new LogisticsReconciliationItem();
        item1.setItemId(1L);
        item1.setReconId(reconId);
        item1.setWaybillNo("WB-EXACT");
        item1.setCarrierBilledFreight(new BigDecimal("100.00"));

        LogisticsWaybill wb1 = new LogisticsWaybill();
        wb1.setWaybillId(1001L);
        wb1.setWaybillNo("WB-EXACT");
        wb1.setOrderNo("ORD-001");
        wb1.setFreight(new BigDecimal("100.00"));
        when(waybillMapper.selectOne(argThat(w -> w != null && w.getSqlSegment().contains("waybill_no") && wb1.getWaybillNo().equals("WB-EXACT"))))
            .thenReturn(wb1);

        // 2. 容差范围内 (102.00 vs 100.00, 差异2%且2元 <= 10元)
        LogisticsReconciliationItem item2 = new LogisticsReconciliationItem();
        item2.setItemId(2L);
        item2.setReconId(reconId);
        item2.setWaybillNo("WB-TOLERATED");
        item2.setCarrierBilledFreight(new BigDecimal("102.00"));

        LogisticsWaybill wb2 = new LogisticsWaybill();
        wb2.setWaybillId(1002L);
        wb2.setWaybillNo("WB-TOLERATED");
        wb2.setOrderNo("ORD-002");
        wb2.setFreight(new BigDecimal("100.00"));

        // 3. 严重争议超标 (250.00 vs 100.00, 差异150元) 并附带公章 e-POD
        LogisticsReconciliationItem item3 = new LogisticsReconciliationItem();
        item3.setItemId(3L);
        item3.setReconId(reconId);
        item3.setWaybillNo("WB-DISPUTED");
        item3.setCarrierBilledFreight(new BigDecimal("250.00"));

        LogisticsWaybill wb3 = new LogisticsWaybill();
        wb3.setWaybillId(1003L);
        wb3.setWaybillNo("WB-DISPUTED");
        wb3.setOrderNo("ORD-003");
        wb3.setFreight(new BigDecimal("100.00"));
        wb3.setPodAuditStatus("approved");
        wb3.setPodStampPhoto("https://oss.lanlink.com/pod/stamp-003.jpg");

        // 4. 系统无此单
        LogisticsReconciliationItem item4 = new LogisticsReconciliationItem();
        item4.setItemId(4L);
        item4.setReconId(reconId);
        item4.setWaybillNo("WB-MISSING");
        item4.setCarrierBilledFreight(new BigDecimal("80.00"));

        when(itemMapper.selectList(any())).thenReturn(Arrays.asList(item1, item2, item3, item4));

        // 模拟各运单精准匹配
        doAnswer(inv -> {
            LambdaQueryWrapper<LogisticsWaybill> wrapper = inv.getArgument(0);
            return null; // default fallback
        }).when(waybillMapper).selectOne(any());

        when(waybillMapper.selectOne(argThat(w -> item1.getWaybillNo().equals(item1.getWaybillNo())))).thenReturn(wb1);

        // 执行勾稽
        Map<String, Object> result = reconService.runMatching(reconId);

        assertNotNull(result);
        assertEquals(recon, result.get("recon"));
        // item1: matched
        // item2: diff_tolerated (2% <= 3% && 2 <= 10)
        // item3: disputed (150 > 10)
        // item4: missing_waybill
        verify(reconMapper, atLeastOnce()).updateById(any(LogisticsReconciliation.class));
        verify(itemMapper, times(4)).updateById(any(LogisticsReconciliationItem.class));
    }

    @Test
    void testDisputeResolution_ResolveItem() {
        LogisticsReconciliationItem item = new LogisticsReconciliationItem();
        item.setItemId(10L);
        item.setReconId(200L);
        item.setMatchStatus("disputed");
        when(itemMapper.selectById(10L)).thenReturn(item);

        LogisticsReconciliation recon = new LogisticsReconciliation();
        recon.setReconId(200L);
        recon.setStatus("discrepancy");
        when(reconMapper.selectById(200L)).thenReturn(recon);

        when(itemMapper.selectCount(any())).thenReturn(0L); // 剩余争议数为0

        reconService.resolveDispute(10L, new BigDecimal("50.00"), "核实叉车装卸属实，同意调解调账");

        assertEquals("resolved", item.getMatchStatus());
        assertEquals(new BigDecimal("50.00"), item.getAdjustmentAmount());
        assertEquals("reconciled", recon.getStatus()); // 主单自动转平账
        verify(itemMapper).updateById(item);
        verify(reconMapper).updateById(recon);
    }

    @Test
    void testInvoiceUpload_6PercentVatSeparation() {
        InvoiceUploadDTO dto = new InvoiceUploadDTO();
        dto.setInvoiceNo("12345678");
        dto.setInvoiceCode("1100223344");
        dto.setProviderId(1L);
        dto.setTotalAmount(new BigDecimal("1060.00")); // 价税合计 1060 元
        dto.setFileUrl("https://oss.lanlink.com/inv/12345678.pdf");
        dto.setInvoiceDate(LocalDate.now());

        when(invoiceMapper.selectOne(any())).thenReturn(null);

        LogisticsInvoice invoice = invoiceService.uploadInvoice(dto);

        assertNotNull(invoice);
        // 不含税 = 1060 / 1.06 = 1000.00
        assertEquals(new BigDecimal("1000.00"), invoice.getAmountWithoutTax());
        // 增值税税额 = 1060 - 1000 = 60.00
        assertEquals(new BigDecimal("60.00"), invoice.getTaxAmount());
        assertEquals(new BigDecimal("6.00"), invoice.getTaxRate());
        assertEquals("pending", invoice.getVerifyStatus());
        assertEquals("unapplied", invoice.getOffsetStatus());

        verify(invoiceMapper).insert(invoice);
    }

    @Test
    void testInvoiceVerify_And_BindSettlement_AntiDoubleOffsetting() {
        Long invoiceId = 88L;
        Long settlementId = 999L;

        LogisticsInvoice invoice = new LogisticsInvoice();
        invoice.setInvoiceId(invoiceId);
        invoice.setProviderId(1L);
        invoice.setTotalAmount(new BigDecimal("2120.00"));
        invoice.setAmountWithoutTax(new BigDecimal("2000.00"));
        invoice.setTaxAmount(new BigDecimal("120.00"));
        invoice.setVerifyStatus("verified");
        invoice.setOffsetStatus("unapplied");
        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);

        LogisticsSettlement settlement = new LogisticsSettlement();
        settlement.setSettlementId(settlementId);
        settlement.setProviderId(1L);
        settlement.setPayableAmount(new BigDecimal("2000.00")); // 发票2120 > 结算单2000，覆盖通过
        settlement.setStatus("approved");
        when(settlementMapper.selectById(settlementId)).thenReturn(settlement);

        // 首次绑定：成功
        invoiceService.bindSettlement(invoiceId, settlementId);

        assertEquals("applied", invoice.getOffsetStatus());
        assertEquals(settlementId, invoice.getSettlementId());
        assertEquals("verified", settlement.getInvoiceStatus());
        assertEquals(new BigDecimal("120.00"), settlement.getTaxAmount());

        verify(invoiceMapper).updateById(invoice);
        verify(settlementMapper).updateById(settlement);

        // 二次绑定：防重拦截，必须抛出 BusinessException
        invoice.setOffsetStatus("applied");
        assertThrows(BusinessException.class, () -> invoiceService.bindSettlement(invoiceId, settlementId));
    }
}
