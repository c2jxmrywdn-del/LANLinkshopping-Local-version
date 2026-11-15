package com.lanlink.shopping.module.settlement.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 结算读模型的对外视图（record 即 DTO，不做实体直出，避免把 ref_earning_id / refund_seq 等内部字段暴露给前端）。
 *
 * <p>字段口径与 {@code t_merchant_earning} / {@code t_merchant_settlement} / {@code t_merchant_settlement_item}
 * 一致；金额均为 DECIMAL(14,2) 对应的 BigDecimal，不做单位换算。
 *
 * <p>平台只做记账与对账（二清红线）：这些视图只读，不含任何资金划转语义。
 */
public class SettlementViews {

    /** 商户收益汇总（四池）：待结算 / 可结算 / 已入批次 / 平台累计佣金（含冲正回冲，可为负）。 */
    public record SummaryVO(BigDecimal pending, BigDecimal available, BigDecimal settled,
                            BigDecimal totalCommission) {
    }

    /** 收益台账行。批次明细（{@code adminDetail.items}）只用其中的 earningId/orderNo/itemId/type/netAmount。 */
    public record EarningVO(Long earningId, String orderNo, Long itemId, String type, BigDecimal grossAmount,
                            BigDecimal commissionAmount, BigDecimal netAmount, String status,
                            LocalDateTime availableAt, LocalDateTime createTime) {
    }

    /** 结算批次。payVoucher 仅存线下打款凭证引用，不代表平台代付。 */
    public record BatchVO(Long settleId, String settleNo, LocalDateTime periodStart, LocalDateTime periodEnd,
                          BigDecimal totalNet, BigDecimal commissionTotal, Integer itemCount, String status,
                          String payVoucher, LocalDateTime createTime) {
    }

    /**
     * 批次详情（批次摘要 + 明细）的类型化形态，与 {@code SettlementQueryService.adminDetail} 返回的
     * {@code {"batch": BatchVO, "items": List<EarningVO>}} 结构一一对应。
     */
    public record BatchDetailVO(BatchVO batch, List<EarningVO> items) {
    }
}
