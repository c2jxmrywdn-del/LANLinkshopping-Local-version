package com.lanlink.shopping.module.pricemonitor.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CategoryMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

/**
 * 五维因子自动分析链单测：
 * ① 网络检索失败降级不阻断（degraded 标注 + 快照照常落库）；
 * ② 失败隔离 24h 冷却自动复位；
 * ③ 首轮扫描 LIMIT 上限（防检索降级拖穿整点周期）；
 * ④ 管理端全量重建异步受理（skipped=-3，daemon 线程执行）；
 * ⑤ 启动补跑走 enabled + running 守卫。
 */
class ProductFactorAutoServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String COOLDOWN_SQL =
            "UPDATE t_product_factor_auto_fail SET fail_count=0 WHERE fail_count>=3 AND last_fail_at < DATE_SUB(NOW(), INTERVAL 24 HOUR)";

    private ProductMapper productMapper;
    private CategoryMapper categoryMapper;
    private PriceMonitorAiService aiService;
    private MarketWebSearchService webSearch;
    private AnalysisSafetyFilter safetyFilter;
    private JdbcTemplate jdbc;
    private ProductFactorAutoService service;

    @BeforeAll
    static void initTableInfo() {
        // lambdaQuery 的 eq(Product::getStatus,...) 依赖 TableInfo 缓存；纯单测无 MyBatis 上下文需手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Product.class);
    }

    @BeforeEach
    void setUp() {
        productMapper = mock(ProductMapper.class);
        categoryMapper = mock(CategoryMapper.class);
        aiService = mock(PriceMonitorAiService.class);
        webSearch = mock(MarketWebSearchService.class);
        safetyFilter = mock(AnalysisSafetyFilter.class);
        jdbc = mock(JdbcTemplate.class);
        service = new ProductFactorAutoService(productMapper, categoryMapper, aiService, webSearch, safetyFilter, jdbc);
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    private Product product() {
        Product p = new Product();
        p.setProdId(1069L);
        p.setCatId(7L);
        p.setStatus(1);
        p.setReviewStatus(1);
        p.setTitle("螺纹钢 HRB400 Φ12");
        return p;
    }

    private Map<String, Object> factorResult() {
        Map<String, Object> factors = Map.of("factors", List.of(), "sentimentIndex", 52, "dominant", "sentiment",
                "summary", "中性", "systematicAnalysis", Map.of("conclusion", "测试"));
        Map<String, Object> out = Map.of("factors", factors.get("factors"), "sentimentIndex", 52,
                "dominant", "sentiment", "summary", "中性", "systematicAnalysis", factors.get("systematicAnalysis"));
        return out;
    }

    @Test
    void analyzeOneMarksDegradedAndStillPersistsWhenWebSearchThrows() throws Exception {
        Product p = product();
        when(productMapper.selectById(1069L)).thenReturn(p);
        when(categoryMapper.selectById(7L)).thenReturn(null);
        when(safetyFilter.screenProductText(any(), any()))
                .thenReturn(Map.of("clean", true, "reason", "", "title", p.getTitle(), "detail", ""));
        when(aiService.factors(eq(1069L), eq("zh"))).thenReturn(factorResult());
        // 检索链契约破坏（未来直接抛异常）：必须降级，绝不阻断快照落库
        when(webSearch.searchMarketInfo(eq(""), eq(5)))
                .thenThrow(new IllegalStateException("web search unavailable: connect timeout"));

        assertDoesNotThrow(() -> service.analyzeOne(1069L));

        ArgumentCaptor<Object> payloadCap = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(startsWith("INSERT INTO t_product_factor_auto"), eq(1069L), payloadCap.capture(), eq(0));
        JsonNode payload = OM.readTree(String.valueOf(payloadCap.getValue()));
        assertEquals(true, payload.get("degraded").asBoolean());
        assertTrue(payload.get("degradedReason").asText().contains("connect timeout"));
        assertEquals(0, payload.get("webInfo").size());
        assertEquals("螺纹钢 HRB400 Φ12", payload.get("title").asText());
        // 本地五维因子不受检索降级影响
        assertEquals(52, payload.get("sentimentIndex").asInt());
    }

    @Test
    void analyzeOneWritesNoDegradedKeyWhenSearchSucceeds() throws Exception {
        Product p = product();
        when(productMapper.selectById(1069L)).thenReturn(p);
        when(categoryMapper.selectById(7L)).thenReturn(null);
        when(safetyFilter.screenProductText(any(), any()))
                .thenReturn(Map.of("clean", true, "reason", "", "title", p.getTitle(), "detail", ""));
        when(aiService.factors(eq(1069L), eq("zh"))).thenReturn(factorResult());
        when(webSearch.searchMarketInfo(eq(""), eq(5))).thenReturn(List.of(
                new MarketWebSearchService.WebInfoItem("钢材行情", "https://example.com", "涨")));

        service.analyzeOne(1069L);

        ArgumentCaptor<Object> payloadCap = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(startsWith("INSERT INTO t_product_factor_auto"), eq(1069L), payloadCap.capture(), eq(1));
        JsonNode payload = OM.readTree(String.valueOf(payloadCap.getValue()));
        assertNull(payload.get("degraded"), "正常检索不得写入 degraded 标注");
        assertNull(payload.get("degradedReason"));
        assertEquals(1, payload.get("webInfo").size());
    }

    @Test
    void analyzeMissingResetsStaleCooldownAndClearsFailRecordOnSuccess() {
        when(productMapper.selectList(any())).thenReturn(List.of(product()));
        when(categoryMapper.selectById(7L)).thenReturn(null);
        when(productMapper.selectById(anyLong())).thenAnswer(inv -> product());
        when(safetyFilter.screenProductText(any(), any()))
                .thenReturn(Map.of("clean", true, "reason", "", "title", "t", "detail", ""));
        when(aiService.factors(anyLong(), eq("zh"))).thenReturn(factorResult());
        when(webSearch.searchMarketInfo(any(), eq(5))).thenReturn(List.of());

        Map<String, Integer> out = service.analyzeMissing();

        assertEquals(1, out.get("analyzed"));
        assertEquals(0, out.get("skipped"));
        // 隔离冷却复位先行（24h 无新失败 → fail_count=0 重新纳入扫描）
        verify(jdbc).update(COOLDOWN_SQL);
        // 成功后清失败记录
        verify(jdbc).update(eq("DELETE FROM t_product_factor_auto_fail WHERE prod_id=?"), eq(1069L));
    }

    @Test
    void analyzeMissingFirstRoundScanIsBoundedByLimit100() {
        when(productMapper.selectList(any())).thenReturn(List.of());

        service.analyzeMissing();

        ArgumentCaptor<Wrapper<Product>> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(productMapper).selectList(cap.capture());
        // 首轮无上限 × 检索降级（每商品最长 12s）会拖穿整点周期，LIMIT 100 是硬约束
        String sql = String.valueOf(cap.getValue().getSqlSegment()) + " " + String.valueOf(cap.getValue().getCustomSqlSegment());
        assertTrue(sql.contains("LIMIT 100"), "first-round scan must be bounded: " + sql);
    }

    @Test
    void analyzeMissingRespectsEnabledSwitch() {
        ReflectionTestUtils.setField(service, "enabled", false);

        Map<String, Integer> out = service.analyzeMissing();

        assertEquals(0, out.get("analyzed"));
        assertEquals(-1, out.get("skipped"));
        verify(productMapper, never()).selectList(any());
        verify(jdbc, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    void rebuildAllAsyncAcceptsImmediatelyAndClearsTablesInDaemonThread() {
        Map<String, Integer> out = service.rebuildAllAsync();

        // HTTP 线程立即受理返回（-3=已受理异步执行），响应键集与同步版一致（字段只增不改契约）
        assertEquals(0, out.get("analyzed"));
        assertEquals(-3, out.get("skipped"));
        // daemon 线程完成清表 + 重算扫描
        verify(jdbc, timeout(5000)).update("DELETE FROM t_product_factor_auto");
        verify(jdbc, timeout(5000)).update("DELETE FROM t_product_factor_auto_fail");
        verify(jdbc, timeout(5000)).update(COOLDOWN_SQL);
        verify(productMapper, timeout(5000)).selectList(any());
    }

    @Test
    void bootstrapCatchUpRunsAnalyzeMissingThroughGuards() {
        // 启动补跑：延迟归零直接触发，必须照走 analyzeMissing 的 enabled + running 守卫链
        ReflectionTestUtils.setField(service, "bootstrapDelayMs", 0L);
        when(productMapper.selectList(any())).thenReturn(List.of());

        service.scheduleBootstrapCatchUp();

        verify(jdbc, timeout(5000)).update(COOLDOWN_SQL);
        verify(productMapper, timeout(5000)).selectList(any());
    }
}
