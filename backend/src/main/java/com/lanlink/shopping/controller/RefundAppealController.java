package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.OrderRma;
import com.lanlink.shopping.entity.SupportTicket;
import com.lanlink.shopping.service.OrderRmaService;
import com.lanlink.shopping.support.SupportTicketService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 退款驳回申诉（P0-C 申诉最小实现）——复用人工客服工单，不新建表。
 *
 * <p>状态约定：
 * <pre>
 *  RMA: platform_rejected（平台已驳回） --买家提交申诉--> t_support_ticket(status=OPEN)
 *       --运营处理中--> PROCESSING --运营回复/关闭--> REPLIED/CLOSED
 *  运营认定申诉成立后，调用 POST /admin/refund/review/rma/{rmaNo}/reopen（要求存在引用该 RMA 的
 *  在途工单 OPEN/PROCESSING）把 RMA 放回 platform_pending，再走正常平台复核出款。
 *  申诉不改变平台驳回结论本身，只作为“重开复核”的前置凭据，防止无凭据推翻驳回。
 * </pre>
 *
 * <p>防重复：同一买家对同一 RMA 已有在途申诉工单时直接返回既有工单，不再新建（避免刷单）。
 * 本接口在 /order/** 前缀下：需登录 + CSRF 令牌（CsrfInterceptor 覆盖）。
 */
@RestController
@RequestMapping("/order/rma")
public class RefundAppealController {

    private final OrderRmaService rmaService;
    private final SupportTicketService supportTicketService;

    public RefundAppealController(OrderRmaService rmaService, SupportTicketService supportTicketService) {
        this.rmaService = rmaService;
        this.supportTicketService = supportTicketService;
    }

    /** 买家对「平台已驳回」的售后单提交申诉工单。 */
    @PostMapping("/{rmaNo}/appeal")
    public R<SupportTicket> appeal(@PathVariable String rmaNo,
                                   @RequestBody(required = false) Map<String, Object> body,
                                   HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        // 归属校验：非本人售后单按不存在处理（含地址/凭证等敏感信息，防枚举探测）
        OrderRma rma = rmaService.getRmaDetailForBuyer(userId, rmaNo);
        if (!OrderRmaService.RMA_STATUS_PLATFORM_REJECTED.equals(rma.getStatus())) {
            throw new BusinessException(409, "仅平台已驳回的售后单可提交申诉");
        }
        String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason")).trim();
        if (reason == null || reason.length() < 5) {
            throw new BusinessException("请填写申诉理由（至少 5 字）");
        }
        List<SupportTicket> mine = supportTicketService.myTickets(userId);
        for (SupportTicket t : mine) {
            if (t.getStatus() != null && List.of("OPEN", "PROCESSING").contains(t.getStatus())
                    && t.getContent() != null && t.getContent().contains(rmaNo)) {
                return R.ok("申诉工单已在处理中", t); // 幂等：同一单不重复建工单
            }
        }
        String title = "退款驳回申诉 " + rmaNo;
        String content = "售后单 " + rmaNo + "（订单 " + rma.getOrderNo() + "）平台复核驳回，申诉理由：" + reason;
        SupportTicket ticket = supportTicketService.submit(userId, "order", title, content, null);
        return R.ok("申诉工单已提交，客服会尽快处理", ticket);
    }
}
