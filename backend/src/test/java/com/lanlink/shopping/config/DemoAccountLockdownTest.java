package com.lanlink.shopping.config;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DemoAccountLockdownTest {

    @Test
    void locksOnlyKnownFixtureAccountStillUsingDemoPassword() {
        UserMapper userMapper = mock(UserMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        User demoUser = new User();
        demoUser.setUserId(7L);
        demoUser.setPhone("13800000000");
        demoUser.setNickname("平台运营");
        demoUser.setPassword("encoded-demo-password");

        AtomicInteger lookupCount = new AtomicInteger();
        when(userMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation ->
                lookupCount.getAndIncrement() == 0 ? demoUser : null);
        when(encoder.matches("123456", "encoded-demo-password")).thenReturn(true);
        when(encoder.encode(anyString())).thenReturn("encoded-random-password");

        new DemoAccountLockdown(userMapper, encoder, false, false).run();

        verify(userMapper, times(3)).selectOne(any(Wrapper.class));
        verify(encoder, times(1)).encode(anyString());
        verify(userMapper, times(1)).updateById(demoUser);
        assertEquals("encoded-random-password", demoUser.getPassword());
    }

    @Test
    void doesNotTouchAnyAccountsWhenDemoAccountsAreExplicitlyEnabled() {
        UserMapper userMapper = mock(UserMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        new DemoAccountLockdown(userMapper, encoder, true, false).run();

        verifyNoInteractions(userMapper, encoder);
    }

    @Test
    void doesNotTouchAnyAccountsWhenDemoDataIsEnabled() {
        UserMapper userMapper = mock(UserMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        new DemoAccountLockdown(userMapper, encoder, false, true).run();

        verifyNoInteractions(userMapper, encoder);
    }
}
