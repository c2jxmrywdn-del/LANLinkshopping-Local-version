package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.lanlink.shopping.dto.MerchantApplyDTO;
import com.lanlink.shopping.entity.Enterprise;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.EnterpriseMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.QualificationMapper;
import com.lanlink.shopping.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MerchantServiceRoleUpgradeTest {

    private MerchantMapper merchantMapper;
    private EnterpriseMapper enterpriseMapper;
    private QualificationMapper qualificationMapper;
    private AuditService auditService;
    private UserMapper userMapper;
    private MerchantService service;

    @BeforeEach
    void setUp() {
        merchantMapper = mock(MerchantMapper.class);
        enterpriseMapper = mock(EnterpriseMapper.class);
        qualificationMapper = mock(QualificationMapper.class);
        auditService = mock(AuditService.class);
        userMapper = mock(UserMapper.class);
        service = new MerchantService(merchantMapper, enterpriseMapper, qualificationMapper, auditService, userMapper);
    }

    @Test
    void springCanCreateServiceWhenMultipleConstructorsExist() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("merchantMapper", merchantMapper);
            context.getBeanFactory().registerSingleton("enterpriseMapper", enterpriseMapper);
            context.getBeanFactory().registerSingleton("qualificationMapper", qualificationMapper);
            context.getBeanFactory().registerSingleton("auditService", auditService);
            context.getBeanFactory().registerSingleton("userMapper", userMapper);
            context.register(MerchantService.class);
            context.refresh();
            assertNotNull(context.getBean(MerchantService.class));
        }
    }

    @Test
    void applyWithPassingScreeningUpgradesUserRoleToMerchant() {
        Long userId = 10L;
        User user = new User();
        user.setUserId(userId);
        user.setRoleId(1L);

        when(merchantMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.selectById(userId)).thenReturn(user);
        doAnswer(invocation -> {
            Enterprise ent = invocation.getArgument(0);
            ent.setEntId(55L);
            return 1;
        }).when(enterpriseMapper).insert(any(Enterprise.class));

        MerchantApplyDTO dto = new MerchantApplyDTO();
        dto.setEntName("科技公司");
        dto.setRegType("公司");
        dto.setRegCapital(new BigDecimal("500000"));
        dto.setTaxStatus(1);
        dto.setJoinType("普通商户");

        Merchant m = service.apply(userId, null, dto);

        assertEquals(1, m.getReviewStatus(), "公司主体且纳税记录正常，初筛应通过");
        assertEquals(2L, user.getRoleId(), "初筛通过后，用户角色应升级为 2 (商户)");
        assertEquals(55L, user.getEntId(), "初筛通过后，用户应关联企业ID");
        verify(userMapper).updateById(user);
        verify(auditService).record(eq(userId), eq("IDENTITY_CHANGE"), contains("buyer -> merchant"), isNull());
    }

    @Test
    void reviewApproveUpgradesUserRole() {
        Long userId = 20L;
        Merchant m = new Merchant();
        m.setMerId(1L);
        m.setUserId(userId);
        m.setEntId(88L);
        m.setReviewStatus(0);

        User user = new User();
        user.setUserId(userId);
        user.setRoleId(1L);

        when(merchantMapper.selectById(1L)).thenReturn(m);
        when(userMapper.selectById(userId)).thenReturn(user);

        service.review(1L, 1, null);

        assertEquals(2L, user.getRoleId(), "人工审核通过后，用户角色应升级为 2 (商户)");
        assertEquals(88L, user.getEntId(), "人工审核通过后，用户企业ID应同步");
        verify(userMapper).updateById(user);
    }

    @Test
    void reviewRejectResetsMerchantRoleToBuyer() {
        Long userId = 30L;
        Merchant m = new Merchant();
        m.setMerId(2L);
        m.setUserId(userId);
        m.setReviewStatus(1);

        User user = new User();
        user.setUserId(userId);
        user.setRoleId(2L);

        when(merchantMapper.selectById(2L)).thenReturn(m);
        when(userMapper.selectById(userId)).thenReturn(user);

        service.review(2L, 2, "证照不符合要求");

        assertEquals(1L, user.getRoleId(), "人工审核驳回后，若角色为商户应重置为买家 (1)");
        verify(userMapper).updateById(user);
    }
}
