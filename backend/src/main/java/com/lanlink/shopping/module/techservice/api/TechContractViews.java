package com.lanlink.shopping.module.techservice.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.module.techservice.domain.TechContractTemplates;
import com.lanlink.shopping.module.techservice.entity.TechAcceptance;
import com.lanlink.shopping.module.techservice.entity.TechContract;
import com.lanlink.shopping.module.techservice.entity.TechEscrowLedger;
import com.lanlink.shopping.module.techservice.entity.TechServiceFee;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** P0 合同 / 服务费 / 监管台账 / 验收 的对外契约。 */
public final class TechContractViews {

    private TechContractViews() {
    }

    public record Contract(Long contractId, String contractNo, Long projectId, Long dealId, String templateCode,
                           String contractType, String title, String partyA, String partyB, BigDecimal amount,
                           BigDecimal commissionRate, String status, String fileHash, String signProvider,
                           String signRef, LocalDateTime signedAt, Long createdBy,
                           LocalDateTime createTime, LocalDateTime updateTime) {
    }

    public record ServiceFee(Long feeId, String feeNo, Long contractId, String contractNo, Long projectId,
                             Long payerUserId, String feeType, BigDecimal amount, String status,
                             String payChannel, String externalRef, String invoiceNo, LocalDateTime paidAt,
                             LocalDateTime createTime, LocalDateTime updateTime) {
    }

    public record Escrow(Long ledgerId, String escrowNo, Long dealId, Long contractId, BigDecimal amount,
                         String direction, String provider, String externalRef, String status,
                         LocalDateTime occurredAt, LocalDateTime createTime) {
    }

    public record Acceptance(Long acceptanceId, String acceptanceNo, Long projectId, Long contractId, String result,
                             Integer score, String note, String evidenceHash, Long acceptedBy,
                             LocalDateTime createTime) {
    }

    public record Template(String code, String contractType, String name, String description,
                           List<String> clauses, String defaultCommissionRate) {
    }

    public static Contract ofContract(TechContract e) {
        return new Contract(e.getContractId(), e.getContractNo(), e.getProjectId(), e.getDealId(),
                e.getTemplateCode(), e.getContractType(), e.getTitle(), e.getPartyA(), e.getPartyB(),
                e.getAmount(), e.getCommissionRate(), e.getStatus(), e.getFileHash(), e.getSignProvider(),
                e.getSignRef(), e.getSignedAt(), e.getCreatedBy(), e.getCreateTime(), e.getUpdateTime());
    }

    public static ServiceFee ofFee(TechServiceFee e) {
        return new ServiceFee(e.getFeeId(), e.getFeeNo(), e.getContractId(), e.getContractNo(), e.getProjectId(),
                e.getPayerUserId(), e.getFeeType(), e.getAmount(), e.getStatus(), e.getPayChannel(),
                e.getExternalRef(), e.getInvoiceNo(), e.getPaidAt(), e.getCreateTime(), e.getUpdateTime());
    }

    public static Escrow ofEscrow(TechEscrowLedger e) {
        return new Escrow(e.getLedgerId(), e.getEscrowNo(), e.getDealId(), e.getContractId(), e.getAmount(),
                e.getDirection(), e.getProvider(), e.getExternalRef(), e.getStatus(), e.getOccurredAt(),
                e.getCreateTime());
    }

    public static Acceptance ofAcceptance(TechAcceptance e) {
        return new Acceptance(e.getAcceptanceId(), e.getAcceptanceNo(), e.getProjectId(), e.getContractId(),
                e.getResult(), e.getScore(), e.getNote(), e.getEvidenceHash(), e.getAcceptedBy(),
                e.getCreateTime());
    }

    /** 供 Controller 转换服务层 Map 中的对象，避免 Controller 直接引用实体类型 */
    public static Acceptance ofAcceptance(Object raw) {
        return ofAcceptance((TechAcceptance) raw);
    }

    public static Template ofTemplate(TechContractTemplates.Template t) {
        return new Template(t.code(), t.contractType(), t.name(), t.description(), t.clauses(),
                t.defaultCommissionRate());
    }

    public static <E, V> Map<String, Object> page(Page<E> page, Function<E, V> mapper) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", page.getRecords().stream().map(mapper).toList());
        out.put("total", page.getTotal());
        out.put("size", page.getSize());
        out.put("current", page.getCurrent());
        out.put("pages", page.getPages());
        out.put("orders", page.orders());
        out.put("optimizeCountSql", page.optimizeCountSql());
        out.put("searchCount", page.searchCount());
        out.put("countId", page.countId());
        out.put("maxLimit", page.maxLimit());
        out.put("page", page.getCurrent());
        return out;
    }
}
