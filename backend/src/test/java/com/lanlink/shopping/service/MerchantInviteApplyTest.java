package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.dto.MerchantApplyDTO;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.EnterpriseMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.QualificationMapper;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.invite.entity.InviteCode;
import com.lanlink.shopping.module.invite.service.InviteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 商家入驻 × 邀请链路：入驻邀约码的校验与绑定。
 *
 * <p>关键约束：
 * <ul>
 *   <li>平台发放的 INV 前缀邀约码无效/过期/用尽 → 直接拒绝入驻（不能靠乱填绕过审核）；</li>
 *   <li>商户自定义的非 INV 邀约码保持历史行为（不拦截）；</li>
 *   <li>入驻成功后记录 pending 关系，审核通过后置 bound。</li>
 * </ul>
 */
class MerchantInviteApplyTest {

    private MerchantMapper merchantMapper;
    private EnterpriseMapper enterpriseMapper;
    private UserMapper userMapper;
    private InviteService inviteService;
    private MerchantService service;

    @BeforeEach
    void setUp() {
        merchantMapper = mock(MerchantMapper.class);
        enterpriseMapper = mock(EnterpriseMapper.class);
        userMapper = mock(UserMapper.class);
        inviteService = mock(InviteService.class);
        service = new MerchantService(merchantMapper, enterpriseMapper,
                mock(QualificationMapper.class), mock(AuditService.class), userMapper);
        ReflectionTestUtils.setField(service, "inviteService", inviteService);
        when(merchantMapper.selectCount(any())).thenReturn(0L);
    }

    private MerchantApplyDTO applyDto(String joinType, String joinCode) {
        MerchantApplyDTO dto = new MerchantApplyDTO();
        dto.setEntName("测试企业有限公司");
        dto.setCreditCode("91310000MA1K000000");
        dto.setRegType("公司");
        dto.setRegCapital(new BigDecimal("100000"));
        dto.setTaxStatus(1);
        dto.setJoinType(joinType);
        dto.setJoinCode(joinCode);
        return dto;
    }

    private InviteCode validApplyCode() {
        InviteCode code = new InviteCode();
        code.setId(1L);
        code.setCode("INVVALID000001");
        code.setInviteType("merchant_apply");
        code.setScene("admin_console");
        code.setStatus(1);
        return code;
    }

    /** 平台邀约码无效：必须拒绝，且在写库之前。 */
    @Test
    void invalidPlatformInviteCodeRejectsApply() {
        when(inviteService.verifyMerchantApplyCode("INVINVALID0001")).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.apply(7L, null, applyDto("邀约", "INVINVALID0001")));

        assertTrue(e.getMessage().contains("邀请码无效"), e.getMessage());
        verify(merchantMapper, never()).insert(any(Merchant.class));
    }

    /** 有效平台邀约码：正常入驻并记录 pending 关系。 */
    @Test
    void validPlatformInviteCodeRecordsPendingRelation() {
        when(inviteService.verifyMerchantApplyCode("INVVALID000001")).thenReturn(validApplyCode());
        when(inviteService.bindMerchantApply("INVVALID000001", 7L)).thenReturn(true);
        when(enterpriseMapper.insert(any(com.lanlink.shopping.entity.Enterprise.class))).thenAnswer(inv -> {
            com.lanlink.shopping.entity.Enterprise ent = inv.getArgument(0);
            ent.setEntId(100L);
            return 1;
        });
        when(merchantMapper.insert(any(Merchant.class))).thenAnswer(inv -> {
            Merchant m = inv.getArgument(0);
            m.setMerId(200L);
            return 1;
        });

        Merchant created = service.apply(7L, null, applyDto("邀约", "INVVALID000001"));

        assertNotNull(created);
        assertEquals(200L, created.getMerId());
        verify(inviteService).bindMerchantApply("INVVALID000001", 7L);
    }

    /**
     * 校验通过但绑定阶段失败（读码后过期/用尽/并发绑定/关系写入异常）：
     * apply 必须抛错并回滚，不能留下 platform_invited=1 但关系表缺失的商户。
     */
    @Test
    void bindFailureRejectsApplyInsteadOfLeavingInvitedFlagInconsistent() {
        when(inviteService.verifyMerchantApplyCode("INVVALID000002")).thenReturn(validApplyCode());
        when(inviteService.bindMerchantApply("INVVALID000002", 7L)).thenReturn(false);
        when(enterpriseMapper.insert(any(com.lanlink.shopping.entity.Enterprise.class))).thenAnswer(inv -> {
            com.lanlink.shopping.entity.Enterprise ent = inv.getArgument(0);
            ent.setEntId(102L);
            return 1;
        });
        when(merchantMapper.insert(any(Merchant.class))).thenAnswer(inv -> {
            Merchant m = inv.getArgument(0);
            m.setMerId(202L);
            return 1;
        });

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.apply(7L, null, applyDto("邀约", "INVVALID000002")));

        assertTrue(e.getMessage().contains("绑定失败"), e.getMessage());
        verify(inviteService).bindMerchantApply("INVVALID000002", 7L);
    }

    /** 非 INV 前缀的自定义邀约码：保持历史行为，不触发邀请校验。 */
    @Test
    void customInviteCodeKeepsLegacyBehaviour() {
        when(enterpriseMapper.insert(any(com.lanlink.shopping.entity.Enterprise.class))).thenAnswer(inv -> {
            com.lanlink.shopping.entity.Enterprise ent = inv.getArgument(0);
            ent.setEntId(101L);
            return 1;
        });
        when(merchantMapper.insert(any(Merchant.class))).thenAnswer(inv -> {
            Merchant m = inv.getArgument(0);
            m.setMerId(201L);
            return 1;
        });

        Merchant created = service.apply(7L, null, applyDto("邀约", "PARTNER2026"));

        assertNotNull(created);
        verify(inviteService, never()).verifyMerchantApplyCode(any());
        verify(inviteService, never()).bindMerchantApply(any(), any());
    }

    /** 审核通过：把入驻邀约关系置为 bound。 */
    @Test
    void approveMarksInviteRelationBound() {
        Merchant m = new Merchant();
        m.setMerId(200L);
        m.setUserId(7L);
        m.setReviewStatus(0);
        when(merchantMapper.selectById(200L)).thenReturn(m);
        User user = new User();
        user.setUserId(7L);
        user.setRoleId(1L);
        when(userMapper.selectById(7L)).thenReturn(user);

        service.review(200L, 1, null);

        assertEquals(2L, user.getRoleId(), "审核通过后用户角色必须升级为商户");
        verify(inviteService).markMerchantBound(7L);
    }
}

