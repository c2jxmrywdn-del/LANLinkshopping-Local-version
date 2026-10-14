package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.RoleMapper;
import com.lanlink.shopping.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
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