package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.service.ChatMediaService;
import com.lanlink.shopping.service.MerchantChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 买家↔商家 对话（商户端收发）：merId 一律由登录态在服务端推导，前端传参不可信。
 */
@RestController
@RequestMapping("/merchant/center/chat")
@RequirePerm("merchant:manage")
public class MerchantChatController {

    private final MerchantChatService chatService;
    private final ChatMediaService chatMediaService;

    public MerchantChatController(MerchantChatService chatService, ChatMediaService chatMediaService) {
        this.chatService = chatService;
        this.chatMediaService = chatMediaService;
    }

    /** 会话分页列表（含买家昵称与未读） */
    @GetMapping("/sessions")
    public R<Map<String, Object>> sessions(@RequestParam(defaultValue = "1") long page,
                                           @RequestParam(defaultValue = "20") long size,
                                           HttpServletRequest request) {
        return R.ok(chatService.merchantSessions(UserContext.currentUserId(request), page, size));
    }

    /** 未读总数（侧栏「客户消息」角标） */
    @GetMapping("/unread")
    public R<Map<String, Object>> unread(HttpServletRequest request) {
        return R.ok(Map.of("count", chatService.merchantUnread(UserContext.currentUserId(request))));
    }

    /** 会话消息（afterId 增量拉取；进入即回执已读） */
    @GetMapping("/{sessionId}/messages")
    public R<Map<String, Object>> messages(@PathVariable Long sessionId,
                                           @RequestParam(defaultValue = "0") long afterId,
                                           HttpServletRequest request) {
        return R.ok(chatService.messages(UserContext.currentUserId(request), sessionId, afterId, "merchant"));
    }

    /** 聊天媒体上传（商户侧，沿用类级 merchant:manage 权限）：kind=image/file/voice，返回 { url, name, size } */
    @PostMapping("/media")
    public R<Map<String, Object>> uploadMedia(@RequestParam("file") MultipartFile file,
                                              @RequestParam String kind,
                                              HttpServletRequest request) throws IOException {
        return R.ok("上传成功", chatMediaService.upload(UserContext.currentUserId(request), file, kind));
    }

    /** 回复消息（body 兼容文本 { content } 与媒体 { msgType, mediaUrl, ... } 两种形态） */
    @PostMapping("/{sessionId}/messages")
    public R<Map<String, Object>> send(@PathVariable Long sessionId,
                                       @RequestBody Map<String, Object> body,
                                       HttpServletRequest request) {
        return R.ok("已回复", chatService.send(UserContext.currentUserId(request), sessionId, "merchant", body));
    }
}
