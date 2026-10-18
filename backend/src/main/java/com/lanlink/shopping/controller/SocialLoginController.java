package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.service.SocialLoginService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 社交登录入口（公开，鉴权白名单仅放行 providers / authorize / callback）
 *  - GET  /auth/social/providers          渠道可用性（未配置也返回，前端据此渲染并提示）
 *  - GET  /auth/social/authorize/{p}      取平台授权地址（未配置→明确报错，不伪造登录）
 *  - POST /auth/social/callback/{p}       前端带回授权码 → 换取身份 → 已绑定则建立会话登录
 *  - POST /auth/social/bind/{p}           已登录用户绑定第三方账号（需登录）
 */
@RestController
@RequestMapping("/auth/social")
public class SocialLoginController {

    private final SocialLoginService service;
    private final AuthController authController;

    public SocialLoginController(SocialLoginService service, AuthController authController) {
        this.service = service;
        this.authController = authController;
    }

    @GetMapping("/providers")
    public R<List<Map<String, Object>>> providers() {
        return R.ok(service.providers());
    }

    @GetMapping("/authorize/{provider}")
    public R<Map<String, Object>> authorize(@PathVariable String provider,
                                           @RequestParam(required = false) String state) {
        // state：一次性防 CSRF 值；未传时由服务端生成，回调时需原样带回校验
        String st = (state == null || state.isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : state;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", provider);
        out.put("state", st);
        out.put("authorizeUrl", service.authorizeUrl(provider, st));
        return R.ok(out);
    }

    /**
     * 授权回调：平台把授权码回跳给前端，前端再带 code/state 调本接口完成换取与登录。
     * 未绑定的第三方账号不会自动建号，返回可执行的绑定指引。
     */
    @PostMapping("/callback/{provider}")
    public R<Map<String, Object>> callback(@PathVariable String provider,
                                          @RequestBody(required = false) Map<String, String> body,
                                          HttpServletRequest request) {
        String code = body == null ? null : body.get("code");
        String state = body == null ? null : body.get("state");
        User u = service.callback(provider, code, state);
        return R.ok("登录成功", authController.establishSessionAndView(u, provider + " 登录", request));
    }

    /** 已登录用户绑定：账号设置 → 第三方授权 的入口 */
    @PostMapping("/bind/{provider}")
    public R<Map<String, Object>> bind(@PathVariable String provider,
                                       @RequestBody(required = false) Map<String, String> body,
                                       HttpServletRequest request) {
        User me = UserContext.current(request);
        if (me == null) throw new BusinessException(401, "请先登录后再绑定");
        String code = body == null ? null : body.get("code");
        String state = body == null ? null : body.get("state");
        return R.ok("绑定成功", service.bindIdentity(me.getUserId(), provider, code, state));
    }
}
