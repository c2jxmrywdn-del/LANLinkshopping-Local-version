package com.lanlink.shopping.payment.wallet.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 钱包 API（需登录）：
 *  - GET  /wallet/my        余额 + 最近流水
 *  - POST /wallet/recharge  充值（模拟网关即时到账）
 */
@RestController
@RequestMapping("/wallet")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping("/my")
    public R<Map<String, Object>> my(HttpServletRequest request) {
        return R.ok(walletService.myView(UserContext.currentUserId(request)));
    }

    @PostMapping("/recharge")
    public R<Map<String, Object>> recharge(@RequestBody RechargeDTO dto, HttpServletRequest request) {
        walletService.recharge(UserContext.currentUserId(request), dto.getAmount(), dto.getRemark());
        return R.ok("充值成功", walletService.myView(UserContext.currentUserId(request)));
    }

    @Data
    public static class RechargeDTO {
        @NotNull(message = "充值金额不能为空")
        @DecimalMin(value = "0.01", message = "充值金额需大于 0")
        @Digits(integer = 10, fraction = 2, message = "金额最多两位小数")
        private BigDecimal amount;
        private String remark;
    }
}
