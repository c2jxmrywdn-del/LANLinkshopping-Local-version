package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanlink.shopping.entity.LogisticsAlert;
import com.lanlink.shopping.entity.LogisticsProvider;
import com.lanlink.shopping.entity.LogisticsTemplate;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.mapper.LogisticsAlertMapper;
import com.lanlink.shopping.mapper.LogisticsProviderMapper;
import com.lanlink.shopping.mapper.LogisticsTemplateMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LogisticsPhase3AServiceTest {

    @Mock
    private LogisticsProviderMapper providerMapper;
    @Mock
    private LogisticsTemplateMapper templateMapper;
    @Mock
    private LogisticsWaybillMapper waybillMapper;
    @Mock
    private LogisticsAlertMapper alertMapper;

    private LogisticsFreightCalculator freightCalculator;
    private LogisticsCarrierRecommenderService recommenderService;
    private LogisticsSlaWatchdogService watchdogService;

    @BeforeEach
    void setUp() {
        freightCalculator = new LogisticsFreightCalculator();
        recommenderService = new LogisticsCarrierRecommenderService(providerMapper, templateMapper, freightCalculator);
        watchdogService = new LogisticsSlaWatchdogService(waybillMapper, alertMapper, providerMapper);
    }

    @Test
    void testCarrierRecommender_MultiGoalOptimization() {
        // 构造三家模拟服务商：顺丰（特快高质量）、安能（极致性价比）、德邦（大件服务优选）
        LogisticsProvider sf = new LogisticsProvider();
        sf.setProviderId(1L);
        sf.setProviderName("顺丰特快");
        sf.setProviderCode("SF");
        sf.setStatus(1);
        sf.setAvgEtaHours(24);
        sf.setServiceScore(98);
        sf.setSlaRate(99.5);
        sf.setSort(1);

        LogisticsProvider ane = new LogisticsProvider();
        ane.setProviderId(2L);
        ane.setProviderName("安能快运");
        ane.setProviderCode("ANE");
        ane.setStatus(1);
        ane.setAvgEtaHours(72);
        ane.setServiceScore(90);
        ane.setSlaRate(96.0);
        ane.setSort(2);

        when(providerMapper.selectList(any())).thenReturn(Arrays.asList(sf, ane));

        // 构造运费模板：顺丰运费稍高但极速，安能单价极低
        LogisticsTemplate sfTpl = new LogisticsTemplate();
        sfTpl.setTemplateId(101L);
        sfTpl.setProviderId(1L);
        sfTpl.setTemplateName("顺丰特快航空标快");
        sfTpl.setStatus(1);
        sfTpl.setPricingType("weight");
        sfTpl.setTierMode("linear");
        sfTpl.setFirstPrice(BigDecimal.valueOf(25.0));
        sfTpl.setContinuedPrice(BigDecimal.valueOf(8.0));
        sfTpl.setVasRuleJson("{\"forklift\":{\"base\":100},\"woodenCrate\":{\"m3Rate\":120}}");

        LogisticsTemplate aneTpl = new LogisticsTemplate();
        aneTpl.setTemplateId(102L);
        aneTpl.setProviderId(2L);
        aneTpl.setTemplateName("安能经济零担");
        aneTpl.setStatus(1);
        aneTpl.setPricingType("weight");
        aneTpl.setTierMode("tiered");
        aneTpl.setFirstPrice(BigDecimal.valueOf(15.0));
        aneTpl.setContinuedPrice(BigDecimal.valueOf(2.5));
        aneTpl.setVasRuleJson("{\"forklift\":{\"base\":60}}");

        when(templateMapper.selectList(argThat(qw -> qw != null))).thenAnswer(inv -> {
            // 返回对应服务商的模板
            return Arrays.asList(sfTpl, aneTpl);
        });

        LogisticsCarrierRecommenderService.RecommendRequest req = new LogisticsCarrierRecommenderService.RecommendRequest();
        req.setWeight(BigDecimal.valueOf(120.0));
        req.setVolume(BigDecimal.valueOf(0.5));
        req.setReceiverProvince("广东省");
        req.setForklift(true);

        LogisticsCarrierRecommenderService.RecommendResult result = recommenderService.recommend(req);

        assertNotNull(result);
        assertNotNull(result.getCostOptimal(), "性价比最优卡片不应为空");
        assertNotNull(result.getSpeedOptimal(), "极速时效卡片不应为空");
        assertNotNull(result.getServiceOptimal(), "服务优选卡片不应为空");
        assertFalse(result.getAllQuotes().isEmpty(), "全量候选列表不应为空");

        // 验证安能为成本最优（运费更低）
        assertTrue(result.getCostOptimal().getTotalFreight().compareTo(result.getSpeedOptimal().getTotalFreight()) <= 0);
        // 验证顺丰为时效最优（24h 优于 72h）
        assertTrue(result.getSpeedOptimal().getEtaHours() <= result.getCostOptimal().getEtaHours());
    }

    @Test
    void testSlaWatchdog_InspectionAndIdempotency() {
        LocalDateTime now = LocalDateTime.now();

        // 1. 模拟一单运输中停滞 55 小时的运单
        LogisticsWaybill stagnantWb = new LogisticsWaybill();
        stagnantWb.setWaybillId(888L);
        stagnantWb.setWaybillNo("WB88888888");
        stagnantWb.setStatus("in_transit");
        stagnantWb.setLastTraceTime(now.minusHours(55));
        stagnantWb.setUpdateTime(now.minusHours(55));

        // 2. 模拟一单派送中超过 30 小时的运单
        LogisticsWaybill delayWb = new LogisticsWaybill();
        delayWb.setWaybillId(999L);
        delayWb.setWaybillNo("WB99999999");
        delayWb.setStatus("out_for_delivery");
        delayWb.setUpdateTime(now.minusHours(30));

        when(waybillMapper.selectList(any())).thenReturn(Arrays.asList(stagnantWb, delayWb));
        // 模拟未创建过同类未完结告警
        when(alertMapper.selectCount(any())).thenReturn(0L);

        LogisticsSlaWatchdogService.InspectionReport report = watchdogService.runInspection();

        assertEquals(2, report.getScannedWaybills());
        assertEquals(2, report.getNewAlertsTriggered(), "应成功识别触发 2 起在途异常告警");
        assertEquals(0, report.getExistingAlertsSkipped());

        // 验证持久化层调用
        verify(alertMapper, times(2)).insert(any(LogisticsAlert.class));
        verify(waybillMapper, times(2)).updateById(any(LogisticsWaybill.class));

        // 模拟再次巡检时已存在未完结告警（测试防重幂等）
        when(alertMapper.selectCount(any())).thenReturn(1L);
        LogisticsSlaWatchdogService.InspectionReport secondReport = watchdogService.runInspection();
        assertEquals(0, secondReport.getNewAlertsTriggered(), "幂等防重生效，不应重复触发新告警");
        assertEquals(2, secondReport.getExistingAlertsSkipped(), "应跳过 2 起已存在告警");
    }

    @Test
    void testSlaDashboardOverview_Aggregation() {
        when(waybillMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(alertMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(providerMapper.selectList(any())).thenReturn(Collections.emptyList());

        LogisticsSlaWatchdogService.SlaDashboardOverview overview = watchdogService.getSlaDashboardOverview();

        assertNotNull(overview);
        assertEquals(0, overview.getTotalInTransit());
        assertTrue(overview.getOnTimeRate() >= 90.0);
        assertTrue(overview.getSlaHealthScore() >= 90.0);
    }
}
