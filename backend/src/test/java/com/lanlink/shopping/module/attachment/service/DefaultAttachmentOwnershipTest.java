package com.lanlink.shopping.module.attachment.service;

import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.module.techservice.mapper.TechAppraisalMapper;
import com.lanlink.shopping.module.techservice.mapper.TechExpertMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 归属解析的行为锁。
 *
 * <p>说明：这三条是实现之后补写的表征测试，不是红先行；它们锁定"查哪张表、按哪个字段判、
 * 查不到就拒"的口径。端到端的越权拒绝仍需在真机上用别人的 merId 实际打一次才能定论。
 */
class DefaultAttachmentOwnershipTest {

    private MerchantMapper merchantMapper;
    private DefaultAttachmentOwnership ownership;

    @BeforeEach
    void setUp() {
        merchantMapper = mock(MerchantMapper.class);
        ownership = new DefaultAttachmentOwnership(merchantMapper,
                mock(TechExpertMapper.class), mock(TechAppraisalMapper.class));
    }

    @Test
    void merchantTargetIsOwnedOnlyByItsAccountHolder() {
        Merchant merchant = new Merchant();
        merchant.setMerId(55L);
        merchant.setUserId(1001L);
        when(merchantMapper.selectById(55L)).thenReturn(merchant);

        assertTrue(ownership.owns(AttachmentBizType.MERCHANT_LICENSE, 55L, 1001L));
        assertFalse(ownership.owns(AttachmentBizType.MERCHANT_LICENSE, 55L, 2002L));
    }

    @Test
    void missingMerchantTargetIsDeniedRatherThanAllowed() {
        when(merchantMapper.selectById(any())).thenReturn(null);

        assertFalse(ownership.owns(AttachmentBizType.MERCHANT_LICENSE, 999L, 1001L));
    }

    @Test
    void domainsWithoutOwnerColumnAreNotSupportedAtAll() {
        // 机构与交易表没有 user_id 列：与其假装校验通过，不如声明不支持，让上层失败关闭
        assertFalse(ownership.supports(AttachmentBizType.TECH_ORGANIZATION));
        assertFalse(ownership.supports(AttachmentBizType.TECH_DEAL_DOCUMENT));
        assertTrue(ownership.supports(AttachmentBizType.TECH_EXPERT_PROFILE));

        assertFalse(ownership.owns(AttachmentBizType.MERCHANT_TAX_PROOF, 55L, null));
        verify(merchantMapper, never()).selectById(null);
    }
}
