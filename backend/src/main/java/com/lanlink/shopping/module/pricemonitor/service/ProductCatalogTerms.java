package com.lanlink.shopping.module.pricemonitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.entity.Category;
import com.lanlink.shopping.entity.Industry;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CategoryMapper;
import com.lanlink.shopping.mapper.IndustryMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 在售商品词表（资讯相关性闸门的唯一数据源）。
 *
 * <p>写入侧（{@code ExternalInfoFetchService} 入库前）与读取侧（{@code MarketNewsService}
 * 首页每日资讯）共用同一份词表，避免"写入过滤一套、展示另一套"导致口径漂移。
 *
 * <p>词表来自「在售 + 审核通过」商品的标题/品牌/规格/分类/行业，10 分钟缓存；
 * 商品增删改后最多滞后 10 分钟，抓取周期（15 分钟）内必然收敛。
 */
@Service
public class ProductCatalogTerms {

    private static final Logger log = LoggerFactory.getLogger(ProductCatalogTerms.class);

    /** 过宽词不参与判定，否则几乎所有商业稿都会命中。 */
    static final java.util.Set<String> STOP = java.util.Set.of(
            "价格", "行情", "市场", "行业", "商品", "采购", "供应", "供给", "需求", "政策",
            "订单", "公司", "中国", "电子", "产品", "设备", "材料", "型号", "规格");

    /** 单字品类词信息量足够，允许参与匹配。 */
    private static final java.util.Set<String> SINGLE_CHAR =
            java.util.Set.of("钢", "铜", "铝", "锂", "锡", "镍", "锌", "煤", "油", "硅", "铁", "纸");

    private static final long TTL_MS = 10 * 60_000L;
    private static final int MAX_TERMS = 400;

    private final ProductMapper productMapper;
    private final IndustryMapper industryMapper;
    private final CategoryMapper categoryMapper;

    private volatile List<String> cached = List.of();
    private volatile long loadedAt;

    public ProductCatalogTerms(ProductMapper productMapper, IndustryMapper industryMapper,
                               CategoryMapper categoryMapper) {
        this.productMapper = productMapper;
        this.industryMapper = industryMapper;
        this.categoryMapper = categoryMapper;
    }

    /** 在售商品词表（带缓存）；查询失败返回空表，调用方按"无法判断"处理。 */
    public List<String> terms() {
        long now = System.currentTimeMillis();
        List<String> snapshot = cached;
        if (!snapshot.isEmpty() && now - loadedAt < TTL_MS) return snapshot;
        try {
            List<String> built = build();
            cached = built;
            loadedAt = now;
            return built;
        } catch (Exception e) {
            log.warn("[catalog-terms] load failed: {}", e.getMessage());
            return snapshot == null ? List.of() : snapshot;
        }
    }

    /** 资讯是否可能影响在售商品：标题/摘要命中任一商品词即算相关。 */
    public boolean affects(String title, String summary) {
        return affects(title, summary, terms());
    }

    /**
     * 词表匹配（纯函数）。
     *
     * @return 词表为空返回 false（无法判断相关性，由调用方决定兜底策略）
     */
    public static boolean affects(String title, String summary, List<String> terms) {
        if (terms == null || terms.isEmpty()) return false;
        String text = ((title == null ? "" : title) + " " + (summary == null ? "" : summary))
                .toLowerCase(Locale.ROOT);
        if (text.isBlank()) return false;
        for (String term : terms) {
            if (term == null) continue;
            String needle = term.trim().toLowerCase(Locale.ROOT);
            if (needle.isEmpty() || STOP.contains(needle)) continue;
            if (needle.length() < 2 && !SINGLE_CHAR.contains(needle)) continue;
            if (text.contains(needle)) return true;
        }
        return false;
    }

    private List<String> build() {
        List<Product> products = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                .eq(Product::getStatus, 1)
                .eq(Product::getReviewStatus, 1)
                .select(Product::getTitle, Product::getBrand, Product::getSpec, Product::getIndId, Product::getCatId)
                .last("LIMIT 500"));
        if (products == null || products.isEmpty()) return List.of();
        Map<Long, String> industries = new LinkedHashMap<>();
        if (industryMapper != null) {
            for (Industry industry : industryMapper.selectList(null)) {
                if (industry != null && industry.getIndId() != null && industry.getName() != null) {
                    industries.put(industry.getIndId(), industry.getName());
                }
            }
        }
        Map<Long, String> categories = new LinkedHashMap<>();
        if (categoryMapper != null) {
            for (Category category : categoryMapper.selectList(null)) {
                if (category != null && category.getCatId() != null && category.getName() != null) {
                    categories.put(category.getCatId(), category.getName());
                }
            }
        }
        java.util.LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (Product product : products) {
            String industryName = product.getIndId() == null ? "" : industries.getOrDefault(product.getIndId(), "");
            String categoryName = product.getCatId() == null ? "" : categories.getOrDefault(product.getCatId(), "");
            ProductFactorProfile profile = ProductFactorProfile.of(product, industryName, categoryName);
            for (String word : profile.productKeywords()) add(terms, word);
            for (String word : profile.industryKeywords()) add(terms, word);
            if (terms.size() >= MAX_TERMS) break;
        }
        return List.copyOf(terms);
    }

    private static void add(java.util.Set<String> terms, String word) {
        if (word == null) return;
        String term = word.trim();
        if (term.length() < 2 || STOP.contains(term)) return;
        terms.add(term);
    }
}
