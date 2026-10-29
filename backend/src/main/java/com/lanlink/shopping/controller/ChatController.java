package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.service.ChatMediaService;
import com.lanlink.shopping.service.MerchantChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 买家↔商家 对话（买家侧）：需登录（游客点「联系商家」由前端引导登录）。
 * sender_role 由服务端按登录身份强制写入，前端不可透传。
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final MerchantChatService chatService;
    private final ChatMediaService chatMediaService;

    public ChatController(MerchantChatService chatService, ChatMediaService chatMediaService) {
        this.chatService = chatService;
        this.chatMediaService = chatMediaService;
    }

    /** 从商品详情页发起（携带 merId+prodId）：find-or-create，返回会话视图 */
    @PostMapping("/session")
    public R<Map<String, Object>> start(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long merId = toLong(body.get("merId"));
        Long prodId = toLong(body.get("prodId"));
        return R.ok(chatService.startFromProduct(UserContext.currentUserId(request), merId, prodId));
    }

    /** 我的会话列表 */
    @GetMapping("/sessions")
    public R<List<Map<String, Object>>> sessions(HttpServletRequest request) {
        return R.ok(chatService.buyerSessions(UserContext.currentUserId(request)));
    }

    /** 未读总数 */
    @GetMapping("/unread")
    public R<Map<String, Object>> unread(HttpServletRequest request) {
        return R.ok(Map.of("count", chatService.buyerUnread(UserContext.currentUserId(request))));
    }

    /** 会话消息（afterId 增量拉取；进入即回执已读） */
    @GetMapping("/{sessionId}/messages")
    public R<Map<String, Object>> messages(@PathVariable Long sessionId,
                                           @RequestParam(defaultValue = "0") long afterId,
                                           HttpServletRequest request) {
        return R.ok(chatService.messages(UserContext.currentUserId(request), sessionId, afterId, "buyer"));
    }

    /** 聊天媒体上传（仅需登录）：kind=image/file/voice，返回 { url, name, size } */
    @PostMapping("/media")
    public R<Map<String, Object>> uploadMedia(@RequestParam("file") MultipartFile file,
                                              @RequestParam String kind,
                                              HttpServletRequest request) throws IOException {
        return R.ok("上传成功", chatMediaService.upload(UserContext.currentUserId(request), file, kind));
    }

    /** 发送消息（body 兼容文本 { content } 与媒体 { msgType, mediaUrl, ... } 两种形态） */
    @PostMapping("/{sessionId}/messages")
    public R<Map<String, Object>> send(@PathVariable Long sessionId,
                                       @RequestBody Map<String, Object> body,
                                       HttpServletRequest request) {
        return R.ok("已发送", chatService.send(UserContext.currentUserId(request), sessionId, "buyer", body));
    }

    private static Long toLong(Object o) {
        try { return o == null ? null : Long.valueOf(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }
}
