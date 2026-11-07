package com.lanlink.shopping.integration.pricing;

import com.lanlink.shopping.entity.User;

/**
 * 会员折扣资格判定（共用静态工具，防口径漂移）。
 *
 * 背景：下单（OrderService.checkout）与下单前促销预览（PricingPreviewService）必须对
 * 「谁享有会员等级折扣」使用同一判定，否则预览展示与订单实付不一致。
 * 口径：仅采购方身份（roleId=1）享有会员等级折扣；商户/管理员不参与
 * （与 PricingFacade.quote 的 memberEligible 参数语义一致）。
 */
public final class MemberEligibility {

    private MemberEligibility() {
    }

    /**
     * 是否享有会员等级折扣：采购方（roleId=1）为真；商户/管理员/空用户为假。
     * 下单与预览两条路径唯一判定入口，禁止在调用方各自内联 roleId 判断。
     */
    public static boolean isMemberEligible(User user) {
        return user != null && user.getRoleId() != null && user.getRoleId() == 1L;
    }
}
