package com.lanlink.shopping.module.productreview.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 自动审核配置单测：无视觉自动通过开关默认关闭、view 暴露该键、白名单可持久化 true/false。
 */
class ProductReviewConfigServiceTest {

    private JdbcTemplate jdbcTemplate;
    private ProductReviewConfigService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new ProductReviewConfigService(jdbcTemplate);
    }

    @Test
    void allowApproveWithoutVisionDefaultsFalseInView() {
        Map<String, Object> view = service.view();
        assertEquals(false, view.get(ProductReviewConfigService.KEY_ALLOW_APPROVE_WITHOUT_VISION));
        assertFalse(service.allowApproveWithoutVision());
    }

    @Test
    void updatePersistsAllowApproveWithoutVisionTrue() {
        service.update(Map.of(ProductReviewConfigService.KEY_ALLOW_APPROVE_WITHOUT_VISION, true));
        verify(jdbcTemplate).update(
                "UPDATE t_product_review_config SET config_value = ? WHERE config_key = ?",
                "true", ProductReviewConfigService.KEY_ALLOW_APPROVE_WITHOUT_VISION);
    }

    @Test
    void updatePersistsAllowApproveWithoutVisionFalse() {
        service.update(Map.of(ProductReviewConfigService.KEY_ALLOW_APPROVE_WITHOUT_VISION, false));
        verify(jdbcTemplate).update(
                "UPDATE t_product_review_config SET config_value = ? WHERE config_key = ?",
                "false", ProductReviewConfigService.KEY_ALLOW_APPROVE_WITHOUT_VISION);
    }
}