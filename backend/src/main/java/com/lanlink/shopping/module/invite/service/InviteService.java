package com.lanlink.shopping.module.invite.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.UserRoles;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.invite.entity.InviteCode;
import com.lanlink.shopping.module.invite.entity.InviteRelation;
import com.lanlink.shopping.module.invite.mapper.InviteCodeMapper;
import com.lanlink.shopping.module.invite.mapper.InviteRelationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 邀请链路：邀请码生成 / 注册绑定 / 商家入驻邀约码校验 / 邀请统计。
 *
 * <p><b>身份分配规则（关键约束）</b>
 * <table border="1">
 *   <caption>场景 × 身份</caption>
 *   <tr><th>生成方</th><th>场景</th><th>inviteType</th><th>被邀请人最终身份</th></tr>
 *   <tr><td>普通用户 / 商户（前台）</td><td>frontend</td><td>buyer</td><td>买家（注册即得）</td></tr>
 *   <tr><td>平台运营（前台）</td><td>frontend</td><td>buyer</td><td>买家（注册即得）</td></tr>
 *   <tr><td>平台运营（运营后台）</td><td>admin_console</td><td>merchant_apply</td>
 *       <td>商户（<b>必须走商家入驻申请并审核通过</b>，注册接口永远只给买家）</td></tr>
 * </table>
 *
 * <p>因此：<b>注册接口 {@link #roleIdForRegister} 永远返回买家角色</b>，商户身份只能由
 * {@code MerchantService.apply/review} 在资质校验通过后升级，杜绝"靠邀请绕过入驻审核"。
 *
 * <p>其它约束：
 * <ul>
 *   <li><b>不可篡改</b>：邀请码由 {@link SecureRandom} 生成并落库校验，改任何字符都查不到；</li>
 *   <li><b>唯一有效码</b>：同一用户同一场景重新生成时旧码立即失效；</li>
 *   <li><b>有效期</b>：{@code lanlink.invite.expires-days}（默认 30 天，&le;0 永不过期）；</li>
 *   <li><b>使用次数</b>：{@code max_uses}（0=不限），每次成功使用原子 +1，用尽后失效；</li>
 *   <li><b>越权拦截</b>：merchant_apply 只允许运营生成（服务层 + 控制器层双校验）；</li>
 *   <li><b>绑定一次</b>：{@code (invitee_id, invite_type)} 唯一键兜底同类型重复绑定。</li>
 * </ul>
 */
@Service
public class InviteService {

    private static final Logger log = LoggerFactory.getLogger(InviteService.class);

    /** 去掉易混字符（0/O/1/I/L）后的 Base32 子集，共 31 个字符。 */
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final String PREFIX = "INV";
    /** 12 位随机串：31^12 ≈ 7.9e17，配合唯一键足以避免碰撞。 */
    private static final int RANDOM_LEN = 12;

    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_REVOKED = 2;

    /** 前台注册邀请：被邀请人注册为买家。 */
    public static final String TYPE_BUYER = "buyer";
    /** 运营后台商家入驻邀请码：在入驻申请中作为邀约码，审核通过后才成为商户。 */
    public static final String TYPE_MERCHANT_APPLY = "merchant_apply";

    /** 场景：前台（用户中心/首页分享）。 */
    public static final String SCENE_FRONTEND = "frontend";
    /** 场景：运营后台（商家入驻邀约）。 */
    public static final String SCENE_ADMIN_CONSOLE = "admin_console";

    private final InviteCodeMapper codeMapper;
    private final InviteRelationMapper relationMapper;
    private final InviteBindingWriter bindingWriter;
    private final UserMapper userMapper;
    private final SecureRandom random = new SecureRandom();

    private final int expiresDays;
    private final int defaultMaxUses;
    /** 前台注册页地址（拼邀请链接，指向 SPA 而非 API）。 */
    private final String registerUrl;
    /** 商家入驻页地址（运营后台邀约码对应的落地页）。 */
    private final String merchantApplyUrl;

    public InviteService(InviteCodeMapper codeMapper, InviteRelationMapper relationMapper,
                         InviteBindingWriter bindingWriter,
                         UserMapper userMapper,
                         @Value("${lanlink.invite.expires-days:30}") int expiresDays,
                         @Value("${lanlink.invite.max-uses:0}") int defaultMaxUses,
                         @Value("${lanlink.invite.register-url:http://localhost:5173/register}") String registerUrl,
                         @Value("${lanlink.invite.merchant-apply-url:http://localhost:5173/merchant}")
                         String merchantApplyUrl) {
        this.codeMapper = codeMapper;
        this.relationMapper = relationMapper;
        this.bindingWriter = bindingWriter;
        this.userMapper = userMapper;
        this.expiresDays = expiresDays;
        this.defaultMaxUses = Math.max(0, defaultMaxUses);
        this.registerUrl = registerUrl;
        this.merchantApplyUrl = merchantApplyUrl;
    }

    // ==================== 邀请码生成 ====================

    /**
     * 前台邀请链接：任何登录角色（买家/商户/运营）都只能生成<b>注册为买家</b>的邀请。
     *
     * @param inviter 邀请人（必须已登录）
     */
    public Map<String, Object> currentLink(User inviter) {
        requireInviter(inviter);
        InviteCode code = findActive(inviter.getUserId(), TYPE_BUYER, SCENE_FRONTEND);
        if (code == null) {
            code = create(inviter, TYPE_BUYER, SCENE_FRONTEND, defaultMaxUses);
        }
        return toView(code);
    }

    /**
     * 重新生成前台邀请链接：旧码立即失效。
     *
     * @param maxUses 本次生成的最大使用次数；&le;0 用配置默认值（0 表示不限）
     */
    @Transactional
    public Map<String, Object> rotate(User inviter, Integer maxUses) {
        requireInviter(inviter);
        revokeActive(inviter.getUserId(), TYPE_BUYER, SCENE_FRONTEND);
        int uses = maxUses == null || maxUses <= 0 ? defaultMaxUses : maxUses;
        return toView(create(inviter, TYPE_BUYER, SCENE_FRONTEND, uses));
    }

    /**
     * 运营后台：生成商家入驻邀约码。
     *
     * <p><b>仅平台运营可调用</b>（控制器 + 本方法双校验）。生成的码用于
     * {@code POST /merchant/apply} 的 {@code joinCode}（入驻方式=邀约），
     * 被邀请人仍需提交资质并经审核，<b>不会</b>在注册时直接变成商户。
     *
     * @throws BusinessException 403 非运营调用
     */
    @Transactional
    public Map<String, Object> issueMerchantApplyCode(User inviter, Integer maxUses, boolean rotate) {
        requireInviter(inviter);
        if (!UserRoles.isAdmin(inviter.getRoleId())) {
            throw new BusinessException(403, "无权限：仅平台运营可生成商家入驻邀请码");
        }
        if (rotate) {
            revokeActive(inviter.getUserId(), TYPE_MERCHANT_APPLY, SCENE_ADMIN_CONSOLE);
        } else {
            InviteCode existing = findActive(inviter.getUserId(), TYPE_MERCHANT_APPLY, SCENE_ADMIN_CONSOLE);
            if (existing != null) return toView(existing);
        }
        int uses = maxUses == null || maxUses <= 0 ? defaultMaxUses : maxUses;
        return toView(create(inviter, TYPE_MERCHANT_APPLY, SCENE_ADMIN_CONSOLE, uses));
    }

    // ==================== 解析 / 身份分配 ====================

    /**
     * 邀请码类型（无效/过期/用尽返回 null）。
     */
    public String resolveInviteType(String rawCode) {
        InviteCode code = usableCode(rawCode);
        return code == null ? null : code.getInviteType();
    }

    /**
     * 邀请码对应的邀请人 ID（不可用返回 null，不抛异常）。
     */
    public Long resolveInviter(String rawCode) {
        InviteCode code = usableCode(rawCode);
        return code == null ? null : code.getInviterId();
    }

    /**
     * 注册时应分配的角色。
     *
     * <p><b>恒为买家</b>：无论是普通邀请、运营前台邀请，还是误把入驻邀约码填进注册表单，
     * 注册接口都不会给出商户/管理员身份。商户身份只能通过入驻申请 + 审核升级。
     *
     * @return 固定 {@link UserRoles#BUYER_ROLE_ID}
     */
    public Long roleIdForRegister(String rawCode) {
        return UserRoles.BUYER_ROLE_ID;
    }

    /**
     * 校验商家入驻邀约码（供 {@code MerchantService.apply} 使用）。
     *
     * <p>只接受 {@code invite_type=merchant_apply} 且发起人是平台运营的码；
     * 其它码（买家邀请码/已过期/已用尽/伪造）一律返回 null，由调用方给出业务提示。
     */
    public InviteCode verifyMerchantApplyCode(String rawCode) {
        InviteCode code = usableCode(rawCode);
        if (code == null) return null;
        if (!TYPE_MERCHANT_APPLY.equals(code.getInviteType())) return null;
        if (!UserRoles.isAdmin(code.getInviterRoleId())) {
            log.warn("[invite] merchant apply code issued by non-admin (inviter={}, role={}), rejected",
                    code.getInviterId(), code.getInviterRoleId());
            return null;
        }
        return code;
    }

    // ==================== 绑定 ====================

    /**
     * 注册成功后绑定邀请关系（仅前台注册邀请有效）。
     *
     * <p>失败不阻断注册：码无效/过期/用尽/自邀/重复绑定都只记日志并返回 false。
     * merchant_apply 码不参与注册绑定（它只用于入驻）。
     */
    public boolean bindOnRegister(String rawCode, Long inviteeId, Long grantedRoleId) {
        if (!StringUtils.hasText(rawCode) || inviteeId == null) return false;
        try {
            InviteCode code = usableCode(rawCode);
            if (code == null) {
                log.debug("[invite] unusable code on register: {}", rawCode);
                return false;
            }
            if (!TYPE_BUYER.equals(code.getInviteType())) {
                log.debug("[invite] non-register code used on register: {}", rawCode);
                return false;
            }
            Long inviterId = code.getInviterId();
            if (inviterId == null || inviterId.equals(inviteeId)) {
                log.debug("[invite] self-invite rejected: {}", inviteeId);
                return false;
            }
            // 按类型判重：注册邀请只与注册邀请互斥（入驻邀约是另一条链路）
            if (relationMapper.countByInviteeAndType(inviteeId, TYPE_BUYER) > 0) {
                log.debug("[invite] invitee already bound by register invite: {}", inviteeId);
                return false;
            }
            // 扣次与关系写入在同一事务：关系写入失败会回滚扣次，不会白扣额度。
            boolean consumed = bindingWriter.consumeAndInsert(
                    code, inviterId, inviteeId, grantedRoleId, false);
            syncUsageState(code, consumed);
            if (!consumed) {
                log.debug("[invite] quota exhausted or expired at consume time for code {}", code.getCode());
                return false;
            }
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("[invite] concurrent bind skipped: {}", inviteeId);
            return false;
        } catch (RuntimeException e) {
            log.warn("[invite] bind failed for invitee {}: {}", inviteeId, e.getMessage());
            return false;
        }
    }

    /**
     * 商家入驻申请时绑定邀请关系（merchant_apply 码）。
     *
     * <p>由 {@code MerchantService.apply} 在入驻记录写入后调用：校验码有效后写入
     * {@code pending} 关系并消耗一次使用额度；审核通过时由 {@code markMerchantBound} 置为 bound。
     *
     * @return true 表示码有效且已记录
     */
    public boolean bindMerchantApply(String rawCode, Long applicantUserId) {
        if (!StringUtils.hasText(rawCode) || applicantUserId == null) return false;
        try {
            InviteCode code = verifyMerchantApplyCode(rawCode);
            if (code == null) return false;
            Long inviterId = code.getInviterId();
            if (inviterId == null || inviterId.equals(applicantUserId)) {
                log.debug("[invite] self merchant-apply rejected: {}", applicantUserId);
                return false;
            }
            // 只与「入驻邀约」互斥：已通过注册邀请进来的用户仍可被运营邀约入驻
            if (relationMapper.countByInviteeAndType(applicantUserId, TYPE_MERCHANT_APPLY) > 0) {
                log.debug("[invite] applicant already has merchant-apply relation: {}", applicantUserId);
                return false;
            }
            // 扣次与 pending 关系写入同事务；失败时整体回滚，调用方按 false 拒绝入驻。
            boolean consumed = bindingWriter.consumeAndInsert(
                    code, inviterId, applicantUserId, UserRoles.BUYER_ROLE_ID, true);
            syncUsageState(code, consumed);
            if (!consumed) {
                log.debug("[invite] merchant-apply quota exhausted or expired: {}", code.getCode());
                return false;
            }
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        } catch (RuntimeException e) {
            log.warn("[invite] merchant-apply bind failed for {}: {}", applicantUserId, e.getMessage());
            return false;
        }
    }

    /**
     * 商户审核通过后把关系置为 bound 并记录最终商户角色（审计用）。
     *
     * <p>失败只记日志，不影响审核主流程。
     */
    public void markMerchantBound(Long applicantUserId) {
        if (applicantUserId == null) return;
        try {
            InviteRelation relation = relationMapper.selectByInviteeAndType(applicantUserId, TYPE_MERCHANT_APPLY);
            if (relation == null) return;
            relation.setStatus("bound");
            relation.setGrantedRoleId(UserRoles.MERCHANT_ROLE_ID);
            relation.setBoundAt(LocalDateTime.now());
            relationMapper.updateById(relation);
        } catch (RuntimeException e) {
            log.warn("[invite] mark merchant bound failed for {}: {}", applicantUserId, e.getMessage());
        }
    }

    /**
     * 邀请统计（邀请人视角，含两种场景）。
     */
    public Map<String, Object> stats(User inviter) {
        requireInviter(inviter);
        Map<String, Object> out = new LinkedHashMap<>();
        List<InviteRelation> relations = relationMapper.selectByInviter(inviter.getUserId(), 50);
        if (relations == null) relations = List.of();
        int bound = 0;
        int pending = 0;
        int merchantBound = 0;
        List<Map<String, Object>> recent = new ArrayList<>();
        for (InviteRelation r : relations) {
            if ("bound".equals(r.getStatus())) bound++;
            else pending++;
            if (UserRoles.MERCHANT_ROLE_ID.equals(r.getGrantedRoleId())) merchantBound++;
            if (recent.size() < 10) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("inviteeId", r.getInviteeId());
                item.put("inviteeNickname", nicknameOf(r.getInviteeId()));
                item.put("inviteType", r.getInviteType());
                item.put("scene", r.getScene());
                item.put("grantedRoleId", r.getGrantedRoleId());
                item.put("status", r.getStatus());
                item.put("boundAt", r.getBoundAt());
                recent.add(item);
            }
        }
        out.put("invited", relations.size());
        out.put("bound", bound);
        out.put("pending", pending);
        out.put("merchantBound", merchantBound);
        InviteCode frontend = findActive(inviter.getUserId(), TYPE_BUYER, SCENE_FRONTEND);
        out.put("remainingUses", frontend == null ? 0 : remainingUses(frontend));
        out.put("recent", recent);
        return out;
    }

    // ==================== 内部 ====================

    private void requireInviter(User inviter) {
        if (inviter == null || inviter.getUserId() == null) {
            throw new BusinessException(401, "请先登录");
        }
    }

    private InviteCode usableCode(String rawCode) {
        InviteCode code = findByCode(rawCode);
        if (code == null) return null;
        if (code.getStatus() == null || code.getStatus() != STATUS_ACTIVE) return null;
        if (isExpired(code)) {
            revoke(code);
            return null;
        }
        if (isExhausted(code)) return null;
        return code;
    }

    private InviteCode findActive(Long inviterId, String type, String scene) {
        InviteCode code = codeMapper.selectActive(inviterId, type, scene);
        if (code == null) return null;
        if (isExpired(code) || isExhausted(code)) {
            revoke(code);
            return null;
        }
        return code;
    }

    private InviteCode findByCode(String rawCode) {
        if (!StringUtils.hasText(rawCode)) return null;
        String normalized = rawCode.trim().toUpperCase(Locale.ROOT);
        return codeMapper.selectByCode(normalized);
    }

    private InviteCode create(User inviter, String type, String scene, int maxUses) {
        LocalDateTime now = LocalDateTime.now();
        InviteCode code = new InviteCode();
        code.setCode(nextCode());
        code.setInviterId(inviter.getUserId());
        code.setInviterRoleId(inviter.getRoleId());
        code.setInviteType(type);
        code.setScene(scene);
        code.setStatus(STATUS_ACTIVE);
        code.setUsedCount(0);
        code.setMaxUses(Math.max(0, maxUses));
        code.setExpiresAt(expiresDays > 0 ? now.plusDays(expiresDays) : null);
        code.setCreatedAt(now);
        code.setUpdatedAt(now);
        try {
            codeMapper.insert(code);
        } catch (DuplicateKeyException e) {
            code.setCode(nextCode());
            codeMapper.insert(code);
        }
        return code;
    }

    /**
     * 把数据库原子扣次结果同步回内存快照。
     *
     * <p>扣次失败说明码在读取后已过期/失效或被并发请求用完，调用方不应继续使用该快照；
     * 扣次成功则累加 usedCount，达到上限时同步置为失效。
     */
    private static void syncUsageState(InviteCode code, boolean consumed) {
        LocalDateTime now = LocalDateTime.now();
        if (!consumed) {
            int max = code.getMaxUses() == null ? 0 : code.getMaxUses();
            if (max > 0) {
                code.setUsedCount(max);
            }
            code.setStatus(STATUS_REVOKED);
            code.setUpdatedAt(now);
            return;
        }
        int used = (code.getUsedCount() == null ? 0 : code.getUsedCount()) + 1;
        code.setUsedCount(used);
        int max = code.getMaxUses() == null ? 0 : code.getMaxUses();
        if (max > 0 && used >= max) {
            code.setStatus(STATUS_REVOKED);
        }
        code.setUpdatedAt(now);
    }

    private void revokeActive(Long inviterId, String type, String scene) {
        List<Long> activeIds = codeMapper.selectActiveIds(inviterId, type, scene);
        if (activeIds == null) return;
        for (Long id : activeIds) {
            // 只按 id 置失效：不读整行，避免并发下用旧快照覆盖其它字段
            codeMapper.revokeById(id);
        }
    }

    private void revoke(InviteCode code) {
        code.setStatus(STATUS_REVOKED);
        code.setUpdatedAt(LocalDateTime.now());
        // 只置状态：避免用旧快照整行覆盖（并发下 used_count 可能已被其它请求更新）
        codeMapper.revokeById(code.getId());
    }

    private static boolean isExpired(InviteCode code) {
        return code.getExpiresAt() != null && code.getExpiresAt().isBefore(LocalDateTime.now());
    }

    private static boolean isExhausted(InviteCode code) {
        int max = code.getMaxUses() == null ? 0 : code.getMaxUses();
        if (max <= 0) return false;
        int used = code.getUsedCount() == null ? 0 : code.getUsedCount();
        return used >= max;
    }

    /** 剩余可用次数；-1 表示不限。 */
    private static int remainingUses(InviteCode code) {
        int max = code.getMaxUses() == null ? 0 : code.getMaxUses();
        if (max <= 0) return -1;
        int used = code.getUsedCount() == null ? 0 : code.getUsedCount();
        return Math.max(0, max - used);
    }

    private String nextCode() {
        StringBuilder sb = new StringBuilder(PREFIX.length() + RANDOM_LEN);
        sb.append(PREFIX);
        for (int i = 0; i < RANDOM_LEN; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    private String nicknameOf(Long userId) {
        if (userId == null) return "";
        try {
            User u = userMapper.selectById(userId);
            if (u == null || u.getNickname() == null) return "";
            String nick = u.getNickname().trim();
            if (nick.length() <= 1) return nick;
            return nick.charAt(0) + "*".repeat(Math.min(3, nick.length() - 1));
        } catch (RuntimeException e) {
            return "";
        }
    }

    private Map<String, Object> toView(InviteCode code) {
        boolean merchantApply = TYPE_MERCHANT_APPLY.equals(code.getInviteType());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code.getCode());
        out.put("url", (merchantApply ? merchantApplyUrl : registerUrl) + "?invite=" + code.getCode());
        out.put("inviteType", code.getInviteType());
        out.put("scene", code.getScene());
        // 被邀请人最终身份：注册邀请 → buyer；入驻邀约 → 审核通过后才 merchant
        out.put("grantedRoleCode", merchantApply ? "merchant" : "buyer");
        out.put("requiresMerchantReview", merchantApply);
        out.put("expiresAt", code.getExpiresAt());
        out.put("expiresInSeconds", expiresInSeconds(code));
        out.put("maxUses", code.getMaxUses());
        out.put("usedCount", code.getUsedCount());
        out.put("remainingUses", remainingUses(code));
        out.put("channel", "link");
        return out;
    }

    private static long expiresInSeconds(InviteCode code) {
        if (code.getExpiresAt() == null) return -1L;
        long seconds = Duration.between(LocalDateTime.now(), code.getExpiresAt()).getSeconds();
        return Math.max(0L, seconds);
    }
}
