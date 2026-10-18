package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.service.LocaleHintService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 语言自动识别的地理提示（公开接口，已加入鉴权白名单 /i18n/**）。
 *
 * GET /api/i18n/locale-hint → { country, locale, source }
 *   source: geo（已识别）| private（内网/回环，跳过）| disabled（已关闭）| unavailable（查询失败）
 *
 * 前端仅在「时区 + 浏览器语言」等离线信号不明确时调用；高置信场景直接跳过，不产生往返。
 */
@RestController
@RequestMapping("/i18n")
public class LocaleHintController {

    private final LocaleHintService service;

    public LocaleHintController(LocaleHintService service) {
        this.service = service;
    }

    @GetMapping("/locale-hint")
    public R<Map<String, Object>> localeHint(HttpServletRequest request) {
        String ip = LocaleHintService.clientIp(
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getRemoteAddr());
        return R.ok(service.hint(ip));
    }
}
