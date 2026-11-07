package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.dto.rma.BuyerShipBackDTO;
import com.lanlink.shopping.dto.rma.MerchantInspectDTO;
import com.lanlink.shopping.dto.rma.MerchantRmaApproveDTO;
import com.lanlink.shopping.dto.rma.RmaApplyDTO;
import com.lanlink.shopping.entity.OrderRma;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.service.OrderRmaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * RMA 售后退货退款与逆向物流控制器 (P2 阶段)。
 */
@RestController
@RequestMapping("/order/rma")
public class OrderRmaController {

    private final OrderRmaService rmaService;

    public OrderRmaController(OrderRmaService rmaService) {
        this.rmaService = rmaService;
    }

    /** 买家提交售后退货退款/仅退款申请（@Valid 校验 applyReason/proofImages 非空，异常交全局处理器） */
    @PostMapping("/apply")
    public R<OrderRma> apply(@Valid @RequestBody RmaApplyDTO dto, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        return R.ok("售后申请已提交", rmaService.applyRma(userId, dto));
    }

    /** 买家填报退货物流承运商与运单号 */
    @PostMapping("/ship-back/{rmaNo}")
    public R<Void> shipBack(@PathVariable String rmaNo,
                            @RequestBody BuyerShipBackDTO dto,
                            HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        rmaService.buyerShipBack(userId, rmaNo, dto);
        return R.ok("退货物流单号已填报", null);
    }

    /** 按订单号查询最新售后 RMA 详情 */
    @GetMapping("/order/{orderNo}")
    public R<OrderRma> getByOrderNo(@PathVariable String orderNo) {
        return R.ok(rmaService.getByOrderNo(orderNo));
    }

    /** 查询 RMA 详情 */
    @GetMapping("/detail/{rmaNo}")
    public R<OrderRma> getDetail(@PathVariable String rmaNo) {
        return R.ok(rmaService.getRmaDetail(rmaNo));
    }

    /** 商户初审通过：下发退货收件人与仓库地址，或仅退款直接出款 */
    @PostMapping("/merchant/approve/{rmaNo}")
    @RequirePerm("merchant:manage")
    public R<Void> merchantApprove(@PathVariable String rmaNo,
                                   @RequestBody(required = false) MerchantRmaApproveDTO dto,
                                   HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        rmaService.merchantApproveRma(userId, rmaNo, dto);
        return R.ok("商户初审通过", null);
    }

    /** 商户仓库扫码验货质检入库 */
    @PostMapping("/merchant/inspect/{rmaNo}")
    @RequirePerm("merchant:manage")
    public R<Void> merchantInspect(@PathVariable String rmaNo,
                                   @RequestBody(required = false) MerchantInspectDTO dto,
                                   HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        rmaService.merchantInspect(userId, rmaNo, dto);
        return R.ok("验货质检已完成", null);
    }

    /** 商户驳回售后申请 */
    @PostMapping("/merchant/reject/{rmaNo}")
    @RequirePerm("merchant:manage")
    public R<Void> merchantReject(@PathVariable String rmaNo,
                                  @RequestBody(required = false) Map<String, String> body,
                                  HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        String reason = body != null ? body.get("reason") : null;
        rmaService.rejectRma(userId, rmaNo, reason);
        return R.ok("售后申请已驳回", null);
    }
}
