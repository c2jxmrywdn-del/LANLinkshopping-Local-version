package com.lanlink.shopping.module.merchantexit.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.module.merchantexit.entity.MerchantDeposit;
import com.lanlink.shopping.module.merchantexit.mapper.MerchantDepositMapper;
import com.lanlink.shopping.module.merchantexit.mapper.MerchantExitLogMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 退店查询的分页边界：size 过小/过大/负数都必须收敛到安全区间，避免整表加载。 */
class MerchantExitQueryServicePagingTest {

    private MerchantDepositMapper depositMapper;
    private MerchantExitQueryService svc;

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MerchantDeposit.class);
        depositMapper = mock(MerchantDepositMapper.class);
        when(depositMapper.selectPage(any(), any())).thenReturn(new Page<>());
        svc = new MerchantExitQueryService(mock(JdbcTemplate.class), depositMapper,
                mock(MerchantExitLogMapper.class), mock(MerchantDepositService.class));
    }

    @SuppressWarnings("unchecked")
    private long pageSizeUsed(long requested) {
        ArgumentCaptor<IPage<MerchantDeposit>> cap = ArgumentCaptor.forClass(IPage.class);
        verify(depositMapper).selectPage(cap.capture(), any());
        return cap.getValue().getSize();
    }

    @Test
    void oversized_page_size_is_capped() {
        Map<String, Object> out = svc.deposits(1L, 1, 1_000_000);
        assertEquals(200L, out.get("size"), "size 必须被压到上限 200");
        assertEquals(200L, pageSizeUsed(1_000_000));
    }

    @Test
    void zero_or_negative_size_falls_back_to_default() {
        assertEquals(10L, svc.deposits(1L, 1, 0).get("size"));
        assertEquals(10L, svc.deposits(1L, 1, -5).get("size"));
    }

    @Test
    void zero_or_negative_current_falls_back_to_first_page() {
        assertEquals(1L, svc.deposits(1L, 0, 10).get("current"));
        assertEquals(1L, svc.deposits(1L, -3, 10).get("current"));
    }

    @Test
    void normal_size_passes_through() {
        assertEquals(50L, svc.deposits(1L, 2, 50).get("size"));
    }
}
