package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退店冻结在管理端上架路径上的回归：审核通过 / 手动上架 / 自动审核都不得把退店中商户的商品重新上架。
 * 商户端冻结（publish/myStatus/SKU/报价/活动）由 MerchantExitGuard 覆盖，真机探针验证。
 */
class ProductServiceExitFreezeTest {

    private ProductMapper productMapper;
    private MerchantMapper merchantMapper;
    private ProductService svc;

    @BeforeEach
    void setup() {
        // 纯单元测试不启动 MyBatis，需手动初始化实体元数据，LambdaUpdateWrapper 才能解析列名
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Product.class);
        productMapper = mock(ProductMapper.class);
        merchantMapper = mock(MerchantMapper.class);
        svc = new ProductService(productMapper, merchantMapper, null, null, null, null, null, null);
    }

    private Product product(Long prodId, Long merId) {
        Product p = new Product();
        p.setProdId(prodId);
        p.setMerId(merId);
        return p;
    }

    private Merchant merchant(String exitStatus) {
        Merchant m = new Merchant();
        m.setMerId(1L);
        m.setExitStatus(exitStatus);
        return m;
    }

    @Test
    void review_approve_is_blocked_while_exiting() {
        when(productMapper.selectById(9L)).thenReturn(product(9L, 1L));
        when(merchantMapper.selectById(1L)).thenReturn(merchant("PUBLIC_NOTICE"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.review(9L, 1, null));
        assertEquals(400, e.getCode());
        verify(productMapper, never()).update(eq(null), any());
    }

    @Test
    void review_reject_is_still_allowed_while_exiting() {
        when(productMapper.selectById(9L)).thenReturn(product(9L, 1L));
        when(merchantMapper.selectById(1L)).thenReturn(merchant("EXIT_REQUESTED"));
        when(productMapper.update(eq(null), any())).thenReturn(1);
        // 驳回不涉及上架，必须放行
        svc.review(9L, 2, "资料不全");
        verify(productMapper).update(eq(null), any());
    }

    @Test
    void admin_set_status_up_is_blocked_while_exiting() {
        when(productMapper.selectById(9L)).thenReturn(product(9L, 1L));
        when(merchantMapper.selectById(1L)).thenReturn(merchant("FROZEN"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.setStatus(9L, 1));
        assertEquals(400, e.getCode());
        verify(productMapper, never()).update(eq(null), any());
    }

    @Test
    void admin_set_status_down_is_allowed_while_exiting() {
        when(productMapper.selectById(9L)).thenReturn(product(9L, 1L));
        when(merchantMapper.selectById(1L)).thenReturn(merchant("EXIT_REQUESTED"));
        when(productMapper.update(eq(null), any())).thenReturn(1);
        svc.setStatus(9L, 0);
        verify(productMapper).update(eq(null), any());
    }

    @Test
    void auto_review_approve_returns_false_while_exiting() {
        when(productMapper.selectById(9L)).thenReturn(product(9L, 1L));
        when(merchantMapper.selectById(1L)).thenReturn(merchant("PUBLIC_NOTICE"));
        assertFalse(svc.applyAutoReviewDecision(9L, true, null, 90));
        verify(productMapper, never()).update(eq(null), any());
    }

    @Test
    void auto_review_reject_still_applies_while_exiting() {
        when(productMapper.selectById(9L)).thenReturn(product(9L, 1L));
        when(merchantMapper.selectById(1L)).thenReturn(merchant("PUBLIC_NOTICE"));
        when(productMapper.update(eq(null), any())).thenReturn(1);
        svc.applyAutoReviewDecision(9L, false, "不符合规范", 20);
        verify(productMapper).update(eq(null), any());
    }

    @Test
    void active_merchant_is_unaffected() {
        when(productMapper.selectById(9L)).thenReturn(product(9L, 1L));
        when(merchantMapper.selectById(1L)).thenReturn(merchant(null));
        when(productMapper.update(eq(null), any())).thenReturn(1);
        svc.review(9L, 1, null);
        verify(productMapper).update(eq(null), any());
    }
}
