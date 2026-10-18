package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.LogisticsProvider;
import com.lanlink.shopping.entity.LogisticsSettlement;
import com.lanlink.shopping.entity.LogisticsSettlementItem;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.mapper.LogisticsProviderMapper;
import com.lanlink.shopping.mapper.LogisticsSettlementItemMapper;
import com.lanlink.shopping.mapper.LogisticsSettlementMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 物流结算服务：账期归集、对账单生成（幂等）、审核打款闭环。
 *
 * 规则（docs/物流板块业务设计文档.md 3.5）：
 *  - 同一服务商同账期只允许一张非作废结算单（生成时校验，幂等）；
 *  - 结算明细逐单留痕（t_logistics_settlement_item），运费合计 = 明细之和；
 *  - 结算状态机：draft → pending_review → approved → paid（reject 回 draft）。
 */
@Service
public class LogisticsSettlementService {

    private static final DateTimeFormatter SETTLE_DAY = DateTimeFormatter.ofPattern("yyyyMM");

    private final LogisticsSettlementMapper settlementMapper;
    private final LogisticsSettlementItemMapper itemMapper;
    private final LogisticsWaybillMapper waybillMapper;
    private final LogisticsProviderMapper providerMapper;
    private final AuditService auditService;
    private final SequenceService sequenceService;

    public LogisticsSettlementService(LogisticsSettlementMapper settlementMapper,
                                      LogisticsSettlementItemMapper itemMapper,
                                      LogisticsWaybillMapper waybillMapper,
                                      LogisticsProviderMapper providerMapper,
                                      AuditService auditService,
                                      SequenceService sequenceService) {
        this.settlementMapper = settlementMapper;
        this.itemMapper = itemMapper;
        this.waybillMapper = waybillMapper;
        this.providerMapper = providerMapper;
        this.auditService = auditService;
        this.sequenceService = sequenceService;
    }

    /** 生成对账单（同服务商同账期幂等） */
    @Transactional
    public LogisticsSettlement generate(Long providerId, LocalDate periodStart, LocalDate periodEnd,
                                        BigDecimal deduction, BigDecimal extraFee,
                                        Long operatorId, jakarta.servlet.http.HttpServletRequest httpReq) {
        LogisticsProvider provider = providerMapper.selectById(providerId);
        if (provider == null) throw new BusinessException("物流服务商不存在");
        if (settlementMapper.countByProviderPeriod(providerId, periodStart, periodEnd) > 0) {
            throw new BusinessException("该服务商本账期已存在结算单，不能重复生成");
        }
        List<LogisticsWaybill> waybills = waybillMapper.selectList(new LambdaQueryWrapper<LogisticsWaybill>()
                .eq(LogisticsWaybill::getProviderId, providerId)
                .ge(LogisticsWaybill::getShipTime, periodStart.atStartOfDay())
                .lt(LogisticsWaybill::getShipTime, periodEnd.plusDays(1).atStartOfDay())
                .in(LogisticsWaybill::getStatus, "delivered", "returned", "lost"));
        if (waybills.isEmpty()) throw new BusinessException("本账期无已签收/关闭运单，无结算数据");

        LogisticsSettlement s = new LogisticsSettlement();
        s.setSettlementNo(nextSettlementNo());
        s.setProviderId(providerId);
        s.setPeriodStart(periodStart);
        s.setPeriodEnd(periodEnd);
        s.setWaybillCount(waybills.size());
        BigDecimal total = waybills.stream()
                .map(w -> w.getFreight() == null ? BigDecimal.ZERO : w.getFreight())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        s.setTotalFreight(total);
        s.setDeduction(deduction == null ? BigDecimal.ZERO : deduction);
        s.setExtraFee(extraFee == null ? BigDecimal.ZERO : extraFee);
        s.setPayableAmount(total.subtract(s.getDeduction()).add(s.getExtraFee()));
        s.setStatus("draft");
        settlementMapper.insert(s);

        for (LogisticsWaybill w : waybills) {
            LogisticsSettlementItem item = new LogisticsSettlementItem();
            item.setSettlementId(s.getSettlementId());
            item.setWaybillId(w.getWaybillId());
            item.setWaybillNo(w.getWaybillNo());
            item.setFreight(w.getFreight());
            itemMapper.insert(item);
        }
        auditService.record(operatorId, "LOGISTICS_SETTLEMENT_GENERATE",
                "生成结算单 " + s.getSettlementNo() + " 运单数=" + s.getWaybillCount(), httpReq);
        return s;
    }

    /** 结算状态流转（draft→pending_review→approved→paid，reject 回 draft） */
    @Transactional
    public LogisticsSettlement review(Long settlementId, String action, String note,
                                      Long operatorId, jakarta.servlet.http.HttpServletRequest httpReq) {
        LogisticsSettlement s = settlementMapper.selectById(settlementId);
        if (s == null) throw new BusinessException("结算单不存在");
        String next = switch (action) {
            case "submit" -> {
                if (!"draft".equals(s.getStatus())) throw new BusinessException("仅草稿可提交审核");
                yield "pending_review";
            }
            case "approve" -> {
                if (!"pending_review".equals(s.getStatus())) throw new BusinessException("仅待审核可通过");
                yield "approved";
            }
            case "pay" -> {
                if (!"approved".equals(s.getStatus())) throw new BusinessException("仅已通过可打款");
                yield "paid";
            }
            case "reject" -> {
                if (!"pending_review".equals(s.getStatus())) throw new BusinessException("仅待审核可驳回");
                yield "draft";
            }
            default -> throw new BusinessException("非法结算操作: " + action);
        };
        s.setStatus(next);
        s.setReviewerId(operatorId);
        s.setReviewNote(note);
        if ("paid".equals(next)) s.setPaidTime(LocalDateTime.now());
        settlementMapper.updateById(s);
        auditService.record(operatorId, "LOGISTICS_SETTLEMENT_" + action.toUpperCase(),
                "结算单 " + s.getSettlementNo() + " 操作 " + action, httpReq);
        return s;
    }

    public Page<LogisticsSettlement> page(long page, long size, String status, Long providerId) {
        LambdaQueryWrapper<LogisticsSettlement> qw = new LambdaQueryWrapper<LogisticsSettlement>()
                .orderByDesc(LogisticsSettlement::getSettlementId);
        if (status != null && !status.isBlank()) qw.eq(LogisticsSettlement::getStatus, status);
        if (providerId != null) qw.eq(LogisticsSettlement::getProviderId, providerId);
        return settlementMapper.selectPage(new Page<>(page, size), qw);
    }

    public List<LogisticsSettlementItem> items(Long settlementId) {
        return itemMapper.selectList(new LambdaQueryWrapper<LogisticsSettlementItem>()
                .eq(LogisticsSettlementItem::getSettlementId, settlementId));
    }

    private String nextSettlementNo() {
        String ym = LocalDate.now().format(SETTLE_DAY);
        // 序列服务原子取号（防并发同序号撞 uk_settlement_no 唯一约束），键含年月天然按月重置
        return "JS" + ym + sequenceService.nextNo("settlement:" + ym, 4);
    }
}
