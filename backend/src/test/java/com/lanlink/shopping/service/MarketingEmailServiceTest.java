package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.dto.MarketingEmailCampaignDTO;
import com.lanlink.shopping.entity.UserProfile;
import com.lanlink.shopping.mapper.UserProfileMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketingEmailServiceTest {
    private static final String CANONICAL_UNSUBSCRIBE_URL =
            "https://example.com/api/marketing-email/unsubscribe";

    @Test
    void campaignSendsEachOptedInRecipientSeparatelyAndIncludesSignedOptOut() {
        AgentLoadoutMcpClient client = mock(AgentLoadoutMcpClient.class);
        UserProfileMapper profileMapper = mock(UserProfileMapper.class);
        UserSettingsService settingsService = mock(UserSettingsService.class);
        MarketingEmailUnsubscribeService unsubscribeService = mock(MarketingEmailUnsubscribeService.class);
        when(client.isConfigured()).thenReturn(true);
        when(unsubscribeService.isConfigured()).thenReturn(true);
        when(unsubscribeService.canonicalUnsubscribeUrl()).thenReturn(CANONICAL_UNSUBSCRIBE_URL);
        when(unsubscribeService.buildUnsubscribeUrl(1L, "first@example.com"))
                .thenReturn(CANONICAL_UNSUBSCRIBE_URL + "?userId=1&token=first-signed-token");
        when(unsubscribeService.buildUnsubscribeUrl(2L, "second@example.com"))
                .thenReturn(CANONICAL_UNSUBSCRIBE_URL + "?userId=2&token=second-signed-token");
        when(profileMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                profile(1L, "first@example.com"), profile(2L, "second@example.com")));
        when(settingsService.get(1L)).thenReturn(marketingOptInSettings());
        when(settingsService.get(2L)).thenReturn(marketingOptInSettings());
        MarketingEmailService service = new MarketingEmailService(client, profileMapper, settingsService, unsubscribeService);

        var result = service.sendCampaign(campaign(List.of("first@example.com", "second@example.com"), true));

        assertEquals(2, result.get("requested"));
        assertEquals(2, result.get("queued"));
        assertEquals(0, result.get("failed"));
        assertEquals(0, result.get("skippedNoConsent"));

        ArgumentCaptor<String> recipients = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subjects = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).sendMessage(recipients.capture(), subjects.capture(),
                bodies.capture(), isNull(), keys.capture());

        assertEquals(List.of("first@example.com", "second@example.com"), recipients.getAllValues());
        assertTrue(subjects.getAllValues().stream().allMatch(s -> s.startsWith("【商业推广】")));
        assertTrue(bodies.getAllValues().get(0).contains("first-signed-token"));
        assertTrue(bodies.getAllValues().get(1).contains("second-signed-token"));
        assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
        assertTrue(keys.getAllValues().stream().allMatch(k -> k.matches("[0-9a-f]{64}")));
    }

    @Test
    void campaignSkipsRecipientWithoutPromotionPreference() {
        AgentLoadoutMcpClient client = mock(AgentLoadoutMcpClient.class);
        UserProfileMapper profileMapper = mock(UserProfileMapper.class);
        UserSettingsService settingsService = mock(UserSettingsService.class);
        MarketingEmailUnsubscribeService unsubscribeService = configuredUnsubscribeService();
        when(client.isConfigured()).thenReturn(true);
        when(profileMapper.selectList(any(Wrapper.class))).thenReturn(List.of(profile(1L, "person@example.com")));
        when(settingsService.get(1L)).thenReturn(Map.of("notify", Map.of(
                "email", true, "groups", Map.of("promotion", false))));
        MarketingEmailService service = new MarketingEmailService(client, profileMapper, settingsService, unsubscribeService);

        var result = service.sendCampaign(campaign(List.of("person@example.com"), true));

        assertEquals(0, result.get("queued"));
        assertEquals(1, result.get("skippedNoConsent"));
        verify(client, never()).sendMessage(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void campaignRejectsWithoutBatchConsentConfirmation() {
        AgentLoadoutMcpClient client = mock(AgentLoadoutMcpClient.class);
        UserProfileMapper profileMapper = mock(UserProfileMapper.class);
        UserSettingsService settingsService = mock(UserSettingsService.class);
        MarketingEmailUnsubscribeService unsubscribeService = configuredUnsubscribeService();
        when(client.isConfigured()).thenReturn(true);
        MarketingEmailService service = new MarketingEmailService(client, profileMapper, settingsService, unsubscribeService);

        assertThrows(BusinessException.class,
                () -> service.sendCampaign(campaign(List.of("person@example.com"), false)));
        verifyNoInteractions(profileMapper, settingsService);
        verify(client, never()).sendMessage(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void campaignRejectsDuplicateRecipients() {
        AgentLoadoutMcpClient client = mock(AgentLoadoutMcpClient.class);
        UserProfileMapper profileMapper = mock(UserProfileMapper.class);
        UserSettingsService settingsService = mock(UserSettingsService.class);
        MarketingEmailUnsubscribeService unsubscribeService = configuredUnsubscribeService();
        when(client.isConfigured()).thenReturn(true);
        MarketingEmailService service = new MarketingEmailService(client, profileMapper, settingsService, unsubscribeService);

        assertThrows(BusinessException.class, () -> service.sendCampaign(
                campaign(List.of("person@example.com", "PERSON@example.com"), true)));
        verifyNoInteractions(profileMapper, settingsService);
        verify(client, never()).sendMessage(anyString(), anyString(), anyString(), any(), anyString());
    }

    private static MarketingEmailUnsubscribeService configuredUnsubscribeService() {
        MarketingEmailUnsubscribeService unsubscribe = mock(MarketingEmailUnsubscribeService.class);
        when(unsubscribe.isConfigured()).thenReturn(true);
        when(unsubscribe.canonicalUnsubscribeUrl()).thenReturn(CANONICAL_UNSUBSCRIBE_URL);
        when(unsubscribe.buildUnsubscribeUrl(anyLong(), anyString()))
                .thenAnswer(invocation -> CANONICAL_UNSUBSCRIBE_URL + "?userId="
                        + invocation.getArgument(0) + "&token=signed-token");
        return unsubscribe;
    }

    private static UserProfile profile(Long id, String email) {
        UserProfile profile = new UserProfile();
        profile.setUserId(id);
        profile.setEmail(email);
        return profile;
    }

    private static Map<String, Object> marketingOptInSettings() {
        Map<String, Object> groups = new LinkedHashMap<>();
        groups.put("promotion", true);
        Map<String, Object> notify = new LinkedHashMap<>();
        notify.put("email", true);
        notify.put("groups", groups);
        return Map.of("notify", notify);
    }

    private static MarketingEmailCampaignDTO campaign(List<String> recipients, boolean consent) {
        MarketingEmailCampaignDTO dto = new MarketingEmailCampaignDTO();
        dto.setRecipients(recipients);
        dto.setSubject("New supplier offers");
        dto.setText("See this week's B2B offers.");
        dto.setUnsubscribeUrl(CANONICAL_UNSUBSCRIBE_URL);
        dto.setIdempotencyKey("campaign-2026-10");
        dto.setConsentConfirmed(consent);
        return dto;
    }
}
