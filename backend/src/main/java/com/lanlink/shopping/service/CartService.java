package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Cart;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CartMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.vo.CartItemVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 购物车服务
 */
@Service
public class CartService {

    private final CartMapper cartMapper;
    private final ProductMapper productMapper;

    public CartService(CartMapper cartMapper, ProductMapper productMapper) {
        this.cartMapper = cartMapper;
        this.productMapper = productMapper;
    }

    public void add(Long userId, Long prodId, Integer quantity) {
        Product p = productMapper.selectById(prodId);
        if (p == null || p.getStatus() == null || p.getStatus() != 1) {
            throw new BusinessException("商品不存在或已下架");
        }
        Cart exist = cartMapper.selectOne(Wrappers.<Cart>lambdaQuery()
                .eq(Cart::getUserId, userId).eq(Cart::getProdId, prodId));
        if (exist != null) {
            exist.setQuantity(exist.getQuantity() + quantity);
            cartMapper.updateById(exist);
        } else {
            Cart c = new Cart();
            c.setUserId(userId);
            c.setProdId(prodId);
            c.setQuantity(quantity);
            c.setChecked(1);
            c.setCreateTime(LocalDateTime.now());
            cartMapper.insert(c);
        }
    }

    public List<CartItemVO> list(Long userId) {
        List<Cart> carts = cartMapper.selectList(Wrappers.<Cart>lambdaQuery()
                .eq(Cart::getUserId, userId).orderByDesc(Cart::getCreateTime));
        List<CartItemVO> vos = new ArrayList<>();
        for (Cart c : carts) {
            Product p = productMapper.selectById(c.getProdId());
            if (p == null) continue;
            CartItemVO vo = new CartItemVO();
            vo.setCartId(c.getCartId());
            vo.setProdId(p.getProdId());
            vo.setTitle(p.getTitle());
            vo.setCoverUrl(p.getCoverUrl());
            vo.setPrice(p.getPrice());
            vo.setQuantity(c.getQuantity());
            vo.setStock(p.getStock());
            vo.setChecked(c.getChecked());
            vo.setSubtotal(p.getPrice().multiply(BigDecimal.valueOf(c.getQuantity())));
            vos.add(vo);
        }
        return vos;
    }

    public void setChecked(Long userId, Long cartId, Integer checked) {
        Cart c = cartMapper.selectById(cartId);
        if (c == null || !c.getUserId().equals(userId)) throw new BusinessException("购物车项不存在");
        c.setChecked(checked != null && checked != 0 ? 1 : 0);
        cartMapper.updateById(c);
    }

    public void remove(Long userId, Long cartId) {
        cartMapper.delete(Wrappers.<Cart>lambdaQuery()
                .eq(Cart::getUserId, userId).eq(Cart::getCartId, cartId));
    }

    public void updateQuantity(Long userId, Long cartId, Integer quantity) {
        Cart c = cartMapper.selectById(cartId);
        if (c == null || !c.getUserId().equals(userId)) throw new BusinessException("购物车项不存在");
        c.setQuantity(quantity);
        cartMapper.updateById(c);
    }
}
