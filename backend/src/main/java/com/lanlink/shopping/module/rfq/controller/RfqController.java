package com.lanlink.shopping.module.rfq.controller;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.module.rfq.dto.RfqAcceptRequest;
import com.lanlink.shopping.module.rfq.dto.RfqCreateRequest;
import com.lanlink.shopping.module.rfq.dto.RfqQuoteView;
import com.lanlink.shopping.module.rfq.dto.RfqView;
import com.lanlink.shopping.module.rfq.service.RfqQuoteService;
import com.lanlink.shopping.module.rfq.service.RfqService;
import com.lanlink.shopping.service.OrderService;
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
 * 买家询价单（RFQ）：发布、我的列表、本人详情、收到的报价、接受报价转订单。
 * 写接口需登录 + CSRF（见 CsrfInterceptor）；所有读接口按 user_id 收窄，他人询价一律 404。
 */
@RestController
@RequestMapping("/rfq")
public class RfqController {

    private final RfqService rfqService;
    private final RfqQuoteService rfqQuoteService;
    private final OrderService orderService;

    public RfqController(RfqService rfqService, RfqQuoteService rfqQuoteService,
                         OrderService orderService) {
        this.rfqService = rfqService;
        this.rfqQuoteService = rfqQuoteService;
        this.orderService = orderService;
    }

    @PostMapping
    public R<RfqView> create(@Valid @RequestBody RfqCreateRequest req, HttpServletRequest request) {
        return R.ok(RfqView.of(rfqService.create(requireLogin(request), req)));
    }

    @GetMapping("/my")
    public R<List<RfqView>> my(HttpServletRequest request) {
        return R.ok(rfqService.listMine(requireLogin(request)).stream().map(RfqView::of).toList());
    }

    @GetMapping("/{rfqNo}")
    public R<RfqView> detail(@PathVariable String rfqNo, HttpServletRequest request) {
        return R.ok(RfqView.of(rfqService.detail(requireLogin(request), rfqNo)));
    }

    /** 买家查看自己询价收到的报价（含商户报价明细，用于选择接受） */
    @GetMapping("/{rfqNo}/quotes")
    public R<List<RfqQuoteView>> quotes(@PathVariable String rfqNo, HttpServletRequest request) {
        return R.ok(rfqQuoteService.buyerQuotes(requireLogin(request), rfqNo)
                .stream().map(RfqQuoteView::of).toList());
    }

    /** 接受报价并转订单：成交价=报价总额，跳过阶梯/会员/促销/券/积分 */
    @PostMapping("/quotes/{quoteId}/accept")
    public R<Order> accept(@PathVariable Long quoteId,
                           @Valid @RequestBody RfqAcceptRequest req,
                           HttpServletRequest request) {
        return R.ok(orderService.checkoutFromQuote(requireLogin(request), quoteId, req.toCheckout()));
    }

    private Long requireLogin(HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        if (userId == null) throw new BusinessException(401, "请先登录");
        return userId;
    }
}