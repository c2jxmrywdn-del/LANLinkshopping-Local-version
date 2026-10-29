package com.lanlink.shopping.support;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.SupportTicket;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 人工客服工单（用户侧）：提交留言、查看我的工单与客服回复。需登录（默认鉴权拦截器）。
 */
@RestController
@RequestMapping("/support-ticket")
public class SupportTicketController {

    private final SupportTicketService service;

    public SupportTicketController(SupportTicketService service) {
        this.service = service;
    }

    @PostMapping("/submit")
    public R<SupportTicket> submit(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        String category = str(body.get("category"));
        String title = str(body.get("title"));
        String content = str(body.get("content"));
        String contact = str(body.get("contact"));
        return R.ok("工单已提交，客服会尽快处理", service.submit(userId, category, title, content, contact));
    }

    @GetMapping("/my")
    public R<List<SupportTicket>> my(HttpServletRequest request) {
        return R.ok(service.myTickets(UserContext.currentUserId(request)));
    }

    @GetMapping("/{id}")
    public R<SupportTicket> detail(@PathVariable Long id, HttpServletRequest request) {
        return R.ok(service.detail(UserContext.currentUserId(request), id));
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }
}
