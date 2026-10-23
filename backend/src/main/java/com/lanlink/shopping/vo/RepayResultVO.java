package com.lanlink.shopping.vo;

import com.lanlink.shopping.entity.CreditBill;
import com.lanlink.shopping.entity.CreditRepayment;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 账期还款接口（POST /credit-term/bills/{id}/repay）的返回视图。
 *
 * <p>取代此前返回的裸 {@code Map<String,Object>}，用显式字段固定对外契约：字段名与
 * 原 Map 键一致（bill / repayment / pending / payableAmount），前端读取方式不变。
 * 钱包还款即时结算（pending=false）；对公转账仅登记待核销（pending=true，不冲账单不释放授信）。</p>
 */
@Data
public class RepayResultVO {
    /** 还款后的账单快照 */
    private CreditBill bill;
    /** 本次生成的还款记录（钱包=CONFIRMED / 转账=PENDING） */
    private CreditRepayment repayment;
    /** 是否为待核销的转账登记 */
    private boolean pending;
    /** 本次操作后的剩余可还金额（已扣掉待确认转账，最小 0） */
    private BigDecimal payableAmount;
}
