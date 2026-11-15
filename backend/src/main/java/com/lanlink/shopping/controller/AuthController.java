package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.DesensitizeUtil;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.common.UserRoles;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.dto.Login2FADTO;
import com.lanlink.shopping.dto.LoginDTO;
import com.lanlink.shopping.dto.PasswordResetDTO;
import com.lanlink.shopping.dto.ProfileUpdateDTO;
import com.lanlink.shopping.dto.RegisterCodeDTO;
import com.lanlink.shopping.dto.RegisterDTO;
import com.lanlink.shopping.entity.Role;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.entity.UserProfile;
import com.lanlink.shopping.service.AuthLockoutService;
import com.lanlink.shopping.service.LoginLogService;
import com.lanlink.shopping.service.AuditService;
import com.lanlink.shopping.service.IdentityService;
import com.lanlink.shopping.service.MessageService;
import com.lanlink.shopping.service.TotpService;
import com.lanlink.shopping.service.UserProfileService;
import com.lanlink.shopping.service.UserService;
import com.lanlink.shopping.service.VerifyCodeService;
import com.lanlink.shopping.module.invite.service.InviteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证控制器: 注册 / 登录(含两步验证与登录安全记录、防爆破锁定) / 登出 / 当前用户 / 个人资料 / 修改密码
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
    private final AuthLockoutService authLockoutService;
    private final VerifyCodeService verifyCodeService;
    private final InviteService inviteService;

    public AuthController(UserService userService, UserProfileService profileService, TotpService totpService,
                          LoginLogService loginLogService, MessageService messageService,
                          IdentityService identityService, AuditService auditService,
                          AuthLockoutService authLockoutService,
                          VerifyCodeService verifyCodeService,
                          InviteService inviteService) {
        this.userService = userService;
        this.profileService = profileService;
        this.totpService = totpService;
        this.loginLogService = loginLogService;
        this.messageService = messageService;
        this.identityService = identityService;
        this.auditService = auditService;
        this.authLockoutService = authLockoutService;
        this.verifyCodeService = verifyCodeService;
        this.inviteService = inviteService;
    }

    @PostMapping("/register")
    public R<Map<String, Object>> register(@Valid @RequestBody RegisterDTO dto, HttpServletRequest request) {
        // 前端传了验证码就必须校验通过；未传保持旧行为（兼容存量客户端）
        if (dto.getCode() != null && !dto.getCode().isBlank()) {
            verifyCodeService.verifyForRegistration(dto.getPhone(), dto.getCode());
        }
        // 注册恒为买家；merchant_apply 只用于入驻（审核通过后由 MerchantService 升级角色）。
        // 邀请码无效/过期/用尽一律回退 buyer，不阻断注册。
        Long invitedRoleId = inviteService.roleIdForRegister(dto.getInviteCode());
        User u = userService.register(dto, invitedRoleId);
        // 邀请绑定：失败（码无效/过期/用尽/自邀）不阻断注册，仅跳过
        inviteService.bindOnRegister(dto.getInviteCode(), u.getUserId(), u.getRoleId());
        auditService.record(u.getUserId(), "IDENTITY_RECOGNIZE",
                "注册，身份: " + UserRoles.codeOf(u.getRoleId())
                        + (dto.getInviteCode() == null || dto.getInviteCode().isBlank()
                        ? "" : "，邀请码: " + dto.getInviteCode()),
                request);
        return R.ok("注册成功", toView(u, request));
    }

    /**
     * 注册前发送短信验证码（匿名可调用）。
     *
     * <p>安全约束：
     * <ul>
     *   <li>响应不区分"手机号是否已注册"，避免账号枚举；</li>
     *   <li>限流由 {@code /auth/register-code} 的 AuthRateLimitInterceptor 策略兜底；</li>
     *   <li>仅演示模式回显 devCode，生产通道真实下发时返回空对象。</li>
     * </ul>
     */
    @PostMapping("/register-code")
    public R<Map<String, String>> registerCode(@Valid @RequestBody RegisterCodeDTO dto) {
        String code = verifyCodeService.sendForRegistration(dto.getPhone());
        Map<String, String> out = new HashMap<>();
        if (code != null) out.put("devCode", code);
        return R.ok(out);
    }

    /**
     * 忘记密码：校验短信验证码后重置密码（匿名可调用）。
     *
     * <p>安全约束：
     * <ul>
     *   <li>验证码与注册链路同一作用域，5 次错误即作废（见 VerifyCodeService.verify）；</li>
     *   <li>账号不存在与验证码错误分别提示——但验证码校验先于账号查询，
     *       未通过验证码无法探测手机号是否注册；</li>
     *   <li>重置成功后记录审计日志，便于事后追溯异常重置。</li>
     * </ul>
     */
    @PostMapping("/password-reset")
    public R<Void> resetPassword(@Valid @RequestBody PasswordResetDTO dto, HttpServletRequest request) {
        verifyCodeService.verifyForRegistration(dto.getPhone(), dto.getCode());
        userService.resetPasswordByPhone(dto.getPhone(), dto.getPassword());
        User u = userService.findByPhone(dto.getPhone());
        auditService.record(u == null ? null : u.getUserId(), "PASSWORD_RESET",
                "通过短信验证码重置密码", request);
        return R.ok("密码已重置，请用新密码登录", null);
    }

    /**
     * 登录：密码正确后若已启用两步验证，不建会话，返回 require2fa + 一次性票据，
     * 由前端展示动态码输入，再调 /auth/login/2fa 完成登录。
     * 成功/失败均记录登录安全日志。
     */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request) {
        String clientIp = resolveClientIp(request);
        authLockoutService.checkLocked(dto.getPhone(), clientIp);
        Long foundUserId = null;
        try {
            User u = userService.login(dto);
            foundUserId = u.getUserId();
            authLockoutService.recordSuccess(dto.getPhone(), clientIp);
            if (totpService.isEnabled(u.getUserId())) {
                Map<String, Object> out = new HashMap<>();
                out.put("require2fa", true);
                out.put("loginTicket", totpService.issueTicket(u.getUserId()));
                out.put("hint", maskPhone(u.getPhone()));
                loginLogService.record(u.getUserId(), dto.getPhone(), true, "密码校验通过（待两步验证）", request);
                return R.ok(out);
            }
            establishSession(request, u);
            loginLogService.record(u.getUserId(), dto.getPhone(), true, "登录成功", request);
            auditService.record(u.getUserId(), "IDENTITY_RECOGNIZE",
                    "登录，身份: " + identityService.identify(u.getUserId(), u.getRoleId()).getCode(), request);
            return R.ok("登录成功", toView(u, request));
        } catch (BusinessException e) {
            authLockoutService.recordFailure(dto.getPhone(), clientIp);
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
        establishSession(request, u);
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

    /**
     * 供其它登录入口（社交登录）复用：建立会话 + 记录登录安全日志与身份审计 + 返回登录视图。
     * 统一出口避免各入口各写一套视图/日志逻辑。
     */
    public Map<String, Object> establishSessionAndView(User u, String channel, HttpServletRequest request) {
        establishSession(request, u);
        loginLogService.record(u.getUserId(), u.getPhone(), true, "登录成功（" + channel + "）", request);
        auditService.record(u.getUserId(), "IDENTITY_RECOGNIZE",
                "登录，身份: " + identityService.identify(u.getUserId(), u.getRoleId()).getCode(), request);
        return toView(u, request);
    }

    /**
     * 建立已认证会话，并轮换 Session ID（防会话固定）。
     *
     * <p>攻击者可预先让受害者浏览器持有一个已知 JSESSIONID，受害者登录后该 ID 直接
     * 升级为已认证会话。这里先创建/复用会话再 {@code changeSessionId()}：
     * Servlet 容器会保留属性但换发新 ID，旧 ID 立即失效。
     */
    private void establishSession(HttpServletRequest request, User u) {
        jakarta.servlet.http.HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(UserContext.SESSION_KEY, u);
    }

    private Map<String, Object> toView(User u, HttpServletRequest request) {
        Map<String, Object> map = new HashMap<>();
        map.put("userId", u.getUserId());
        map.put("phone", DesensitizeUtil.maskPhone(u.getPhone()));
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

    private String resolveClientIp(HttpServletRequest request) {
        String ip = request.getHeader("CF-Connecting-IP");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Forwarded-For");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip == null ? "unknown" : ip;
    }
}
