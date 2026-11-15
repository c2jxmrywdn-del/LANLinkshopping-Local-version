package com.lanlink.shopping.module.merchantexit.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class MerchantExitViews {

    private MerchantExitViews() {
    }

    /** 单个门禁阻断项：code 给前端定 icon/跳转，label 直接可展示，hint 给下一步动作。 */
    public record GateBlocker(String code, String label, long count, String hint) {
    }

    public record GateReport(boolean ready, List<GateBlocker> blockers) {
    }

    public record ExitStatusVO(String exitStatus, LocalDateTime requestTime, LocalDateTime noticeEndTime,
                               LocalDateTime closeTime, String rejectReason, BigDecimal depositBalance) {
    }
}
