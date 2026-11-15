package com.lanlink.shopping.module.merchantexit.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;

/** 退店写操作门禁：exit_status 非 NULL 即冻结经营类写操作；下架不受限制由调用方自行放行。 */
public final class MerchantExitGuard {

    private MerchantExitGuard() {
    }

    public static void requireWritable(Merchant m) {
        if (m == null || m.getExitStatus() == null || m.getExitStatus().isBlank()) {
            return;
        }
        String label = switch (m.getExitStatus()) {
            case "EXIT_REQUESTED" -> "退店申请中";
            case "PUBLIC_NOTICE" -> "退店公示中";
            case "FROZEN" -> "退店清算中";
            case "CLOSED" -> "已闭店";
            default -> "退店处理中";
        };
        throw new BusinessException(400, "店铺" + label + "，暂不可执行该操作（商品下架不受限制）");
    }
}
