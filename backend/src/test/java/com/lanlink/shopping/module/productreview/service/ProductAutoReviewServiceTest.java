package com.lanlink.shopping.module.productreview.service;

import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.productreview.entity.ProductReviewRecord;
import com.lanlink.shopping.module.productreview.mapper.ProductReviewRecordMapper;
import com.lanlink.shopping.service.AuditService;
import com.lanlink.shopping.service.MessageService;
import com.lanlink.shopping.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 自动审核决策单测：硬红线自动驳回、规则+文本+视觉全过自动通过、AI 不可用转人工。
 */
class ProductAutoReviewServiceTest {

    private ProductMapper productMapper;
    private MerchantMapper merchantMapper;
    private ProductReviewRuleEngine ruleEngine;
    private ProductReviewAiService aiService;
    private ProductReviewVisionService visionService;
    private ProductReviewConfigService configService;
    private ProductReviewRecordMapper recordMapper;
    private ProductService productService;
    private MessageService messageService;
    private AuditService auditService;
    private JdbcTemplate jdbcTemplate;
    private ProductAutoReviewService service;

    @BeforeEach
    void setUp() {
        productMapper = mock(ProductMapper.class);
        merchantMapper = mock(MerchantMapper.class);
        ruleEngine = mock(ProductReviewRuleEngine.class);
        aiService = mock(ProductReviewAiService.class);
        visionService = mock(ProductReviewVisionService.class);
        configService = mock(ProductReviewConfigService.class);
        recordMapper = mock(ProductReviewRecordMapper.class);
        productService = mock(ProductService.class);
        messageService = mock(MessageService.class);
        auditService = mock(AuditService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new ProductAutoReviewService(productMapper, merchantMapper, ruleEngine, aiService,
                visionService, configService, recordMapper, productService, messageService, auditService,
                jdbcTemplate);

        when(configService.autoEnabled()).thenReturn(true);
        when(configService.aiEnabled()).thenReturn(true);
        when(configService.visionEnabled()).thenReturn(true);
        when(configService.autoApproveScore()).thenReturn(85);
        when(configService.dailyAiBudget()).thenReturn(300);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        when(recordMapper.insert(any(ProductReviewRecord.class))).thenReturn(1);
        when(productService.applyAutoReviewDecision(any(), anyBoolean(), any(), anyInt())).thenReturn(true);
        Merchant m = new Merchant();
        m.setMerId(1L);
        m.setUserId(9L);
        when(merchantMapper.selectById(1L)).thenReturn(m);
    }

    private Product product() {
        Product p = new Product();
        p.setProdId(100L);
        p.setMerId(1L);
        p.setTitle("工业阀门 不锈钢球阀");
        p.setPrice(new BigDecimal("120"));
        p.setStock(10);
        p.setCoverUrl("/api/uploads/products/a.jpg");
        return p;
    }

    @Test
    void hardRuleAutoRejectsWithoutCallingAi() {
        when(productMapper.selectById(100L)).thenReturn(product());
        when(ruleEngine.evaluate(any())).thenReturn(new ProductReviewRuleEngine.RuleResult(
                true, 0, List.of(new ProductReviewRuleEngine.Issue("PROHIBITED_GOODS", "HARD", "命中禁售词")),
                Map.of()));

        ProductReviewRecord rec = service.reviewNow(100L, false);

        assertNotNull(rec);
        assertEquals(ProductAutoReviewService.AUTO_REJECT, rec.getDecision());
        verify(aiService, never()).review(any(), any());
        verify(productService).applyAutoReviewDecision(eq(100L), eq(false), any(), eq(0));
        verify(messageService).send(eq(9L), eq("system"), anyString(), anyString(), eq("prod:100"));
    }

    @Test
    void ruleAiVisionAllPassAutoApproves() {
        when(productMapper.selectById(100L)).thenReturn(product());
        when(ruleEngine.evaluate(any())).thenReturn(new ProductReviewRuleEngine.RuleResult(
                false, 92, List.of(), Map.of()));
        when(aiService.review(any(), any())).thenReturn(Optional.of(
                new ProductReviewAiService.TextReview("PASS", "LOW", 95, List.of(), "deepseek", "{}")));
        when(visionService.review(any())).thenReturn(Optional.of(
                new ProductReviewVisionService.VisionReview(true, "LOW", List.of(), "qwen2.5-vl", "{}")));

        ProductReviewRecord rec = service.reviewNow(100L, false);

        assertNotNull(rec);
        assertEquals(ProductAutoReviewService.AUTO_APPROVE, rec.getDecision());
        verify(productService).applyAutoReviewDecision(eq(100L), eq(true), any(), eq(92));
    }

    @Test
    void alreadyProcessedProductKeepsAutoVerdictAsSuggestion() {
        when(productMapper.selectById(100L)).thenReturn(product());
        when(ruleEngine.evaluate(any())).thenReturn(new ProductReviewRuleEngine.RuleResult(
                true, 0, List.of(new ProductReviewRuleEngine.Issue("PROHIBITED_GOODS", "HARD", "命中禁售词")),
                Map.of()));
        when(productService.applyAutoReviewDecision(any(), anyBoolean(), any(), anyInt())).thenReturn(false);

        ProductReviewRecord rec = service.reviewNow(100L, true);

        assertEquals(ProductAutoReviewService.SUGGEST, rec.getDecision());
    }

    @Test
    void aiPassWithIssuesFallsBackToManual() {
        when(productMapper.selectById(100L)).thenReturn(product());
        when(ruleEngine.evaluate(any())).thenReturn(new ProductReviewRuleEngine.RuleResult(
                false, 92, List.of(), Map.of()));
        when(aiService.review(any(), any())).thenReturn(Optional.of(
                new ProductReviewAiService.TextReview("PASS", "LOW", 95,
                        List.of(new ProductReviewAiService.AiIssue("CATEGORY_MISMATCH", "类目不符")),
                        "deepseek", "{}")));
        when(visionService.review(any())).thenReturn(Optional.of(
                new ProductReviewVisionService.VisionReview(true, "LOW", List.of(), "qwen2.5-vl", "{}")));

        ProductReviewRecord rec = service.reviewNow(100L, false);

        assertEquals(ProductAutoReviewService.MANUAL, rec.getDecision());
        verify(productService, never()).applyAutoReviewDecision(any(), anyBoolean(), any(), anyInt());
    }

    @Test
    void aiUnavailableFallsBackToManualWithoutApplying() {
        when(productMapper.selectById(100L)).thenReturn(product());
        when(ruleEngine.evaluate(any())).thenReturn(new ProductReviewRuleEngine.RuleResult(
                false, 92, List.of(), Map.of()));
        when(aiService.review(any(), any())).thenReturn(Optional.empty());
        when(visionService.review(any())).thenReturn(Optional.of(
                new ProductReviewVisionService.VisionReview(true, "LOW", List.of(), "qwen2.5-vl", "{}")));

        ProductReviewRecord rec = service.reviewNow(100L, false);

        assertNotNull(rec);
        assertEquals(ProductAutoReviewService.MANUAL, rec.getDecision());
        verify(productService, never()).applyAutoReviewDecision(any(), anyBoolean(), any(), anyInt());
    }
}
