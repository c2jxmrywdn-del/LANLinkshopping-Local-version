package com.lanlink.shopping.social;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.config.SocialLoginProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 四平台「授权码 → 用户信息」换取单测（HTTP 层注入假实现，无需真实凭证与公网）。
 * 覆盖：请求构造、响应解析、平台错误码分支、Apple id_token 验签取值。
 */
class SocialIdentityResolverTest {

    private FakeHttp http;
    private SocialIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        http = new FakeHttp();
        resolver = new SocialIdentityResolver(http, new JwtVerifier(), new AlipaySigner());
    }

    private SocialLoginProperties.Provider cfg(String id, String secret) {
        SocialLoginProperties.Provider p = new SocialLoginProperties.Provider();
        p.setEnabled(true);
        p.setAppId(id);
        p.setAppSecret(secret);
        return p;
    }

    /** 支付宝配置：RSA2 签名用「商户应用私钥」，与 appSecret 无关 */
    private SocialLoginProperties.Provider alipayCfg(String appId, String privateKeyPem) {
        SocialLoginProperties.Provider p = new SocialLoginProperties.Provider();
        p.setEnabled(true);
        p.setAppId(appId);
        p.setAlipayPrivateKey(privateKeyPem);
        return p;
    }

    /* ---------------- Google ---------------- */

    @Test
    void googleExchangesTokenThenReadsOidcUserinfo() {
        http.onPost("https://oauth2.googleapis.com/token", "{\"access_token\":\"at-1\",\"id_token\":\"x\"}")
            .onGet("https://openidconnect.googleapis.com/v1/userinfo",
                "{\"sub\":\"g-123\",\"email\":\"u@example.com\",\"email_verified\":true,\"name\":\"Jane\",\"picture\":\"https://p/x.png\"}");

        SocialIdentity id = resolver.resolve("google", cfg("client-id", "client-secret"), "code-1", "http://localhost:5173/login");

        assertEquals("google", id.provider());
        assertEquals("g-123", id.subject());
        assertEquals("Jane", id.nickname());
        assertEquals("u@example.com", id.email());
        assertTrue(id.emailVerified(), "Google 已验证邮箱应标记为 verified");
        assertTrue(http.lastCall().contains("openidconnect.googleapis.com"), "第二步应调用 userinfo");
    }

    @Test
    void googleMissingAccessTokenGivesActionableError() {
        http.onPost("https://oauth2.googleapis.com/token", "{\"error\":\"invalid_grant\"}");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.resolve("google", cfg("id", "sec"), "bad", "http://x"));
        assertTrue(ex.getMessage().contains("Google"));
    }

    /* ---------------- 微信 ---------------- */

    @Test
    void wechatSuccessMapsNicknameAvatarAndUnionId() {
        http.onGet("https://api.weixin.qq.com/sns/oauth2/access_token",
                "{\"access_token\":\"wt-1\",\"openid\":\"o-1\",\"unionid\":\"un-1\"}")
            .onGet("https://api.weixin.qq.com/sns/userinfo",
                "{\"nickname\":\"小明\",\"headimgurl\":\"https://h/x.jpg\",\"unionid\":\"un-1\"}");

        SocialIdentity id = resolver.resolve("wechat", cfg("wx-appid", "wx-secret"), "code-w", null);

        assertEquals("wechat", id.provider());
        assertEquals("o-1", id.subject());
        assertEquals("un-1", id.unionId());
        assertEquals("小明", id.nickname());
        assertEquals("https://h/x.jpg", id.avatar());
        assertFalse(id.emailVerified(), "微信不提供邮箱");
    }

    @Test
    void wechatErrcodeSurfacesPlatformMessage() {
        http.onGet("https://api.weixin.qq.com/sns/oauth2/access_token",
                "{\"errcode\":40029,\"errmsg\":\"invalid code\"}");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.resolve("wechat", cfg("id", "sec"), "bad", null));
        assertTrue(ex.getMessage().contains("微信"));
    }

    /* ---------------- 支付宝 ---------------- */

    @Test
    void alipaySignsGatewayCallAndParsesUserInfo() throws Exception {
        String pem = AlipaySignerTest.pkcs8Pem(
                (java.security.interfaces.RSAPrivateKey) AlipaySignerTest.keyPair().getPrivate());
        http.onPost("alipay.system.oauth.token",
                "{\"alipay_system_oauth_token_response\":{\"access_token\":\"al-t\",\"user_id\":\"2088\"}}")
            .onPost("alipay.user.info.share",
                "{\"alipay_user_info_share_response\":{\"user_id\":\"2088\",\"nick_name\":\"老王\",\"avatar\":\"https://a/x.png\"}}");

        SocialLoginProperties.Provider cfg = alipayCfg("2021app", pem);
        // 回归护栏：appSecret 不是签名私钥。这里给一个「明显不是 PKCS#8 私钥」的 appSecret，
        // 若签名逻辑退回误用 appSecret，本次换取会因私钥解析失败而抛错，测试即失败。
        cfg.setAppSecret("app-secret-not-usable-as-signing-key");

        SocialIdentity id = resolver.resolve("alipay", cfg, "code-a", null);

        assertEquals("alipay", id.provider());
        assertEquals("2088", id.subject());
        assertEquals("老王", id.nickname());
        assertEquals("https://a/x.png", id.avatar());
        assertTrue(http.hasCall("alipay.user.info.share"), "应再调用一次用户信息接口");
    }

    @Test
    void alipayErrorResponseSurfacesSubMessage() throws Exception {
        String pem = AlipaySignerTest.pkcs8Pem(
                (java.security.interfaces.RSAPrivateKey) AlipaySignerTest.keyPair().getPrivate());
        http.onPost("alipay.system.oauth.token",
                "{\"error_response\":{\"sub_code\":\"isv.code-invalid\",\"sub_msg\":\"invalid auth code\"}}");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.resolve("alipay", alipayCfg("app", pem), "bad", null));
        assertTrue(ex.getMessage().contains("支付宝"));
    }

    /* ---------------- Apple ---------------- */

    @Test
    void appleVerifiesIdTokenSignatureBeforeTrustingClaims() throws Exception {
        java.security.KeyPair kp = AlipaySignerTest.keyPair();
        String idToken = JwtVerifierTest.signJwt(kp, "k1", JwtVerifierTest.payload(
                Instant.now().getEpochSecond() + 600, "https://appleid.apple.com",
                "com.lanlink.shopping.web", "apple-sub-7", "u@privaterelay.appleid.com"));
        http.onPost("https://appleid.apple.com/auth/token", "{\"id_token\":\"" + idToken + "\"}")
            .onGet("https://appleid.apple.com/auth/keys",
                "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k1\",\"n\":\"" + JwtVerifierTest.nB64(kp)
                        + "\",\"e\":\"" + JwtVerifierTest.eB64(kp) + "\"}]}");

        SocialIdentity id = resolver.resolve("apple", cfg("com.lanlink.shopping.web", "jwt-secret"), "code-ap", "http://localhost:5173/login");

        assertEquals("apple", id.provider());
        assertEquals("apple-sub-7", id.subject());
        assertEquals("u@privaterelay.appleid.com", id.email());
        assertTrue(id.emailVerified());
    }

    @Test
    void appleUnknownKidCannotBeVerified() throws Exception {
        java.security.KeyPair kp = AlipaySignerTest.keyPair();
        String idToken = JwtVerifierTest.signJwt(kp, "k1", JwtVerifierTest.payload(
                Instant.now().getEpochSecond() + 600, "https://appleid.apple.com",
                "com.lanlink.shopping.web", "apple-sub-7", null));
        http.onPost("https://appleid.apple.com/auth/token", "{\"id_token\":\"" + idToken + "\"}")
            .onGet("https://appleid.apple.com/auth/keys", "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"other-kid\",\"n\":\"AQAB\",\"e\":\"AQAB\"}]}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.resolve("apple", cfg("com.lanlink.shopping.web", "s"), "c", "http://x"));
        assertTrue(ex.getMessage().contains("kid"));
    }

    /* ---------------- 假 HTTP 层 ---------------- */

    static class FakeHttp implements SocialHttp {
        private final Map<String, String> posts = new HashMap<>();
        private final List<String[]> gets = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();

        FakeHttp onPost(String key, String body) { posts.put(key, body); return this; }

        FakeHttp onGet(String prefix, String body) { gets.add(new String[]{prefix, body}); return this; }

        boolean hasCall(String method) { return calls.stream().anyMatch(c -> c.contains(method)); }

        String lastCall() { return calls.isEmpty() ? "" : calls.get(calls.size() - 1); }

        @Override
        public String postForm(String url, Map<String, String> form, Map<String, String> headers) {
            String key = form.getOrDefault("method", url);
            calls.add("POST " + key);
            String body = posts.get(key);
            if (body == null) throw new AssertionError("no fake POST response for key=" + key);
            return body;
        }

        @Override
        public String get(String url, Map<String, String> headers) {
            calls.add("GET " + url);
            for (String[] r : gets) if (url.startsWith(r[0])) return r[1];
            throw new AssertionError("no fake GET response for " + url);
        }
    }
}
