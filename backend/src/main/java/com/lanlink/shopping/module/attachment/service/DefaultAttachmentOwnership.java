package com.lanlink.shopping.module.attachment.service;

import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.module.techservice.entity.TechAppraisal;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.mapper.TechAppraisalMapper;
import com.lanlink.shopping.module.techservice.mapper.TechExpertMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 以业务表归属人列为准的归属解析。
 *
 * <p>只覆盖库里真有 user_id 列的业务域：商户三证走 t_merchant.user_id，专家档案与评价材料
 * 走各自实体的 userId。机构（t_tech_organization）与交易（t_tech_deal）没有归属人列，
 * 这里刻意不支持，交给失败关闭处理——运营需要时以 admin 身份直接绑定。
 */
@Component
public class DefaultAttachmentOwnership implements AttachmentOwnership {

    private final MerchantMapper merchantMapper;
    private final TechExpertMapper expertMapper;
    private final TechAppraisalMapper appraisalMapper;

    public DefaultAttachmentOwnership(MerchantMapper merchantMapper,
                                      TechExpertMapper expertMapper,
                                      TechAppraisalMapper appraisalMapper) {
        this.merchantMapper = merchantMapper;
        this.expertMapper = expertMapper;
        this.appraisalMapper = appraisalMapper;
    }

    @Override
    public boolean supports(AttachmentBizType type) {
        return switch (type) {
            case MERCHANT_LICENSE, MERCHANT_TAX_PROOF, MERCHANT_SPECIAL_PERMIT,
                    TECH_EXPERT_PROFILE, TECH_APPRAISAL_MATERIAL -> true;
            default -> false;
        };
    }

    @Override
    public boolean owns(AttachmentBizType type, Long bizId, Long userId) {
        if (bizId == null || userId == null) {
            return false;
        }
        return switch (type) {
            case MERCHANT_LICENSE, MERCHANT_TAX_PROOF, MERCHANT_SPECIAL_PERMIT -> {
                Merchant merchant = merchantMapper.selectById(bizId);
                yield merchant != null && Objects.equals(merchant.getUserId(), userId);
            }
            case TECH_EXPERT_PROFILE -> {
                TechExpert expert = expertMapper.selectById(bizId);
                yield expert != null && Objects.equals(expert.getUserId(), userId);
            }
            case TECH_APPRAISAL_MATERIAL -> {
                TechAppraisal appraisal = appraisalMapper.selectById(bizId);
                yield appraisal != null && Objects.equals(appraisal.getUserId(), userId);
            }
            default -> false;
        };
    }
}
