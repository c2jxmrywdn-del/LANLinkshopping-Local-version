package com.lanlink.shopping.service;

import com.lanlink.shopping.entity.Enterprise;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.EnterpriseMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 特邀标识回填：商品列表/详情与店铺卡片两条出口。
 *
 * <p>关键点：
 * <ul>
 *   <li>只有「已标记 + 已过审 + 未关店」的商品才带 platformInvited=true；</li>
 *   <li>未标记商户的商品 platformInvited 必须为 null（JSON 不输出，前端不渲染）；</li>
 *   <li>店铺卡片只暴露公开字段，不含敏感信息。</li>
 * </ul>
 */
class PlatformInvitedBadgeAttachTest {

    private MerchantMapper merchantMapper;
    private EnterpriseMapper enterpriseMapper;
    private MerchantService merchantService;

    @BeforeEach
    void setUp() {
        merchantMapper = mock(MerchantMapper.class);
        enterpriseMapper = mock(EnterpriseMapper.class);
        merchantService = new MerchantService(mock(com.lanlink.shopping.mapper.MerchantMapper.class),
                enterpriseMapper, mock(com.lanlink.shopping.mapper.QualificationMapper.class),
                mock(AuditService.class), mock(com.lanlink.shopping.mapper.UserMapper.class));
        // 用独立 mapper 覆盖，避免构造器里的 mock 干扰
        org.springframework.test.util.ReflectionTestUtils.setField(merchantService, "merchantMapper", merchantMapper);
    }

    private Merchant merchant(long id, Integer reviewStatus, Integer platformInvited, String exitStatus) {
        Merchant m = new Merchant();
        m.setMerId(id);
        m.setEntId(id * 10);
        m.setReviewStatus(reviewStatus);
        m.setPlatformInvited(platformInvited);
        m.setExitStatus(exitStatus);
        return m;
    }

    private Product product(long prodId, long merId) {
        Product p = new Product();
        p.setProdId(prodId);
        p.setMerId(merId);
        return p;
    }

    @Test
    void attachSetsTrueOnlyForMarkedApprovedOpenMerchant() {
        when(merchantMapper.selectBatchIds(any())).thenReturn(List.of(
                merchant(1L, 1, 1, null),
                merchant(2L, 1, 0, null),
                merchant(3L, 0, 1, null),
                merchant(4L, 1, 1, "CLOSED")));
        List<Product> products = List.of(product(100L, 1L), product(101L, 2L),
                product(102L, 3L), product(103L, 4L));

        merchantService.attachPlatformInvitedBadge(products);

        assertEquals(Boolean.TRUE, products.get(0).getPlatformInvited(), "已标记+过审+营业 → 展示");
        assertNull(products.get(1).getPlatformInvited(), "未标记 → 不展示");
        assertNull(products.get(2).getPlatformInvited(), "未过审 → 不展示");
        assertNull(products.get(3).getPlatformInvited(), "已关店 → 不展示");
    }

    @Test
    void attachIsNoopForEmptyOrNullInput() {
        merchantService.attachPlatformInvitedBadge(null);
        merchantService.attachPlatformInvitedBadge(List.of());
        verifyNoInteractions(merchantMapper);
    }

    @Test
    void publicShopCardsExposeOnlyPublicFields() {
        when(merchantMapper.selectBatchIds(any())).thenReturn(List.of(
                merchant(1L, 1, 1, null),
                merchant(2L, 1, 0, null)));
        Enterprise ent = new Enterprise();
        ent.setEntId(10L);
        ent.setName("特邀建材有限公司");
        Enterprise ent2 = new Enterprise();
        ent2.setEntId(20L);
        ent2.setName("普通商户有限公司");
        when(enterpriseMapper.selectBatchIds(any())).thenReturn(List.of(ent, ent2));

        Map<Long, Map<String, Object>> cards = merchantService.publicShopCards(List.of(1L, 2L));

        assertEquals(2, cards.size());
        assertEquals("特邀建材有限公司", cards.get(1L).get("shopName"));
        assertEquals(Boolean.TRUE, cards.get(1L).get("platformInvited"));
        assertNull(cards.get(2L).get("platformInvited"), "普通商户不得带标识");
        // 只暴露公开字段
        assertEquals(java.util.Set.of("merId", "shopName", "entName", "platformInvited"),
                cards.get(1L).keySet());
    }

    @Test
    void publicShopCardsFallsBackWhenEnterpriseMissing() {
        when(merchantMapper.selectBatchIds(any())).thenReturn(List.of(merchant(9L, 1, 1, null)));
        when(enterpriseMapper.selectBatchIds(any())).thenReturn(List.of());

        Map<String, Object> card = merchantService.publicShopCards(List.of(9L)).get(9L);

        assertEquals("商户#9", card.get("shopName"), "缺企业名时用商户号兜底，避免空白标题");
        assertEquals(Boolean.TRUE, card.get("platformInvited"));
    }
}
