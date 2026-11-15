package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.storage.StorageUrls;
import com.lanlink.shopping.entity.Cart;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.integration.pricing.TierPricing;
import com.lanlink.shopping.mapper.CartMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.vo.CartItemVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
        int qty = quantity == null ? 0 : quantity;
        // 阶梯价起订量（P1-17）：非空阶梯的最小档 minQty 为 MOQ，不足给可读文案
        int moq = TierPricing.minOrderQty(p.getTierPriceJson());
        if (moq > 0 && qty < moq) {
            throw new BusinessException("该商品起订量为 " + moq + " 件，请调整购买数量");
        }
        /*
         * 原子累加：并发加购同一商品时由数据库做 quantity = quantity + qty，
         * 不再「读旧值 → 内存相加 → 写回」（那个模式会丢失更新）。
         *
         * MOQ 校验仍放在加购入口：单次加购数量已在上方按 moq 校验过，
         * 累加后的总量只会更大，不会因为累加而低于起订量。
         */
        cartMapper.upsertAdd(userId, prodId, qty);
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
            vo.setCoverUrl(StorageUrls.publicUrlOf(p.getCoverUrl()));  // 出站拼公开绝对 URL（key/存量相对路径归一）
            // 购物车展示与结算同口径：按当前数量命中的阶梯单价展示，避免"车里一个价、下单另一个价"
            BigDecimal unitPrice = TierPricing.resolve(p.getPrice(), p.getTierPriceJson(), c.getQuantity()).unitPrice();
            vo.setPrice(unitPrice);
            vo.setQuantity(c.getQuantity());
            vo.setStock(p.getStock());
            vo.setChecked(c.getChecked());
            vo.setSubtotal(unitPrice.multiply(BigDecimal.valueOf(c.getQuantity())));
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
        if (quantity == null || quantity < 1) throw new BusinessException("购买数量需为正整数");
        // 阶梯价起订量（P1-17）：改数量与加购同口径，不足 MOQ 在购物车侧即给可读提示（checkout 仍服务端兜底）
        Product p = productMapper.selectById(c.getProdId());
        if (p != null) {
            int moq = TierPricing.minOrderQty(p.getTierPriceJson());
            if (moq > 0 && quantity < moq) {
                throw new BusinessException("「" + p.getTitle() + "」起订量为 " + moq + " 件，请调整购买数量");
            }
        }
        c.setQuantity(quantity);
        cartMapper.updateById(c);
    }
}
