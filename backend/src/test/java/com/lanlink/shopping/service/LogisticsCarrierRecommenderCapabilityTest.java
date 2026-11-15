package com.lanlink.shopping.service;

import com.lanlink.shopping.entity.LogisticsProvider;
import com.lanlink.shopping.entity.LogisticsTemplate;
import com.lanlink.shopping.mapper.LogisticsProviderMapper;
import com.lanlink.shopping.mapper.LogisticsTemplateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 承运商扩展能力测试（out/carrier-expansion-design.md §5.1）：
 * 大件软加权、国际过滤、¥0 比价定稿、VAS JSON 解析、SELF 特例。
 * 与既有 LogisticsPhase3AServiceTest 边界：capability 维度全部在本类，不回填旧类。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LogisticsCarrierRecommenderCapabilityTest {

    @Mock
    private LogisticsProviderMapper providerMapper;
    @Mock
    private LogisticsTemplateMapper templateMapper;

    private LogisticsCarrierRecommenderService service;

    @BeforeEach
    void setUp() {
        service = new LogisticsCarrierRecommenderService(providerMapper, templateMapper, new LogisticsFreightCalculator());
    }

    private LogisticsProvider provider(Long id, String name, String code, int eta, int score, double sla, String capabilityJson) {
        LogisticsProvider p = new LogisticsProvider();
        p.setProviderId(id);
        p.setProviderName(name);
        p.setProviderCode(code);
        p.setStatus(1);
        p.setAvgEtaHours(eta);
        p.setServiceScore(score);
        p.setSlaRate(sla);
        p.setSort(id.intValue());
        p.setCapabilityJson(capabilityJson);
        return p;
    }

    private LogisticsTemplate tieredTemplate(Long id, Long providerId, String name,
                                             BigDecimal minCharge, String vasRuleJson) {
        LogisticsTemplate t = new LogisticsTemplate();
        t.setTemplateId(id);
        t.setProviderId(providerId);
        t.setTemplateName(name);
        t.setStatus(1);
        t.setPricingType("weight");
        t.setTierMode("tiered");
        // KYE 阶梯自洽：50kg = 30 + 50×0.3 = 45（min_charge=45 同值兜底）
        t.setTierRuleJson("[{\"minWeight\":0,\"maxWeight\":300,\"unitPrice\":0.3,\"basePrice\":30}," +
                "{\"minWeight\":300,\"maxWeight\":1000,\"unitPrice\":0.22,\"basePrice\":30}," +
                "{\"minWeight\":1000,\"maxWeight\":999999,\"unitPrice\":0.15,\"basePrice\":30}]");
        t.setMinCharge(minCharge);
        t.setVolumeFactor(BigDecimal.valueOf(6000));
        t.setFreeThreshold(BigDecimal.ZERO);
        t.setRemoteSurcharge(BigDecimal.ZERO);
        t.setVasRuleJson(vasRuleJson);
        return t;
    }

    /** 两个大件承运商：KYE（heavyCargo，限时赔付）+ ANE（heavyCargo，低成本） */
    private void mockHeavyCarriers() {
        LogisticsProvider kye = provider(1L, "跨越速运", "KYE", 24, 98, 99.5,
                "{\"scope\":\"domestic\",\"heavyCargo\":true,\"oversize\":true,\"guaranteed\":true,\"costLevel\":\"mid\"}");
        LogisticsProvider ane = provider(2L, "安能物流", "ANE", 72, 90, 96.0,
                "{\"scope\":\"domestic\",\"heavyCargo\":true,\"costLevel\":\"low\"}");
        when(providerMapper.selectList(any())).thenReturn(List.of(kye, ane));
        when(templateMapper.selectList(any())).thenAnswer(inv -> List.of(
                tieredTemplate(101L, 1L, "跨越特快-大件限时", BigDecimal.valueOf(45), null),
                tieredTemplate(102L, 2L, "安能零担-大件经济", BigDecimal.valueOf(25), null)
        ));
    }

    @Test
    void kyeTieredPricingSelfConsistent() {
        mockHeavyCarriers();
        LogisticsCarrierRecommenderService.RecommendRequest req = new LogisticsCarrierRecommenderService.RecommendRequest();
        req.setWeight(BigDecimal.valueOf(50));
        req.setVolume(BigDecimal.valueOf(0.1));

        LogisticsCarrierRecommenderService.RecommendResult result = service.recommend(req);

        LogisticsCarrierRecommenderService.CarrierQuote kye = result.getAllQuotes().stream()
                .filter(q -> "KYE".equals(q.getProviderCode())).findFirst().orElseThrow();
        // 阶梯公式 30 + 50×0.3 = 45 = min_charge（设计 §3 起价口径自洽）
        assertEquals(0, BigDecimal.valueOf(45).compareTo(kye.getTotalFreight()),
                "KYE 50kg 实付 30+50×0.3=45，与起价 ¥45 一致");
        assertEquals(0, BigDecimal.ZERO.compareTo(kye.getFreeThreshold()), "比价不启用包邮门槛下发为 0");
    }

    @Test
    void heavyDemandPrefersHeavyCargoCarriers() {
        // 大件需求下 heavyCargo 承运商 matchScore 高于同档非 heavyCargo（软惩罚 ×0.85 生效）
        LogisticsProvider heavy = provider(1L, "跨越速运", "KYE", 24, 98, 99.5,
                "{\"scope\":\"domestic\",\"heavyCargo\":true,\"costLevel\":\"mid\"}");
        LogisticsProvider plain = provider(2L, "普通快递", "PLN", 24, 98, 99.5,
                "{\"scope\":\"domestic\",\"costLevel\":\"mid\"}");
        when(providerMapper.selectList(any())).thenReturn(List.of(heavy, plain));
        when(templateMapper.selectList(any())).thenAnswer(inv -> List.of(
                tieredTemplate(101L, 1L, "跨越特快-大件限时", BigDecimal.valueOf(45), null),
                tieredTemplate(102L, 2L, "普通标快", BigDecimal.valueOf(45), null)
        ));

        LogisticsCarrierRecommenderService.RecommendRequest req = new LogisticsCarrierRecommenderService.RecommendRequest();
        req.setWeight(BigDecimal.valueOf(80));
        req.setVolume(BigDecimal.valueOf(0.4));

        LogisticsCarrierRecommenderService.RecommendResult result = service.recommend(req);

        double heavyScore = result.getAllQuotes().stream()
                .filter(q -> "KYE".equals(q.getProviderCode())).findFirst().orElseThrow().getMatchScore();
        double plainScore = result.getAllQuotes().stream()
                .filter(q -> "PLN".equals(q.getProviderCode())).findFirst().orElseThrow().getMatchScore();
        assertTrue(heavyScore > plainScore, "大件需求（80kg/0.4m³）下 heavyCargo 承运商应软加权胜出: "
                + heavyScore + " vs " + plainScore);
    }

    @Test
    void internationalProvidersExcludedFromQuotes() {
        // 即使误配为 status=1 + 启用模板，scope=international 也不入 allQuotes
        LogisticsProvider intl = provider(1L, "DHL Express", "DHL", 120, 95, 99.0,
                "{\"scope\":\"international\",\"routeNotes\":\"国际线路 · 报价联系客服\"}");
        LogisticsProvider kye = provider(2L, "跨越速运", "KYE", 24, 98, 99.5,
                "{\"scope\":\"domestic\",\"heavyCargo\":true}");
        when(providerMapper.selectList(any())).thenReturn(List.of(intl, kye));
        when(templateMapper.selectList(any())).thenAnswer(inv -> List.of(
                tieredTemplate(101L, 1L, "DHL国际件", BigDecimal.valueOf(200), null),
                tieredTemplate(102L, 2L, "跨越特快-大件限时", BigDecimal.valueOf(45), null)
        ));

        LogisticsCarrierRecommenderService.RecommendRequest req = new LogisticsCarrierRecommenderService.RecommendRequest();
        req.setWeight(BigDecimal.valueOf(50));
        req.setVolume(BigDecimal.valueOf(0.1));

        LogisticsCarrierRecommenderService.RecommendResult result = service.recommend(req);

        assertTrue(result.getAllQuotes().stream().noneMatch(q -> "DHL".equals(q.getProviderCode())),
                "国际线路不得进入推荐候选池");
        assertTrue(result.getAllQuotes().stream().anyMatch(q -> "KYE".equals(q.getProviderCode())));
    }

    @Test
    void zeroOrderAmountNeverYieldsFreeQuote() {
        // 比价恒传 0：包邮短路失效，带包邮门槛的模板也报门市运费，不再 ¥0 垄断
        LogisticsProvider zt = provider(1L, "中通快递", "ZT", 48, 92, 98.0, "{\"scope\":\"domestic\"}");
        LogisticsProvider sf = provider(2L, "顺丰速运", "SF", 24, 98, 99.5, "{\"scope\":\"domestic\"}");
        when(providerMapper.selectList(any())).thenReturn(List.of(zt, sf));

        LogisticsTemplate ztTpl = tieredTemplate(101L, 1L, "中通经济-全国", BigDecimal.ZERO, null);
        ztTpl.setTierMode("linear");
        ztTpl.setTierRuleJson(null);
        ztTpl.setFirstWeight(BigDecimal.ONE);
        ztTpl.setFirstPrice(BigDecimal.valueOf(8));
        ztTpl.setContinuedWeight(BigDecimal.ONE);
        ztTpl.setContinuedPrice(BigDecimal.valueOf(3));
        ztTpl.setFreeThreshold(BigDecimal.valueOf(199)); // 包邮门槛 199，orderAmount=0 不触发
        LogisticsTemplate sfTpl = tieredTemplate(102L, 2L, "顺丰标快-全国", BigDecimal.ZERO, null);
        sfTpl.setTierMode("linear");
        sfTpl.setTierRuleJson(null);
        sfTpl.setFirstWeight(BigDecimal.ONE);
        sfTpl.setFirstPrice(BigDecimal.valueOf(12));
        sfTpl.setContinuedWeight(BigDecimal.ONE);
        sfTpl.setContinuedPrice(BigDecimal.valueOf(5));
        sfTpl.setFreeThreshold(BigDecimal.valueOf(299));
        when(templateMapper.selectList(any())).thenAnswer(inv -> List.of(ztTpl, sfTpl));

        LogisticsCarrierRecommenderService.RecommendRequest req = new LogisticsCarrierRecommenderService.RecommendRequest();
        req.setWeight(BigDecimal.valueOf(10));
        req.setVolume(BigDecimal.valueOf(0.05));

        LogisticsCarrierRecommenderService.RecommendResult result = service.recommend(req);

        assertTrue(result.getAllQuotes().stream().noneMatch(q ->
                        q.getTotalFreight().compareTo(BigDecimal.ZERO) == 0),
                "orderAmount=0 比价不得出现 ¥0 报价（包邮短路失效）");
        // freeThreshold 随报价下发，前端渲染「满 ¥x 包邮」角标
        assertEquals(0, BigDecimal.valueOf(199).compareTo(result.getAllQuotes().stream()
                .filter(q -> "ZT".equals(q.getProviderCode())).findFirst().orElseThrow().getFreeThreshold()));
    }

    @Test
    void vasSupportParsedByJsonKeysIncludingOversize() {
        LogisticsProvider kye = provider(1L, "跨越速运", "KYE", 24, 98, 99.5,
                "{\"scope\":\"domestic\",\"heavyCargo\":true,\"oversize\":true}");
        when(providerMapper.selectList(any())).thenReturn(List.of(kye));
        // vas_rule_json 含 oversizeOverweight 键：勾选超长超重应判支持（旧字符串 contains 漏判键）
        LogisticsTemplate kyeTpl = tieredTemplate(101L, 1L, "跨越特快-大件限时", BigDecimal.valueOf(45),
                "{\"forklift\":{\"basePrice\":80,\"freeTons\":1,\"perTonPrice\":50}," +
                        "\"upstairs\":{\"basePrice\":50,\"perFloorPrice\":20}," +
                        "\"oversizeOverweight\":{\"surchargeRate\":0.2}}");
        when(templateMapper.selectList(any())).thenAnswer(inv -> List.of(kyeTpl));

        LogisticsCarrierRecommenderService.RecommendRequest req = new LogisticsCarrierRecommenderService.RecommendRequest();
        req.setWeight(BigDecimal.valueOf(80));
        req.setVolume(BigDecimal.valueOf(0.4));
        req.setOversize(true);
        req.setForklift(true);

        LogisticsCarrierRecommenderService.RecommendResult result = service.recommend(req);

        LogisticsCarrierRecommenderService.CarrierQuote q = result.getAllQuotes().stream()
                .filter(x -> "KYE".equals(x.getProviderCode())).findFirst().orElseThrow();
        assertTrue(Boolean.TRUE.equals(q.getVasFullySupported()),
                "oversizeOverweight 键存在时勾选超长超重不应误判不支持");
        assertTrue(q.getUnsupportedVas().isEmpty());
    }

    @Test
    void vasUnsupportedWhenKeyMissing() {
        LogisticsProvider kye = provider(1L, "跨越速运", "KYE", 24, 98, 99.5,
                "{\"scope\":\"domestic\",\"heavyCargo\":true}");
        when(providerMapper.selectList(any())).thenReturn(List.of(kye));
        // 仅含 forklift：勾选上楼/木架/超重均判不支持
        LogisticsTemplate kyeTpl = tieredTemplate(101L, 1L, "跨越特快-大件限时", BigDecimal.valueOf(45),
                "{\"forklift\":{\"basePrice\":80}}");
        when(templateMapper.selectList(any())).thenAnswer(inv -> List.of(kyeTpl));

        LogisticsCarrierRecommenderService.RecommendRequest req = new LogisticsCarrierRecommenderService.RecommendRequest();
        req.setWeight(BigDecimal.valueOf(80));
        req.setVolume(BigDecimal.valueOf(0.4));
        req.setForklift(true);
        req.setUpstairs(true);
        req.setWoodenCrate(true);
        req.setOversize(true);

        LogisticsCarrierRecommenderService.RecommendResult result = service.recommend(req);

        LogisticsCarrierRecommenderService.CarrierQuote q = result.getAllQuotes().stream()
                .filter(x -> "KYE".equals(x.getProviderCode())).findFirst().orElseThrow();
        assertFalse(Boolean.TRUE.equals(q.getVasFullySupported()));
        assertEquals(3, q.getUnsupportedVas().size(), "上楼/木架/超重三项应判不支持");
    }

    @Test
    void selfProviderNotOnSpeedOrCostCards() {
        LogisticsProvider self = provider(1L, "商家自送", "SELF", 48, 90, 98.0, "{}");
        LogisticsTemplate selfTpl = tieredTemplate(101L, 1L, "商家自送-固定", BigDecimal.ZERO, null);
        selfTpl.setTierMode("linear");
        selfTpl.setTierRuleJson(null);
        selfTpl.setPricingType("fixed");
        selfTpl.setFirstPrice(BigDecimal.valueOf(5));
        LogisticsProvider kye = provider(2L, "跨越速运", "KYE", 24, 98, 99.5,
                "{\"scope\":\"domestic\",\"heavyCargo\":true}");
        when(providerMapper.selectList(any())).thenReturn(List.of(self, kye));
        when(templateMapper.selectList(any())).thenAnswer(inv -> List.of(
                selfTpl,
                tieredTemplate(102L, 2L, "跨越特快-大件限时", BigDecimal.valueOf(45), null)
        ));

        LogisticsCarrierRecommenderService.RecommendRequest req = new LogisticsCarrierRecommenderService.RecommendRequest();
        req.setWeight(BigDecimal.valueOf(80));
        req.setVolume(BigDecimal.valueOf(0.4));

        LogisticsCarrierRecommenderService.RecommendResult result = service.recommend(req);

        assertTrue(result.getAllQuotes().stream().anyMatch(q -> "self".equals(q.getTransportMode())),
                "SELF 应入 allQuotes 且 mode=self");
        assertNotEquals("SELF", result.getSpeedOptimal().getProviderCode(),
                "SELF 不得占据极速时效卡");
        assertNotEquals("SELF", result.getCostOptimal().getProviderCode(),
                "SELF 不得占据性价比卡");
    }
}
