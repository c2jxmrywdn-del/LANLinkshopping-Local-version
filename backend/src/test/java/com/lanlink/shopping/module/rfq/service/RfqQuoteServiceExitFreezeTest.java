package com.lanlink.shopping.module.rfq.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.rfq.mapper.RfqMapper;
import com.lanlink.shopping.module.rfq.mapper.RfqQuoteMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RFQ 报价在退店流程中的冻结（设计 §5）：submitQuote 走可写入口，退店中一律拒绝；
 * 只读入口 requireMerchant 不受影响（商户仍可查看自己的报价）。
 */
class RfqQuoteServiceExitFreezeTest {

    private MerchantMapper merchantMapper;
    private RfqQuoteService svc;

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Merchant.class);
        merchantMapper = mock(MerchantMapper.class);
        svc = new RfqQuoteService(mock(RfqMapper.class), mock(RfqQuoteMapper.class),
                mock(ProductMapper.class), merchantMapper);
    }

    private Merchant merchant(String exitStatus) {
        Merchant m = new Merchant();
        m.setMerId(1L);
        m.setUserId(7L);
        m.setReviewStatus(1);
        m.setExitStatus(exitStatus);
        return m;
    }

    @Test
    void writable_merchant_resolution_is_rejected_while_exiting() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant("EXIT_REQUESTED"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.requireWritableMerchant(7L));
        assertEquals(400, e.getCode());
        assertEquals("店铺退店申请中，暂不可执行该操作（商品下架不受限制）", e.getMessage());
    }

    @Test
    void read_only_merchant_resolution_still_works_while_exiting() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant("PUBLIC_NOTICE"));

        // 只读入口不设退店门禁：商户退店公示期内仍可查看自己的报价记录
        assertEquals(1L, svc.requireMerchant(7L));
    }

    @Test
    void active_merchant_can_resolve_as_writable() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(null));

        assertEquals(1L, svc.requireWritableMerchant(7L));
    }
}
