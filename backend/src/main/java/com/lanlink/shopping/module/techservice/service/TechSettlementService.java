package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.domain.TechNoGenerator;
import com.lanlink.shopping.module.techservice.dto.TechEscrowCreateRequest;
import com.lanlink.shopping.module.techservice.dto.TechServiceFeeCreateRequest;
import com.lanlink.shopping.module.techservice.entity.TechContract;
import com.lanlink.shopping.module.techservice.entity.TechEscrowLedger;
import com.lanlink.shopping.module.techservice.entity.TechServiceFee;
import com.lanlink.shopping.module.techservice.mapper.TechContractMapper;
import com.lanlink.shopping.module.techservice.mapper.TechEscrowLedgerMapper;
import com.lanlink.shopping.module.techservice.mapper.TechServiceFeeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * P0 结算：平台服务费台账 + 交易本金监管台账。
 *
 * <p>硬约束：平台只登记服务费，不归集交易本金；本金流水只登记外部存管/分账/交易所凭证。
 */
@Service
public class TechSettlementService {

    private static final Set<String> FEE_TYPES = Set.of(
            "BROKERAGE", "SERVICE", "EVALUATION", "SUBSCRIPTION");
    private static final Set<String> DIRECTIONS = Set.of("IN", "OUT");

    private final TechServiceFeeMapper feeMapper;
    private final TechEscrowLedgerMapper escrowMapper;
    private final TechContractMapper contractMapper;

    public TechSettlementService(TechServiceFeeMapper feeMapper,
                                 TechEscrowLedgerMapper escrowMapper,
                                 TechContractMapper contractMapper) {
        this.feeMapper = feeMapper;
        this.escrowMapper = escrowMapper;
        this.contractMapper = contractMapper;
    }

    @Transactional
    public TechServiceFee createFee(Long userId, String contractNo, TechServiceFeeCreateRequest req) {
        TechContract contract = contractMapper.selectOne(Wrappers.<TechContract>lambdaQuery()
                .eq(TechContract::getContractNo, contractNo).last("LIMIT 1"));
        if (contract == null) throw new BusinessException(404, "合同不存在");
        if (!Objects.equals(contract.getCreatedBy(), userId)) {
            throw new BusinessException(403, "无权为该合同登记费用");
        }
        String feeType = req.feeType() == null ? "SERVICE" : req.feeType().trim().toUpperCase();
        if (!FEE_TYPES.contains(feeType)) {
            throw new BusinessException(400, "不支持的服务费类型");
        }
        BigDecimal amount = req.amount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "服务费金额必须大于 0");
        }
        TechServiceFee fee = new TechServiceFee();
        fee.setFeeNo(TechNoGenerator.next("SF"));
        fee.setContractId(contract.getContractId());
        fee.setContractNo(contract.getContractNo());
        fee.setProjectId(contract.getProjectId());
        fee.setPayerUserId(userId);
        fee.setFeeType(feeType);
        fee.setAmount(amount);
        fee.setStatus("PENDING");
        feeMapper.insert(fee);
        return fee;
    }

    /**
     * 运营确认服务费到账（条件更新：仅 PENDING 可置 PAID，避免重复确认）。
     */
    @Transactional
    public TechServiceFee markPaid(Long feeId, String payChannel, String externalRef, String invoiceNo) {
        TechServiceFee existing = feeMapper.selectById(feeId);
        if (existing == null) throw new BusinessException(404, "服务费记录不存在");
        if (!"PENDING".equals(existing.getStatus())) {
            throw new BusinessException(400, "该服务费已处理，不能重复确认");
        }
        int updated = feeMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<TechServiceFee>()
                .eq("fee_id", feeId)
                .eq("status", "PENDING")
                .set("status", "PAID")
                .set("paid_at", LocalDateTime.now())
                .set("pay_channel", payChannel)
                .set("external_ref", externalRef)
                .set("invoice_no", invoiceNo));
        if (updated == 0) {
            throw new BusinessException(400, "该服务费已处理，不能重复确认");
        }
        return feeMapper.selectById(feeId);
    }

    public List<TechServiceFee> myFees(Long userId) {
        return feeMapper.selectList(Wrappers.<TechServiceFee>lambdaQuery()
                .eq(TechServiceFee::getPayerUserId, userId).orderByDesc(TechServiceFee::getCreateTime));
    }

    public Page<TechServiceFee> adminFeePage(long page, long size, String status, String keyword) {
        String st = text(status);
        String kw = text(keyword);
        return feeMapper.selectPage(TechPageable.of(page, size, TechPageable.ADMIN_MAX_SIZE), Wrappers.<TechServiceFee>lambdaQuery()
                .eq(st != null, TechServiceFee::getStatus, st)
                .and(kw != null, w -> w.like(TechServiceFee::getFeeNo, kw)
                        .or().like(TechServiceFee::getContractNo, kw))
                .orderByDesc(TechServiceFee::getCreateTime));
    }

    @Transactional
    public TechEscrowLedger recordEscrow(TechEscrowCreateRequest req) {
        String direction = req.direction() == null ? "" : req.direction().trim().toUpperCase();
        if (!DIRECTIONS.contains(direction)) {
            throw new BusinessException(400, "资金方向只能是 IN 或 OUT");
        }
        if (req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "监管金额必须大于 0");
        }
        if (req.provider() == null || req.provider().isBlank()) {
            throw new BusinessException(400, "请填写存管/分账/结算机构");
        }
        if (req.dealId() == null && req.contractId() == null) {
            throw new BusinessException(400, "请关联交易或合同");
        }
        TechEscrowLedger ledger = new TechEscrowLedger();
        ledger.setEscrowNo(TechNoGenerator.next("ES"));
        ledger.setDealId(req.dealId());
        ledger.setContractId(req.contractId());
        ledger.setAmount(req.amount());
        ledger.setDirection(direction);
        ledger.setProvider(req.provider().trim());
        ledger.setExternalRef(req.externalRef());
        ledger.setStatus("CONFIRMED");
        ledger.setOccurredAt(LocalDateTime.now());
        escrowMapper.insert(ledger);
        return ledger;
    }

    public Page<TechEscrowLedger> escrowPage(long page, long size) {
        return escrowMapper.selectPage(TechPageable.of(page, size, TechPageable.ADMIN_MAX_SIZE),
                Wrappers.<TechEscrowLedger>lambdaQuery().orderByDesc(TechEscrowLedger::getOccurredAt));
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
