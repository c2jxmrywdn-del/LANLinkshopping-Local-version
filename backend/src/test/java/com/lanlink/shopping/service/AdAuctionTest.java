package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.AdCampaign;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.AdCampaignMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 广告 GSP 拍卖引擎 + 计费链路单测。
 *
 * <p>覆盖：精确等式（q 注入重载）/ 真实整数对经 betaSmoothedCtr 走公开签名（±ε）/
 * 下限 0.01 与上限自身 bid 钳制 / 末位与单候选按自身 bid / 类目相关性改变 rank_score 与扣费 /
 * 零曝光 q 相等时 GSP 价塌缩为下一位 bid / limit 截断与无效出价剔除；
 * 以及服务接线（AdCampaignService）：出价入口校验、stats 出价均值与实付均价口径、
 * slots 端到端 GSP 价记忆、trackClick 拍卖价优先与无记录回退。
 */
class AdAuctionTest {

    // ===== 工具 =====

    private static AdCampaign campaign(long id, String bid, long clicks, long impressions) {
        AdCampaign c = new AdCampaign();
        c.setCampaignId(id);
        c.setCpcPrice(new BigDecimal(bid));
        c.setClicks(clicks);
        c.setImpressions(impressions);
        c.setProdId(5L);
        c.setStatus("active");
        return c;
    }

    private static AdAuction.Candidate cand(AdCampaign c, Long catId) {
        return new AdAuction.Candidate(c, catId);
    }

    // ===== GSP 数学（q 注入重载：精确等式） =====

    @Test
    void gspNextPriceExactEquationWithInjectedQuality() {
        // q1=0.05/q2=0.01、bid1=1.00/bid2=0.50 → p1 = 0.50×0.01/0.05 = 0.10（setScale(2, HALF_UP)）
        List<AdAuction.Winner> winners = AdAuction.auction(
                List.of(cand(campaign(1, "1.00", 0, 0), 100L), cand(campaign(2, "0.50", 0, 0), 200L)),
                c -> c.campaign().getCampaignId() == 1L ? 0.05 : 0.01,
                3);
        assertEquals(2, winners.size());
        assertEquals(1L, winners.get(0).campaign().getCampaignId());
        assertEquals(new BigDecimal("0.10"), winners.get(0).chargePrice());
        // ≤ 自身 bid、≥ 下限 0.01
        assertTrue(winners.get(0).chargePrice().compareTo(winners.get(0).campaign().getCpcPrice()) <= 0);
        assertTrue(winners.get(0).chargePrice().compareTo(AdAuction.MIN_CHARGE) >= 0);
        // 末位按下一位计费不可得 → 自身 bid
        assertEquals(new BigDecimal("0.50"), winners.get(1).chargePrice());
    }

    @Test
    void chargePriceFloorsAtCent() {
        // bid2=0.50、q2=1、q1=100 → raw = 0.005 → 下限钳到 0.01
        List<AdAuction.Winner> winners = AdAuction.auction(
                List.of(cand(campaign(1, "1.00", 0, 0), null), cand(campaign(2, "0.50", 0, 0), null)),
                c -> c.campaign().getCampaignId() == 1L ? 100.0 : 1.0, 2);
        assertEquals(new BigDecimal("0.01"), winners.get(0).chargePrice());
    }

    @Test
    void chargePriceClampBounds() {
        // 钳制边界直测：raw=50 → 上限自身 bid 1.00；raw=0.005 → 下限 0.01（2dp HALF_UP）
        assertEquals(new BigDecimal("1.00"), AdAuction.clampCharge(50.0, new BigDecimal("1.00")));
        assertEquals(new BigDecimal("0.01"), AdAuction.clampCharge(0.005, new BigDecimal("1.00")));
        // 竞价内 raw 由排序不变量（rank_{k+1} ≤ rank_k）保证 ≤ 自身 bid：q 悬殊时下一位价贴地而非越界
        // q1=0.01、q2=1.0 → c2（rank 0.50）反超居首，p = bid_c1 × q1/q2 = 1.00×0.01/1.0 = 0.01
        List<AdAuction.Winner> winners = AdAuction.auction(
                List.of(cand(campaign(1, "1.00", 0, 0), null), cand(campaign(2, "0.50", 0, 0), null)),
                c -> c.campaign().getCampaignId() == 1L ? 0.01 : 1.0, 2);
        assertEquals(2L, winners.get(0).campaign().getCampaignId());
        assertEquals(new BigDecimal("0.01"), winners.get(0).chargePrice());
        assertTrue(winners.get(0).chargePrice().compareTo(winners.get(0).campaign().getCpcPrice()) <= 0);
    }

    // ===== GSP 数学（公开签名：真实整数对经 betaSmoothedCtr） =====

    @Test
    void gspNextPriceWithRealBetaSmoothedCtrThroughPublicSignature() {
        // 计划1: clicks=45/imp=900 → q1=(45.1)/(910.1)≈0.0495539；计划2: 1/100 → q2=1.1/110.1≈0.0099909
        // bid 1.00 / 0.50 → p1 = 0.50×q2/q1 ≈ 0.1008 → 舍入后 0.10
        List<AdAuction.Winner> winners = AdAuction.auction(
                List.of(cand(campaign(1, "1.00", 45, 900), 100L), cand(campaign(2, "0.50", 1, 100), 100L)),
                100L, 3);
        assertEquals(2, winners.size());
        assertEquals(1L, winners.get(0).campaign().getCampaignId());
        assertEquals(new BigDecimal("0.10"), winners.get(0).chargePrice());
        assertTrue(Math.abs(winners.get(0).chargePrice().doubleValue() - 0.1008) < 1e-3,
                "GSP 价应 ≈0.1008（±1e-3），实际 " + winners.get(0).chargePrice());
        assertEquals(new BigDecimal("0.50"), winners.get(1).chargePrice());
    }

    @Test
    void singleCandidateAndLastPositionPayOwnBid() {
        // 单候选：无「下一位」→ 按自身 bid
        List<AdAuction.Winner> single = AdAuction.auction(
                List.of(cand(campaign(1, "0.80", 45, 900), 100L)), 100L, 3);
        assertEquals(1, single.size());
        assertEquals(new BigDecimal("0.80"), single.get(0).chargePrice());

        // 两候选：末位按自身 bid
        List<AdAuction.Winner> two = AdAuction.auction(
                List.of(cand(campaign(1, "1.00", 45, 900), 100L), cand(campaign(2, "0.30", 1, 100), 100L)),
                100L, 2);
        assertEquals(new BigDecimal("0.30"), two.get(1).chargePrice());
    }

    // ===== 相关性质量分门槛 =====

    @Test
    void relevanceReshapesRankScoreAndChargePrice() {
        // 同 pCTR（45/900）：c1 出价 1.00 类目未命中(×0.5)，c2 出价 0.80 命中(×1.0)
        // rank_score: c1 = 1.00×0.5q, c2 = 0.80×1.0q → 相关性门槛使 c2 反超居首
        // p_c2 = bid_c1 × q_c1/q_c2 = 1.00 × 0.5/1.0 = 0.50
        List<AdAuction.Winner> withCtx = AdAuction.auction(
                List.of(cand(campaign(1, "1.00", 45, 900), 200L), cand(campaign(2, "0.80", 45, 900), 100L)),
                100L, 2);
        assertEquals(2L, withCtx.get(0).campaign().getCampaignId());
        assertEquals(new BigDecimal("0.50"), withCtx.get(0).chargePrice());

        // 无上下文（contextCatId=null）→ 相关性均 1.0 → c1 居首，p = 0.80×q/q = 0.80
        List<AdAuction.Winner> noCtx = AdAuction.auction(
                List.of(cand(campaign(1, "1.00", 45, 900), 200L), cand(campaign(2, "0.80", 45, 900), 100L)),
                (Long) null, 2);
        assertEquals(1L, noCtx.get(0).campaign().getCampaignId());
        assertEquals(new BigDecimal("0.80"), noCtx.get(0).chargePrice());

        // 同一计划（c1）的质量分因相关性而不同：未命中 0.5 倍 vs 无上下文 1.0 倍
        assertNotEquals(withCtx.get(1).quality(), noCtx.get(0).quality());
        // 相关性取值：命中 1.0 / 未命中 0.5 / 无上下文与缺类目均不折减
        assertEquals(AdAuction.RELEVANCE_HIT, AdAuction.relevance(100L, 100L));
        assertEquals(AdAuction.RELEVANCE_MISS, AdAuction.relevance(200L, 100L));
        assertEquals(AdAuction.RELEVANCE_HIT, AdAuction.relevance(100L, null));
        assertEquals(AdAuction.RELEVANCE_MISS, AdAuction.relevance(null, 100L));
    }

    @Test
    void zeroImpressionEqualQualityCollapsesToNextBid() {
        // 两计划均 0 曝光 → q 相等（(0+0.1)/(0+10.1)）→ GSP 价塌缩为下一位 bid=0.50（理论退化情形）
        List<AdAuction.Winner> winners = AdAuction.auction(
                List.of(cand(campaign(1, "1.00", 0, 0), 100L), cand(campaign(2, "0.50", 0, 0), 100L)),
                100L, 3);
        assertEquals(new BigDecimal("0.50"), winners.get(0).chargePrice());
        assertEquals(new BigDecimal("0.50"), winners.get(1).chargePrice());
    }

    // ===== 边界 =====

    @Test
    void limitTruncatesAndEmptyCandidatesReturnEmpty() {
        List<AdAuction.Winner> winners = AdAuction.auction(
                List.of(cand(campaign(1, "1.00", 45, 900), 100L), cand(campaign(2, "0.50", 1, 100), 100L)),
                100L, 1);
        assertEquals(1, winners.size());
        assertEquals(1L, winners.get(0).campaign().getCampaignId());
        assertTrue(AdAuction.auction(List.of(), 100L, 3).isEmpty());
        assertTrue(AdAuction.auction(null, 100L, 3).isEmpty());
    }

    @Test
    void invalidBidCandidatesAreSkipped() {
        AdCampaign noBid = campaign(1, "1.00", 45, 900);
        noBid.setCpcPrice(null);
        List<AdAuction.Winner> winners = AdAuction.auction(
                List.of(cand(noBid, 100L), cand(campaign(2, "0.30", 1, 100), 100L)), 100L, 3);
        assertEquals(1, winners.size());
        assertEquals(2L, winners.get(0).campaign().getCampaignId());
    }

    // ===== 服务接线（AdCampaignService：出价入口 / stats 口径 / GSP 价记忆与回退） =====

    private AdCampaignMapper campaignMapper;
    private MerchantMapper merchantMapper;
    private ProductMapper productMapper;
    private WalletService walletService;
    private AdCampaignService service;

    @BeforeEach
    void setUp() {
        campaignMapper = mock(AdCampaignMapper.class);
        merchantMapper = mock(MerchantMapper.class);
        productMapper = mock(ProductMapper.class);
        walletService = mock(WalletService.class);
        service = new AdCampaignService(campaignMapper, merchantMapper, productMapper, walletService);
    }

    private Merchant merchant() {
        Merchant m = new Merchant();
        m.setMerId(9L);
        m.setUserId(7L);
        return m;
    }

    private Product sellableProduct() {
        Product p = new Product();
        p.setProdId(5L);
        p.setMerId(9L);
        p.setCatId(100L);
        p.setTitle("测试商品");
        p.setCoverUrl("/p/5.jpg");
        p.setPrice(new BigDecimal("9.90"));
        p.setBrand("品牌A");
        p.setSales(10);
        p.setStock(5);
        p.setStatus(1);
        p.setReviewStatus(1);
        return p;
    }

    @Test
    void createAcceptsCustomBidWithinRangeAndDefaultsToHalf() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant());
        when(productMapper.selectById(5L)).thenReturn(sellableProduct());

        // 显式出价 1.00 → 计划 cpc_price=1.00
        Map<String, Object> withBid = Map.of("prodId", 5L, "totalBudget", "100", "durationDays", 7, "cpcPrice", "1.00");
        AdCampaign created = service.create(7L, withBid);
        assertEquals(new BigDecimal("1.00"), created.getCpcPrice());

        // 缺省 → 0.50
        Map<String, Object> noBid = Map.of("prodId", 5L, "totalBudget", "100", "durationDays", 7);
        assertEquals(new BigDecimal("0.50"), service.create(7L, noBid).getCpcPrice());

        // 越界（<0.10 / >5.00）→ 业务异常
        assertThrows(BusinessException.class, () -> service.create(7L,
                Map.of("prodId", 5L, "totalBudget", "100", "durationDays", 7, "cpcPrice", "0.05")));
        assertThrows(BusinessException.class, () -> service.create(7L,
                Map.of("prodId", 5L, "totalBudget", "100", "durationDays", 7, "cpcPrice", "6.00")));
    }

    @Test
    void statsCpcPriceIsBidMeanAndAvgActualCpcPrefersRealSpend() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant());
        AdCampaign a = new AdCampaign();
        a.setCpcPrice(new BigDecimal("1.00"));
        a.setSpent(new BigDecimal("0.30"));
        a.setClicks(3L);
        a.setImpressions(100L);
        AdCampaign b = new AdCampaign();
        b.setCpcPrice(new BigDecimal("0.50"));
        b.setSpent(BigDecimal.ZERO);
        b.setClicks(0L);
        b.setImpressions(0L);
        when(campaignMapper.selectList(any())).thenReturn(List.of(a, b));
        when(walletService.balanceOf(7L)).thenReturn(BigDecimal.TEN);

        Map<String, Object> stats = service.stats(7L);
        // cpcPrice = 全部计划出价算术平均 (1.00+0.50)/2 = 0.75（2dp HALF_UP）
        assertEquals(new BigDecimal("0.75"), stats.get("cpcPrice"));
        // avgActualCpc = totalSpent 0.30 / clicks 3 = 0.10（实扣优先）
        assertEquals(new BigDecimal("0.10"), stats.get("avgActualCpc"));
    }

    @Test
    void statsFallsBackWhenNoClicksOrNoPlans() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant());
        // 无点击 → avgActualCpc 回退出价均值；无计划 → cpcPrice 回退 0.50
        AdCampaign a = new AdCampaign();
        a.setCpcPrice(new BigDecimal("1.00"));
        a.setSpent(BigDecimal.ZERO);
        a.setClicks(0L);
        a.setImpressions(0L);
        when(campaignMapper.selectList(any())).thenReturn(List.of(a));
        when(walletService.balanceOf(7L)).thenReturn(BigDecimal.TEN);
        Map<String, Object> stats = service.stats(7L);
        assertEquals(new BigDecimal("1.00"), stats.get("cpcPrice"));
        assertEquals(new BigDecimal("1.00"), stats.get("avgActualCpc"));

        // 无计划
        when(campaignMapper.selectList(any())).thenReturn(List.of());
        Map<String, Object> empty = service.stats(7L);
        assertEquals(AdCampaignService.DEFAULT_CPC, empty.get("cpcPrice"));
        assertEquals(AdCampaignService.DEFAULT_CPC, empty.get("avgActualCpc"));
    }

    @Test
    void slotsAuctionPricesFeedTrackClickAndFieldsStayIntact() {
        // 端到端（mock DAO）：两计划 bid=1.00/0.50、真实 pCTR（45/900 与 1/100）→ slots 居首者 chargePrice=0.10
        AdCampaign c1 = campaign(11L, "1.00", 45, 900);
        AdCampaign c2 = campaign(12L, "0.50", 1, 100);
        when(campaignMapper.activeSlots(50)).thenReturn(List.of(c1, c2));
        Product p = sellableProduct();
        when(productMapper.selectById(anyLong())).thenReturn(p);
        when(campaignMapper.chargeClick(anyLong(), any())).thenReturn(1);
        when(campaignMapper.selectById(11L)).thenReturn(c1);
        when(campaignMapper.selectById(12L)).thenReturn(c2);

        List<Map<String, Object>> slots = service.slots(2, 100L);
        assertEquals(2, slots.size());
        assertEquals(11L, slots.get(0).get("campaignId"));
        assertEquals(new BigDecimal("0.10"), slots.get(0).get("chargePrice"));
        assertEquals(new BigDecimal("0.50"), slots.get(1).get("chargePrice"));
        // 原有字段齐全（仅增 chargePrice）
        assertEquals(5L, slots.get(0).get("prodId"));
        assertEquals("测试商品", slots.get(0).get("title"));
        assertEquals("/p/5.jpg", slots.get(0).get("coverUrl"));
        assertEquals(new BigDecimal("9.90"), slots.get(0).get("price"));
        assertEquals("品牌A", slots.get(0).get("brand"));
        assertEquals(10, slots.get(0).get("sales"));
        assertEquals(5, slots.get(0).get("stock"));
        assertEquals(Boolean.TRUE, slots.get(0).get("ad"));

        // 点击计费优先取拍卖价：c1 实扣 0.10（而非自身 bid 1.00），c2 实扣 0.50
        service.trackClick(11L, "203.0.113.7");
        verify(campaignMapper).chargeClick(eq(11L), eq(new BigDecimal("0.10")));
        service.trackClick(12L, "203.0.113.7");
        verify(campaignMapper).chargeClick(eq(12L), eq(new BigDecimal("0.50")));
    }

    @Test
    void trackClickFallsBackToPlanBidWithoutFreshAuctionRecord() {
        // 无新鲜拍卖记录（未经过 slots）→ 按计划自身 cpc_price 计费
        AdCampaign c = campaign(11L, "1.00", 45, 900);
        c.setStatus("active");
        when(campaignMapper.selectById(11L)).thenReturn(c);
        when(campaignMapper.chargeClick(anyLong(), any())).thenReturn(1);

        assertTrue(service.trackClick(11L, "203.0.113.7"));
        verify(campaignMapper).chargeClick(eq(11L), eq(new BigDecimal("1.00")));
    }

    @Test
    void duplicateClickFromSameIpWithinWindowIsNotCharged() {
        // P0-3 去重窗口：同 IP 同计划窗口内重复上报直接丢弃，不计数不扣费
        AdCampaign c = campaign(11L, "0.50", 0, 100);
        when(campaignMapper.selectById(11L)).thenReturn(c);
        when(campaignMapper.chargeClick(anyLong(), any())).thenReturn(1);

        assertTrue(service.trackClick(11L, "198.51.100.5"));
        assertFalse(service.trackClick(11L, "198.51.100.5"));
        // 仅首拍计费一次
        verify(campaignMapper, times(1)).chargeClick(eq(11L), any());
    }

    @Test
    void sameIpAcrossDifferentCampaignsAndOtherIpsStillCharged() {
        // 去重键为 IP+计划：同 IP 点击不同计划、不同 IP 点击同计划均正常计费（不动正常投放语义）
        AdCampaign c1 = campaign(11L, "0.50", 0, 100);
        AdCampaign c2 = campaign(12L, "0.50", 0, 100);
        when(campaignMapper.selectById(11L)).thenReturn(c1);
        when(campaignMapper.selectById(12L)).thenReturn(c2);
        when(campaignMapper.chargeClick(anyLong(), any())).thenReturn(1);

        assertTrue(service.trackClick(11L, "198.51.100.5"));
        assertTrue(service.trackClick(12L, "198.51.100.5"));
        assertTrue(service.trackClick(11L, "198.51.100.6"));
        // 计划 11 被两个不同 IP 各计费一次
        verify(campaignMapper, times(2)).chargeClick(eq(11L), any());
        verify(campaignMapper, times(1)).chargeClick(eq(12L), any());
    }

    @Test
    void clickWithoutClientIpIsRejectedFailClosed() {
        // 拿不到来源 IP 的匿名点击一律不计费
        AdCampaign c = campaign(11L, "0.50", 0, 100);
        when(campaignMapper.selectById(11L)).thenReturn(c);
        when(campaignMapper.chargeClick(anyLong(), any())).thenReturn(1);

        assertFalse(service.trackClick(11L, null));
        assertFalse(service.trackClick(11L, "  "));
        verify(campaignMapper, never()).chargeClick(anyLong(), any());
    }
}
