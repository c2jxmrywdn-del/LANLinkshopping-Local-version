package com.lanlink.shopping.service;

import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 管理端合规初筛与自动审核规则同源：adminDetail 的违禁词扫描须复用 ProductReviewRuleEngine 的
 * 绝对化词表，命中范围一致，且不误伤「绝对值」这类正常技术词。
 */
class ProductServiceAdminComplianceTest {

    private ProductMapper productMapper;
    private ProductService svc;

    @BeforeEach
    void setup() {
        productMapper = mock(ProductMapper.class);
        svc = new ProductService(productMapper, mock(MerchantMapper.class), null, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private List<String> bannedHits(String title, String detail) {
        Product p = new Product();
        p.setProdId(1L);
        p.setTitle(title);
        p.setDetail(detail);
        when(productMapper.selectById(1L)).thenReturn(p);
        Map<String, Object> out = svc.adminDetail(1L);
        Map<String, Object> compliance = (Map<String, Object>) out.get("compliance");
        return (List<String>) compliance.get("bannedWordsHit");
    }

    @Test
    void scansWordsOnlyPresentInRuleEngineList() {
        // 世界级 / 最高级 仅存在于规则引擎词表，旧管理端硬编码清单漏检
        List<String> hits = bannedHits("世界级最高级工艺", "交付说明齐全。");
        assertTrue(hits.contains("世界级"));
        assertTrue(hits.contains("最高级"));
    }

    @Test
    void doesNotFlagAbsoluteValueAsAbsoluteClaim() {
        // 对齐后不再把「绝对值」误判为绝对化用语
        List<String> hits = bannedHits("公差绝对值 0.02mm", "工业管材现货供应。");
        assertFalse(hits.contains("绝对"));
    }
}