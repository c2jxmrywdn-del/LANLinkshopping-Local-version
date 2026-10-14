package com.lanlink.shopping.module.membership.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.module.membership.service.MembershipService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 会员系统 API（营销中台）
 *  - GET /membership/my            我的会员卡（等级/成长值/积分/流水/规则）
 *  - GET /membership/redeem-quote  积分抵现试算（下单前预览可抵扣金额）
 */
@RestController
@RequestMapping("/membership")
public class MembershipController {

    private final MembershipService membershipService;

    public MembershipController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @GetMapping("/my")
    public R<Map<String, Object>> my(HttpServletRequest request) {
        return R.ok(membershipService.myView(UserContext.currentUserId(request)));
    }

    /** 积分抵现试算：返回实际可抵扣积分/金额、本单上限、积分余额 */
    @GetMapping("/redeem-quote")
    public R<Map<String, Object>> redeemQuote(@RequestParam Integer points,
                                              @RequestParam java.math.BigDecimal orderAmount,
                                              HttpServletRequest request) {
        return R.ok(membershipService.redeemQuote(UserContext.currentUserId(request), points, orderAmount));
    }
}
