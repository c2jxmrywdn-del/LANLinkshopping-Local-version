package com.lanlink.shopping.service;

import com.lanlink.shopping.common.UserIdentity;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdentityServiceTest {

    private OrderMapper orderMapper;
    private UserMapper userMapper;
    private IdentityService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        userMapper = mock(UserMapper.class);
        service = new IdentityService(orderMapper, userMapper);
    }

    @Test
    void springCanCreateServiceWhenMultipleConstructorsExist() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("orderMapper", orderMapper);
            context.getBeanFactory().registerSingleton("userMapper", userMapper);
            context.register(IdentityService.class);
            context.refresh();
            assertNotNull(context.getBean(IdentityService.class));
        }
    }

    @Test
    void identifyGuestWhenNoUserInSession() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(request.getSession()).thenReturn(session);
        when(session.getAttribute(UserContext.SESSION_KEY)).thenReturn(null);

        assertEquals(UserIdentity.GUEST, service.identify(request));
    }

    @Test
    void identifyHotSwitchesToMerchantWhenDbRoleChanges() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(request.getSession()).thenReturn(session);
        when(request.getSession(false)).thenReturn(session);

        User sessionUser = new User();
        sessionUser.setUserId(5L);
        sessionUser.setRoleId(1L); // session holds stale role (buyer)
        when(session.getAttribute(UserContext.SESSION_KEY)).thenReturn(sessionUser);

        User dbUser = new User();
        dbUser.setUserId(5L);
        dbUser.setRoleId(2L); // DB has updated role (merchant)
        dbUser.setEntId(100L);
        when(userMapper.selectById(5L)).thenReturn(dbUser);

        UserIdentity identity = service.identify(request);

        assertEquals(UserIdentity.MERCHANT, identity, "实时识别应反映数据库中最新的商户角色");
        assertEquals(2L, sessionUser.getRoleId(), "Session 中的用户角色应被热更新为 2");
        assertEquals(100L, sessionUser.getEntId(), "Session 中的企业ID应同步更新");
        verify(session).setAttribute(UserContext.SESSION_KEY, sessionUser);
    }

    @Test
    void identifyVipWhenPaidAmountReachesThreshold() {
        when(orderMapper.sumPaidAmount(6L)).thenReturn(new BigDecimal("5000.00"));
        UserIdentity identity = service.identify(6L, 1L);
        assertEquals(UserIdentity.VIP, identity);
    }

    @Test
    void identifyBuyerWhenPaidAmountBelowThreshold() {
        when(orderMapper.sumPaidAmount(7L)).thenReturn(new BigDecimal("4999.99"));
        UserIdentity identity = service.identify(7L, 1L);
        assertEquals(UserIdentity.BUYER, identity);
    }
}
