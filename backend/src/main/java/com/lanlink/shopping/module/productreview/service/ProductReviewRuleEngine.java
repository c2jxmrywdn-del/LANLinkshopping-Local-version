package com.lanlink.shopping.module.productreview.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.entity.Category;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CategoryMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.pricemonitor.mapper.MarketBenchmarkMapper;
import com.lanlink.shopping.module.pricemonitor.service.AnalysisSafetyFilter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 商品自动审核规则引擎（确定性第一层）：
 *  - HARD：必须人工介入或直接驳回的红线（有害/违禁内容、类目错放、资质/必填缺失、价格越界）；
 *  - WARN：降低评分并提示的软问题（广告法绝对化用语、内容单薄、价格偏离、重复铺货、站外联系方式）。
 * 规则结果作为 AI 复核的输入与最终决策的硬边界，AI 不得覆盖 HARD 结论。
 */
@Service
public class ProductReviewRuleEngine {

    /** 广告法绝对化/夸大用语（命中扣分，严重词组直接 HARD）。 */
    private static final String[] AD_ABSOLUTE_WORDS = {
            "国家级", "世界级", "最高级", "最佳", "第一品牌", "全网第一", "销量第一",
            "绝无仅有", "独一无二", "史无前例", "万能", "100%", "永久", "零风险", "绝对安全"
    };

    /** 平台禁售/高危品类词（演示起步集，正式环境应接内容安全服务）。 */
    private static final String[] PROHIBITED_WORDS = {
            "枪支", "弹药", "雷管", "炸药", "毒品", "冰毒", "海洛因", "大麻",
            "赌博", "博彩", "色情", "淫秽", "假币", "假证", "代办证件", "发票代开",
            "洗钱", "传销", "个人信息买卖", "身份证信息", "银行卡买卖", "象牙", "野生动物"
    };

    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern CONTACT = Pattern.compile("(?i)(微信|加v|vx|qq|二维码|扫码加)");

    private final MerchantMapper merchantMapper;
    private final CategoryMapper categoryMapper;
    private final ProductMapper productMapper;
    private final MarketBenchmarkMapper benchmarkMapper;
    private final AnalysisSafetyFilter safetyFilter;

    public ProductReviewRuleEngine(MerchantMapper merchantMapper, CategoryMapper categoryMapper,
                                   ProductMapper productMapper,
                                   MarketBenchmarkMapper benchmarkMapper,
                                   AnalysisSafetyFilter safetyFilter) {
        this.merchantMapper = merchantMapper;
        this.categoryMapper = categoryMapper;
        this.productMapper = productMapper;
        this.benchmarkMapper = benchmarkMapper;
        this.safetyFilter = safetyFilter;
    }

    public record Issue(String code, String severity, String message) {}

    public record RuleResult(boolean hardReject, int score, List<Issue> issues,
                             Map<String, Object> dimensions) {
        public boolean hasWarnings() {
            return issues.stream().anyMatch(i -> "WARN".equals(i.severity()));
        }
    }

    public RuleResult evaluate(Product p) {
        List<Issue> issues = new ArrayList<>();
        Map<String, Object> dims = new LinkedHashMap<>();
        if (p == null) {
            issues.add(new Issue("PRODUCT_MISSING", "HARD", "商品不存在"));
            return new RuleResult(true, 0, issues, dims);
        }

        // ===== 商户资质 =====
        Merchant m = p.getMerId() == null ? null : merchantMapper.selectById(p.getMerId());
        boolean merchantApproved = m != null && m.getReviewStatus() != null && m.getReviewStatus() == 1;
        dims.put("merchantApproved", merchantApproved);
        if (!merchantApproved) issues.add(new Issue("MERCHANT_NOT_APPROVED", "HARD", "商户未通过平台审核"));

        // ===== 必填与边界 =====
        String title = p.getTitle() == null ? "" : p.getTitle().trim();
        String detail = p.getDetail() == null ? "" : p.getDetail().trim();
        dims.put("titleLength", title.length());
        dims.put("detailLength", detail.length());
        if (title.isEmpty()) issues.add(new Issue("MISSING_TITLE", "HARD", "商品名称不能为空"));
        if (!StringUtils.hasText(p.getCoverUrl())) issues.add(new Issue("MISSING_COVER", "HARD", "商品主图不能为空"));
        if (p.getPrice() == null || p.getPrice().signum() <= 0) {
            issues.add(new Issue("INVALID_PRICE", "HARD", "商品价格必须大于 0"));
        }
        if (p.getStock() == null || p.getStock() <= 0) {
            issues.add(new Issue("INVALID_STOCK", "HARD", "商品库存必须大于 0"));
        }
        if (p.getCatId() == null) issues.add(new Issue("MISSING_CATEGORY", "HARD", "请选择商品分类"));
        if (p.getIndId() == null) issues.add(new Issue("MISSING_INDUSTRY", "HARD", "请选择商品行业"));

        // ===== 类目与行业一致性 =====
        Category cat = p.getCatId() == null ? null : categoryMapper.selectById(p.getCatId());
        boolean categoryMatches = cat != null && p.getIndId() != null && cat.getIndId() != null
                && cat.getIndId().equals(p.getIndId());
        dims.put("categoryMatchesIndustry", categoryMatches);
        if (cat != null && cat.getIndId() != null && p.getIndId() != null && !categoryMatches) {
            issues.add(new Issue("CATEGORY_MISMATCH", "HARD", "商品分类与所选行业不一致"));
        }

        // ===== 有害/违禁内容 =====
        String merged = title + "\n" + detail;
        AnalysisSafetyFilter.SafetyVerdict harmful = safetyFilter.checkText(merged);
        dims.put("harmful", !harmful.clean());
        if (!harmful.clean()) {
            issues.add(new Issue("HARMFUL_CONTENT", "HARD", harmful.reason()));
        }
        List<String> prohibitedHits = hits(merged, PROHIBITED_WORDS);
        dims.put("prohibitedHits", prohibitedHits);
        if (!prohibitedHits.isEmpty()) {
            issues.add(new Issue("PROHIBITED_GOODS", "HARD", "命中禁售/高危品类词：" + String.join("、", prohibitedHits)));
        }

        // ===== 广告法绝对化用语 =====
        List<String> adHits = hits(merged, AD_ABSOLUTE_WORDS);
        dims.put("adHits", adHits);
        if (!adHits.isEmpty()) {
            issues.add(new Issue("ABSOLUTE_CLAIM", "WARN", "疑似绝对化/夸大用语：" + String.join("、", adHits)));
        }

        // ===== 内容完整度 =====
        if (title.length() < 10) issues.add(new Issue("TITLE_TOO_SHORT", "WARN", "商品名称过短，信息不足"));
        if (detail.length() < 30) issues.add(new Issue("DETAIL_TOO_SHORT", "WARN", "商品描述过短，建议补充材质/用途/交付说明"));
        if (!StringUtils.hasText(p.getBrand())) issues.add(new Issue("MISSING_BRAND", "WARN", "未填写品牌"));
        if (!StringUtils.hasText(p.getSpec())) issues.add(new Issue("MISSING_SPEC", "WARN", "未填写规格参数"));
        if (PHONE.matcher(merged).find() || CONTACT.matcher(merged).find()) {
            issues.add(new Issue("CONTACT_INFO", "WARN", "商品文本疑似包含站外联系方式"));
        }

        // ===== 价格合理性（行业基准偏离） =====
        BigDecimal deviationPct = null;
        try {
            if (p.getIndId() != null && p.getPrice() != null) {
                BigDecimal avg = benchmarkMapper.avgPriceByIndustry(p.getIndId());
                if (avg != null && avg.signum() > 0) {
                    deviationPct = p.getPrice().subtract(avg).abs()
                            .divide(avg, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                    dims.put("priceDeviationPct", deviationPct.setScale(1, RoundingMode.HALF_UP));
                    if (deviationPct.compareTo(BigDecimal.valueOf(300)) > 0) {
                        issues.add(new Issue("PRICE_FAR_FROM_MARKET", "WARN",
                                "价格偏离行业均价 " + deviationPct.setScale(0, RoundingMode.HALF_UP) + "%，需人工确认"));
                    }
                }
            }
        } catch (Exception ignore) { /* 基准缺失不阻断审核 */ }

        // ===== 重复铺货（同商户 30 天内标题重复） =====
        boolean duplicateTitle = false;
        try {
            if (p.getMerId() != null && p.getProdId() != null && !title.isEmpty()) {
                duplicateTitle = productMapper.selectCount(Wrappers.<Product>lambdaQuery()
                        .eq(Product::getMerId, p.getMerId())
                        .eq(Product::getTitle, title)
                        .ne(Product::getProdId, p.getProdId())
                        .ge(Product::getCreateTime, LocalDateTime.now().minusDays(30))) > 0;
            }
        } catch (Exception ignore) { /* 查询失败不阻断审核 */ }
        dims.put("duplicateTitle", duplicateTitle);
        if (duplicateTitle) issues.add(new Issue("DUPLICATE_LISTING", "WARN", "30 天内存在同标题商品，疑似重复铺货"));

        // ===== 评分 =====
        int score = 100;
        score -= Math.min(24, adHits.size() * 8);
        if (title.length() < 10) score -= 8;
        if (detail.length() < 30) score -= 8;
        if (!StringUtils.hasText(p.getBrand())) score -= 4;
        if (!StringUtils.hasText(p.getSpec())) score -= 4;
        if (deviationPct != null && deviationPct.compareTo(BigDecimal.valueOf(300)) > 0) score -= 15;
        if (duplicateTitle) score -= 10;
        if (PHONE.matcher(merged).find() || CONTACT.matcher(merged).find()) score -= 12;
        score = Math.max(0, Math.min(100, score));

        boolean hard = issues.stream().anyMatch(i -> "HARD".equals(i.severity()));
        if (hard) score = 0; // 命中红线直接 0 分，避免下游误用评分
        dims.put("ruleScore", score);
        return new RuleResult(hard, score, List.copyOf(issues), dims);
    }

    private static List<String> hits(String text, String[] words) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(text)) return out;
        String lower = text.toLowerCase();
        for (String w : words) {
            if (lower.contains(w.toLowerCase())) out.add(w);
        }
        return out;
    }
}
