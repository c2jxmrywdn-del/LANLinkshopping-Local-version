package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.common.UserIdentity;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.dto.Login2FADTO;
import com.lanlink.shopping.dto.LoginDTO;
import com.lanlink.shopping.dto.ProfileUpdateDTO;
import com.lanlink.shopping.dto.RegisterDTO;
import com.lanlink.shopping.entity.Role;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.entity.UserProfile;
import com.lanlink.shopping.service.LoginLogService;
import com.lanlink.shopping.service.AuditService;
import com.lanlink.shopping.service.IdentityService;
import com.lanlink.shopping.service.MessageService;
import com.lanlink.shopping.service.TotpService;
import com.lanlink.shopping.service.UserProfileService;
import com.lanlink.shopping.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证控制器: 注册 / 登录(含两步验证与登录安全记录) / 登出 / 当前用户 / 个人资料 / 修改密码
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;
    private final UserProfileService profileService;
    private final TotpService totpService;
    private final LoginLogService loginLogService;
    private final MessageService messageService;
    private final IdentityService identityService;
    private final AuditService auditService;

    public AuthController(UserService userService, UserProfileService profileService, TotpService totpService,
                          LoginLogService loginLogService, MessageService messageService,
                          IdentityService identityService, AuditService auditService) {
        this.userService = userService;
        this.profileService = profileService;
        this.totpService = totpService;
        this.loginLogService = loginLogService;
        this.messageService = messageService;
        this.identityService = identityService;
        this.auditService = auditService;
    }

    @PostMapping("/register")
    public R<Map<String, Object>> register(@Valid @RequestBody RegisterDTO dto, HttpServletRequest request) {
        User u = userService.register(dto);
        auditService.record(u.getUserId(), "IDENTITY_RECOGNIZE", "注册，身份: " + UserIdentity.BUYER.getCode(), request);
        return R.ok("注册成功", toView(u, request));
    }

    /**
     * 登录：密码正确后若已启用两步验证，不建会话，返回 require2fa + 一次性票据，
     * 由前端展示动态码输入，再调 /auth/login/2fa 完成登录。
     * 成功/失败均记录登录安全日志。
     */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request) {
        Long foundUserId = null;
        try {
            User u = userService.login(dto);
            foundUserId = u.getUserId();
            if (totpService.isEnabled(u.getUserId())) {
                Map<String, Object> out = new HashMap<>();
                out.put("require2fa", true);
                out.put("loginTicket", totpService.issueTicket(u.getUserId()));
                out.put("hint", maskPhone(u.getPhone()));
                loginLogService.record(u.getUserId(), dto.getPhone(), true, "密码校验通过（待两步验证）", request);
                return R.ok(out);
            }
            request.getSession().setAttribute(UserContext.SESSION_KEY, u);
            loginLogService.record(u.getUserId(), dto.getPhone(), true, "登录成功", request);
            auditService.record(u.getUserId(), "IDENTITY_RECOGNIZE",
                    "登录，身份: " + identityService.identify(u.getUserId(), u.getRoleId()).getCode(), request);
            return R.ok("登录成功", toView(u, request));
        } catch (BusinessException e) {
            loginLogService.record(foundUserId, dto.getPhone(), false, e.getMessage(), request);
            throw e;
        }
    }

    /** 两步验证登录：一次性票据 + 动态验证码 */
    @PostMapping("/login/2fa")
    public R<Map<String, Object>> login2fa(@Valid @RequestBody Login2FADTO dto, HttpServletRequest request) {
        Long userId = totpService.consumeTicket(dto.getLoginTicket());
        if (userId == null) {
            loginLogService.record(null, "", false, "登录状态已过期", request);
            throw new BusinessException("登录状态已过期，请重新登录");
        }
        if (!totpService.verify(userId, dto.getCode())) {
            User u = userService.findById(userId);
            loginLogService.record(userId, u == null ? "" : u.getPhone(), false, "动态验证码错误", request);
            throw new BusinessException("动态验证码错误");
        }
        User u = userService.findById(userId);
        if (u == null) throw new BusinessException("用户不存在");
        request.getSession().setAttribute(UserContext.SESSION_KEY, u);
        loginLogService.record(userId, u.getPhone(), true, "登录成功（两步验证）", request);
        auditService.record(userId, "IDENTITY_RECOGNIZE",
                "登录，身份: " + identityService.identify(userId, u.getRoleId()).getCode(), request);
        return R.ok("登录成功", toView(u, request));
    }

    private String maskPhone(String p) {
        if (p == null || p.length() != 11) return p == null ? "" : p;
        return p.substring(0, 3) + "****" + p.substring(7);
    }

    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request) {
        request.getSession().invalidate();
        return R.ok();
    }

    @GetMapping("/me")
    public R<Map<String, Object>> me(HttpServletRequest request) {
        User u = UserContext.current(request);
        if (u == null) throw new BusinessException(401, "未登录");
        return R.ok(toView(u, request));
    }

    private Map<String, Object> toView(User u, HttpServletRequest request) {
        Map<String, Object> map = new HashMap<>();
        map.put("userId", u.getUserId());
        map.put("phone", u.getPhone());
        map.put("nickname", u.getNickname());
        map.put("roleId", u.getRoleId());
        map.put("entId", u.getEntId());
        Role r = userService.findRole(u.getRoleId());
        map.put("roleCode", r == null ? null : r.getRoleCode());
        map.put("roleName", r == null ? null : r.getRoleName());
        // 实时身份（访客/普通/VIP/商户/管理员 + 权限 + VIP 达标进度）
        map.put("identity", identityService.identityView(request));
        // 未读站内消息数（顶栏角标），login/login2fa/me 全链路生效
        map.put("unreadCount", messageService.unreadCount(u.getUserId()));
        return map;
    }
}