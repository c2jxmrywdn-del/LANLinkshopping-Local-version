package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.CsrfInterceptor;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.dto.*;
import com.lanlink.shopping.entity.Address;
import com.lanlink.shopping.entity.ThirdAuth;
import com.lanlink.shopping.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 账号中心控制器：个人信息 / 联系方式换绑 / 账户安全(TOTP) / 系统设置 / 第三方授权 / 审计 / CSRF
 * 说明：非 GET 请求由 CsrfInterceptor 校验 X-CSRF-TOKEN；登录态由 AuthInterceptor 保障。
 */
@RestController
@RequestMapping("/user")
public class UserController {

    /** 头像上传目录：{user.dir}/uploads/avatars（与 WebConfig 静态映射 /uploads/** 对应） */
    private static final String AVATAR_DIR = System.getProperty("user.dir") + File.separator + "uploads" + File.separator + "avatars";

    private final UserService userService;
    private final UserProfileService profileService;
    private final VerifyCodeService verifyCodeService;
    private final TotpService totpService;
    private final UserSettingsService settingsService;
    private final ThirdAuthService thirdAuthService;
    private final AuditService auditService;
    private final MessageService messageService;
    private final LoginLogService loginLogService;
    private final AddressService addressService;

    public UserController(UserService userService, UserProfileService profileService,
                          VerifyCodeService verifyCodeService, TotpService totpService,
                          UserSettingsService settingsService, ThirdAuthService thirdAuthService,
                          AuditService auditService, MessageService messageService,
                          LoginLogService loginLogService, AddressService addressService) {
        this.userService = userService;
        this.profileService = profileService;
        this.verifyCodeService = verifyCodeService;
        this.totpService = totpService;
        this.settingsService = settingsService;
        this.thirdAuthService = thirdAuthService;
        this.auditService = auditService;
        this.messageService = messageService;
        this.loginLogService = loginLogService;
        this.addressService = addressService;
    }

    // ===== CSRF =====

    @GetMapping("/csrf-token")
    public R<Map<String, String>> csrfToken(HttpServletRequest request) {
        return R.ok(Map.of("token", CsrfInterceptor.token(request)));
    }

    // ===== 个人信息 =====

    @GetMapping("/profile")
    public R<ProfileVO> profile(HttpServletRequest request) {
        return R.ok(toVO(UserContext.currentUserId(request)));
    }

    @PutMapping("/profile")
    public R<ProfileVO> updateProfile(@RequestBody ProfileUpdateDTO dto, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        if (dto.getNickname() != null) {
            userService.updateNickname(userId, dto.getNickname());
        }
        if (dto.getBio() != null) {
            dto.setBio(sanitizeBio(dto.getBio())); // 服务端内容合规预检
        }
        if (dto.getRealName() != null || dto.getGender() != null || dto.getBirthday() != null || dto.getBio() != null) {
            profileService.save(userId, dto);
        }
        return R.ok(toVO(userId));
    }

    // ===== 头像上传 =====

    @PostMapping("/avatar")
    public R<Map<String, String>> uploadAvatar(@RequestParam("file") MultipartFile file,
                                               HttpServletRequest request) throws Exception {
        Long userId = UserContext.currentUserId(request);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择图片文件");
        if (file.getSize() > 5 * 1024 * 1024) throw new IllegalArgumentException("图片大小不能超过 5MB");
        String contentType = file.getContentType();
        String ext;
        if ("image/jpeg".equals(contentType)) ext = "jpg";
        else if ("image/png".equals(contentType)) ext = "png";
        else if ("image/webp".equals(contentType)) ext = "webp";
        else throw new IllegalArgumentException("仅支持 JPG/PNG/WebP 图片");

        File dir = new File(AVATAR_DIR);
        if (!dir.exists() && !dir.mkdirs()) throw new RuntimeException("上传目录创建失败");
        String name = userId + "_" + System.currentTimeMillis() + "." + ext;
        File dest = new File(dir, name);
        file.transferTo(dest.getAbsoluteFile());

        String url = "/api/uploads/avatars/" + name;
        ProfileUpdateDTO p = new ProfileUpdateDTO();
        p.setAvatar(url);
        profileService.save(userId, p);
        return R.ok(Map.of("url", url));
    }

    // ===== 联系方式换绑（短信/邮箱验证码） =====

    @PostMapping("/phone-code")
    public R<Map<String, String>> sendPhoneCode(@Valid @RequestBody PhoneDTO dto, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        String code = verifyCodeService.send(userId, "phone", dto.getPhone());
        return R.ok(Map.of("devCode", code));
    }

    @PostMapping("/phone-bind")
    public R<ProfileVO> bindPhone(@Valid @RequestBody BindPhoneDTO dto, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        verifyCodeService.verify(userId, "phone", dto.getPhone(), dto.getCode());
        // 唯一性/占用检查在 changePhone 内完成；t_user_profile.phone 为密文不可查，以 t_user.phone 为准
        userService.changePhone(userId, dto.getPhone());
        ProfileUpdateDTO p = new ProfileUpdateDTO();
        p.setPhone(dto.getPhone());
        profileService.save(userId, p);
        return R.ok(toVO(userId));
    }

    @PostMapping("/email-code")
    public R<Map<String, String>> sendEmailCode(@Valid @RequestBody EmailDTO dto, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        String code = verifyCodeService.send(userId, "email", dto.getEmail());
        // Agent Loadout/SMTP/邮件 API 真实发送时不回显验证码；仅显式开启的演示模式可返回 devCode
        Map<String, String> out = new HashMap<>();
        if (code != null) out.put("devCode", code);
        return R.ok(out);
    }

    @PostMapping("/email-bind")
    public R<ProfileVO> bindEmail(@Valid @RequestBody BindEmailDTO dto, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        verifyCodeService.verify(userId, "email", dto.getEmail(), dto.getCode());
        ProfileUpdateDTO p = new ProfileUpdateDTO();
        p.setEmail(dto.getEmail());
        profileService.save(userId, p);
        return R.ok(toVO(userId));
    }

    // ===== 账户安全：密码 / TOTP =====

    @PostMapping("/password")
    public R<Void> changePassword(@Valid @RequestBody PasswordDTO dto, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        userService.changePassword(userId, dto.getOldPassword(), dto.getNewPassword());
        auditService.record(userId, "CHANGE_PASSWORD", "修改登录密码", request);
        return R.ok();
    }

    @GetMapping("/security")
    public R<Map<String, Object>> security(HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totpEnabled", totpService.isEnabled(userId));
        LocalDateTime ft = totpService.firstVerifyTime(userId);
        out.put("firstVerifyTime", ft == null ? null : ft.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return R.ok(out);
    }

    @PostMapping("/2fa/setup")
    public R<Map<String, String>> totpSetup(HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        String account = userService.findById(userId).getPhone();
        return R.ok(totpService.setup(userId, account));
    }

    @PostMapping("/2fa/enable")
    public R<Void> totpEnable(@Valid @RequestBody TotpCodeDTO dto, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        totpService.enable(userId, dto.getCode());
        auditService.record(userId, "ENABLE_2FA", "启用两步验证", request);
        return R.ok();
    }

    @PostMapping("/2fa/disable")
    public R<Void> totpDisable(@Valid @RequestBody TotpCodeDTO dto, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        totpService.disable(userId, dto.getCode());
        auditService.record(userId, "DISABLE_2FA", "关闭两步验证", request);
        return R.ok();
    }

    // ===== 系统设置 =====

    @GetMapping("/settings")
    public R<Map<String, Object>> settings(HttpServletRequest request) {
        return R.ok(settingsService.get(UserContext.currentUserId(request)));
    }

    @PutMapping("/settings")
    public R<Map<String, Object>> updateSettings(@RequestBody Map<String, Object> patch, HttpServletRequest request) {
        return R.ok(settingsService.patch(UserContext.currentUserId(request), patch));
    }

    @PostMapping("/settings/reset")
    public R<Map<String, Object>> resetSettings(HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        Map<String, Object> def = settingsService.reset(userId);
        auditService.record(userId, "SETTINGS_RESET", "恢复默认系统设置", request);
        return R.ok(def);
    }

    // ===== 第三方授权 =====

    @GetMapping("/third-auth")
    public R<List<ThirdAuth>> thirdAuthList(HttpServletRequest request) {
        return R.ok(thirdAuthService.listByUser(UserContext.currentUserId(request)));
    }

    @DeleteMapping("/third-auth/{id}")
    public R<Void> revokeThirdAuth(@PathVariable Long id, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        thirdAuthService.revoke(userId, id);
        auditService.record(userId, "REVOKE_THIRD_AUTH", "撤回第三方授权 id=" + id, request);
        return R.ok();
    }

    // ===== 审计（客户端事件上报，如缓存清空） =====

    @PostMapping("/audit")
    public R<Void> audit(@Valid @RequestBody AuditDTO dto, HttpServletRequest request) {
        auditService.record(UserContext.currentUserId(request), dto.getAction(), dto.getDetail(), request);
        return R.ok();
    }

    // ===== VIP 专属服务（需 vip:discount 权限，由 PermInterceptor 强制校验） =====

    @GetMapping("/vip/benefits")
    @com.lanlink.shopping.integration.security.RequirePerm("vip:discount")
    public R<java.util.List<String>> vipBenefits() {
        return R.ok(java.util.List.of(
                "全场商品 VIP 专属价（95 折）",
                "每月 2 张满减券",
                "专属客服通道",
                "积分双倍累计"));
    }

    // ===== 消息通知中心 =====

    @GetMapping("/message/page")
    public R<Map<String, Object>> messagePage(@RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "10") long size,
                                              @RequestParam(required = false) String type,
                                              HttpServletRequest request) {
        return R.ok(messageService.page(UserContext.currentUserId(request), page, size, type));
    }

    @GetMapping("/message/unread")
    public R<Map<String, Long>> messageUnread(HttpServletRequest request) {
        return R.ok(Map.of("count", messageService.unreadCount(UserContext.currentUserId(request))));
    }

    @PostMapping("/message/read/{id}")
    public R<Void> messageRead(@PathVariable Long id, HttpServletRequest request) {
        messageService.read(UserContext.currentUserId(request), id);
        return R.ok();
    }

    @PostMapping("/message/read-all")
    public R<Void> messageReadAll(HttpServletRequest request) {
        messageService.readAll(UserContext.currentUserId(request));
        return R.ok();
    }

    // ===== 登录安全记录 =====

    @GetMapping("/login-log")
    public R<Map<String, Object>> loginLog(@RequestParam(defaultValue = "1") long page,
                                           @RequestParam(defaultValue = "10") long size,
                                           HttpServletRequest request) {
        return R.ok(loginLogService.page(UserContext.currentUserId(request), page, size));
    }

    // ===== 收货地址 =====

    @GetMapping("/address")
    public R<List<Address>> addressList(HttpServletRequest request) {
        return R.ok(addressService.list(UserContext.currentUserId(request)));
    }

    @PostMapping("/address")
    public R<Address> addressAdd(@RequestBody Address dto, HttpServletRequest request) {
        return R.ok(addressService.add(UserContext.currentUserId(request), dto));
    }

    @PutMapping("/address/{id}")
    public R<Address> addressUpdate(@PathVariable Long id, @RequestBody Address dto, HttpServletRequest request) {
        return R.ok(addressService.update(UserContext.currentUserId(request), id, dto));
    }

    @DeleteMapping("/address/{id}")
    public R<Void> addressDelete(@PathVariable Long id, HttpServletRequest request) {
        addressService.delete(UserContext.currentUserId(request), id);
        return R.ok();
    }

    @PostMapping("/address/{id}/default")
    public R<Void> addressDefault(@PathVariable Long id, HttpServletRequest request) {
        addressService.setDefault(UserContext.currentUserId(request), id);
        return R.ok();
    }

    // ===== 工具 =====

    /** 个人资料合并视图：基础字段(t_user) + 资料字段(t_user_profile，敏感字段已解密) */
    private ProfileVO toVO(Long userId) {
        ProfileVO vo = new ProfileVO();
        vo.setUserId(userId);
        var u = userService.findById(userId);
        if (u != null) vo.setNickname(u.getNickname());
        var p = profileService.get(userId);
        if (p != null) {
            vo.setRealName(p.getRealName());
            vo.setGender(p.getGender());
            vo.setBirthday(p.getBirthday());
            vo.setAvatar(p.getAvatar());
            vo.setBio(p.getBio());
            vo.setPhone(p.getPhone());
            vo.setEmail(p.getEmail());
        }
        // 资料表手机号未绑定时回退到登录手机号（t_user.phone），保证联系方式始终可见
        if ((vo.getPhone() == null || vo.getPhone().isBlank()) && u != null) {
            vo.setPhone(u.getPhone());
        }
        return vo;
    }

    private static final Pattern TAG_BLOCK = Pattern.compile("<\\s*(script|style)[^>]*>[\\s\\S]*?<\\s*/\\s*\\1\\s*>", Pattern.CASE_INSENSITIVE);

    /** 简介净化：剔除 script/style 块与所有标签，最长 500 字 */
    private String sanitizeBio(String s) {
        if (s == null) return "";
        String out = TAG_BLOCK.matcher(s).replaceAll("");
        out = out.replaceAll("<[^>]*>", "");
        return out.length() > 500 ? out.substring(0, 500) : out;
    }
}