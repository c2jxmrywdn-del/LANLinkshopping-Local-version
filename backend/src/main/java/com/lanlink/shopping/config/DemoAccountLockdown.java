package com.lanlink.shopping.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Prevents well-known sample credentials from remaining usable in production
 * if a development profile previously seeded the demo fixture accounts.
 *
 * This never deletes accounts or business data. It only replaces the password
 * hash when both the fixture phone/nickname pair and the known demo password
 * match exactly. Production must keep demo-data and demo-account switches off.
 */
@Component
@Profile("prod")
@Order(Ordered.LOWEST_PRECEDENCE)
public class DemoAccountLockdown implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoAccountLockdown.class);
    private static final String DEMO_PASSWORD = "123456";
    private static final Map<String, String> FIXTURE_ACCOUNTS = Map.of(
            "13800000000", "平台运营",
            "13900000001", "重庆建工采购",
            "13700000002", "华晨建材"
    );

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final boolean demoAccountsEnabled;
    private final boolean demoDataEnabled;

    public DemoAccountLockdown(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            @Value("${app.demo.accounts.enabled:false}") boolean demoAccountsEnabled,
            @Value("${lanlink.demo-data.enabled:false}") boolean demoDataEnabled) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.demoAccountsEnabled = demoAccountsEnabled;
        this.demoDataEnabled = demoDataEnabled;
    }

    @Override
    public void run(String... args) {
        if (demoAccountsEnabled || demoDataEnabled) {
            log.warn("Demo-account lockdown skipped because a demo feature is enabled");
            return;
        }
        int locked = 0;
        for (Map.Entry<String, String> fixture : FIXTURE_ACCOUNTS.entrySet()) {
            User user = userMapper.selectOne(new QueryWrapper<User>()
                    .eq("phone", fixture.getKey())
                    .eq("nickname", fixture.getValue()));
            if (user == null || user.getPassword() == null
                    || !passwordEncoder.matches(DEMO_PASSWORD, user.getPassword())) {
                continue;
            }

            String unusableRandomPassword = UUID.randomUUID().toString();
            user.setPassword(passwordEncoder.encode(unusableRandomPassword));
            user.setUpdateTime(LocalDateTime.now());
            userMapper.updateById(user);
            locked++;
        }
        if (locked > 0) {
            log.warn("Disabled {} seeded demo login(s) in production; account and business rows were retained", locked);
        } else {
            log.info("Production demo-account lockdown check complete; no known demo password matched");
        }
    }
}
