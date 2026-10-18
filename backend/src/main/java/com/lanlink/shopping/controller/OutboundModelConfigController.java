package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.support.OutboundModelConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 出站模型渠道配置管理（仅平台运营）。
 * 需登录 + @RequirePerm("admin:all")：/admin/outbound-model-config 全部端点。
 * 回显仅脱敏前缀（apiKeyPrefix），绝不回传 API Key 明文。
 */
@RestController
@RequestMapping("/admin/outbound-model-config")
public class OutboundModelConfigController {

    private final OutboundModelConfigService service;

    public OutboundModelConfigController(OutboundModelConfigService service) {
        this.service = service;
    }

    /** 渠道配置列表（脱敏回显）+ 功能开关状态 */
    @GetMapping
    @RequirePerm("admin:all")
    public R<Map<String, Object>> list() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("channels", service.list());
        out.put("intentExternalEnabled", service.toggle("intent_external_enabled"));
        return R.ok(out);
    }

    /** 新增/更新渠道配置（apiKey 为空 = 保留旧密钥） */
    @PostMapping
    @RequirePerm("admin:all")
    public R<Void> upsert(@RequestBody UpsertRequest body, HttpServletRequest request) {
        service.upsert(body.channel(), body.baseUrl(), body.apiKey(), body.modelName(),
                body.enabled() == null || body.enabled(),
                UserContext.currentUserId(request), request);
        return R.ok("渠道配置已保存", null);
    }

    /** 渠道连通性与 API 密钥测试 */
    @PostMapping("/test")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> test(@RequestBody TestRequest body) {
        Map<String, Object> result = service.testConnection(
                body.channel(), body.baseUrl(), body.apiKey(), body.modelName());
        return R.ok(result);
    }

    /** AI 草稿与关键词生成（供客服候选学习审核/沙盒使用） */
    @PostMapping("/ai-suggest")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> aiSuggest(@RequestBody SuggestRequest body) {
        if (body == null || body.question() == null || body.question().isBlank()) {
            return R.fail(400, "问题不能为空");
        }
        return R.ok(service.generateAiDraft(body.question()));
    }

    /** 功能开关（如 intent_external_enabled：外部模型可选意图增强） */
    @PostMapping("/toggle")
    @RequirePerm("admin:all")
    public R<Void> toggle(@RequestBody ToggleRequest body, HttpServletRequest request) {
        if (body.key() == null || body.key().isBlank()) {
            return R.fail(400, "开关名不能为空");
        }
        service.setToggle(body.key().trim(), Boolean.TRUE.equals(body.on()),
                UserContext.currentUserId(request), request);
        return R.ok("开关已更新", null);
    }

    public record UpsertRequest(String channel, String baseUrl, String apiKey, String modelName, Boolean enabled) {
        public UpsertRequest(String channel, String baseUrl, String apiKey, Boolean enabled) {
            this(channel, baseUrl, apiKey, null, enabled);
        }
    }
    public record TestRequest(String channel, String baseUrl, String apiKey, String modelName) {}
    public record SuggestRequest(String question) {}
    public record ToggleRequest(String key, Boolean on) {}
}
