package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.config.SocialLoginProperties;
import com.lanlink.shopping.entity.SocialBinding;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.social.SocialIdentity;
import com.lanlink.shopping.social.SocialIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 社交登录服务单测。
 *
 * 覆盖：渠道可用性探测、未配置时的明确报错（不伪造登录）、四平台真实授权地址构造、
 * 授权码换取后的「绑定判定」——**未绑定不自动建号**、已绑定才允许登录。
 */
class SocialLoginServiceTest {

    private SocialLoginProperties props;
    private SocialIdentityResolver resolver;
    private SocialBindingService bindingService;
    private UserService userService;
    private SocialLoginService service;

    @BeforeEach
    void setUp() {
        props = new SocialLoginProperties();
        props.setRedirectBase("http://localhost:5173/login");
        Map<String, SocialLoginProperties.Provider> map = new LinkedHashMap<>();
        for (String p : SocialLoginService.SUPPORTED) {
            SocialLoginProperties.Provider cfg = new SocialLoginProperties.Provider();
            cfg.setEnabled(false);
            map.put(p, cfg);
        }
        props.setProviders(map);
        resolver = mock(SocialIdentityResolver.class);
        bindingService = mock(SocialBindingService.class);
        userService = mock(UserService.class);
        service = new SocialLoginService(props, resolver, bindingService, userService);
    }

    private void enable(String provider, String id, String secret) {
        SocialLoginProperties.Provider cfg = props.provider(provider);
        cfg.setEnabled(true);
        cfg.setAppId(id);
        cfg.setAppSecret(secret);
    }

    /* ---------------- 可用性 & 授权地址 ---------------- */

    @Test
    void providersAlwaysReturnsAllFourWithEnabledFlag() {
        List<Map<String, Object>> list = service.providers();
        assertEquals(4, list.size());
        assertEquals(List.of("wechat", "alipay", "apple", "google"),
                list.stream().map(m -> (String) m.get("provider")).toList());
        assertTrue(list.stream().noneMatch(m -> Boolean.TRUE.equals(m.get("enabled"))));
    }

    @Test
    void providerEnabledOnlyWhenIdAndSecretBothPresent() {
        enable("google", "gid", "gsecret");
        assertTrue(service.providers().stream()
                .filter(m -> "google".equals(m.get("provider"))).findFirst().orElseThrow()
                .get("enabled").equals(Boolean.TRUE));
        // 半配置不算可用
        props.provider("wechat").setEnabled(true);
        props.provider("wechat").setAppId("wid");
        assertFalse(props.configured("wechat"));
    }

    @Test
    void alipayConfiguredRequiresPrivateKeyInsteadOfAppSecret() {
        props.provider("alipay").setEnabled(true);
        props.provider("alipay").setAppId("alipayapp");
        // 只有平台下发的 appSecret，不能算可用：RSA2 签名必须使用商户应用私钥
        props.provider("alipay").setAppSecret("alipaysecret");
        assertFalse(props.configured("alipay"), "缺应用私钥时不应视为可用，否则回调换取会签名失败");
        // 私钥在位即视为可用（appSecret 不参与支付宝任何调用）
        props.provider("alipay").setAlipayPrivateKey("pkcs8-private-key-pem");
        assertTrue(props.configured("alipay"));
    }

    @Test
    void authorizeUrlUnconfiguredThrowsActionableError() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.authorizeUrl("wechat", "st1"));
        assertTrue(ex.getMessage().contains("未配置"));
        assertTrue(ex.getMessage().contains("微信"));
    }

    @Test
    void authorizeUrlWechatBuildsQrConnectUrl() {
        enable("wechat", "wxappid", "wxsecret");
        String url = service.authorizeUrl("wechat", "st-abc");
        assertTrue(url.startsWith("https://open.weixin.qq.com/connect/qrconnect?"));
        assertTrue(url.contains("appid=wxappid"));
        assertTrue(url.contains("scope=snsapi_login"));
        assertTrue(url.endsWith("#wechat_redirect"));
        assertTrue(url.contains("state=st-abc"));
        assertTrue(url.contains("redirect_uri=" + enc("http://localhost:5173/login")));
    }

    @Test
    void authorizeUrlAlipayAppleGoogleUsePlatformCorrectParams() {
        // 支付宝要求「应用私钥」在位才算可用（appSecret 不参与签名）
        enable("alipay", "alipayapp", "alipaysecret");
        props.provider("alipay").setAlipayPrivateKey("test-only-pkcs8-placeholder");
        String alipay = service.authorizeUrl("alipay", "st2");
        assertTrue(alipay.startsWith("https://openauth.alipay.com/oauth2/publicAppAuthorize.htm?"));
        assertTrue(alipay.contains("app_id=alipayapp"));
        assertTrue(alipay.contains("scope=auth_user"));

        enable("apple", "apple.client.id", "jwt-client-secret");
        String appleUrl = service.authorizeUrl("apple", "st3");
        assertTrue(appleUrl.startsWith("https://appleid.apple.com/auth/authorize?"));
        assertTrue(appleUrl.contains("client_id=apple.client.id"));
        assertTrue(appleUrl.contains("scope=" + enc("name email")));

        enable("google", "google-client-id", "gsecret");
        String googleUrl = service.authorizeUrl("google", "st4");
        assertTrue(googleUrl.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));
        assertTrue(googleUrl.contains("scope=" + enc("openid email profile")));
    }

    @Test
    void unknownProviderRejected() {
        assertTrue(assertThrows(BusinessException.class, () -> service.authorizeUrl("github", "st"))
                .getMessage().contains("不支持"));
    }

    /* ---------------- 回调：换取 → 绑定判定 ---------------- */

    @Test
    void callbackValidatesCodeAndState() {
        enable("google", "gid", "gsecret");
        assertTrue(assertThrows(BusinessException.class, () -> service.callback("google", null, "st"))
                .getMessage().contains("授权码"));
        assertTrue(assertThrows(BusinessException.class, () -> service.callback("google", "code1", ""))
                .getMessage().contains("状态校验失败"));
    }

    @Test
    void callbackUnconfiguredDoesNotFakeLogin() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.callback("alipay", "code1", "st1"));
        assertTrue(ex.getMessage().contains("未配置"));
        verifyNoInteractions(resolver);
    }

    @Test
    void callbackUnboundThirdPartyAccountIsNotAutoCreated() {
        enable("google", "gid", "gsecret");
        when(resolver.resolve(eq("google"), any(), eq("code-1"), any()))
                .thenReturn(new SocialIdentity("google", "g-sub", null, "Jane", null, "j@x.com", true));
        when(bindingService.find("google", "g-sub")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.callback("google", "code-1", "st"));
        assertTrue(ex.getMessage().contains("尚未绑定"), "未绑定应给出绑定指引而非静默建号");
        verify(userService, never()).findById(any());
    }

    @Test
    void callbackBoundAccountLogsIn() {
        enable("google", "gid", "gsecret");
        when(resolver.resolve(eq("google"), any(), eq("code-2"), any()))
                .thenReturn(new SocialIdentity("google", "g-sub", null, "Jane", null, "j@x.com", true));
        SocialBinding binding = new SocialBinding();
        binding.setUserId(7L);
        binding.setProvider("google");
        binding.setSubject("g-sub");
        when(bindingService.find("google", "g-sub")).thenReturn(binding);
        User u = new User();
        u.setUserId(7L);
        when(userService.findById(7L)).thenReturn(u);

        assertSame(u, service.callback("google", "code-2", "st"));
    }

    @Test
    void callbackDanglingBindingIsRejected() {
        enable("google", "gid", "gsecret");
        when(resolver.resolve(any(), any(), any(), any()))
                .thenReturn(new SocialIdentity("google", "g-sub", null, null, null, null, false));
        SocialBinding binding = new SocialBinding();
        binding.setUserId(99L);
        when(bindingService.find("google", "g-sub")).thenReturn(binding);
        when(userService.findById(99L)).thenReturn(null);

        assertTrue(assertThrows(BusinessException.class, () -> service.callback("google", "c", "s"))
                .getMessage().contains("不存在或已注销"));
    }

    /* ---------------- 绑定（已登录态） ---------------- */

    @Test
    void bindIdentityRequiresLogin() {
        assertTrue(assertThrows(BusinessException.class, () -> service.bindIdentity(null, "google", "c", "s"))
                .getMessage().contains("请先登录"));
    }

    @Test
    void bindIdentityRejectsThirdPartyAlreadyBoundToAnotherAccount() {
        enable("google", "gid", "gsecret");
        when(resolver.resolve(any(), any(), any(), any()))
                .thenReturn(new SocialIdentity("google", "g-sub", null, "Jane", null, null, false));
        SocialBinding other = new SocialBinding();
        other.setUserId(1L);
        when(bindingService.find("google", "g-sub")).thenReturn(other);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.bindIdentity(7L, "google", "c", "s"));
        assertTrue(ex.getMessage().contains("已被其它平台账号绑定"));
    }

    @Test
    void bindIdentityWritesBindingAndMasksSubject() {
        enable("google", "gid", "gsecret");
        when(resolver.resolve(any(), any(), any(), any()))
                .thenReturn(new SocialIdentity("google", "subject-12345678", null, "Jane", "https://a/p.png", "j@x.com", true));
        SocialBinding saved = new SocialBinding();
        saved.setSubject("subject-12345678");
        saved.setNickname("Jane");
        saved.setAvatar("https://a/p.png");
        saved.setEmail("j@x.com");
        when(bindingService.find("google", "subject-12345678")).thenReturn(null);
        when(bindingService.bind(any(), any(), any(), any(), any(), any(), any())).thenReturn(saved);

        Map<String, Object> out = service.bindIdentity(7L, "google", "c", "s");
        assertEquals("google", out.get("provider"));
        assertEquals("Jane", out.get("nickname"));
        assertEquals("sub****678", out.get("subjectMasked"));
        verify(bindingService).bind(7L, "google", "subject-12345678", null, "Jane", "https://a/p.png", "j@x.com");
    }

    private static String enc(String v) {
        return java.net.URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
