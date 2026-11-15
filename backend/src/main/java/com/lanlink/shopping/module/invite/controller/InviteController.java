package com.lanlink.shopping.module.invite.controller;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.common.UserRoles;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.module.invite.service.InviteService;
import com.lanlink.shopping.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 邀请链接 / 二维码接口。
 *
 * <table border="1">
 *   <caption>端点 × 权限</caption>
 *   <tr><th>端点</th><th>可调用角色</th><th>产出身份</th></tr>
 *   <tr><td>GET  /invite/link</td><td>任意登录用户</td><td>买家（注册即得）</td></tr>
 *   <tr><td>POST /invite/link/rotate</td><td>任意登录用户</td><td>买家（注册即得）</td></tr>
 *   <tr><td>GET  /invite/stats</td><td>任意登录用户</td><td>—</td></tr>
 *   <tr><td>POST /invite/admin/merchant-apply</td><td><b>仅平台运营</b></td>
 *       <td>商户（须走入驻申请 + 审核）</td></tr>
 * </table>
 *
 * <p>权限双层拦截：AuthInterceptor 保证登录；本控制器对运营专属端点做角色校验
 * （{@link #requireAdmin}），服务层 {@code issueMerchantApplyCode} 再校验一次。
 * 未登录 → 401，非运营调用运营端点 → 403。
 */
@RestController
@RequestMapping("/invite")
public class InviteController {

    private final InviteService inviteService;
    private final AuditService auditService;

    public InviteController(InviteService inviteService, AuditService auditService) {
        this.inviteService = inviteService;
        this.auditService = auditService;
    }

    /** 前台邀请链接（任意登录角色）。 */
    @GetMapping("/link")
    public R<Map<String, Object>> link(HttpServletRequest request) {
        return R.ok(inviteService.currentLink(requireUser(request)));
    }

    /** 重新生成前台邀请链接。 */
    @PostMapping("/link/rotate")
    public R<Map<String, Object>> rotate(@RequestParam(required = false) Integer maxUses,
                                         HttpServletRequest request) {
        User user = requireUser(request);
        Map<String, Object> out = inviteService.rotate(user, maxUses);
        auditService.record(user.getUserId(), "INVITE_ROTATE",
                "重新生成邀请链接，类型: " + out.get("inviteType") + "，次数上限: " + out.get("maxUses"), request);
        return R.ok("已生成新的邀请链接", out);
    }

    /** 邀请统计。 */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats(HttpServletRequest request) {
        return R.ok(inviteService.stats(requireUser(request)));
    }

    /**
     * 运营后台：商家入驻邀约码。
     *
     * @param maxUses 使用次数上限（&le;0 用配置默认值，0=不限）
     * @param rotate  是否作废旧码重新生成
     */
    @PostMapping("/admin/merchant-apply")
    public R<Map<String, Object>> merchantApplyCode(@RequestParam(required = false) Integer maxUses,
                                                    @RequestParam(defaultValue = "false") boolean rotate,
                                                    HttpServletRequest request) {
        User admin = requireAdmin(request);
        Map<String, Object> out = inviteService.issueMerchantApplyCode(admin, maxUses, rotate);
        auditService.record(admin.getUserId(), "INVITE_MERCHANT_APPLY_ISSUE",
                "生成商家入驻邀约码，次数上限: " + out.get("maxUses") + "，rotate=" + rotate, request);
        return R.ok("已生成商家入驻邀请码", out);
    }

    private static User requireUser(HttpServletRequest request) {
        User u = UserContext.current(request);
        if (u == null) {
            throw new BusinessException(401, "请先登录");
        }
        return u;
    }

    /** 接口层权限拦截：非平台运营直接 403，并记审计。 */
    private User requireAdmin(HttpServletRequest request) {
        User u = requireUser(request);
        if (!UserRoles.isAdmin(u.getRoleId())) {
            auditService.record(u.getUserId(), "ACCESS_DENIED", "越权生成商家入驻邀请码", request);
            throw new BusinessException(403, "无权限：仅平台运营可生成商家入驻邀请码");
        }
        return u;
    }
}
