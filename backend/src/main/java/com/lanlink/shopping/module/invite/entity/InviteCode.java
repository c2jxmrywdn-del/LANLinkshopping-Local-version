package com.lanlink.shopping.module.invite.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邀请码（邀请人 → 专属链接/二维码）。
 *
 * <p>一个用户同时最多只有一个 {@code status=1} 的有效码；重新生成会把旧码置为 2（已失效），
 * 使旧链接立即不可用（满足「不可篡改 + 支持有效期」）。
 */
@Data
@TableName("t_invite_code")
public class InviteCode {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 邀请码明文（大写 Base32 子集，去易混字符），全局唯一。 */
    private String code;

    /** 邀请人用户 ID。 */
    private Long inviterId;

    /**
     * 邀请用途类型。
     *
     * <ul>
     *   <li>{@code buyer}：前台注册邀请 → 被邀请人注册后为买家（buyer）</li>
     *   <li>{@code merchant_apply}：运营后台商家入驻邀请码 → 在入驻申请里作为邀约码使用，
     *       审核通过后才获得商户身份（不走注册接口）</li>
     * </ul>
     */
    private String inviteType;

    /** 生成场景：{@code frontend} 前台 / {@code admin_console} 运营后台。 */
    private String scene;

    /** 邀请人发起时的角色 ID（审计/排查用，不参与身份分配决策）。 */
    private Long inviterRoleId;

    /** 1=有效 2=已失效（重新生成/手动撤销） */
    private Integer status;

    /** 已使用次数（注册成功即 +1）。 */
    private Integer usedCount;

    /** 最大使用次数；{@code 0} 表示不限次数。 */
    private Integer maxUses;

    /** 过期时间；null 表示永不过期（仅在配置 expiresDays<=0 时出现）。 */
    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
