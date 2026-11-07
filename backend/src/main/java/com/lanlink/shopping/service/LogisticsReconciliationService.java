package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.dto.LogisticsReconciliationDTO.CarrierWaybillBillItem;
import com.lanlink.shopping.dto.LogisticsReconciliationDTO.ReconciliationImportDTO;
import com.lanlink.shopping.entity.LogisticsReconciliation;
import com.lanlink.shopping.entity.LogisticsReconciliationItem;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.mapper.LogisticsReconciliationItemMapper;
import com.lanlink.shopping.mapper.LogisticsReconciliationMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 承运商月结对账单三单智能自动勾稽引擎 (Three-Way Matching)
 */
@Service
public class LogisticsReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(LogisticsReconciliationService.class);

    private final LogisticsReconciliationMapper reconMapper;
    private final LogisticsReconciliationItemMapper itemMapper;
    private final LogisticsWaybillMapper waybillMapper;

    public LogisticsReconciliationService(LogisticsReconciliationMapper reconMapper,
                                          LogisticsReconciliationItemMapper itemMapper,
                                          LogisticsWaybillMapper waybillMapper) {
        this.reconMapper = reconMapper;
        this.itemMapper = itemMapper;
        this.waybillMapper = waybillMapper;
    }

    /**
     * 导入承运商月结账单
     */
    @Transactional(rollbackFor = Exception.class)
    public LogisticsReconciliation importCarrierBill(ReconciliationImportDTO req, Long userId) {
        if (req.getProviderId() == null) {
            throw new BusinessException("承运服务商ID不能为空");
        }
        if (req.getBillPeriod() == null || !req.getBillPeriod().matches("^\\d{4}-\\d{2}$")) {
            throw new BusinessException("账单所属周期格式必须为 YYYY-MM");
        }
        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw new BusinessException("对账单导入运单明细不能为空");
        }

        // 检查是否存在同周期未结案的对账单
        LogisticsReconciliation existing = reconMapper.selectOne(
            new LambdaQueryWrapper<LogisticsReconciliation>()
                .eq(LogisticsReconciliation::getProviderId, req.getProviderId())
                .eq(LogisticsReconciliation::getBillPeriod, req.getBillPeriod())
        );
        if (existing != null && !"closed".equals(existing.getStatus())) {
            throw new BusinessException("该服务商在 " + req.getBillPeriod() + " 周期已有处理中的对账单(" + existing.getReconNo() + ")，请勿重复导入");
        }

        String reconNo = "REC" + req.getBillPeriod().replace("-", "") + String.format("%04d", (int) (System.currentTimeMillis() % 10000));
        LogisticsReconciliation recon = new LogisticsReconciliation();
        recon.setReconNo(reconNo);
        recon.setProviderId(req.getProviderId());
        recon.setBillPeriod(req.getBillPeriod());
        recon.setTotalWaybills(req.getItems().size());
        recon.setMatchedCount(0);
        recon.setDiscrepancyCount(0);
        recon.setSystemFreightTotal(BigDecimal.ZERO);
        recon.setCarrierBilledTotal(BigDecimal.ZERO);
        recon.setDiffAmount(BigDecimal.ZERO);
        recon.setStatus("imported");
        recon.setRemark(req.getRemark());
        recon.setCreatedBy(userId);
        recon.setCreateTime(LocalDateTime.now());
        recon.setUpdateTime(LocalDateTime.now());
        reconMapper.insert(recon);

        BigDecimal carrierTotal = BigDecimal.ZERO;
        for (CarrierWaybillBillItem it : req.getItems()) {
            if (it.getWaybillNo() == null || it.getWaybillNo().trim().isEmpty()) {
                continue;
            }
            BigDecimal freight = it.getCarrierBilledFreight() != null ? it.getCarrierBilledFreight() : BigDecimal.ZERO;
            carrierTotal = carrierTotal.add(freight);

            LogisticsReconciliationItem item = new LogisticsReconciliationItem();
            item.setReconId(recon.getReconId());
            item.setWaybillNo(it.getWaybillNo().trim());
            item.setCarrierBilledFreight(freight);
            item.setSystemFreight(BigDecimal.ZERO);
            item.setDiffAmount(BigDecimal.ZERO);
            item.setMatchStatus("pending");
            item.setEpodVerified(0);
            item.setAdjustmentAmount(BigDecimal.ZERO);
            item.setCreateTime(LocalDateTime.now());
            item.setUpdateTime(LocalDateTime.now());
            itemMapper.insert(item);
        }

        recon.setCarrierBilledTotal(carrierTotal);
        reconMapper.updateById(recon);
        log.info("[Reconciliation] 成功导入对账单: reconId={}, reconNo={}, items={}", recon.getReconId(), reconNo, req.getItems().size());
        return recon;
    }

    /**
     * 触发三单智能自动勾稽引擎 (系统运单 vs 承运账单 vs 现场加盖公章 e-POD)
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> runMatching(Long reconId) {
        LogisticsReconciliation recon = reconMapper.selectById(reconId);
        if (recon == null) {
            throw new BusinessException("对账单记录不存在: " + reconId);
        }

        List<LogisticsReconciliationItem> items = itemMapper.selectList(
            new LambdaQueryWrapper<LogisticsReconciliationItem>().eq(LogisticsReconciliationItem::getReconId, reconId)
        );

        int matchedCount = 0;
        int discrepancyCount = 0;
        BigDecimal systemTotal = BigDecimal.ZERO;
        BigDecimal carrierTotal = BigDecimal.ZERO;

        for (LogisticsReconciliationItem item : items) {
            BigDecimal cFreight = item.getCarrierBilledFreight() != null ? item.getCarrierBilledFreight() : BigDecimal.ZERO;
            carrierTotal = carrierTotal.add(cFreight);

            // 1. 查找系统出库运单
            LogisticsWaybill wb = waybillMapper.selectOne(
                new LambdaQueryWrapper<LogisticsWaybill>()
                    .eq(LogisticsWaybill::getWaybillNo, item.getWaybillNo())
                    .or()
                    .eq(LogisticsWaybill::getLogisticsNo, item.getWaybillNo())
            );

            if (wb == null) {
                item.setMatchStatus("missing_waybill");
                item.setDiffReason("系统无此运单记录，需核对承运商是否录错单号");
                discrepancyCount++;
                itemMapper.updateById(item);
                continue;
            }

            BigDecimal sysFreight = wb.getFreight() != null ? wb.getFreight() : BigDecimal.ZERO;
            systemTotal = systemTotal.add(sysFreight);
            item.setWaybillId(wb.getWaybillId());
            item.setOrderNo(wb.getOrderNo());
            item.setSystemFreight(sysFreight);

            BigDecimal diff = cFreight.subtract(sysFreight);
            item.setDiffAmount(diff);

            // 2. 穿透查验现场加盖公章 e-POD 回执
            if ("approved".equals(wb.getPodAuditStatus())) {
                item.setEpodVerified(1);
                item.setEpodUrl(wb.getPodStampPhoto());
            }

            // 3. 容差匹配判定: <= 3% 且 <= 10元
            BigDecimal absDiff = diff.abs();
            boolean isZeroDiff = absDiff.compareTo(BigDecimal.ZERO) == 0;
            boolean withinTolerance = sysFreight.compareTo(BigDecimal.ZERO) > 0
                && absDiff.divide(sysFreight, 4, RoundingMode.HALF_UP).compareTo(new BigDecimal("0.03")) <= 0
                && absDiff.compareTo(new BigDecimal("10.00")) <= 0;

            if (isZeroDiff) {
                item.setMatchStatus("matched");
                item.setDiffReason("金额完全吻合");
                matchedCount++;
            } else if (withinTolerance) {
                item.setMatchStatus("diff_tolerated");
                item.setDiffReason("在3%合理计重公差内(<=10元)，系统自动平账");
                matchedCount++;
            } else {
                item.setMatchStatus("disputed");
                item.setDiffReason("运费差额超公差范围，需核实重抛与VAS增值服务费用");
                discrepancyCount++;
            }
            item.setUpdateTime(LocalDateTime.now());
            itemMapper.updateById(item);
        }

        recon.setSystemFreightTotal(systemTotal);
        recon.setCarrierBilledTotal(carrierTotal);
        recon.setDiffAmount(carrierTotal.subtract(systemTotal));
        recon.setMatchedCount(matchedCount);
        recon.setDiscrepancyCount(discrepancyCount);
        recon.setStatus(discrepancyCount == 0 ? "reconciled" : "discrepancy");
        recon.setUpdateTime(LocalDateTime.now());
        reconMapper.updateById(recon);

        log.info("[Reconciliation] 对账单勾稽计算完成: reconId={}, matched={}, disputed={}", reconId, matchedCount, discrepancyCount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recon", recon);
        result.put("matchedCount", matchedCount);
        result.put("discrepancyCount", discrepancyCount);
        result.put("systemFreightTotal", systemTotal);
        result.put("carrierBilledTotal", carrierTotal);
        result.put("diffAmount", recon.getDiffAmount());
        result.put("status", recon.getStatus());
        result.put("items", items);
        return result;
    }

    /**
     * 人工争议调解平账 (Resolve Dispute)
     */
    @Transactional(rollbackFor = Exception.class)
    public void resolveDispute(Long itemId, BigDecimal adjustmentAmount, String note) {
        LogisticsReconciliationItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException("对账明细记录不存在: " + itemId);
        }
        item.setAdjustmentAmount(adjustmentAmount != null ? adjustmentAmount : BigDecimal.ZERO);
        item.setAdjustNote(note != null ? note.trim() : "人工核准调解平账");
        item.setMatchStatus("resolved");
        item.setUpdateTime(LocalDateTime.now());
        itemMapper.updateById(item);

        // 检查主单是否仍有未解决争议
        Long remainingDisputes = itemMapper.selectCount(
            new LambdaQueryWrapper<LogisticsReconciliationItem>()
                .eq(LogisticsReconciliationItem::getReconId, item.getReconId())
                .in(LogisticsReconciliationItem::getMatchStatus, Arrays.asList("disputed", "missing_waybill"))
        );

        LogisticsReconciliation recon = reconMapper.selectById(item.getReconId());
        if (recon != null) {
            recon.setDiscrepancyCount(remainingDisputes != null ? remainingDisputes.intValue() : 0);
            if (remainingDisputes == null || remainingDisputes == 0) {
                recon.setStatus("reconciled");
            }
            recon.setUpdateTime(LocalDateTime.now());
            reconMapper.updateById(recon);
        }
    }

    /**
     * 查询对账单详情与逐笔明细
     */
    public Map<String, Object> getReconciliationDetail(Long reconId) {
        LogisticsReconciliation recon = reconMapper.selectById(reconId);
        if (recon == null) {
            throw new BusinessException("对账单不存在: " + reconId);
        }
        List<LogisticsReconciliationItem> items = itemMapper.selectList(
            new LambdaQueryWrapper<LogisticsReconciliationItem>()
                .eq(LogisticsReconciliationItem::getReconId, reconId)
                .orderByDesc(LogisticsReconciliationItem::getDiffAmount)
        );
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("recon", recon);
        map.put("items", items);
        return map;
    }
}
