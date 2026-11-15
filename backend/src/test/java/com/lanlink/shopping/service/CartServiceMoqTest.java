package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Cart;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CartMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.vo.CartItemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 购物车侧 MOQ（P1-17）：加购与改数量共用同一阶梯起订量口径，
 * checkout 的服务端兜底之外，写路径在购物车环节就给出可读提示。
 */
class CartServiceMoqTest {

    private CartMapper cartMapper;
    private ProductMapper productMapper;
    private CartService cartService;

    @BeforeEach
    void setUp() {
        cartMapper = mock(CartMapper.class);
        productMapper = mock(ProductMapper.class);
        cartService = new CartService(cartMapper, productMapper);
    }

    private static Product product(long prodId, String tierJson) {
        Product p = new Product();
        p.setProdId(prodId);
        p.setTitle("钢材 A");
        p.setPrice(new BigDecimal("100.00"));
        p.setStatus(1);
        p.setStock(500);
        p.setTierPriceJson(tierJson);
        return p;
    }

    // ===== 加购 =====

    @Test
    void addBelowMoqIsRejectedWithReadableMessage() {
        when(productMapper.selectById(101L)).thenReturn(
                product(101L, "[{\"minQty\":10,\"price\":88.00}]"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.add(7L, 101L, 5));

        assertEquals("该商品起订量为 10 件，请调整购买数量", ex.getMessage());
        verify(cartMapper, never()).insert(any(Cart.class));
    }

    @Test
    void addAtMoqSucceeds() {
        when(productMapper.selectById(101L)).thenReturn(
                product(101L, "[{\"minQty\":10,\"price\":88.00}]"));

        cartService.add(7L, 101L, 10);

        // 加购改为单条原子 upsert（并发安全），不再走 selectOne + insert/updateById
        verify(cartMapper).upsertAdd(7L, 101L, 10);
    }

    /**
     * 并发加购同一商品必须由数据库做累加（quantity = quantity + ?），
     * 不能再「读旧值 → 内存相加 → 写回」，否则两个并发请求会丢失一次加购。
     */
    @Test
    void addMergesViaAtomicUpsertInsteadOfReadModifyWrite() {
        when(productMapper.selectById(101L)).thenReturn(
                product(101L, "[{\"minQty\":10,\"price\":88.00}]"));

        cartService.add(7L, 101L, 10);

        verify(cartMapper).upsertAdd(7L, 101L, 10);
        verify(cartMapper, never()).selectOne(any());
        verify(cartMapper, never()).insert(any(Cart.class));
        verify(cartMapper, never()).updateById(any(Cart.class));
    }

    @Test
    void addWithoutTiersHasNoMoq() {
        when(productMapper.selectById(101L)).thenReturn(product(101L, null));

        cartService.add(7L, 101L, 1);

        verify(cartMapper).upsertAdd(7L, 101L, 1);
    }

    // ===== 改数量 =====

    @Test
    void updateQuantityBelowMoqIsRejected() {
        Cart c = new Cart();
        c.setCartId(11L);
        c.setUserId(7L);
        c.setProdId(101L);
        c.setQuantity(10);
        c.setChecked(1);
        when(cartMapper.selectById(11L)).thenReturn(c);
        when(productMapper.selectById(101L)).thenReturn(
                product(101L, "[{\"minQty\":10,\"price\":88.00}]"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.updateQuantity(7L, 11L, 3));

        assertEquals("「钢材 A」起订量为 10 件，请调整购买数量", ex.getMessage());
        verify(cartMapper, never()).updateById(any(Cart.class));
    }

    @Test
    void updateQuantityAtMoqSucceeds() {
        Cart c = new Cart();
        c.setCartId(11L);
        c.setUserId(7L);
        c.setProdId(101L);
        c.setQuantity(20);
        c.setChecked(1);
        when(cartMapper.selectById(11L)).thenReturn(c);
        when(productMapper.selectById(101L)).thenReturn(
                product(101L, "[{\"minQty\":10,\"price\":88.00}]"));

        cartService.updateQuantity(7L, 11L, 10);

        assertEquals(Integer.valueOf(10), c.getQuantity());
        verify(cartMapper).updateById(c);
    }

    @Test
    void updateQuantityInvalidValueIsRejectedWithoutProductLookup() {
        Cart c = new Cart();
        c.setCartId(11L);
        c.setUserId(7L);
        c.setProdId(101L);
        c.setQuantity(10);
        c.setChecked(1);
        when(cartMapper.selectById(11L)).thenReturn(c);

        assertThrows(BusinessException.class, () -> cartService.updateQuantity(7L, 11L, null));
        assertThrows(BusinessException.class, () -> cartService.updateQuantity(7L, 11L, 0));
        verify(cartMapper, never()).updateById(any(Cart.class));
    }

    // ===== 列表展示口径：命中档单价（与 checkout 同源） =====

    @Test
    void listPricesAtTierUnitPriceForCurrentQuantity() {
        Cart c = new Cart();
        c.setCartId(11L);
        c.setUserId(7L);
        c.setProdId(101L);
        c.setQuantity(120);
        c.setChecked(1);
        when(cartMapper.selectList(any())).thenReturn(List.of(c));
        when(productMapper.selectById(101L)).thenReturn(
                product(101L, "[{\"minQty\":10,\"price\":88.00},{\"minQty\":100,\"price\":80.00}]"));

        List<CartItemVO> vos = cartService.list(7L);

        assertEquals(1, vos.size());
        assertEquals(0, new BigDecimal("80.00").compareTo(vos.get(0).getPrice()));
        assertEquals(0, new BigDecimal("9600.00").compareTo(vos.get(0).getSubtotal()));
    }
}
