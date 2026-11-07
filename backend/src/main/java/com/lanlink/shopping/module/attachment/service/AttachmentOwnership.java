package com.lanlink.shopping.module.attachment.service;

import java.util.List;

/**
 * 绑定目标的归属解析。
 *
 * <p>存在理由：附件行只证明"是谁上传的"，不证明"他要挂上去的那个业务对象是他的"。
 * 缺了这一环，任何登录用户都能把材料投到别人的 merId/expertId 上，污染其审核台。
 * 实现方只需声明自己支持哪些业务域；未被任何实现支持的业务域一律拒绝自助绑定（失败关闭），
 * 因为库里没有归属人列就无法证明归属，默认放过的风险更大。
 */
public interface AttachmentOwnership {

    boolean supports(AttachmentBizType type);

    boolean owns(AttachmentBizType type, Long bizId, Long userId);

    /** 没有任何实现支持该业务域。 */
    static boolean unsupportedByAll(List<AttachmentOwnership> resolvers, AttachmentBizType type) {
        return resolvers.stream().noneMatch(r -> r.supports(type));
    }
}
