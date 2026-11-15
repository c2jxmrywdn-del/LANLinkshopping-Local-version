package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.UserRoles;
import com.lanlink.shopping.dto.RegisterDTO;
import com.lanlink.shopping.entity.Role;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.RoleMapper;
import com.lanlink.shopping.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 用户服务：修改密码强规则（8-20 位含大小写字母/数字/特殊字符）、原密码校验、换绑手机唯一性
 */
class UserServiceTest {

    private UserMapper userMapper;
    private UserService service;
    private User user;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        service = new UserService(userMapper, roleMapper, new BCryptPasswordEncoder());
        user = new User();
        user.setUserId(1L);
        user.setPhone("13900000001");
        user.setPassword(new BCryptPasswordEncoder().encode("OldPass123!"));
        user.setNickname("测试用户");
        when(userMapper.selectById(1L)).thenReturn(user);
    }

    @Test
    void changePasswordSuccess() {
        service.changePassword(1L, "OldPass123!", "NewPass456@");
        assertTrue(new BCryptPasswordEncoder().matches("NewPass456@", user.getPassword()), "新密码应已加密落库");
    }

    /**
     * 注册提权回归：客户端传入 roleCode=admin 时，服务端必须忽略并使用 buyer。
     *
     * <p>历史缺陷：`register` 直接按 dto.getRoleCode() 查角色，任何人可注册成管理员。
     */
    @Test
    void registerIgnoresClientSuppliedRoleAndForcesBuyer() {
        RoleMapper roleMapper = mock(RoleMapper.class);
        UserService svc = new UserService(userMapper, roleMapper, new BCryptPasswordEncoder());
        Role buyer = new Role();
        buyer.setRoleId(1L);
        buyer.setRoleCode("buyer");
        when(roleMapper.selectOne(any())).thenReturn(buyer);
        when(userMapper.selectCount(any())).thenReturn(0L);

        RegisterDTO dto = new RegisterDTO();
        dto.setPhone("13700001234");
        dto.setPassword("Passw0rd!A");
        dto.setNickname("提权测试");
        dto.setRoleCode("admin");

        User created = svc.register(dto);

        assertEquals(1L, created.getRoleId(), "注册角色必须固定为 buyer，不能采用客户端传入的 admin");
        verify(roleMapper).selectOne(any());
    }

    /**
     * 邀请注册的身份白名单：只允许 buyer / merchant，其它（尤其 admin）一律拒绝。
     *
     * <p>即使上游（邀请码校验）被绕过，这里也不能把新用户写成管理员。
     */
    @Test
    void registerWithRoleOnlyAllowsBuyerAndMerchant() {
        RoleMapper roleMapper = mock(RoleMapper.class);
        UserService svc = new UserService(userMapper, roleMapper, new BCryptPasswordEncoder());
        Role buyer = new Role();
        buyer.setRoleId(UserRoles.BUYER_ROLE_ID);
        buyer.setRoleCode("buyer");
        Role merchant = new Role();
        merchant.setRoleId(UserRoles.MERCHANT_ROLE_ID);
        merchant.setRoleCode("merchant");
        // 按调用顺序返回：buyer → merchant（第三个用例在查角色前就抛异常，不会用到第三个返回值）
        when(roleMapper.selectOne(any())).thenReturn(buyer, merchant);
        when(userMapper.selectCount(any())).thenReturn(0L);

        RegisterDTO buyerDto = new RegisterDTO();
        buyerDto.setPhone("13700002222");
        buyerDto.setPassword("Passw0rd!A");
        buyerDto.setNickname("邀请普通用户");
        assertEquals(UserRoles.BUYER_ROLE_ID, svc.register(buyerDto, UserRoles.BUYER_ROLE_ID).getRoleId());

        RegisterDTO merchantDto = new RegisterDTO();
        merchantDto.setPhone("13700003333");
        merchantDto.setPassword("Passw0rd!A");
        merchantDto.setNickname("邀请商户");
        assertEquals(UserRoles.MERCHANT_ROLE_ID,
                svc.register(merchantDto, UserRoles.MERCHANT_ROLE_ID).getRoleId(),
                "运营邀请的商户必须能拿到 merchant 角色");

        RegisterDTO adminDto = new RegisterDTO();
        adminDto.setPhone("13700004444");
        adminDto.setPassword("Passw0rd!A");
        adminDto.setNickname("提权尝试");
        assertThrows(BusinessException.class,
                () -> svc.register(adminDto, UserRoles.ADMIN_ROLE_ID),
                "任何路径都不得把新用户注册为 admin");
    }

    @Test
    void wrongOldPasswordRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.changePassword(1L, "WrongPass1!", "NewPass456@"));
        assertTrue(ex.getMessage().contains("原密码错误"));
    }

    @Test
    void weakPasswordRejected() {
        // 缺少大写/特殊字符
        assertThrows(BusinessException.class, () -> service.changePassword(1L, "OldPass123!", "abcdef1234"));
        // 过短
        assertThrows(BusinessException.class, () -> service.changePassword(1L, "OldPass123!", "Ab1!"));
        // 不含特殊字符
        assertThrows(BusinessException.class, () -> service.changePassword(1L, "OldPass123!", "Abcdef123456"));
        // 超过 20 位
        assertThrows(BusinessException.class,
                () -> service.changePassword(1L, "OldPass123!", "Abcdef123456789012345!"));
    }

    @Test
    void newPasswordSameAsOldRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.changePassword(1L, "OldPass123!", "OldPass123!"));
        assertTrue(ex.getMessage().contains("不能与原密码相同"));
    }

    @Test
    void changePhoneOccupiedRejected() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.changePhone(1L, "13800000000"));
        assertTrue(ex.getMessage().contains("已被占用"));
    }

    @Test
    void changePhoneSuccessUpdatesUser() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        service.changePhone(1L, "13800000000");
        assertEquals("13800000000", user.getPhone());
        verify(userMapper, times(1)).updateById(user);
    }
}
