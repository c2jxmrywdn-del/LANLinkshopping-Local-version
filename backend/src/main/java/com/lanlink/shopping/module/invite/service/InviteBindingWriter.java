package com.lanlink.shopping.module.invite.service;

import com.lanlink.shopping.module.invite.entity.InviteCode;
import com.lanlink.shopping.module.invite.entity.InviteRelation;
import com.lanlink.shopping.module.invite.mapper.InviteCodeMapper;
import com.lanlink.shopping.module.invite.mapper.InviteRelationMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 邀请绑定的原子写入器：额度扣减与关系写入必须同事务，失败一起回滚。
 *
 * <p>拆成独立 Bean 是为了让 {@code @Transactional} 经过 Spring 代理生效：
 * {@link InviteService} 内部自调用不会触发事务拦截器。
 */
@Component
public class InviteBindingWriter {

    private final InviteCodeMapper codeMapper;
    private final InviteRelationMapper relationMapper;

    public InviteBindingWriter(InviteCodeMapper codeMapper, InviteRelationMapper relationMapper) {
        this.codeMapper = codeMapper;
        this.relationMapper = relationMapper;
    }

    /**
     * 原子占用一次额度并写入邀请关系。
     *
     * <p>扣次失败（额度用尽/已过期/已失效）直接返回 false，不写关系。
     * 关系写入抛出的 RuntimeException 自然向外传播，由事务回滚本次扣次。
     *
     * @param merchantApply true=商家入驻邀约（pending，审核通过后 bound）；false=注册邀请（bound）
     * @return true=扣次与关系写入均成功；false=额度未占用，无任何写操作
     */
    @Transactional
    public boolean consumeAndInsert(InviteCode code, Long inviterId, Long inviteeId,
                                    Long grantedRoleId, boolean merchantApply) {
        LocalDateTime now = LocalDateTime.now();
        if (codeMapper.consumeUse(code.getId(), now) == 0) {
            return false;
        }
        InviteRelation relation = new InviteRelation();
        relation.setInviteCode(code.getCode());
        relation.setInviterId(inviterId);
        relation.setInviteeId(inviteeId);
        relation.setInviteType(code.getInviteType());
        relation.setScene(code.getScene());
        relation.setGrantedRoleId(grantedRoleId);
        relation.setStatus(merchantApply ? "pending" : "bound");
        relation.setBoundAt(merchantApply ? null : now);
        relation.setCreatedAt(now);
        relationMapper.insert(relation);
        return true;
    }
}
