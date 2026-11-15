package com.lanlink.shopping.module.invite.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.invite.entity.InviteCode;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 邀请码 Mapper。
 *
 * <p>除 BaseMapper 的通用 CRUD 外，这里把「按码查、按用户+类型+场景查、原子消耗额度」
 * 写成显式 SQL：语义一目了然，也便于单测按参数精确断言（不依赖 Lambda 包装器的懒加载行为）。
 */
public interface InviteCodeMapper extends BaseMapper<InviteCode> {

    /** 按邀请码精确查询（大小写不敏感由调用方归一为大写）。 */
    @Select("""
            SELECT id, code, inviter_id, invite_type, scene, inviter_role_id,
                   status, used_count, max_uses, expires_at, created_at, updated_at
              FROM t_invite_code
             WHERE code = #{code}
             LIMIT 1
            """)
    InviteCode selectByCode(@Param("code") String code);

    /** 取某用户在指定类型+场景下最新的有效码（status=1）。 */
    @Select("""
            SELECT id, code, inviter_id, invite_type, scene, inviter_role_id,
                   status, used_count, max_uses, expires_at, created_at, updated_at
              FROM t_invite_code
             WHERE inviter_id = #{inviterId}
               AND invite_type = #{inviteType}
               AND scene = #{scene}
               AND status = 1
             ORDER BY id DESC
             LIMIT 1
            """)
    InviteCode selectActive(@Param("inviterId") Long inviterId,
                            @Param("inviteType") String inviteType,
                            @Param("scene") String scene);

    /** 某用户在某类型+场景下的全部有效码（重新生成时批量作废）。 */
    @Select("""
            SELECT id FROM t_invite_code
             WHERE inviter_id = #{inviterId}
               AND invite_type = #{inviteType}
               AND scene = #{scene}
               AND status = 1
            """)
    List<Long> selectActiveIds(@Param("inviterId") Long inviterId,
                               @Param("inviteType") String inviteType,
                               @Param("scene") String scene);

    /**
     * 原子消耗一次使用额度。
     *
     * <p>条件带 {@code max_uses = 0 OR used_count < max_uses}：并发下只有一个请求能更新成功，
     * 避免"先读后写"导致的超发（两个请求都读到 used=0、都写 used=1，实际发了 2 个名额）。
     * 同时校验 {@code expires_at}：读取码与扣次之间发生的过期竞态在这里被原子拦截。
     * 达到上限时同一条语句把 status 置 2，不会出现"次数满了但码还有效"的中间态。
     *
     * @return 1=占用成功；0=额度已用尽/码已失效/已过期
     */
    @Update("""
            UPDATE t_invite_code
               SET used_count = used_count + 1,
                   status = CASE WHEN max_uses > 0 AND used_count + 1 >= max_uses THEN 2 ELSE status END,
                   updated_at = NOW()
             WHERE id = #{id}
               AND status = 1
               AND (max_uses = 0 OR used_count < max_uses)
               AND (expires_at IS NULL OR expires_at > #{now})
            """)
    int consumeUse(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 按 id 置为失效（status=2），只改状态与时间，不覆盖其它字段。 */
    @Update("UPDATE t_invite_code SET status = 2, updated_at = NOW() WHERE id = #{id} AND status = 1")
    int revokeById(@Param("id") Long id);
}
