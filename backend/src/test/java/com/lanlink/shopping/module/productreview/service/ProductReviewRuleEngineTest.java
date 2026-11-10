package com.lanlink.shopping.module.productreview.service;

import com.lanlink.shopping.entity.Category;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CategoryMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.pricemonitor.mapper.MarketBenchmarkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 自动审核规则引擎单测：必填/资质/类目红线、有害与禁售词、广告法软扣分、完整评分。
 */
class ProductReviewRuleEngineTest {

    private MerchantMapper merchantMapper;
    private CategoryMapper categoryMapper;
    private ProductMapper productMapper;
    private MarketBenchmarkMapper benchmarkMapper;
    private ProductReviewRuleEngine engine;

    @BeforeEach
    void setUp() {
        merchantMapper = mock(MerchantMapper.class);
        categoryMapper = mock(CategoryMapper.class);
        productMapper = mock(ProductMapper.class);
        benchmarkMapper = mock(MarketBenchmarkMapper.class);
        engine = new ProductReviewRuleEngine(merchantMapper, categoryMapper, productMapper,
                benchmarkMapper, new com.lanlink.shopping.module.pricemonitor.service.AnalysisSafetyFilter());
        Merchant m = new Merchant();
        m.setMerId(1L);
        m.setReviewStatus(1);
        when(merchantMapper.selectById(1L)).thenReturn(m);
        Category c = new Category();
        c.setCatId(10L);
        c.setIndId(2L);
        when(categoryMapper.selectById(10L)).thenReturn(c);
        when(benchmarkMapper.avgPriceByIndustry(any())).thenReturn(new BigDecimal("100"));
        when(productMapper.selectCount(any())).thenReturn(0L);
    }

    private Product product(String title, String detail) {
        Product p = new Product();
        p.setProdId(100L);
        p.setMerId(1L);
        p.setCatId(10L);
        p.setIndId(2L);
        p.setTitle(title);
        p.setDetail(detail);
        p.setBrand("华晨");
        p.setSpec("DN25");
        p.setPrice(new BigDecimal("120"));
        p.setStock(10);
        p.setCoverUrl("/api/uploads/products/a.jpg");
        return p;
    }

    @Test
    void validProductPassesWithoutHardIssue() {
        ProductReviewRuleEngine.RuleResult r = engine.evaluate(
                product("抗震螺纹钢 HRB400E Φ20 现货", "材质、用途、交付说明齐全，支持企业账期与对公转账。"));
        assertFalse(r.hardReject());
        assertTrue(r.score() >= 85);
        assertFalse(r.issues().stream().anyMatch(i -> "HARD".equals(i.severity())));
    }

    @Test
    void prohibitedGoodsIsHardReject() {
        ProductReviewRuleEngine.RuleResult r = engine.evaluate(
                product("工业级雷管 爆破器材", "支持批量采购，详情咨询。"));
        assertTrue(r.hardReject());
        assertEquals(0, r.score());
        assertTrue(r.issues().stream().anyMatch(i -> "PROHIBITED_GOODS".equals(i.code())));
    }

    @Test
    void categoryIndustryMismatchIsHardReject() {
        Product p = product("工业阀门 不锈钢球阀", "规格齐全，现货供应，支持定制。");
        p.setIndId(3L); // 分类属于行业 2，与所选行业 3 不一致
        ProductReviewRuleEngine.RuleResult r = engine.evaluate(p);
        assertTrue(r.hardReject());
        assertTrue(r.issues().stream().anyMatch(i -> "CATEGORY_MISMATCH".equals(i.code())));
    }

    @Test
    void merchantNotApprovedIsHardReject() {
        Merchant m = new Merchant();
        m.setMerId(1L);
        m.setReviewStatus(0);
        when(merchantMapper.selectById(1L)).thenReturn(m);
        ProductReviewRuleEngine.RuleResult r = engine.evaluate(
                product("工业轴承 高精度轴承", "详细参数与交付说明齐全，可提供质检报告。"));
        assertTrue(r.hardReject());
        assertTrue(r.issues().stream().anyMatch(i -> "MERCHANT_NOT_APPROVED".equals(i.code())));
    }

    @Test
    void absoluteClaimAndThinContentDeductScore() {
        ProductReviewRuleEngine.RuleResult r = engine.evaluate(
                product("全网第一 万能阀门", "好货"));
        assertFalse(r.hardReject());
        assertTrue(r.issues().stream().anyMatch(i -> "ABSOLUTE_CLAIM".equals(i.code())));
        assertTrue(r.issues().stream().anyMatch(i -> "TITLE_TOO_SHORT".equals(i.code())));
        assertTrue(r.score() < 85);
    }

    @Test
    void expandedAbsoluteWordsAreWarnNotHard() {
        // 扩充的广告法绝对化词：仅 WARN 扣分，绝不触发 HARD 红线
        ProductReviewRuleEngine.RuleResult r = engine.evaluate(
                product("顶级 最优质 工业管材 现货供应", "首家自营，行业垄断，包治百病，极品好货，交付说明齐全可开发票。"));
        assertFalse(r.hardReject());
        assertTrue(r.issues().stream().anyMatch(i -> "ABSOLUTE_CLAIM".equals(i.code())));
        assertTrue(r.score() < 100);
    }
}
