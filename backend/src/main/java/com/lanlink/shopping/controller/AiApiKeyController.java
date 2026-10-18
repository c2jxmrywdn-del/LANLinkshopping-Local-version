package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.support.AiApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/admin/ai-api-keys")
@RequirePerm("admin:all")
public class AiApiKeyController {
    private final AiApiKeyService keys;
    public AiApiKeyController(AiApiKeyService keys) { this.keys = keys; }

    @GetMapping
    public R<?> list() { return R.ok(keys.list()); }

    @PostMapping
    public R<?> create(@RequestBody(required = false) Request body, HttpServletRequest request) {
        if (body == null || body.name() == null || body.name().isBlank() || body.provider() == null || body.provider().isBlank()) {
            return R.fail(400, "名称和提供商不能为空");
        }
        LocalDateTime expires = null;
        if (body.expiresAt() != null && !body.expiresAt().isBlank()) expires = LocalDateTime.parse(body.expiresAt());
        return R.ok("密钥已创建。原始密钥仅返回一次，请立即保存。", keys.create(body.name(), body.provider(),
                body.permissions() == null ? "intent:analyze" : body.permissions(), expires,
                UserContext.currentUserId(request), request));
    }

    @PostMapping("/{id}/disable")
    public R<?> disable(@PathVariable long id, HttpServletRequest request) {
        keys.disable(id, UserContext.currentUserId(request), request);
        return R.ok(Map.of("disabled", true));
    }

    public record Request(String name, String provider, String permissions, String expiresAt) {}
}
