package com.lanlink.shopping.module.productreview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.productreview.entity.ProductReviewRecord;
import com.lanlink.shopping.module.productreview.event.ProductReviewRequestedEvent;
import com.lanlink.shopping.module.productreview.mapper.ProductReviewRecordMapper;
import com.lanlink.shopping.service.AuditService;
import com.lanlink.shopping.service.MessageService;
import com.lanlink.shopping.service.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 商品自动审核编排器（分级自动化）：
 *  1. 规则引擎先判 HARD 红线：命中即自动驳回（自动审核关闭时只记录建议）；
 *  2. 规则通过后调用文本 AI 与主图视觉 AI；
 *  3. 仅当规则分、AI 分均达阈值，且 AI 判 PASS/LOW、视觉判 match/LOW 时自动通过；
 *  4. 其余（AI 不可用、视觉不可用、风险不确定、评分不足）一律保留待审核转人工；
 *  5. 全程异步执行 + 条件原子更新，人工审核/并发处理优先，自动结论不覆盖已变更状态。
 */
@Service
public class ProductAutoReviewService {

    private static final Logger log = LoggerFactory.getLogger(ProductAutoReviewService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String AUTO_APPROVE = "AUTO_APPROVE";
    public static final String AUTO_REJECT = "AUTO_REJECT";
    public static final String MANUAL = "MANUAL";
    public static final String SUGGEST = "SUGGEST";

    private final ProductMapper productMapper;
    private final MerchantMapper merchantMapper;
    private final ProductReviewRuleEngine ruleEngine;
    private final ProductReviewAiService aiService;
    private final ProductReviewVisionService visionService;
    private final ProductReviewConfigService configService;
    private final ProductReviewRecordMapper recordMapper;
    private final ProductService productService;
    private final MessageService messageService;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = new ThreadPoolExecutor(
            2, 2, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500),
            r -> {
                Thread t = new Thread(r, "product-auto-review");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public ProductAutoReviewService(ProductMapper productMapper, MerchantMapper merchantMapper,
                                    ProductReviewRuleEngine ruleEngine, ProductReviewAiService aiService,
                                    ProductReviewVisionService visionService,
                                    ProductReviewConfigService configService,
                                    ProductReviewRecordMapper recordMapper, ProductService productService,
                                    MessageService messageService, AuditService auditService,
                                    JdbcTemplate jdbcTemplate) {
        this.productMapper = productMapper;
        this.merchantMapper = merchantMapper;
        this.ruleEngine = ruleEngine;
        this.aiService = aiService;
        this.visionService = visionService;
        this.configService = configService;
        this.recordMapper = recordMapper;
        this.productService = productService;
        this.messageService = messageService;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 事务提交后异步触发；同一商品并发触发去重，队列满时丢弃（人工审核仍是兜底）。 */
    public void submit(Long prodId) {
        if (prodId == null) return;
        try {
            executor.execute(() -> {
                if (!inFlight.add(prodId)) return;
                try {
                    reviewNow(prodId, false);
                } catch (Exception e) {
                    log.warn("[product-auto-review] prod {} failed: {}", prodId, e.getMessage());
                } finally {
                    inFlight.remove(prodId);
                }
            });
        } catch (Exception e) {
            log.warn("[product-auto-review] submit prod {} rejected: {}", prodId, e.getMessage());
        }
    }

    /**
     * 执行一轮审核（manual=true 为管理端重跑，不受每日 AI 预算限制仍记录结论）。
     * @return 本轮审核记录（商品不存在返回 null）
     */
    public ProductReviewRecord reviewNow(Long prodId, boolean manual) {
        long t0 = System.currentTimeMillis();
        Product p = productMapper.selectById(prodId);
        if (p == null) return null;

        ProductReviewRuleEngine.RuleResult rules = ruleEngine.evaluate(p);
        Optional<ProductReviewAiService.TextReview> ai = Optional.empty();
        Optional<ProductReviewVisionService.VisionReview> vision = Optional.empty();
        String model = "rule";

        boolean autoEnabled = configService.autoEnabled();
        boolean aiEnabled = configService.aiEnabled();
        boolean visionEnabled = configService.visionEnabled();
        boolean budgetAvailable = manual || aiBudgetAvailable();

        if (aiEnabled && budgetAvailable && !rules.hardReject()) {
            ai = aiService.review(p, rules);
            if (ai.isPresent()) model = "ai:" + ai.get().model();
        }
        if (visionEnabled && !rules.hardReject()) {
            vision = visionService.review(p);
            if (vision.isPresent()) model += "+vision:" + vision.get().model();
        }

        String decision;
        String rejectReason = null;
        int score = rules.score();

        if (rules.hardReject()) {
            decision = autoEnabled ? AUTO_REJECT : SUGGEST;
            rejectReason = joinHardIssues(rules);
            score = 0;
        } else {
            boolean aiPass = ai.isPresent()
                    && "PASS".equals(ai.get().decision()) && "LOW".equals(ai.get().risk())
                    && ai.get().score() >= configService.autoApproveScore()
                    && ai.get().issues().isEmpty();
            boolean visionPass = !visionEnabled || (vision.isPresent()
                    && vision.get().match() && "LOW".equals(vision.get().risk()));
            boolean scorePass = rules.score() >= configService.autoApproveScore();
            boolean imagesOk = !visionEnabled || (p.getCoverUrl() != null && !p.getCoverUrl().isBlank());

            if (autoEnabled && aiPass && visionPass && scorePass && imagesOk) {
                decision = AUTO_APPROVE;
            } else {
                decision = autoEnabled ? MANUAL : SUGGEST;
                if (ai.isEmpty()) {
                    rejectReason = "AI 复核不可用，等待人工审核";
                } else if (visionEnabled && vision.isEmpty()) {
                    rejectReason = "图像复核不可用，等待人工审核";
                } else if (!scorePass) {
                    rejectReason = "综合评分 " + rules.score() + " 未达自动通过阈值，等待人工审核";
                } else if (!aiPass) {
                    rejectReason = "AI 复核存在风险，等待人工审核";
                } else {
                    rejectReason = "图像/内容需人工确认";
                }
            }
        }

        if (autoEnabled && (AUTO_APPROVE.equals(decision) || AUTO_REJECT.equals(decision))) {
            boolean applied = productService.applyAutoReviewDecision(
                    prodId, AUTO_APPROVE.equals(decision), rejectReason, score);
            if (applied) notifyMerchant(p, AUTO_APPROVE.equals(decision), rejectReason);
            else decision = SUGGEST; // 已被人工/并发处理：保留本次自动结论为建议，不覆盖现状态
        }

        ProductReviewRecord rec = new ProductReviewRecord();
        rec.setProdId(prodId);
        rec.setDecision(decision);
        rec.setSource(manual ? "MANUAL" : "AUTO");
        rec.setScore(score);
        rec.setRuleResult(json(rules));
        rec.setAiResult(ai.map(ProductAutoReviewService::json).orElse(null));
        rec.setVisionResult(vision.map(ProductAutoReviewService::json).orElse(null));
        rec.setModel(model);
        rec.setElapsedMs((int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - t0));
        rec.setCreateTime(LocalDateTime.now());
        try {
            recordMapper.insert(rec);
        } catch (Exception e) {
            log.warn("[product-auto-review] save record failed prod={}: {}", prodId, e.getMessage());
        }
        try {
            auditService.record(0L, "PRODUCT_AUTO_REVIEW",
                    "商品 #" + prodId + " decision=" + decision + " score=" + score
                            + " ruleHard=" + rules.hardReject(), null);
        } catch (Exception ignore) { /* 审计旁路 */ }
        log.info("[product-auto-review] prod={} decision={} score={} elapsed={}ms",
                prodId, decision, score, rec.getElapsedMs());
        return rec;
    }

    public Map<String, Object> latest(Long prodId) {
        ProductReviewRecord rec = recordMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ProductReviewRecord>lambdaQuery()
                        .eq(ProductReviewRecord::getProdId, prodId)
                        .orderByDesc(ProductReviewRecord::getId)
                        .last("LIMIT 1"));
        if (rec == null) return Map.of();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("decision", rec.getDecision());
        out.put("source", rec.getSource());
        out.put("score", rec.getScore() == null ? 0 : rec.getScore());
        out.put("ruleResult", rec.getRuleResult() == null ? "" : rec.getRuleResult());
        out.put("aiResult", rec.getAiResult() == null ? "" : rec.getAiResult());
        out.put("visionResult", rec.getVisionResult() == null ? "" : rec.getVisionResult());
        out.put("model", rec.getModel() == null ? "" : rec.getModel());
        out.put("elapsedMs", rec.getElapsedMs() == null ? 0 : rec.getElapsedMs());
        out.put("createTime", rec.getCreateTime() == null ? "" : rec.getCreateTime().toString());
        return out;
    }

    private boolean aiBudgetAvailable() {
        int budget = configService.dailyAiBudget();
        if (budget <= 0) return false;
        try {
            Integer used = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_product_review_record "
                            + "WHERE DATE(create_time) = CURDATE() AND ai_result IS NOT NULL",
                    Integer.class);
            return used == null || used < budget;
        } catch (Exception e) {
            return true;
        }
    }

    private void notifyMerchant(Product p, boolean approved, String reason) {
        try {
            if (p.getMerId() == null || messageService == null) return;
            Merchant m = merchantMapper.selectById(p.getMerId());
            if (m == null || m.getUserId() == null) return;
            String title = approved ? "商品自动审核通过" : "商品自动审核未通过";
            String content = approved
                    ? "商品「" + p.getTitle() + "」已通过自动审核并上架，可正常销售。"
                    : "商品「" + p.getTitle() + "」未通过自动审核：" + (reason == null ? "请修改后重新提交" : reason);
            messageService.send(m.getUserId(), "system", title, content, "prod:" + p.getProdId());
        } catch (Exception e) {
            log.warn("[product-auto-review] notify merchant failed prod={}: {}", p.getProdId(), e.getMessage());
        }
    }

    private static String joinHardIssues(ProductReviewRuleEngine.RuleResult rules) {
        List<String> msgs = new ArrayList<>();
        for (ProductReviewRuleEngine.Issue i : rules.issues()) {
            if ("HARD".equals(i.severity())) msgs.add(i.message());
        }
        String joined = msgs.isEmpty() ? "商品不符合上架要求" : String.join("；", msgs);
        return joined.length() > 255 ? joined.substring(0, 255) : joined;
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
