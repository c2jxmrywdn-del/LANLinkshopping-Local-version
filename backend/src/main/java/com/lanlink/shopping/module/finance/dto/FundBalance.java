package com.lanlink.shopping.module.finance.dto;

import java.math.BigDecimal;

/**
 * 商户可提余额快照（三项聚合 + 推导余额）。
 *
 * <p>口径（{@link com.lanlink.shopping.module.finance.service.MerchantFundLockService} 唯一实现）：
 * <ul>
 *   <li>{@code settled} = Σ(t_merchant_earning.net_amount WHERE status IN ('SETTLED','REVERSED'))
 *       —— 已进结算批次但批次**未打款**的净额（PAID 已随批次打款，不在口径内）；</li>
 *   <li>{@code paid} = Σ(t_merchant_withdrawal.amount WHERE status=1) —— 已打款提现；</li>
 *   <li>{@code frozen} = Σ(t_merchant_withdrawal.amount WHERE status=0) —— 在途冻结；</li>
 *   <li>{@code balance} = settled − paid − frozen（判定用**原值**，展示侧另做 max(0,·)）。</li>
 * </ul>
 */
public record FundBalance(BigDecimal settled, BigDecimal paid, BigDecimal frozen, BigDecimal balance) {

    /** 是否足以覆盖 amount（金额比较用 compareTo，避免 scale 差异误判；非正金额视为无需覆盖）。 */
    public boolean covers(BigDecimal amount) {
        return amount == null || amount.signum() <= 0 || balance.compareTo(amount) >= 0;
    }
}
