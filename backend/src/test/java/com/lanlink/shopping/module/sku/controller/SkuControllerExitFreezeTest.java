package com.lanlink.shopping.module.sku.controller;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.module.sku.dto.SkuTreeSaveRequest;
import com.lanlink.shopping.module.sku.dto.SkuTreeView;
import com.lanlink.shopping.module.sku.service.SkuService;
import com.lanlink.shopping.service.MerchantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKU 保存在退店流程中的冻结（设计 §5）：save 是唯一 SKU 写入口，退店中必须拒绝；
 * load 是只读，不设门禁。
 */
class SkuControllerExitFreezeTest {

    private SkuService skuService;
    private MerchantService merchantService;
    private SkuController controller;

    @BeforeEach
    void setup() {
        skuService = mock(SkuService.class);
        merchantService = mock(MerchantService.class);
        controller = new SkuController(skuService, merchantService);
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
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/product/my/sku/9/save");
        User user = new User();
        user.setUserId(7L);
        req.getSession(true).setAttribute(UserContext.SESSION_KEY, user);
        return req;
    }

    @Test
    void save_is_rejected_while_exiting() {
        when(merchantService.getByUser(7L)).thenReturn(merchant("EXIT_REQUESTED"));

        assertThrows(BusinessException.class,
                () -> controller.save(9L, new SkuTreeSaveRequest(), request()));
        verify(skuService, never()).save(anyLong(), anyLong(), any());
    }

    @Test
    void load_is_allowed_while_exiting() {
        when(merchantService.getByUser(7L)).thenReturn(merchant("PUBLIC_NOTICE"));
        when(skuService.load(1L, 9L)).thenReturn(new SkuTreeView());

        controller.load(9L, request());

        // 只读树形结构不设退店门禁：商户退店中仍需查看自己的规格
        verify(skuService).load(1L, 9L);
    }

    @Test
    void save_proceeds_for_active_merchant() {
        when(merchantService.getByUser(7L)).thenReturn(merchant(null));
        when(skuService.save(anyLong(), anyLong(), any())).thenReturn(new SkuTreeView());

        controller.save(9L, new SkuTreeSaveRequest(), request());

        verify(skuService).save(anyLong(), anyLong(), any());
    }
}
