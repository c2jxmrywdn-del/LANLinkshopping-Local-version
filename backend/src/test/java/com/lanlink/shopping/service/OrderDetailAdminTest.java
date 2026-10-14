package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.mapper.CartMapper;
import com.lanlink.shopping.mapper.OrderItemMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.integration.pricing.PricingFacade;
import com.lanlink.shopping.module.membership.service.MembershipService;
import com.lanlink.shopping.vo.OrderDetailVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderDetailAdminTest {

    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        CartMapper cartMapper = mock(CartMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        MessageService messageService = mock(MessageService.class);
        IdentityService identityService = mock(IdentityService.class);
        AuditService auditService = mock(AuditService.class);
        PricingFacade pricingFacade = mock(PricingFacade.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        MembershipService membershipService = mock(MembershipService.class);
        CreditTermService creditTermService = mock(CreditTermService.class);

        orderService = new OrderService(cartMapper, productMapper, orderMapper, orderItemMapper,
                userMapper, messageService, identityService, auditService, pricingFacade,
                eventPublisher, membershipService, creditTermService);
    }

    @Test
    void detailRejectsNonOwnerWhenNotAdmin() {
        Order o = new Order();
        o.setOrderNo("ORD123");
        o.setUserId(10L);
        when(orderMapper.selectById("ORD123")).thenReturn(o);

        assertThrows(BusinessException.class, () -> orderService.detail(99L, "ORD123", false));
    }

    @Test
    void detailAllowsAdminToViewAnyOrder() {
        Order o = new Order();
        o.setOrderNo("ORD123");
        o.setUserId(10L);
        when(orderMapper.selectById("ORD123")).thenReturn(o);
        when(orderItemMapper.selectList(any())).thenReturn(Collections.emptyList());

        OrderDetailVO vo = orderService.detail(99L, "ORD123", true);
        assertNotNull(vo);
        assertEquals("ORD123", vo.getOrder().getOrderNo());
    }
}
