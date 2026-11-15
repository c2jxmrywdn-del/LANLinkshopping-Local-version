package com.lanlink.shopping.module.invite.controller;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.UserRoles;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.module.invite.service.InviteService;
import com.lanlink.shopping.service.AuditService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 邀请接口的权限边界（接口层第一道拦截）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>未登录 → 401（不允许匿名拿邀请码）；</li>
 *   <li>非运营调用运营专属端点 → 403，且不触达服务层；</li>
 *   <li>运营调用 → 正常下发入驻码；</li>
 *   <li>越权被拒时写入审计。</li>
 * </ul>
 */
class InviteControllerPermissionTest {

    private InviteService inviteService;
    private AuditService auditService;
    private InviteController controller;

    @BeforeEach
    void setUp() {
        inviteService = mock(InviteService.class);
        auditService = mock(AuditService.class);
        controller = new InviteController(inviteService, auditService);
    }

    /** 构造带登录会话的请求。 */
    private MockHttpServletRequest requestOf(User user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (user != null) {
            HttpSession session = request.getSession(true);
            session.setAttribute(com.lanlink.shopping.config.UserContext.SESSION_KEY, user);
        }
        return request;
    }

    private User user(Long roleId) {
        User u = new User();
        u.setUserId(11L);
        u.setRoleId(roleId);
        u.setNickname("测试用户");
        return u;
    }

    @Test
    void anonymousCannotGetInviteLink() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.link(requestOf(null)));
        assertEquals(401, e.getCode());
        verifyNoInteractions(inviteService);
    }

    @Test
    void buyerCannotIssueMerchantApplyCode() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.merchantApplyCode(null, false, requestOf(user(UserRoles.BUYER_ROLE_ID))));

        assertEquals(403, e.getCode(), "买家调用运营端点必须 403");
        verifyNoInteractions(inviteService);
        verify(auditService).record(eq(11L), eq("ACCESS_DENIED"), any(), any());
    }

    @Test
    void merchantCannotIssueMerchantApplyCode() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.merchantApplyCode(null, false, requestOf(user(UserRoles.MERCHANT_ROLE_ID))));

        assertEquals(403, e.getCode(), "商户调用运营端点必须 403");
        verifyNoInteractions(inviteService);
    }

    @Test
    void adminCanIssueMerchantApplyCode() {
        User admin = user(UserRoles.ADMIN_ROLE_ID);
        when(inviteService.issueMerchantApplyCode(any(), any(), anyBoolean()))
                .thenReturn(Map.of("code", "INVABCDEF1234", "inviteType", "merchant_apply"));

        var result = controller.merchantApplyCode(5, true, requestOf(admin));

        assertEquals(200, result.getCode());
        verify(inviteService).issueMerchantApplyCode(admin, 5, true);
        verify(auditService).record(eq(11L), eq("INVITE_MERCHANT_APPLY_ISSUE"), any(), any());
    }

    @Test
    void anyLoggedInRoleCanGetFrontendLink() {
        for (Long roleId : new Long[]{UserRoles.BUYER_ROLE_ID, UserRoles.MERCHANT_ROLE_ID,
                UserRoles.ADMIN_ROLE_ID}) {
            reset(inviteService);
            when(inviteService.currentLink(any())).thenReturn(Map.of("code", "INVX", "inviteType", "buyer"));
            var result = controller.link(requestOf(user(roleId)));
            assertEquals(200, result.getCode(), "角色 " + roleId + " 应可获取前台邀请链接");
        }
    }
}
