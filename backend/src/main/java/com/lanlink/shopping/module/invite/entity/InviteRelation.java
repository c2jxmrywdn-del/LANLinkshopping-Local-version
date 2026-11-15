package com.lanlink.shopping.module.invite.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邀请关系（{@code (invitee_id, invite_type)} 唯一：同一用户每种邀请类型只能绑定一次）。
 *
 * <p>绑定时机：注册成功后由 {@code InviteService.bindOnRegister} 写入；
 * 商家入驻申请时由 {@code InviteService.bindMerchantApply} 写入 pending 关系。
 * 两条链路互不阻塞：先被邀请注册的用户仍可再被运营邀约入驻。
 */
@Data
@TableName("t_invite_relation")
public class InviteRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String inviteCode;

    private Long inviterId;

    private Long inviteeId;

    /** 邀请来源类型（buyer/merchant），记录注册时邀请码声明的目标身份。 */
    private String inviteType;

    /** 邀请场景（frontend 前台注册 / admin_console 运营后台入驻）。 */
    private String scene;

    /** 被邀请人注册后实际分配的角色 ID（审计：与 inviteType 期望值对照，防身份分配错误）。 */
    private Long grantedRoleId;

    /**
     * 关系状态。
     *
     * <ul>
     *   <li>{@code bound}：注册邀请已生效 / 入驻邀约审核通过；</li>
     *   <li>{@code pending}：入驻申请已提交、等待审核。</li>
     * </ul>
     */
    private String status;

    private LocalDateTime boundAt;

    private LocalDateTime createdAt;
}
