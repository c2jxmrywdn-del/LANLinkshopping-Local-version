package com.lanlink.shopping.module.invite.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.invite.entity.InviteRelation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 邀请关系 Mapper。
 *
 * <p>{@code t_invite_relation} 上有 {@code (invitee_id, invite_type)} 唯一键，因此
 * "一个被邀请人在同一种邀请类型下只绑定一次"由数据库兜底；这里的查询用于应用层
 * 提前给出友好结果（避免靠异常驱动流程）。
 */
public interface InviteRelationMapper extends BaseMapper<InviteRelation> {

    /**
     * 某被邀请人在指定邀请类型下是否已有关系（0/1）。
     *
     * <p>按 {@code invite_type} 隔离：「注册邀请(buyer)」与「商家入驻邀约(merchant_apply)」
     * 是两条独立链路，同一用户可以先被邀请注册、之后又被运营邀约入驻。
     */
    @Select("""
            SELECT COUNT(*) FROM t_invite_relation
             WHERE invitee_id = #{inviteeId} AND invite_type = #{inviteType}
            """)
    int countByInviteeAndType(@Param("inviteeId") Long inviteeId,
                              @Param("inviteType") String inviteType);

    /** 取某被邀请人在指定类型下的邀请关系（最多一条）。 */
    @Select("""
            SELECT id, invite_code, inviter_id, invitee_id, invite_type, scene,
                   granted_role_id, status, bound_at, created_at
              FROM t_invite_relation
             WHERE invitee_id = #{inviteeId} AND invite_type = #{inviteType}
             LIMIT 1
            """)
    InviteRelation selectByInviteeAndType(@Param("inviteeId") Long inviteeId,
                                          @Param("inviteType") String inviteType);

    /** 某邀请人名下的邀请关系（按绑定时间倒序）。 */
    @Select("""
            SELECT id, invite_code, inviter_id, invitee_id, invite_type, scene,
                   granted_role_id, status, bound_at, created_at
              FROM t_invite_relation
             WHERE inviter_id = #{inviterId}
             ORDER BY COALESCE(bound_at, created_at) DESC
             LIMIT #{limit}
            """)
    List<InviteRelation> selectByInviter(@Param("inviterId") Long inviterId, @Param("limit") int limit);
}
