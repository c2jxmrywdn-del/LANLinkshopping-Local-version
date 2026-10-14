package com.lanlink.shopping.config;

import com.lanlink.shopping.common.UserIdentity;
import com.lanlink.shopping.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * UserContext.identity() 兜底逻辑单测：
 * 保证任何场景下都不返回 null（修复 NPE 缺陷）。
 */
class UserContextTest {

    private HttpServletRequest request;
    private HttpSession session;

    private void mockRequest() {
        request = mock(HttpServletRequest.class);
        session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
    }

    private User loggedUser() {
        User u = new User();
        u.setUserId(2L);
        u.setRoleId(1L);
        return u;
    }

    @Test
    void anonymousCurrentDoesNotCreateSession() {
        request = mock(HttpServletRequest.class);
        when(request.getSession(false)).thenReturn(null);

        assertNull(UserContext.current(request));
        verify(request).getSession(false);
        verify(request, never()).getSession();
    }

    @Test
    void notLoggedInReturnsGuest() {
        mockRequest();
        // 未登录：无身份属性、session 无登录用户
        when(request.getAttribute(UserContext.IDENTITY_KEY)).thenReturn(null);
        when(session.getAttribute(UserContext.SESSION_KEY)).thenReturn(null);
        assertEquals(UserIdentity.GUEST, UserContext.identity(request));
    }

    @Test
    void loggedInWithoutIdentityAttributeReturnsBuyer() {
        mockRequest();
        // 已登录（session 有用户）但身份属性缺失 → 兜底 BUYER，绝不返回 null
        when(request.getAttribute(UserContext.IDENTITY_KEY)).thenReturn(null);
        when(session.getAttribute(UserContext.SESSION_KEY)).thenReturn(loggedUser());
        UserIdentity id = UserContext.identity(request);
        assertEquals(UserIdentity.BUYER, id);
        // 兜底身份必须可用（PermInterceptor 将直接调用 has()）
        assertFalse(id.has("product:publish"));
        assertTrue(id.has("cart:manage"));
    }

    @Test
    void loggedInWithIdentityAttributeReturnsOriginal() {
        mockRequest();
        // 已登录且身份属性存在 → 原样返回（如商户）
        when(request.getAttribute(UserContext.IDENTITY_KEY)).thenReturn(UserIdentity.MERCHANT);
        when(session.getAttribute(UserContext.SESSION_KEY)).thenReturn(loggedUser());
        assertEquals(UserIdentity.MERCHANT, UserContext.identity(request));
    }

    @Test
    void identityNeverNullAcrossScenarios() {
        mockRequest();
        when(session.getAttribute(UserContext.SESSION_KEY)).thenReturn(null);
        // 遍历常见身份属性取值，断言均非 null
        for (UserIdentity id : new UserIdentity[]{null, UserIdentity.GUEST, UserIdentity.BUYER, UserIdentity.VIP}) {
            when(request.getAttribute(UserContext.IDENTITY_KEY)).thenReturn(id);
            assertNotNull(UserContext.identity(request), "identity() 不应返回 null");
        }
    }
}
