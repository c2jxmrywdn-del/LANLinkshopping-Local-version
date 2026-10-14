package com.lanlink.cloud.cart;

import com.lanlink.cloud.api.ProductApi;
import com.lanlink.cloud.api.dto.CartItemDTO;
import com.lanlink.cloud.api.dto.ProductDTO;
import com.lanlink.cloud.common.BusinessException;
import com.lanlink.cloud.common.R;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 购物车契约实现（CartApi 提供方）。
 * 加购时通过 Feign 调 product-service 校验商品状态与价格（跨服务契约链路示例）。
 * 阶段 1 内存存储，阶段 2 接入 t_cart。
 */
@RestController
@RequestMapping("/carts")
public class CartController {

    private final ProductApi productApi;
    private final Map<Long, List<CartItemDTO>> store = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1000);

    public CartController(ProductApi productApi) {
        this.productApi = productApi;
    }

    @GetMapping("/{userId}/items")
    public R<List<CartItemDTO>> items(@PathVariable Long userId) {
        return R.ok(store.getOrDefault(userId, List.of()));
    }

    /** 加购：Feign 校验商品（真实跨服务调用） */
    @PostMapping("/{userId}/items")
    public R<CartItemDTO> add(@PathVariable Long userId, @RequestBody AddItem req) {
        R<ProductDTO> pr = productApi.getById(req.prodId());
        if (pr == null || !pr.isSuccess() || pr.getData() == null) {
            throw new BusinessException(404, "商品不存在");
        }
        ProductDTO p = pr.getData();
        if (p.getStatus() == null || p.getStatus() != 1) {
            throw new BusinessException(400, "商品暂不可购买: " + p.getTitle());
        }
        CartItemDTO item = new CartItemDTO();
        item.setCartId(seq.incrementAndGet());
        item.setProdId(p.getProdId());
        item.setTitle(p.getTitle());
        item.setPrice(p.getPrice());
        item.setQuantity(req.quantity() == null || req.quantity() <= 0 ? 1 : req.quantity());
        store.compute(userId, (k, v) -> {
            java.util.ArrayList<CartItemDTO> list = v == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(v);
            list.add(item);
            return list;
        });
        return R.ok("已加入购物车", item);
    }

    @PostMapping("/{userId}/remove")
    public R<Void> removeItems(@PathVariable Long userId, @RequestBody List<Long> cartIds) {
        store.computeIfPresent(userId, (k, v) -> v.stream()
                .filter(i -> !cartIds.contains(i.getCartId())).toList());
        return R.ok(null);
    }

    public record AddItem(Long prodId, Integer quantity) {}
}
