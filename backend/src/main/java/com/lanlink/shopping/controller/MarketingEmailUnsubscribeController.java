package com.lanlink.shopping.controller;

import com.lanlink.shopping.service.MarketingEmailUnsubscribeService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signed one-click unsubscribe flow.
 * GET only renders a confirmation form (mail security scanners often prefetch links);
 * POST validates the signed capability token and changes only promotion preferences.
 */
@RestController
@RequestMapping("/marketing-email")
public class MarketingEmailUnsubscribeController {
    private final MarketingEmailUnsubscribeService unsubscribeService;

    public MarketingEmailUnsubscribeController(MarketingEmailUnsubscribeService unsubscribeService) {
        this.unsubscribeService = unsubscribeService;
    }

    @GetMapping(value = "/unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> confirm(
            @RequestParam("userId") Long userId,
            @RequestParam("token") String token) {
        String html = "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>退订营销邮件</title><body style=\"font-family:Arial,sans-serif;max-width:560px;"
                + "margin:64px auto;padding:24px;color:#182433\">"
                + "<h1>确认退订 LANLinkshopping 商业推广邮件</h1>"
                + "<p>确认后，你将不再收到此账号的促销邮件；订单、安全和验证码邮件不受影响。</p>"
                + "<form method=\"post\" action=\"/api/marketing-email/unsubscribe\">"
                + "<input type=\"hidden\" name=\"userId\" value=\"" + userId + "\">"
                + "<input type=\"hidden\" name=\"token\" value=\"" + escapeHtml(token) + "\">"
                + "<button type=\"submit\" style=\"padding:12px 18px;border:0;border-radius:8px;"
                + "background:#13233a;color:white;cursor:pointer\">确认退订</button></form>"
                + "</body></html>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @PostMapping(value = "/unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unsubscribe(
            @RequestParam("userId") Long userId,
            @RequestParam("token") String token) {
        unsubscribeService.unsubscribe(userId, token);
        String html = "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>退订成功</title><body style=\"font-family:Arial,sans-serif;max-width:560px;"
                + "margin:64px auto;padding:24px;color:#182433\">"
                + "<h1>退订成功</h1><p>已停止该账号的 LANLinkshopping 商业推广邮件。"
                + "订单、安全与验证码邮件不受影响。</p></body></html>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }
}
