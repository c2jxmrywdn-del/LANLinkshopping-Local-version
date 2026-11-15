package com.lanlink.shopping.module.invite.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.UserRoles;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.invite.entity.InviteCode;
import com.lanlink.shopping.module.invite.entity.InviteRelation;
import com.lanlink.shopping.module.invite.mapper.InviteCodeMapper;
import com.lanlink.shopping.module.invite.mapper.InviteRelationMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 邀请链路单测：链接生成 / 有效期 / 次数限制 / 篡改 / 角色分型 / 注册绑定 / 统计。
 *
 * <p>不启动 Spring、不连库：Mapper 用 Mockito 内存实现，并按 MyBatis-Plus 下推的
 * SQL 参数过滤，行为对齐真实查询语义。
 */
class InviteServiceTest {

    private InviteCodeMapper codeMapper;
    private InviteRelationMapper relationMapper;
    private UserMapper userMapper;
    private InviteBindingWriter bindingWriter;
    private InviteService service;

    private final List<InviteCode> codes = new ArrayList<>();
    private final List<InviteRelation> relations = new ArrayList<>();
    /** 独立的使用次数账本：模拟数据库行，避免与 service 内存态互相污染导致重复计数。 */
    private final java.util.Map<Long, Integer> usedCounts = new java.util.HashMap<>();
    private long seq = 0;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        codes.clear();
        relations.clear();
        usedCounts.clear();
        seq = 0;
        codeMapper = mock(InviteCodeMapper.class);
        relationMapper = mock(InviteRelationMapper.class);
        userMapper = mock(UserMapper.class);
        bindingWriter = new InviteBindingWriter(codeMapper, relationMapper);

        when(codeMapper.insert(any(InviteCode.class))).thenAnswer(inv -> {
            InviteCode c = inv.getArgument(0);
            c.setId(++seq);
            codes.add(c);
            usedCounts.put(c.getId(), c.getUsedCount() == null ? 0 : c.getUsedCount());
            return 1;
        });
        when(codeMapper.updateById(any(InviteCode.class))).thenReturn(1);
        // ---- 显式 SQL 的等价内存实现（与真实 SQL 语义一致）----
        when(codeMapper.selectByCode(anyString())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            return codes.stream().filter(c -> code.equals(c.getCode())).findFirst().orElse(null);
        });
        when(codeMapper.selectActive(anyLong(), anyString(), anyString())).thenAnswer(inv -> {
            Long inviterId = inv.getArgument(0);
            String type = inv.getArgument(1);
            String scene = inv.getArgument(2);
            return codes.stream()
                    .filter(c -> Integer.valueOf(1).equals(c.getStatus()))
                    .filter(c -> inviterId.equals(c.getInviterId()))
                    .filter(c -> type.equals(c.getInviteType()) && scene.equals(c.getScene()))
                    .reduce((a, b) -> b)
                    .orElse(null);
        });
        when(codeMapper.selectActiveIds(anyLong(), anyString(), anyString())).thenAnswer(inv -> {
            Long inviterId = inv.getArgument(0);
            String type = inv.getArgument(1);
            String scene = inv.getArgument(2);
            return codes.stream()
                    .filter(c -> Integer.valueOf(1).equals(c.getStatus()))
                    .filter(c -> inviterId.equals(c.getInviterId()))
                    .filter(c -> type.equals(c.getInviteType()) && scene.equals(c.getScene()))
                    .map(InviteCode::getId)
                    .toList();
        });
        when(codeMapper.revokeById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            codes.stream().filter(c -> id.equals(c.getId()) && Integer.valueOf(1).equals(c.getStatus()))
                    .forEach(c -> c.setStatus(2));
            return 1;
        });
        // 原子消耗：status=1、未过期且未超额度才 +1，否则返回 0
        when(codeMapper.consumeUse(anyLong(), any(LocalDateTime.class))).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            LocalDateTime now = inv.getArgument(1);
            InviteCode target = codes.stream().filter(c -> id.equals(c.getId())).findFirst().orElse(null);
            if (target == null || !Integer.valueOf(1).equals(target.getStatus())) return 0;
            if (target.getExpiresAt() != null && !target.getExpiresAt().isAfter(now)) return 0;
            int max = target.getMaxUses() == null ? 0 : target.getMaxUses();
            int used = usedCounts.getOrDefault(id, 0);
            if (max > 0 && used >= max) return 0;
            usedCounts.put(id, used + 1);
            // 达到上限：数据库侧会同时把 status 置 2
            if (max > 0 && used + 1 >= max) target.setStatus(2);
            return 1;
        });

        when(relationMapper.insert(any(InviteRelation.class))).thenAnswer(inv -> {
            InviteRelation r = inv.getArgument(0);
            r.setId(++seq);
            relations.add(r);
            return 1;
        });
        when(relationMapper.countByInviteeAndType(anyLong(), anyString())).thenAnswer(inv -> {
            Long inviteeId = inv.getArgument(0);
            String type = inv.getArgument(1);
            return (int) relations.stream()
                    .filter(r -> inviteeId.equals(r.getInviteeId()) && type.equals(r.getInviteType()))
                    .count();
        });
        when(relationMapper.selectByInviteeAndType(anyLong(), anyString())).thenAnswer(inv -> {
            Long inviteeId = inv.getArgument(0);
            String type = inv.getArgument(1);
            return relations.stream()
                    .filter(r -> inviteeId.equals(r.getInviteeId()) && type.equals(r.getInviteType()))
                    .findFirst().orElse(null);
        });
        when(relationMapper.selectByInviter(anyLong(), anyInt())).thenAnswer(inv -> {
            Long inviterId = inv.getArgument(0);
            return relations.stream().filter(r -> inviterId.equals(r.getInviterId())).toList();
        });

        service = new InviteService(codeMapper, relationMapper, bindingWriter, userMapper, 30, 0,
                "http://localhost:5173/register", "http://localhost:5173/merchant-apply");
    }

    private User user(long id, Long roleId, String nickname) {
        User u = new User();
        u.setUserId(id);
        u.setRoleId(roleId);
        u.setNickname(nickname);
        return u;
    }

    private User buyer(long id) {
        return user(id, UserRoles.BUYER_ROLE_ID, "采购" + id);
    }

    private User admin(long id) {
        return user(id, UserRoles.ADMIN_ROLE_ID, "运营" + id);
    }

    // ==================== 生成 / 格式 ====================

    @Test
    void currentLinkCreatesStableCodeAndUrl() {
        Map<String, Object> first = service.currentLink(buyer(7L));
        String code = String.valueOf(first.get("code"));
        assertTrue(code.startsWith("INV"), code);
        assertTrue(code.length() > 10, "随机段必须足够长，避免被枚举");
        String randomPart = code.substring(3);
        assertFalse(randomPart.matches(".*[01IOL].*"), "随机段必须排除易混字符: " + randomPart);
        assertEquals("http://localhost:5173/register?invite=" + code, first.get("url"));
        assertNotNull(first.get("expiresAt"));
        assertTrue((long) first.get("expiresInSeconds") > 0);
        assertEquals("buyer", first.get("inviteType"));

        Map<String, Object> second = service.currentLink(buyer(7L));
        assertEquals(code, second.get("code"), "有效码必须复用，不重复生成");
        assertEquals(1, codes.size());
    }

    @Test
    void expiresDaysZeroMeansNeverExpire() {
        InviteService never = new InviteService(codeMapper, relationMapper, bindingWriter, userMapper,
                0, 0, "http://localhost:5173/register", "http://localhost:5173/merchant-apply");
        Map<String, Object> link = never.currentLink(buyer(1L));
        assertNull(link.get("expiresAt"));
        assertEquals(-1L, link.get("expiresInSeconds"), "-1 表示永不过期");
    }

    @Test
    void rotateRevokesOldCodeImmediately() {
        String oldCode = String.valueOf(service.currentLink(buyer(7L)).get("code"));
        Map<String, Object> rotated = service.rotate(buyer(7L), null);
        String newCode = String.valueOf(rotated.get("code"));

        assertNotEquals(oldCode, newCode, "重新生成必须换新码");
        assertNull(service.resolveInviter(oldCode), "旧链接必须立即失效");
        assertEquals(7L, service.resolveInviter(newCode));
    }

    // ==================== 角色分型 / 身份分配 ====================

    @Test
    void everyFrontendInviteGrantsBuyerRoleRegardlessOfInviterRole() {
        // 普通用户 / 商户 / 平台运营，在前台生成的邀请都只能让被邀请人成为买家
        for (User inviter : List.of(buyer(7L),
                user(8L, UserRoles.MERCHANT_ROLE_ID, "商户8"), admin(3L))) {
            Map<String, Object> link = service.currentLink(inviter);
            assertEquals("buyer", link.get("inviteType"),
                    "前台邀请必须是 buyer 类型，发起人角色=" + inviter.getRoleId());
            assertEquals("frontend", link.get("scene"));
            assertEquals("buyer", link.get("grantedRoleCode"));
            assertEquals(Boolean.FALSE, link.get("requiresMerchantReview"));
            assertEquals(UserRoles.BUYER_ROLE_ID,
                    service.roleIdForRegister(String.valueOf(link.get("code"))),
                    "注册接口必须恒为买家，即使邀请人是运营");
        }
    }

    /** 运营后台生成的入驻邀约码：不是注册邀请，且必须经审核才能成为商户。 */
    @Test
    void adminMerchantApplyCodeRequiresReviewAndNeverRegistersAsMerchant() {
        Map<String, Object> code = service.issueMerchantApplyCode(admin(3L), 5, false);
        String raw = String.valueOf(code.get("code"));

        assertEquals("merchant_apply", code.get("inviteType"));
        assertEquals("admin_console", code.get("scene"));
        assertEquals("merchant", code.get("grantedRoleCode"));
        assertEquals(Boolean.TRUE, code.get("requiresMerchantReview"));
        assertTrue(String.valueOf(code.get("url")).contains("merchant-apply"),
                "入驻码应指向入驻页而不是注册页: " + code.get("url"));

        // 关键：即使被误填进注册表单，也只能拿到买家身份
        assertEquals(UserRoles.BUYER_ROLE_ID, service.roleIdForRegister(raw),
                "入驻邀约码不得通过注册接口给出商户身份");
        assertFalse(service.bindOnRegister(raw, 21L, UserRoles.BUYER_ROLE_ID),
                "入驻邀约码不参与注册绑定");

        // 入驻校验：merchant_apply 有效
        assertNotNull(service.verifyMerchantApplyCode(raw));
    }

    /** 非运营调用入驻码接口必须 403（服务层兜底，控制器层还会再拦一次）。 */
    @Test
    void nonAdminCannotIssueMerchantApplyCode() {
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> service.issueMerchantApplyCode(buyer(7L), null, false));
        assertEquals(403, e1.getCode(), "买家生成入驻码必须 403");

        BusinessException e2 = assertThrows(BusinessException.class,
                () -> service.issueMerchantApplyCode(user(8L, UserRoles.MERCHANT_ROLE_ID, "商户8"), null, false));
        assertEquals(403, e2.getCode(), "商户生成入驻码同样 403");
    }

    /** 买家邀请码不得用于商家入驻（防止用普通邀请绕过入驻审核）。 */
    @Test
    void buyerInviteCodeCannotBeUsedForMerchantApply() {
        String buyerCode = String.valueOf(service.currentLink(buyer(7L)).get("code"));
        assertNull(service.verifyMerchantApplyCode(buyerCode),
                "买家注册邀请码不是入驻邀约码");
        assertFalse(service.bindMerchantApply(buyerCode, 21L));
    }

    /** 伪造 merchant_apply 但发起人不是运营：必须拒绝。 */
    @Test
    void forgedMerchantApplyCodeIsRejected() {
        InviteCode forged = new InviteCode();
        forged.setId(900L);
        forged.setCode("INVFORGED000000");
        forged.setInviterId(7L);
        forged.setInviterRoleId(UserRoles.BUYER_ROLE_ID);
        forged.setInviteType("merchant_apply");
        forged.setScene("admin_console");
        forged.setStatus(1);
        forged.setUsedCount(0);
        forged.setMaxUses(0);
        forged.setExpiresAt(LocalDateTime.now().plusDays(1));
        codes.add(forged);

        assertNull(service.verifyMerchantApplyCode("INVFORGED000000"),
                "非运营签发的入驻码必须拒绝");
        assertFalse(service.bindMerchantApply("INVFORGED000000", 21L));
    }

    @Test
    void registerWithoutInviteDefaultsToBuyer() {
        assertEquals(UserRoles.BUYER_ROLE_ID, service.roleIdForRegister(null));
        assertEquals(UserRoles.BUYER_ROLE_ID, service.roleIdForRegister(""));
        assertEquals(UserRoles.BUYER_ROLE_ID, service.roleIdForRegister("INUNKNOWN"));
    }

    @Test
    void currentLinkRequiresLogin() {
        assertThrows(BusinessException.class, () -> service.currentLink(null));
        assertThrows(BusinessException.class, () -> service.rotate(null, null));
        assertThrows(BusinessException.class, () -> service.stats(null));
    }

    // ==================== 使用次数 ====================

    @Test
    void maxUsesLimitsRegistrations() {
        Map<String, Object> link = service.rotate(buyer(7L), 1);
        String code = String.valueOf(link.get("code"));
        assertEquals(1, link.get("maxUses"));
        assertEquals(1, link.get("remainingUses"));

        assertTrue(service.bindOnRegister(code, 21L, UserRoles.BUYER_ROLE_ID));
        assertEquals(1, usedCounts.get(codes.get(0).getId()), "数据库使用次数必须 +1");
        assertNull(service.resolveInviter(code), "次数用尽后邀请码失效");
        assertFalse(service.bindOnRegister(code, 22L, UserRoles.BUYER_ROLE_ID));
        assertEquals(1, relations.size());
    }

    @Test
    void unlimitedUsesKeepsCodeUsable() {
        Map<String, Object> link = service.currentLink(buyer(7L));
        String code = String.valueOf(link.get("code"));
        assertEquals(0, link.get("maxUses"));
        assertEquals(-1, link.get("remainingUses"), "-1 表示不限次数");

        assertTrue(service.bindOnRegister(code, 21L, UserRoles.BUYER_ROLE_ID));
        assertTrue(service.bindOnRegister(code, 22L, UserRoles.BUYER_ROLE_ID));
        assertEquals(7L, service.resolveInviter(code));
    }

    /** 并发抢占同一 maxUses=1 的码：只有第一个成功，第二个必须被额度挡下（不超发）。 */
    @Test
    void concurrentUseNeverExceedsMaxUses() {
        String code = String.valueOf(service.rotate(buyer(7L), 1).get("code"));

        // 直接驱动底层原子消耗：模拟两个并发请求
        InviteCode target = codes.get(0);
        assertEquals(1, codeMapper.consumeUse(target.getId(), LocalDateTime.now()), "第一个请求应占用成功");
        assertEquals(0, codeMapper.consumeUse(target.getId(), LocalDateTime.now()), "第二个请求必须被额度挡下");
        assertEquals(1, usedCounts.get(target.getId()), "实际使用次数不得超发");
        assertEquals(2, target.getStatus(), "达到上限后码必须自动失效");
        assertNull(service.resolveInviter(code));
    }

    /** 入驻邀约码：申请时记 pending，审核通过后置 bound 并记录商户角色。 */
    @Test
    void merchantApplyBindsPendingThenMarksBoundOnApprove() {
        String raw = String.valueOf(service.issueMerchantApplyCode(admin(3L), 3, false).get("code"));

        assertTrue(service.bindMerchantApply(raw, 42L));
        InviteRelation pending = relations.get(0);
        assertEquals("merchant_apply", pending.getInviteType());
        assertEquals("admin_console", pending.getScene());
        assertEquals("pending", pending.getStatus(), "入驻未通过前必须是 pending");
        assertNull(pending.getBoundAt());

        service.markMerchantBound(42L);
        assertEquals("bound", pending.getStatus(), "审核通过后必须置 bound");
        assertEquals(UserRoles.MERCHANT_ROLE_ID, pending.getGrantedRoleId());
        assertNotNull(pending.getBoundAt());
    }

    /** 入驻码同样受使用次数限制。 */
    @Test
    void merchantApplyCodeRespectsMaxUses() {
        String raw = String.valueOf(service.issueMerchantApplyCode(admin(3L), 1, false).get("code"));
        assertTrue(service.bindMerchantApply(raw, 42L));
        assertNull(service.verifyMerchantApplyCode(raw), "用尽后入驻码失效");
        assertFalse(service.bindMerchantApply(raw, 43L));
    }

    /**
     * 已通过「注册邀请」进来的用户，仍可被运营「入驻邀约」——两条链路互不阻塞。
     *
     * <p>回归缺陷：唯一键若只按 invitee_id，先被邀请注册的用户会被判定"已绑定"，
     * 导致运营邀约入驻时关系写不进去、次数也不消耗。
     */
    @Test
    void registerInviteAndMerchantApplyCoexistForSameUser() {
        String buyerCode = String.valueOf(service.currentLink(buyer(7L)).get("code"));
        assertTrue(service.bindOnRegister(buyerCode, 21L, UserRoles.BUYER_ROLE_ID),
                "先通过注册邀请绑定");

        String applyCode = String.valueOf(service.issueMerchantApplyCode(admin(3L), 3, false).get("code"));
        assertTrue(service.bindMerchantApply(applyCode, 21L),
                "同一用户仍可被运营邀约入驻（不同 invite_type）");

        assertEquals(2, relations.size(), "应同时存在注册邀请与入驻邀约两条关系");
        assertTrue(relations.stream().anyMatch(r -> "buyer".equals(r.getInviteType())));
        assertTrue(relations.stream().anyMatch(r -> "merchant_apply".equals(r.getInviteType())));

        // 入驻审核通过：只把 merchant_apply 那条置 bound，注册邀请关系保持原样
        service.markMerchantBound(21L);
        InviteRelation apply = relations.stream()
                .filter(r -> "merchant_apply".equals(r.getInviteType())).findFirst().orElseThrow();
        InviteRelation register = relations.stream()
                .filter(r -> "buyer".equals(r.getInviteType())).findFirst().orElseThrow();
        assertEquals("bound", apply.getStatus());
        assertEquals(UserRoles.MERCHANT_ROLE_ID, apply.getGrantedRoleId());
        assertEquals("bound", register.getStatus());
        assertEquals(UserRoles.BUYER_ROLE_ID, register.getGrantedRoleId());
    }

    /** 重复调用 issueMerchantApplyCode 默认复用同一个有效码。 */
    @Test
    void issueMerchantApplyCodeIsIdempotentWithoutRotate() {
        String first = String.valueOf(service.issueMerchantApplyCode(admin(3L), null, false).get("code"));
        String second = String.valueOf(service.issueMerchantApplyCode(admin(3L), null, false).get("code"));
        assertEquals(first, second);

        String rotated = String.valueOf(service.issueMerchantApplyCode(admin(3L), null, true).get("code"));
        assertNotEquals(first, rotated, "rotate=true 必须换新码");
        assertNull(service.verifyMerchantApplyCode(first), "旧入驻码必须立即失效");
    }

    // ==================== 解析 / 篡改 / 过期 ====================

    @Test
    void tamperedCodeResolvesToNull() {
        service.currentLink(buyer(7L));
        assertNull(service.resolveInviter("INVAAAAAAAAAAAA"));
        assertNull(service.resolveInviter(""));
        assertNull(service.resolveInviter(null));
        assertNull(service.resolveInviteType("INVAAAAAAAAAAAA"));
    }

    @Test
    void expiredCodeIsRejectedAndRegenerated() {
        Map<String, Object> link = service.currentLink(buyer(7L));
        String expired = String.valueOf(link.get("code"));
        codes.get(0).setExpiresAt(LocalDateTime.now().minusSeconds(1));

        assertNull(service.resolveInviter(expired), "过期码不得解析出邀请人");
        assertFalse(service.bindOnRegister(expired, 99L, UserRoles.BUYER_ROLE_ID), "过期码不得绑定");
        assertEquals(UserRoles.BUYER_ROLE_ID, service.roleIdForRegister(expired), "过期码回退 buyer");

        Map<String, Object> fresh = service.currentLink(buyer(7L));
        assertNotEquals(expired, fresh.get("code"), "过期后应自动换新码");
    }

    /**
     * 读码时尚未过期、扣次前刚好过期：扣次 SQL 的 expires_at 条件必须原子拦截。
     *
     * <p>回归缺陷：consumeUse 只校验 status/max_uses，读码与扣次之间的过期窗口
     * 会让过期码继续消耗额度并写出邀请关系。
     */
    @Test
    void codeExpiringBetweenReadAndConsumeIsRejected() {
        String code = String.valueOf(service.currentLink(buyer(7L)).get("code"));
        InviteCode target = codes.get(0);
        // 在 usableCode 读取之后、consumeUse 之前把过期时间改到过去
        when(relationMapper.countByInviteeAndType(anyLong(), anyString())).thenAnswer(inv -> {
            target.setExpiresAt(LocalDateTime.now().minusSeconds(1));
            return 0;
        });

        assertFalse(service.bindOnRegister(code, 21L, UserRoles.BUYER_ROLE_ID),
                "扣次阶段发现过期必须拒绝绑定");
        assertTrue(relations.isEmpty(), "过期码不得写入邀请关系");
        assertEquals(0, usedCounts.get(target.getId()), "过期码不得消耗使用次数");
    }

    // ==================== 注册绑定 ====================

    @Test
    void bindOnRegisterLinksInviteeOnce() {
        String code = String.valueOf(service.currentLink(buyer(7L)).get("code"));

        assertTrue(service.bindOnRegister(code, 21L, UserRoles.BUYER_ROLE_ID));
        assertEquals(1, relations.size());
        InviteRelation r = relations.get(0);
        assertEquals(7L, r.getInviterId());
        assertEquals(21L, r.getInviteeId());
        assertEquals("buyer", r.getInviteType());
        assertEquals(UserRoles.BUYER_ROLE_ID, r.getGrantedRoleId());
        assertEquals("bound", r.getStatus());
        assertNotNull(r.getBoundAt());
    }

    @Test
    void bindOnRegisterRejectsSelfInviteUnknownAndDuplicate() {
        String code = String.valueOf(service.currentLink(buyer(7L)).get("code"));

        assertFalse(service.bindOnRegister(code, 7L, UserRoles.BUYER_ROLE_ID), "自邀自必须拒绝");
        assertFalse(service.bindOnRegister("INVUNKNOWN000", 21L, UserRoles.BUYER_ROLE_ID), "未知码必须拒绝");
        assertFalse(service.bindOnRegister(null, 21L, UserRoles.BUYER_ROLE_ID));
        assertFalse(service.bindOnRegister("", 21L, UserRoles.BUYER_ROLE_ID));
        assertEquals(0, relations.size());

        assertTrue(service.bindOnRegister(code, 21L, UserRoles.BUYER_ROLE_ID));
        assertFalse(service.bindOnRegister(code, 21L, UserRoles.BUYER_ROLE_ID), "同一被邀请人重复绑定必须拒绝");
        assertEquals(1, relations.size());
    }

    /** 注册绑定失败绝不能抛出：邀请码填错不应阻断注册主链路。 */
    @Test
    void bindFailureNeverThrows() {
        when(codeMapper.selectOne(any())).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> service.bindOnRegister("INVANY", 21L, UserRoles.BUYER_ROLE_ID));
        assertFalse(service.bindOnRegister("INVANY", 21L, UserRoles.BUYER_ROLE_ID));
    }

    // ==================== 统计 ====================

    @Test
    void statsCountsBoundAndMasksNickname() {
        String code = String.valueOf(service.currentLink(buyer(7L)).get("code"));
        service.bindOnRegister(code, 21L, UserRoles.BUYER_ROLE_ID);
        service.bindOnRegister(code, 22L, UserRoles.BUYER_ROLE_ID);
        when(userMapper.selectById(21L)).thenReturn(user(21L, UserRoles.BUYER_ROLE_ID, "张三丰"));
        when(userMapper.selectById(22L)).thenReturn(user(22L, UserRoles.BUYER_ROLE_ID, "李"));

        Map<String, Object> stats = service.stats(buyer(7L));

        assertEquals(2, stats.get("invited"));
        assertEquals(2, stats.get("bound"));
        assertEquals(0, stats.get("pending"));
        assertEquals(0, stats.get("merchantBound"), "普通注册邀请不应产生商户绑定");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recent = (List<Map<String, Object>>) stats.get("recent");
        assertEquals(2, recent.size());
        String nickname = String.valueOf(recent.get(0).get("inviteeNickname"));
        assertTrue(nickname.startsWith("张"), nickname);
        assertTrue(nickname.contains("*"), "昵称必须脱敏: " + nickname);
        assertFalse(nickname.contains("三丰"), "不得泄露完整昵称: " + nickname);
    }

    @Test
    void statsIsEmptyForNewInviter() {
        Map<String, Object> stats = service.stats(buyer(999L));
        assertEquals(0, stats.get("invited"));
        assertEquals(0, stats.get("bound"));
        assertTrue(((List<?>) stats.get("recent")).isEmpty());
    }

    /** 展示用角色编码必须与分配规则一致（不依赖角色字典查询）。 */
    @Test
    void grantedRoleCodeMatchesAssignmentRule() {
        // 前台邀请：即使发起人是运营，被邀请人也是买家
        assertEquals("buyer", service.currentLink(buyer(7L)).get("grantedRoleCode"));
        assertEquals("buyer", service.currentLink(admin(3L)).get("grantedRoleCode"));
        // 运营后台入驻邀约：目标身份是商户，但需要审核
        assertEquals("merchant",
                service.issueMerchantApplyCode(admin(3L), null, false).get("grantedRoleCode"));
    }
}




