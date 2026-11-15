package com.lanlink.shopping.module.rfq.controller;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.rfq.dto.RfqMerchantView;
import com.lanlink.shopping.module.rfq.dto.RfqQuoteCreateRequest;
import com.lanlink.shopping.module.rfq.dto.RfqQuoteView;
import com.lanlink.shopping.module.rfq.service.RfqQuoteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商户侧 RFQ：开放询价列表、询价详情、报价（新建/修改自己的报价）、查看自己的报价。
 * 买家身份与竞对报价永不返回；写接口需登录 + 商户权限 + CSRF。
 */
@RestController
@RequestMapping("/merchant/rfq")
public class RfqMerchantController {

    private final RfqQuoteService quoteService;

    public RfqMerchantController(RfqQuoteService quoteService) {
        this.quoteService = quoteService;
    }

    @GetMapping("/open")
    @RequirePerm("merchant:manage")
    public R<List<RfqMerchantView>> open() {
        return R.ok(quoteService.openRfqs().stream().map(RfqMerchantView::of).toList());
    }

    @GetMapping("/{rfqNo}")
    @RequirePerm("merchant:manage")
    public R<RfqMerchantView> detail(@PathVariable String rfqNo) {
        return R.ok(RfqMerchantView.of(quoteService.merchantView(rfqNo)));
    }

    @PostMapping("/{rfqNo}/quote")
    @RequirePerm("merchant:manage")
    public R<RfqQuoteView> submit(@PathVariable String rfqNo,
                                  @Valid @RequestBody RfqQuoteCreateRequest req,
                                  HttpServletRequest request) {
        return R.ok(RfqQuoteView.of(
                quoteService.submitQuote(requireLogin(request), rfqNo, req)));
    }

    @GetMapping("/{rfqNo}/quote")
    @RequirePerm("merchant:manage")
    public R<RfqQuoteView> myQuote(@PathVariable String rfqNo, HttpServletRequest request) {
        return R.ok(RfqQuoteView.of(
                quoteService.myQuote(requireLogin(request), rfqNo)));
    }

    private Long requireLogin(HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        if (userId == null) throw new BusinessException(401, "请先登录");
        return userId;
    }
}