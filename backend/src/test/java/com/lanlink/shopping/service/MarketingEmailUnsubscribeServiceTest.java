package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.UserProfile;
import com.lanlink.shopping.mapper.UserProfileMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MarketingEmailUnsubscribeServiceTest {
    private static final String SECRET = "test-only-secret-key-with-more-than-32-chars";
    private static final String BASE_URL = "https://example.com/api";

    @Test
    void validSignedLinkDisablesOnlyPromotionNotifications() {
        UserProfileMapper mapper = mock(UserProfileMapper.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        UserProfile profile = new UserProfile();
        profile.setUserId(42L);
        profile.setEmail("Member@Example.com");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(profile));
        MarketingEmailUnsubscribeService service =
                new MarketingEmailUnsubscribeService(mapper, settings, SECRET, BASE_URL);

        String url = service.buildUnsubscribeUrl(42L, "member@example.com");
        String token = url.substring(url.indexOf("&token=") + "&token=".length());
        service.unsubscribe(42L, token);

        verify(settings).patch(eq(42L), argThat(patch -> {
            Object notifyValue = patch.get("notify");
            if (!(notifyValue instanceof Map<?, ?> notify)) return false;
            Object groupsValue = notify.get("groups");
            return groupsValue instanceof Map<?, ?> groups && Boolean.FALSE.equals(groups.get("promotion"));
        }));
    }

    @Test
    void invalidSignedLinkCannotChangePreferences() {
        UserProfileMapper mapper = mock(UserProfileMapper.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        UserProfile profile = new UserProfile();
        profile.setUserId(42L);
        profile.setEmail("member@example.com");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(profile));
        MarketingEmailUnsubscribeService service =
                new MarketingEmailUnsubscribeService(mapper, settings, SECRET, BASE_URL);

        assertThrows(BusinessException.class, () -> service.unsubscribe(42L, "not-a-valid-token"));
        verify(settings, never()).patch(anyLong(), any());
    }

    @Test
    void refusesToGenerateLinksWhenSecretIsTooShort() {
        MarketingEmailUnsubscribeService service =
                new MarketingEmailUnsubscribeService(mock(UserProfileMapper.class),
                        mock(UserSettingsService.class), "short", BASE_URL);
        assertFalse(service.isConfigured());
        assertThrows(BusinessException.class,
                () -> service.buildUnsubscribeUrl(1L, "member@example.com"));
    }
}
