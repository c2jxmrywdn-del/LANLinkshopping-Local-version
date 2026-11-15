package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.service.SocialLoginService;
import com.lanlink.shopping.social.AppleAuthHandoff;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 社交登录入口（公开，鉴权白名单仅放行 providers / authorize / callback）
 *  - GET  /auth/social/providers          渠道可用性（未配置也返回，前端据此渲染并提示）
 *  - GET  /auth/social/authorize/{p}      取平台授权地址（未配置→明确报错，不伪造登录）
 *  - POST /auth/social/apple/return      Apple form_post 中转，302 回前端登录页
 *  - POST /auth/social/callback/{p}       前端带回授权码 → 换取身份 → 已绑定则建立会话登录
 *  - POST /auth/social/bind/{p}           已登录用户绑定第三方账号（需登录）
 */
@RestController
@RequestMapping("/auth/social")
public class SocialLoginController {

    private final SocialLoginService service;
    private final AuthController authController;
    private final AppleAuthHandoff appleHandoff;

    /** Session 中保存的待校验 state（一次性）。 */
    private static final String SOCIAL_STATE_KEY = "LL_SOCIAL_STATE";

    public SocialLoginController(SocialLoginService service, AuthController authController,
                                 AppleAuthHandoff appleHandoff) {
        this.service = service;
        this.authController = authController;
        this.appleHandoff = appleHandoff;
    }

    /**
     * 一次性消费并校验 OAuth state。
     *
     * <p>规则：Session 里没有待校验值 → 拒绝（必须先走 /authorize）；
     * 值不匹配 → 拒绝；匹配 → 立即移除，防止重放。
     */
    private void consumeState(HttpServletRequest request, String state) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        Object expected = session == null ? null : session.getAttribute(SOCIAL_STATE_KEY);
        if (expected == null) {
            throw new BusinessException("授权状态校验失败，请重新发起授权");
        }
        // 无论成功失败都先移除，保证 state 只能用一次
        session.removeAttribute(SOCIAL_STATE_KEY);
        if (state == null || !state.equals(String.valueOf(expected))) {
            throw new BusinessException("授权状态校验失败，请重新发起授权");
        }
    }

    @GetMapping("/providers")
    public R<List<Map<String, Object>>> providers() {
        return R.ok(service.providers());
    }

    @GetMapping("/authorize/{provider}")
    public R<Map<String, Object>> authorize(@PathVariable String provider,
                                           @RequestParam(required = false) String state,
                                           HttpServletRequest request) {
        // state：一次性防 CSRF 值。服务端生成并写入 Session，回调时一次性消费比对。
        // 历史缺陷：state 由客户端传入且从不落库，回调只判非空——攻击者可用自己的
        // 授权码 + 任意 state 诱导受害者会话完成登录（登录 CSRF / 账号混淆）。
        String st = UUID.randomUUID().toString().replace("-", "");
        String nonce = "apple".equals(provider) ? UUID.randomUUID().toString().replace("-", "") : null;
        request.getSession(true).setAttribute(SOCIAL_STATE_KEY, st);
        String url = service.authorizeUrl(provider, st, nonce);
        if (nonce != null && url.startsWith("https://appleid.apple.com/")) {
            appleHandoff.remember(st, nonce);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", provider);
        out.put("state", st);
        out.put("authorizeUrl", url);
        return R.ok(out);
    }

    /**
     * Apple 把 code、state、首次授权的 user JSON 以表单 POST 到这里。
     * 不读登录会话（跨站 POST 带不过来），校验通过后 302 回前端登录页。
     */
    @PostMapping("/apple/return")
    public void appleReturn(@RequestParam(required = false) String code,
                             @RequestParam(required = false) String state,
                             @RequestParam(required = false) String user,
                             @RequestParam(required = false) String error,
                             HttpServletResponse response) throws IOException {
        String location = appleHandoff.relay(code, state, user, error);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.sendRedirect(location);
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
        consumeState(request, state);
        AppleAuthHandoff.Claim apple = takeAppleClaim(provider, state);
        User u = service.callback(provider, code, state,
                apple == null ? null : apple.nonce(),
                apple == null ? null : apple.displayName());
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
        consumeState(request, state);
        AppleAuthHandoff.Claim apple = takeAppleClaim(provider, state);
        return R.ok("绑定成功", service.bindIdentity(me.getUserId(), provider, code, state,
                apple == null ? null : apple.nonce(),
                apple == null ? null : apple.displayName()));
    }

    /** Apple 的 nonce 和首次姓名存在服务端短时缓存，必须和会话里的 state 一起消费。 */
    private AppleAuthHandoff.Claim takeAppleClaim(String provider, String state) {
        if (!"apple".equals(provider)) return null;
        AppleAuthHandoff.Claim claim = appleHandoff.consume(state);
        if (claim == null) throw new BusinessException("授权状态校验失败，请重新发起授权");
        return claim;
    }

    /**
     * 沙箱模拟登录：在未配置海外 OAuth 密钥时，提供高拟真 Google / Apple 登录闭环体验
     */
    @PostMapping("/sandbox-login")
    public R<Map<String, Object>> sandboxLogin(@RequestBody Map<String, String> body,
                                              HttpServletRequest request) {
        String provider = body == null ? null : body.get("provider");
        String sub = body == null ? null : body.get("sub");
        String email = body == null ? null : body.get("email");
        String name = body == null ? null : body.get("name");
        String avatar = body == null ? null : body.get("avatar");
        User u = service.sandboxLogin(provider, sub, email, name, avatar);
        return R.ok(provider + " 授权登录成功", authController.establishSessionAndView(u, provider + " 沙箱登录", request));
    }

    /** 查询当前用户已绑定的第三方社交账号 */
    @GetMapping("/my-bindings")
    public R<List<Map<String, Object>>> myBindings(HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        return R.ok(service.userBindings(userId));
    }

    /** 解绑第三方社交账号 */
    @PostMapping("/unbind/{provider}")
    public R<Boolean> unbind(@PathVariable String provider, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        boolean ok = service.unbindProvider(userId, provider);
        return ok ? R.ok("解绑成功", true) : R.fail("未找到绑定的该渠道账号");
    }
}
