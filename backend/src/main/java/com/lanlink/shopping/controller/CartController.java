package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.dto.CartAddDTO;
import com.lanlink.shopping.service.CartService;
import com.lanlink.shopping.vo.CartItemVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 购物车 (需登录)
 */
@RestController
@RequestMapping("/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @PostMapping("/add")
    public R<Void> add(@Valid @RequestBody CartAddDTO dto, HttpServletRequest request) {
        cartService.add(UserContext.currentUserId(request), dto.getProdId(), dto.getQuantity());
        return R.ok("已加入购物车", null);
    }

    @GetMapping("/list")
    public R<List<CartItemVO>> list(HttpServletRequest request) {
        return R.ok(cartService.list(UserContext.currentUserId(request)));
    }

    @PostMapping("/quantity/{cartId}")
    public R<Void> updateQuantity(@PathVariable Long cartId, @RequestParam Integer quantity,
                                  HttpServletRequest request) {
        cartService.updateQuantity(UserContext.currentUserId(request), cartId, quantity);
        return R.ok();
    }

    @PostMapping("/checked/{cartId}")
    public R<Void> checked(@PathVariable Long cartId, @RequestParam Integer checked,
                           HttpServletRequest request) {
        cartService.setChecked(UserContext.currentUserId(request), cartId, checked);
        return R.ok();
    }

    @DeleteMapping("/{cartId}")
    public R<Void> remove(@PathVariable Long cartId, HttpServletRequest request) {
        cartService.remove(UserContext.currentUserId(request), cartId);
        return R.ok();
    }
}
