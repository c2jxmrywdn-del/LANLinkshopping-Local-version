package com.lanlink.shopping.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.config.SocialLoginProperties;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第三方身份换取：授权码 → access_token → 用户信息 → 归一化 {@link SocialIdentity}。
 *
 * 平台差异已在此收敛：
 *  - Google：标准 OAuth2 + OIDC userinfo 端点；
 *  - Apple：token 端点换 id_token，用户信息取自 **验签后的** id_token 声明（Apple 无 userinfo 端点）；
 *  - 微信：GET 换取 + GET userinfo（错误以 errcode/errmsg 返回）；
 *  - 支付宝：gateway.do 走 **RSA2 签名**，换取与用户信息各一次调用。
 */
@Service
public class SocialIdentityResolver {

    private static final String GOOGLE_TOKEN = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_USERINFO = "https://openidconnect.googleapis.com/v1/userinfo";
    private static final String WECHAT_TOKEN = "https://api.weixin.qq.com/sns/oauth2/access_token";
    private static final String WECHAT_USERINFO = "https://api.weixin.qq.com/sns/userinfo";
    private static final String APPLE_TOKEN = "https://appleid.apple.com/auth/token";
    private static final String APPLE_JWKS = "https://appleid.apple.com/auth/keys";
    private static final String APPLE_ISS = "https://appleid.apple.com";
    private static final String ALIPAY_GATEWAY = "https://openapi.alipay.com/gateway.do";
    private static final DateTimeFormatter ALIPAY_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SocialHttp http;
    private final JwtVerifier jwt;
    private final AlipaySigner alipaySigner;
    private final ObjectMapper mapper = new ObjectMapper();
    /** JWKS 缓存：平台公钥轮换不频繁，避免每次登录都出网 */
    private final Map<String, CachedJwks> jwksCache = new ConcurrentHashMap<>();

    public SocialIdentityResolver(SocialHttp http, JwtVerifier jwt, AlipaySigner alipaySigner) {
        this.http = http;
        this.jwt = jwt;
        this.alipaySigner = alipaySigner;
    }

    private record CachedJwks(JsonNode doc, long at) {}

    public SocialIdentity resolve(String provider, SocialLoginProperties.Provider cfg, String code, String redirectUri) {
        return switch (provider) {
            case "wechat" -> wechat(cfg, code);
            case "alipay" -> alipay(cfg, code);
            case "apple" -> apple(cfg, code, redirectUri);
            case "google" -> google(cfg, code, redirectUri);
            default -> throw new BusinessException("不支持的社交登录渠道");
        };
    }

    /* ---------------- Google ---------------- */

    private SocialIdentity google(SocialLoginProperties.Provider cfg, String code, String redirectUri) {
        JsonNode token = parse(http.postForm(GOOGLE_TOKEN, form(Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "client_id", cfg.resolveId(),
                "client_secret", cfg.resolveSecret(),
                "redirect_uri", safe(redirectUri))), null));
        String accessToken = required(token, "access_token", "Google");
        JsonNode info = parse(http.get(GOOGLE_USERINFO, Map.of("Authorization", "Bearer " + accessToken)));
        String sub = required(info, "sub", "Google");
        return new SocialIdentity("google", sub, null, text(info, "name"), text(info, "picture"),
                text(info, "email"), boolOf(info.get("email_verified")));
    }

    /* ---------------- WeChat 开放平台 ---------------- */

    private SocialIdentity wechat(SocialLoginProperties.Provider cfg, String code) {
        String tokenUrl = WECHAT_TOKEN + "?appid=" + enc(cfg.resolveId())
                + "&secret=" + enc(cfg.resolveSecret())
                + "&code=" + enc(code)
                + "&grant_type=authorization_code";
        JsonNode token = parse(http.get(tokenUrl, null));
        wechatError(token, "换取 access_token 失败");
        String accessToken = required(token, "access_token", "微信");
        String openId = required(token, "openid", "微信");

        String infoUrl = WECHAT_USERINFO + "?access_token=" + enc(accessToken)
                + "&openid=" + enc(openId) + "&lang=zh_CN";
        JsonNode info = parse(http.get(infoUrl, null));
        wechatError(info, "获取用户信息失败");
        return new SocialIdentity("wechat", openId, text(token, "unionid") == null ? text(info, "unionid") : text(token, "unionid"),
                text(info, "nickname"), text(info, "headimgurl"), null, false);
    }

    private void wechatError(JsonNode node, String prefix) {
        long err = node.path("errcode").asLong(0L);
        if (err != 0) {
            throw new BusinessException("微信" + prefix + "：" + node.path("errmsg").asText("errcode=" + err));
        }
    }

    /* ---------------- Apple ---------------- */

    private SocialIdentity apple(SocialLoginProperties.Provider cfg, String code, String redirectUri) {
        JsonNode token = parse(http.postForm(APPLE_TOKEN, form(Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "client_id", cfg.resolveId(),
                "client_secret", cfg.resolveSecret(),
                "redirect_uri", safe(redirectUri))), null));
        String idToken = required(token, "id_token", "Apple");

        String kid = jwt.kidOf(idToken);
        String[] jwk = JwtVerifier.pickJwk(jwks(APPLE_JWKS), kid);
        if (jwk == null) throw new BusinessException("Apple 公钥未匹配到对应 kid，无法校验身份令牌");

        JsonNode payload = jwt.verifyRs256(idToken, jwk[0], jwk[1], APPLE_ISS, cfg.resolveId());
        String sub = required(payload, "sub", "Apple");
        String email = text(payload, "email");
        return new SocialIdentity("apple", sub, null, null, null, email, boolOf(payload.get("email_verified")));
    }

    private JsonNode jwks(String url) {
        CachedJwks cached = jwksCache.get(url);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < 6 * 3600_000L) return cached.doc();
        JsonNode doc = parse(http.get(url, null));
        jwksCache.put(url, new CachedJwks(doc, now));
        return doc;
    }

    /* ---------------- 支付宝 ---------------- */

    private SocialIdentity alipay(SocialLoginProperties.Provider cfg, String code) {
        JsonNode tokenResp = alipayCall(cfg, "alipay.system.oauth.token",
                Map.of("grant_type", "authorization_code", "code", code));
        JsonNode token = tokenResp.path("alipay_system_oauth_token_response");
        String accessToken = required(token, "access_token", "支付宝");
        String userId = required(token, "user_id", "支付宝");

        JsonNode infoResp = alipayCall(cfg, "alipay.user.info.share", Map.of("auth_token", accessToken));
        JsonNode info = infoResp.path("alipay_user_info_share_response");
        String subject = text(info, "user_id") == null ? userId : text(info, "user_id");
        return new SocialIdentity("alipay", subject, null, text(info, "nick_name"),
                text(info, "avatar"), null, false);
    }

    /**
     * 组装公共参数 + 业务参数，RSA2 签名后 POST gateway.do。
     *
     * 签名私钥必须是「商户应用私钥」（app.social.providers.alipay.alipay-private-key，PKCS#8 PEM）：
     * 支付宝开放平台的 RSA2 签名规则要求用商户私钥，appSecret 是平台下发的密钥，不参与签名
     * —— 误用 appSecret 会直接导致签名校验失败（且把平台密钥当私钥使用存在安全风险）。
     */
    private JsonNode alipayCall(SocialLoginProperties.Provider cfg, String method, Map<String, String> biz) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", cfg.resolveId());
        params.put("method", method);
        params.put("charset", "UTF-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(ALIPAY_TS));
        params.put("version", "1.0");
        params.putAll(biz);
        params.put("sign", alipaySigner.sign(params, cfg.getAlipayPrivateKey()));

        JsonNode resp = parse(http.postForm(ALIPAY_GATEWAY, params, null));
        String errorCode = text(resp, "error_response") != null
                ? resp.path("error_response").path("sub_code").asText("")
                : "";
        if (!errorCode.isEmpty()) {
            throw new BusinessException("支付宝接口调用失败：" +
                    resp.path("error_response").path("sub_msg").asText(errorCode));
        }
        return resp;
    }

    /* ---------------- 工具 ---------------- */

    private JsonNode parse(String body) {
        try {
            JsonNode node = mapper.readTree(body == null ? "" : body);
            if (node == null || node.isNull()) throw new BusinessException("社交平台返回为空");
            return node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("社交平台返回内容无法解析");
        }
    }

    private static Map<String, String> form(Map<String, String> m) {
        Map<String, String> out = new LinkedHashMap<>(m);
        out.values().removeIf(v -> v == null);
        return out;
    }

    private static String required(JsonNode node, String field, String platform) {
        String v = text(node, field);
        if (v == null || v.isBlank()) {
            throw new BusinessException("未取得" + platform + "身份标识（" + field + "），授权可能已失效");
        }
        return v;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static boolean boolOf(JsonNode v) {
        if (v == null || v.isNull()) return false;
        return v.isBoolean() ? v.asBoolean() : "true".equalsIgnoreCase(v.asText());
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }
}
