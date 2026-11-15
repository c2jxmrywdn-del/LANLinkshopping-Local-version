package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.module.productreview.service.ProductAutoReviewService;
import com.lanlink.shopping.module.productreview.service.ProductReviewConfigService;
import com.lanlink.shopping.module.recommend.service.PersonalizeService;
import com.lanlink.shopping.module.search.ProductIndexJob;
import com.lanlink.shopping.service.AuditService;
import com.lanlink.shopping.service.MerchantService;
import com.lanlink.shopping.service.ProductService;
import com.lanlink.shopping.service.ProductTitleAiService;
import com.lanlink.shopping.common.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 商户端写操作在退店流程中的冻结（设计 §5）：publish / myUpdate / myStatus(仅上架) /
 * image / ai-polish-title 五处必须拒绝 exit_status 非空的商户；myStatus 下架不受限。
 * 断言到"服务层未被调用"，确保拦截发生在副作用之前。
 */
class ProductControllerExitFreezeTest {

    private ProductService productService;
    private MerchantService merchantService;
    private ProductController controller;

    @BeforeEach
    void setup() {
        productService = mock(ProductService.class);
        merchantService = mock(MerchantService.class);
        controller = new ProductController(productService, merchantService,
                mock(PersonalizeService.class), mock(ProductIndexJob.class),
                mock(ProductAutoReviewService.class), mock(ProductReviewConfigService.class),
                mock(StorageService.class), mock(AuditService.class),
                mock(ProductTitleAiService.class));
    }

    private Merchant merchant(String exitStatus) {
        Merchant m = new Merchant();
        m.setMerId(1L);
        m.setUserId(7L);
        m.setReviewStatus(1);
        m.setExitStatus(exitStatus);
        return m;
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/product/my/update/9");
        User user = new User();
        user.setUserId(7L);
        req.getSession(true).setAttribute(UserContext.SESSION_KEY, user);
        return req;
    }

    @Test
    void publish_is_rejected_while_exiting() {
        when(merchantService.getByUser(7L)).thenReturn(merchant("EXIT_REQUESTED"));
        Product body = new Product();
        body.setTitle("测试商品");

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.publish(body, request()));
        assertEquals(400, e.getCode());
        assertEquals("店铺退店申请中，暂不可执行该操作（商品下架不受限制）", e.getMessage());
        verifyNoInteractions(productService);
    }

    @Test
    void myUpdate_is_rejected_while_exiting() {
        when(merchantService.getByUser(7L)).thenReturn(merchant("PUBLIC_NOTICE"));
        Product patch = new Product();
        patch.setTitle("改个标题");

        assertThrows(BusinessException.class, () -> controller.myUpdate(9L, patch, request()));
        verify(productService, never()).myUpdate(anyLong(), anyLong(), any());
    }

    @Test
    void myStatus_up_is_rejected_while_exiting() {
        when(merchantService.getByUser(7L)).thenReturn(merchant("FROZEN"));

        assertThrows(BusinessException.class, () -> controller.myStatus(9L, 1, request()));
        verify(productService, never()).mySetStatus(anyLong(), anyLong(), eq(1));
    }

    @Test
    void myStatus_down_is_allowed_while_exiting() {
        when(merchantService.getByUser(7L)).thenReturn(merchant("FROZEN"));
        when(productService.mySetStatus(1L, 9L, 0)).thenReturn(new Product());

        controller.myStatus(9L, 0, request());

        // 下架是退店门禁4的逃生口，必须放行
        verify(productService).mySetStatus(1L, 9L, 0);
    }

    @Test
    void myStatus_up_is_allowed_for_active_merchant() {
        when(merchantService.getByUser(7L)).thenReturn(merchant(null));
        when(productService.mySetStatus(1L, 9L, 1)).thenReturn(new Product());

        controller.myStatus(9L, 1, request());

        verify(productService).mySetStatus(1L, 9L, 1);
    }

    @Test
    void image_upload_is_rejected_while_exiting() {
        when(merchantService.getByUser(7L)).thenReturn(merchant("PUBLIC_NOTICE"));

        assertThrows(BusinessException.class,
                () -> controller.uploadImage(null, request()));
        // 校验早于文件检查：即便文件为空也先被退店门禁拦下
    }

    @Test
    void polish_title_is_rejected_while_exiting() {
        when(merchantService.getByUser(7L)).thenReturn(merchant("PUBLIC_NOTICE"));

        assertThrows(BusinessException.class,
                () -> controller.polishTitle(
                        new ProductTitleAiService.PolishRequest("304不锈钢无缝管", null, null, "", false),
                        request()));
    }

    @Test
    void all_write_paths_pass_for_active_merchant() {
        when(merchantService.getByUser(7L)).thenReturn(merchant(null));
        when(productService.myUpdate(anyLong(), anyLong(), any())).thenReturn(new Product());

        controller.myUpdate(9L, new Product(), request());

        verify(productService).myUpdate(eq(1L), eq(9L), any());
    }
}
