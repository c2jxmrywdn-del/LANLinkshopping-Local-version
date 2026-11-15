package com.lanlink.shopping.service;

import com.lanlink.shopping.entity.StockFlow;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.mapper.StockFlowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 库存回补幂等（P1-3）：流水抢键成功才动库存，抢键失败一律不碰 {@code restoreStockAtomic}。
 *
 * <p>纯 Mockito 单测（仓库无 H2/Testcontainers 依赖，既有 service 测试同为此模式），
 * {@code @Transactional(propagation = MANDATORY)} 在无 Spring 代理时不会生效，不影响本测试。
 * 事务传播/回滚行为由后续真机集成验证覆盖。
 */
class StockServiceTest {

    private StockFlowMapper stockFlowMapper;
    private ProductMapper productMapper;
    private StockService stockService;

    @BeforeEach
    void setUp() {
        stockFlowMapper = mock(StockFlowMapper.class);
        productMapper = mock(ProductMapper.class);
        stockService = new StockService(stockFlowMapper, productMapper);
    }

    /** 核心用例：同一 business key 调两次，库存只加一次。 */
    @Test
    void sameBusinessKeyRestoresStockOnlyOnce() {
        // 首次抢键成功(1)，第二次唯一键冲突(0)
        when(stockFlowMapper.insertIgnore(any(StockFlow.class))).thenReturn(1, 0);
        when(productMapper.restoreStockAtomic(7L, 3)).thenReturn(1);

        boolean first = stockService.restore(StockService.BIZ_ORDER_CANCEL, "ON-1", 7L, 11L, 3);
        boolean second = stockService.restore(StockService.BIZ_ORDER_CANCEL, "ON-1", 7L, 11L, 3);

        assertTrue(first, "首次回补应执行");
        assertFalse(second, "重复回补应被幂等跳过");
        verify(productMapper, times(1)).restoreStockAtomic(7L, 3);
        verify(stockFlowMapper, times(2)).insertIgnore(any(StockFlow.class));
    }

    /** 不同 business key 各自生效：不同 bizType 或不同 bizNo 互不干扰。 */
    @Test
    void differentBusinessKeysEachTakeEffect() {
        when(stockFlowMapper.insertIgnore(any(StockFlow.class))).thenReturn(1);
        when(productMapper.restoreStockAtomic(anyLong(), anyInt())).thenReturn(1);

        assertTrue(stockService.restore(StockService.BIZ_ORDER_CANCEL, "ON-1", 7L, 11L, 3));
        assertTrue(stockService.restore(StockService.BIZ_RMA_RESTOCK, "RMA-9", 7L, 11L, 2));

        verify(productMapper, times(2)).restoreStockAtomic(anyLong(), anyInt());
    }

    /** 抢键失败（已回补过）时不得再调库存 UPDATE。 */
    @Test
    void claimedKeySkipsInventoryUpdate() {
        when(stockFlowMapper.insertIgnore(any(StockFlow.class))).thenReturn(0);

        assertFalse(stockService.restore(StockService.BIZ_ORDER_CLOSE, "ON-2", 7L, 11L, 1));

        verify(productMapper, never()).restoreStockAtomic(anyLong(), anyInt());
    }

    /** 流水必须完整携带审计字段，且 orderItemId 非空才能让唯一键生效。 */
    @Test
    void flowRowCarriesFullBusinessKeyAndAuditFields() {
        when(stockFlowMapper.insertIgnore(any(StockFlow.class))).thenReturn(1);
        when(productMapper.restoreStockAtomic(anyLong(), anyInt())).thenReturn(1);

        stockService.restore(StockService.BIZ_FULL_REFUND, "RF-1", 7L, 11L, 2, "ON-1", 99L, "平台复核通过");

        ArgumentCaptor<StockFlow> captor = ArgumentCaptor.forClass(StockFlow.class);
        verify(stockFlowMapper).insertIgnore(captor.capture());
        StockFlow saved = captor.getValue();
        assertEquals(StockService.BIZ_FULL_REFUND, saved.getBizType());
        assertEquals("RF-1", saved.getBizNo());
        assertEquals(7L, saved.getProdId());
        assertEquals(11L, saved.getOrderItemId());
        assertEquals(2, saved.getQuantity());
        assertEquals("ON-1", saved.getOrderNo());
        assertEquals(99L, saved.getOperatorId());
        assertEquals("平台复核通过", saved.getRemark());
    }

    /** 参数非法时 fail-fast，且不得产生任何 mapper 副作用。 */
    @Test
    void invalidArgumentsFailFastWithoutSideEffects() {
        assertThrows(IllegalArgumentException.class,
                () -> stockService.restore("", "ON-1", 7L, 11L, 1), "bizType 不能为空");
        assertThrows(IllegalArgumentException.class,
                () -> stockService.restore(StockService.BIZ_ORDER_CANCEL, " ", 7L, 11L, 1), "bizNo 不能为空");
        assertThrows(IllegalArgumentException.class,
                () -> stockService.restore(StockService.BIZ_ORDER_CANCEL, "ON-1", null, 11L, 1), "prodId 不能为空");
        assertThrows(IllegalArgumentException.class,
                () -> stockService.restore(StockService.BIZ_ORDER_CANCEL, "ON-1", 7L, null, 1),
                "orderItemId 不能为空（幂等键依赖非 NULL）");
        assertThrows(IllegalArgumentException.class,
                () -> stockService.restore(StockService.BIZ_ORDER_CANCEL, "ON-1", 7L, 11L, 0), "quantity 必须 > 0");

        verifyNoInteractions(stockFlowMapper, productMapper);
    }
}
