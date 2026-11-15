package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.config.SocialLoginProperties;
import com.lanlink.shopping.entity.SocialBinding;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.social.SocialIdentity;
import com.lanlink.shopping.social.SocialIdentityResolver;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 社交登录服务：渠道可用性探测 / 授权地址构造 / 授权码换取 / 绑定与登录判定。
 *
 * 设计原则（不伪造登录）：
 *  - 未配置凭证的渠道明确报「未配置」，不做任何假登录；
 *  - 换取到第三方身份后，**仅允许已绑定**的平台账号登录；
 *    未绑定时给出可执行指引（先手机号登录 → 账号设置里绑定），不自动建号；
 *  - 绑定动作在已登录态下由 {@link #bindIdentity} 完成，避免越权把第三方身份挂到他人账号。
 */
@Service
public class SocialLoginService {

    /** 支持的渠道（顺序即前端按钮顺序） */
    public static final List<String> SUPPORTED = List.of("wechat", "alipay", "apple", "google");

    /**
     * 沙箱登录唯一允许绑定的演示账号（买家）。
     *
     * <p>固定常量而非按 email 关键字挑选，避免沙箱模式被用来登录商户/管理员账号。
     */
    private static final String SANDBOX_DEMO_PHONE = "13900000001";

    private static final Map<String, String> DISPLAY_NAMES = Map.of(
            "wechat", "微信",
            "alipay", "支付宝",
            "apple", "Apple",
            "google", "Google"
    );

    /** 各平台授权端点（真实地址） */
    private static final Map<String, String> AUTHORIZE_ENDPOINTS = Map.of(
            "wechat", "https://open.weixin.qq.com/connect/qrconnect",
            "alipay", "https://openauth.alipay.com/oauth2/publicAppAuthorize.htm",
            "apple", "https://appleid.apple.com/auth/authorize",
            "google", "https://accounts.google.com/o/oauth2/v2/auth"
    );

    private final SocialLoginProperties props;
    private final SocialIdentityResolver resolver;
    private final SocialBindingService bindingService;
    private final UserService userService;

    public SocialLoginService(SocialLoginProperties props, SocialIdentityResolver resolver,
                              SocialBindingService bindingService, UserService userService) {
        this.props = props;
        this.resolver = resolver;
        this.bindingService = bindingService;
        this.userService = userService;
    }

    /** 全部渠道的可用性视图：未配置也返回，前端据此渲染品牌按钮并提示「未配置」或「沙箱可用」 */
    public List<Map<String, Object>> providers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String p : SUPPORTED) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("provider", p);
            item.put("name", DISPLAY_NAMES.getOrDefault(p, p));
            boolean configured = props.configured(p);
            item.put("enabled", configured || props.isSandboxEnabled());
            item.put("configured", configured);
            item.put("sandbox", !configured && props.isSandboxEnabled());
            out.add(item);
        }
        return out;
    }

    /** 构造平台授权地址（真实可用；沙箱模式未配置时返回沙箱模拟授权标记） */
    public String authorizeUrl(String provider, String state) {
        return authorizeUrl(provider, state, null);
    }

    /**
     * @param nonce Apple 授权的一次性 nonce。调用方必须把它和 state 一起存下来，回调时再校验。
     *              为空时这里会自己生成一个放进 URL，但那样回调无法对上，只适合拼 URL 的测试。
     */
    public String authorizeUrl(String provider, String state, String nonce) {
        requireSupported(provider);
        SocialLoginProperties.Provider cfg = props.provider(provider);
        if (!props.configured(provider)) {
            if (props.isSandboxEnabled()) {
                return props.getRedirectBase() + "?social_sandbox=" + encode(provider) + "&state=" + encode(state);
            }
            throw new BusinessException(DISPLAY_NAMES.getOrDefault(provider, provider)
                    + "登录未配置：请在 application-social.yml（或环境变量）中填写该渠道的凭证后启用");
        }
        String redirect = encode(props.getRedirectBase());
        String appId = cfg.resolveId();
        return switch (provider) {
            // 微信开放平台扫码登录：appid + snsapi_login + #wechat_redirect
            case "wechat" -> AUTHORIZE_ENDPOINTS.get(provider)
                    + "?appid=" + encode(appId)
                    + "&redirect_uri=" + redirect
                    + "&response_type=code&scope=snsapi_login"
                    + "&state=" + encode(state)
                    + "#wechat_redirect";
            // 支付宝网页授权：app_id + auth_user
            case "alipay" -> AUTHORIZE_ENDPOINTS.get(provider)
                    + "?app_id=" + encode(appId)
                    + "&scope=auth_user&redirect_uri=" + redirect
                    + "&state=" + encode(state);
            // Apple：带 name/email 时必须 form_post，redirect_uri 是后端接收地址而不是前端页面
            case "apple" -> AUTHORIZE_ENDPOINTS.get(provider)
                    + "?client_id=" + encode(appId)
                    + "&redirect_uri=" + encode(props.resolvedAppleRedirectUri())
                    + "&response_type=code&response_mode=form_post"
                    + "&scope=" + encode("name email")
                    + "&state=" + encode(state)
                    + "&nonce=" + encode(appleNonce(nonce));
            // Google：标准 OAuth2 + OIDC
            default -> AUTHORIZE_ENDPOINTS.get("google")
                    + "?client_id=" + encode(appId)
                    + "&redirect_uri=" + redirect
                    + "&response_type=code"
                    + "&scope=" + encode("openid email profile")
                    + "&state=" + encode(state);
        };
    }

    /**
     * 沙箱模拟登录（供开发测试、评审答辩与无外网秘钥环境下完整体验 Google / Apple 等社交登录）
     */
    public User sandboxLogin(String provider, String sub, String email, String name, String avatar) {
        requireSupported(provider);
        if (!props.isSandboxEnabled()) {
            throw new BusinessException("社交登录沙箱模拟模式未启用");
        }
        String subject = (sub != null && !sub.isBlank()) ? sub.trim()
                : ("sandbox_" + provider + "_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        String userEmail = (email != null && !email.isBlank()) ? email.trim() : (provider + ".tester@lanlink.com");
        String displayName = (name != null && !name.isBlank()) ? name.trim() : (DISPLAY_NAMES.getOrDefault(provider, provider) + "用户");
        String userAvatar = (avatar != null && !avatar.isBlank()) ? avatar.trim() : "";

        SocialBinding binding = bindingService.find(provider, subject);
        if (binding != null) {
            User u = userService.findById(binding.getUserId());
            if (u != null) return u;
        }

        // 沙箱只绑定到固定的演示买家账号。
        // 安全约束：绝不允许按 email/sub 里的关键字（如 admin/138/merchant）映射到商户或管理员账号，
        // 否则任何人在沙箱模式下填一个含 "admin" 的邮箱即可直接登录管理员。
        User targetUser = userService.findByPhone(SANDBOX_DEMO_PHONE);
        if (targetUser == null) {
            throw new BusinessException("无法建立测试会话，请确保系统演示账号已初始化");
        }

        bindingService.bind(targetUser.getUserId(), provider, subject, null, displayName, userAvatar, userEmail);
        return targetUser;
    }

    /**
     * 当前登录用户的第三方绑定列表
     */
    public List<Map<String, Object>> userBindings(Long userId) {
        if (userId == null) return List.of();
        List<SocialBinding> list = bindingService.listByUser(userId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SocialBinding b : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("provider", b.getProvider());
            m.put("name", DISPLAY_NAMES.getOrDefault(b.getProvider(), b.getProvider()));
            m.put("nickname", b.getNickname());
            m.put("avatar", b.getAvatar());
            m.put("email", b.getEmail());
            m.put("subjectMasked", mask(b.getSubject()));
            m.put("createTime", b.getCreateTime());
            out.add(m);
        }
        return out;
    }

    /**
     * 解绑第三方账号
     */
    public boolean unbindProvider(Long userId, String provider) {
        if (userId == null) throw new BusinessException(401, "请先登录");
        requireSupported(provider);
        List<SocialBinding> list = bindingService.listByUser(userId);
        for (SocialBinding b : list) {
            if (provider.equals(b.getProvider())) {
                return bindingService.unbind(userId, b.getId());
            }
        }
        return false;
    }

    public User callback(String provider, String code, String state) {
        return callback(provider, code, state, null, null);
    }

    /**
     * 社交登录回调：授权码 → 第三方身份 → 绑定校验 → 返回可登录的平台账号。
     * 未绑定时不自动建号，返回可执行的绑定指引。
     *
     * @param nonce         仅 Apple 使用，必须与授权 URL 上的 nonce 一致
     * @param appleNickname Apple 首次授权带回的姓名，可空
     */
    public User callback(String provider, String code, String state, String nonce, String appleNickname) {
        requireSupported(provider);
        if (code == null || code.isBlank()) throw new BusinessException("授权码缺失，请重新发起授权");
        if (state == null || state.isBlank()) throw new BusinessException("授权状态校验失败，请重新发起授权");
        requireConfigured(provider);

        SocialIdentity identity = exchange(provider, code, nonce, appleNickname);
        SocialBinding binding = bindingService.find(provider, identity.subject());
        if (binding == null) {
            throw new BusinessException("该 " + DISPLAY_NAMES.getOrDefault(provider, provider)
                    + " 账号尚未绑定平台账号：请先用手机号登录，再到「账号设置 → 社交账号绑定」完成绑定");
        }
        User u = userService.findById(binding.getUserId());
        if (u == null) throw new BusinessException("该第三方账号绑定的平台账号不存在或已注销");
        return u;
    }

    public Map<String, Object> bindIdentity(Long userId, String provider, String code, String state) {
        return bindIdentity(userId, provider, code, state, null, null);
    }

    /**
     * 已登录用户绑定第三方账号：换取身份后写入绑定表（幂等）。
     * @return 便于前端展示的绑定信息（subject 脱敏）
     */
    public Map<String, Object> bindIdentity(Long userId, String provider, String code, String state,
                                            String nonce, String appleNickname) {
        if (userId == null) throw new BusinessException(401, "请先登录后再绑定");
        requireSupported(provider);
        if (code == null || code.isBlank()) throw new BusinessException("授权码缺失，请重新发起授权");
        if (state == null || state.isBlank()) throw new BusinessException("授权状态校验失败，请重新发起授权");
        requireConfigured(provider);

        SocialIdentity identity = exchange(provider, code, nonce, appleNickname);
        SocialBinding mine = bindingService.find(provider, identity.subject());
        if (mine != null && !mine.getUserId().equals(userId)) {
            throw new BusinessException("该 " + DISPLAY_NAMES.getOrDefault(provider, provider) + " 账号已被其它平台账号绑定");
        }
        SocialBinding saved = bindingService.bind(userId, provider, identity.subject(), identity.unionId(),
                identity.displayName(), identity.avatar(), identity.email());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", provider);
        out.put("name", DISPLAY_NAMES.getOrDefault(provider, provider));
        out.put("nickname", saved.getNickname());
        out.put("avatar", saved.getAvatar());
        out.put("email", saved.getEmail());
        out.put("subjectMasked", mask(saved.getSubject()));
        return out;
    }

    private SocialIdentity exchange(String provider, String code, String nonce, String appleNickname) {
        SocialLoginProperties.Provider cfg = props.provider(provider);
        if ("apple".equals(provider)) {
            if (nonce == null || nonce.isBlank()) {
                throw new BusinessException("授权状态校验失败，请重新发起授权");
            }
            return resolver.resolve(provider, cfg, code, props.resolvedAppleRedirectUri(), nonce, appleNickname);
        }
        return resolver.resolve(provider, cfg, code, props.getRedirectBase());
    }

    private static String appleNonce(String nonce) {
        if (nonce == null || nonce.isBlank()) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        return nonce.trim();
    }

    private void requireConfigured(String provider) {
        if (!props.configured(provider)) {
            throw new BusinessException(DISPLAY_NAMES.getOrDefault(provider, provider)
                    + "登录未配置：请先完成凭证配置后再发起授权");
        }
    }

    private void requireSupported(String provider) {
        if (provider == null || !SUPPORTED.contains(provider)) {
            throw new BusinessException("不支持的社交登录渠道");
        }
    }

    private static String mask(String subject) {
        if (subject == null || subject.length() <= 6) return subject == null ? "" : subject;
        return subject.substring(0, 3) + "****" + subject.substring(subject.length() - 3);
    }

    private static String encode(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
