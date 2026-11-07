package com.lanlink.shopping.module.membership.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.UserIdentity;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.membership.constant.MemberRule;
import com.lanlink.shopping.module.membership.entity.MemberCard;
import com.lanlink.shopping.module.membership.entity.MemberLevel;
import com.lanlink.shopping.module.membership.mapper.MemberCardMapper;
import com.lanlink.shopping.module.membership.mapper.MemberLevelMapper;
import com.lanlink.shopping.module.membership.mapper.MemberPointLogMapper;
import com.lanlink.shopping.service.IdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * D2 双倍积分统一判据 + P1-7 会员价试算单测。
 * D2：multiplierOf = 身份 VIP ∪ 有效订阅（两通道同源）；商家/管理员身份不参与倍数。
 * P1-7：price-preview 按当前成长值「可达档位」（非最高档）返回立省金额；普通会员档（1.000）→ applicable=false。
 */
class MembershipMultiplierAndPreviewTest {

    private MemberCardMapper cardMapper;
    private MemberLevelMapper levelMapper;
    private MemberPointLogMapper logMapper;
    private SubscriptionService subscriptionService;
    private IdentityService identityService;
    private ProductMapper productMapper;
    private MembershipService service;

    @BeforeEach
    void setUp() {
        cardMapper = mock(MemberCardMapper.class);
        levelMapper = mock(MemberLevelMapper.class);
        logMapper = mock(MemberPointLogMapper.class);
        subscriptionService = mock(SubscriptionService.class);
        service = new MembershipService(cardMapper, levelMapper, logMapper, subscriptionService);
        identityService = mock(IdentityService.class);
        service.setIdentityService(identityService);
        productMapper = mock(ProductMapper.class);
        service.setProductMapper(productMapper);
        when(cardMapper.selectOne(any())).thenReturn(card(0));
    }

    private MemberCard card(int growth) {
        MemberCard c = new MemberCard();
        c.setUserId(7L);
        c.setLevelId(1L);
        c.setGrowth(growth);
        c.setPoints(0);
        return c;
    }

    // ===== D2 multiplierOf：身份 VIP ∪ 有效订阅 =====

    @Test
    void identityVipGetsDoublePoints() {
        when(identityService.identify(7L, 1L)).thenReturn(UserIdentity.VIP);
        when(subscriptionService.hasActiveSubscription(7L)).thenReturn(false);
        assertEquals(MemberRule.VIP_EARN_MULTIPLIER, service.multiplierOf(7L, 1L), "身份 VIP → ×2");
    }

    @Test
    void activeSubscriptionAloneGetsDoublePoints() {
        when(identityService.identify(7L, 1L)).thenReturn(UserIdentity.BUYER);
        when(subscriptionService.hasActiveSubscription(7L)).thenReturn(true);
        assertEquals(MemberRule.VIP_EARN_MULTIPLIER, service.multiplierOf(7L, 1L), "非身份 VIP 但持有效订阅 → ×2（D2 取或）");
    }

    @Test
    void plainBuyerGetsSinglePoints() {
        when(identityService.identify(7L, 1L)).thenReturn(UserIdentity.BUYER);
        when(subscriptionService.hasActiveSubscription(7L)).thenReturn(false);
        assertEquals(1, service.multiplierOf(7L, 1L), "既非身份 VIP 也无订阅 → ×1");
    }

    @Test
    void merchantAndAdminNeverGetMultiplier() {
        when(subscriptionService.hasActiveSubscription(7L)).thenReturn(true);
        assertEquals(1, service.multiplierOf(7L, 2L), "商家身份不参与倍数（口径与 identify 一致）");
        assertEquals(1, service.multiplierOf(7L, 3L), "管理员身份不参与倍数");
    }

    // ===== P1-7 price-preview =====

    private Product product(String price) {
        Product p = new Product();
        p.setProdId(101L);
        p.setTitle("螺纹钢 HRB400");
        p.setPrice(new BigDecimal(price));
        return p;
    }

    @Test
    void pricePreviewUsesReachableTierNotHighestTier() {
        // 成长值 60 万 → 可达白银（0.990），而非黑钻 0.900（按最高档试算会系统性高估）
        when(levelMapper.selectList(any())).thenReturn(List.of(
                level(6L, "黑钻会员", 20000000, new BigDecimal("0.900")),
                level(2L, "白银会员", 500000, new BigDecimal("0.990")),
                level(1L, "普通会员", 0, new BigDecimal("1.000"))));
        when(cardMapper.selectOne(any())).thenReturn(card(600000));
        when(productMapper.selectById(101L)).thenReturn(product("1000"));
        when(subscriptionService.hasActiveSubscription(7L)).thenReturn(false);

        var out = service.pricePreview(7L, 101L);

        assertEquals(Boolean.TRUE, out.get("applicable"));
        assertEquals("白银会员", out.get("levelName"));
        assertEquals(0, new BigDecimal("0.990").compareTo((BigDecimal) out.get("discountRate")));
        // 立省 = 1000 × (1 - 0.990) = 10.00 元（非黑钻口径的 100 元）
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) out.get("saving")));
    }

    @Test
    void pricePreviewFallsBackToWholeSentenceBelowFirstDiscountTier() {
        // 成长值 0 → 可达档仍为普通会员（1.000）→ applicable=false，前端回退无金额整句
        when(levelMapper.selectList(any())).thenReturn(List.of(level(1L, "普通会员", 0, new BigDecimal("1.000"))));
        when(productMapper.selectById(101L)).thenReturn(product("1000"));

        var out = service.pricePreview(7L, 101L);

        assertEquals(Boolean.FALSE, out.get("applicable"));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out.get("saving")));
    }

    @Test
    void pricePreviewRejectsUnknownProduct() {
        when(productMapper.selectById(404L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.pricePreview(7L, 404L));
    }

    private MemberLevel level(Long id, String name, int minGrowth, BigDecimal rate) {
        MemberLevel l = new MemberLevel();
        l.setLevelId(id);
        l.setLevelName(name);
        l.setMinGrowth(minGrowth);
        l.setDiscountRate(rate);
        return l;
    }
}
