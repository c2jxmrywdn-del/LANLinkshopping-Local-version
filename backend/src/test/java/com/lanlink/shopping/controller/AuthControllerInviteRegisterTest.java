package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.UserRoles;
import com.lanlink.shopping.dto.RegisterDTO;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.module.invite.service.InviteService;
import com.lanlink.shopping.service.AuditService;
import com.lanlink.shopping.service.AuthLockoutService;
import com.lanlink.shopping.service.IdentityService;
import com.lanlink.shopping.service.LoginLogService;
import com.lanlink.shopping.service.MessageService;
import com.lanlink.shopping.service.TotpService;
import com.lanlink.shopping.service.UserProfileService;
import com.lanlink.shopping.service.UserService;
import com.lanlink.shopping.service.VerifyCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 注册链路 × 邀请身份分配：确认 controller 真的把「邀请码 → 角色」接到注册与绑定上。
 *
 * <p>回归点：若 controller 忘了调用 {@code roleIdForRegister}，运营邀请的商户会静默变成普通用户；
 * 若忘了 {@code bindOnRegister}，邀请关系丢失、统计恒为 0。
 */
class AuthControllerInviteRegisterTest {

    private UserService userService;
    private InviteService inviteService;
    private AuditService auditService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        inviteService = mock(InviteService.class);
        auditService = mock(AuditService.class);
        controller = new AuthController(userService, mock(UserProfileService.class), mock(TotpService.class),
                mock(LoginLogService.class), mock(MessageService.class), mock(IdentityService.class),
                auditService, mock(AuthLockoutService.class), mock(VerifyCodeService.class),
                inviteService);
    }

    private RegisterDTO dto(String inviteCode) {
        RegisterDTO dto = new RegisterDTO();
        dto.setPhone("13700009999");
        dto.setPassword("Passw0rd!A");
        dto.setNickname("邀请注册用户");
        dto.setInviteCode(inviteCode);
        return dto;
    }

    private User createdUser(Long roleId) {
        User u = new User();
        u.setUserId(500L);
        u.setPhone("13700009999");
        u.setNickname("邀请注册用户");
        u.setRoleId(roleId);
        return u;
    }

    /**
     * 商家入驻邀约码误填进注册表单：真实契约仍是 buyer。
     *
     * <p>历史用例通过 mock 让 roleIdForRegister 返回 merchant，制造了“运营邀请注册为商户”
     * 的假契约；实际 InviteService 恒返回 buyer，商户身份只能走入驻审核。
     */
    @Test
    void merchantApplyInviteStillRegistersAsBuyerAndAuditsBuyer() {
        when(inviteService.roleIdForRegister("INVMERCHANT0001")).thenReturn(UserRoles.BUYER_ROLE_ID);
        when(userService.register(any(RegisterDTO.class), eq(UserRoles.BUYER_ROLE_ID)))
                .thenReturn(createdUser(UserRoles.BUYER_ROLE_ID));

        controller.register(dto("INVMERCHANT0001"), new MockHttpServletRequest());

        verify(inviteService).roleIdForRegister("INVMERCHANT0001");
        verify(userService).register(any(RegisterDTO.class), eq(UserRoles.BUYER_ROLE_ID));
        verify(inviteService).bindOnRegister("INVMERCHANT0001", 500L, UserRoles.BUYER_ROLE_ID);
        verify(userService, never()).register(any(RegisterDTO.class), eq(UserRoles.MERCHANT_ROLE_ID));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(500L), eq("IDENTITY_RECOGNIZE"), detail.capture(), any());
        assertTrue(detail.getValue().contains("身份: buyer"), detail.getValue());
        assertFalse(detail.getValue().contains("merchant"), detail.getValue());
    }

    /** 普通邀请：buyer 角色。 */
    @Test
    void buyerInviteRegistersWithBuyerRole() {
        when(inviteService.roleIdForRegister("INVBUYER000001")).thenReturn(UserRoles.BUYER_ROLE_ID);
        when(userService.register(any(RegisterDTO.class), eq(UserRoles.BUYER_ROLE_ID)))
                .thenReturn(createdUser(UserRoles.BUYER_ROLE_ID));

        controller.register(dto("INVBUYER000001"), new MockHttpServletRequest());

        verify(userService).register(any(RegisterDTO.class), eq(UserRoles.BUYER_ROLE_ID));
        verify(inviteService).bindOnRegister("INVBUYER000001", 500L, UserRoles.BUYER_ROLE_ID);
    }

    /** 无邀请码：走 buyer 默认；绑定调用带空码时服务层会自行跳过。 */
    @Test
    void registerWithoutInviteUsesBuyerAndDoesNotBind() {
        when(inviteService.roleIdForRegister(null)).thenReturn(UserRoles.BUYER_ROLE_ID);
        when(userService.register(any(RegisterDTO.class), eq(UserRoles.BUYER_ROLE_ID)))
                .thenReturn(createdUser(UserRoles.BUYER_ROLE_ID));

        controller.register(dto(null), new MockHttpServletRequest());

        verify(userService).register(any(RegisterDTO.class), eq(UserRoles.BUYER_ROLE_ID));
        // 服务层对空码返回 false（不抛异常），此处只校验调用参数正确
        verify(inviteService).bindOnRegister(null, 500L, UserRoles.BUYER_ROLE_ID);
    }

    /** 邀请码被篡改/过期：服务层回退 buyer，注册仍然成功。 */
    @Test
    void invalidInviteStillRegistersAsBuyer() {
        when(inviteService.roleIdForRegister("INVBADCODE0001")).thenReturn(UserRoles.BUYER_ROLE_ID);
        when(userService.register(any(RegisterDTO.class), eq(UserRoles.BUYER_ROLE_ID)))
                .thenReturn(createdUser(UserRoles.BUYER_ROLE_ID));

        controller.register(dto("INVBADCODE0001"), new MockHttpServletRequest());

        verify(userService).register(any(RegisterDTO.class), eq(UserRoles.BUYER_ROLE_ID));
        verify(inviteService).bindOnRegister("INVBADCODE0001", 500L, UserRoles.BUYER_ROLE_ID);
    }
}
