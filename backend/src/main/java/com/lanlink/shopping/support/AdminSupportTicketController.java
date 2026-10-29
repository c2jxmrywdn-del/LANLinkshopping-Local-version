package com.lanlink.shopping.support;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.SupportTicket;
import com.lanlink.shopping.integration.security.RequirePerm;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 人工客服工单（运营侧）：列表、回复、状态流转。仅平台运营（admin:all）可访问。
 */
@RestController
@RequestMapping("/admin/support-ticket")
@RequirePerm("admin:all")
public class AdminSupportTicketController {

    private final SupportTicketService service;

    public AdminSupportTicketController(SupportTicketService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public R<Map<String, Object>> list(@RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.adminList(status, page, size));
    }

    @PostMapping("/{id}/reply")
    public R<SupportTicket> reply(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                  HttpServletRequest request) {
        String reply = body.get("reply") == null ? null : String.valueOf(body.get("reply"));
        return R.ok("已回复用户", service.adminReply(id, reply, UserContext.currentUserId(request)));
    }

    @PostMapping("/{id}/status")
    public R<SupportTicket> updateStatus(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                         HttpServletRequest request) {
        String status = body.get("status") == null ? null : String.valueOf(body.get("status"));
        return R.ok("状态已更新", service.adminUpdateStatus(id, status, UserContext.currentUserId(request)));
    }
}
