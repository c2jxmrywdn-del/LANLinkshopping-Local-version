package com.lanlink.cloud.order;

import com.lanlink.cloud.api.ProductApi;
import com.lanlink.cloud.api.UserApi;
import com.lanlink.cloud.api.dto.OrderDTO;
import com.lanlink.cloud.api.dto.ProductDTO;
import com.lanlink.cloud.api.dto.UserDTO;
import com.lanlink.cloud.common.BusinessException;
import com.lanlink.cloud.common.R;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单契约实现（OrderApi 提供方）+ 下单接口。
 * 下单链路（跨服务契约调用）：
 *   Feign ProductApi  校验商品状态/价格（下单计价依据）
 *   Feign UserApi     校验下单人档案
 * 阶段 1 内存存储 + 演示计价，阶段 2 接入 t_order/t_order_item + 事务扣库存。
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final ProductApi productApi;
    private final UserApi userApi;
    private final Map<String, OrderDTO> store = new ConcurrentHashMap<>();

    public OrderController(ProductApi productApi, UserApi userApi) {
        this.productApi = productApi;
        this.userApi = userApi;
    }

    /** 下单（核心业务链路：Feign 校验商品 + 用户） */
    @PostMapping
    public R<OrderDTO> checkout(@RequestBody CheckoutRequest req) {
        // ① Feign 校验下单人
        R<UserDTO> ur = userApi.getById(req.userId());
        if (ur == null || !ur.isSuccess() || ur.getData() == null) {
            throw new BusinessException(404, "下单人不存在");
        }
        // ② Feign 校验商品（价格以 product-service 返回为准）
        R<ProductDTO> pr = productApi.getById(req.prodId());
        if (pr == null || !pr.isSuccess() || pr.getData() == null) {
            throw new BusinessException(404, "商品不存在");
        }
        ProductDTO p = pr.getData();
        if (p.getStatus() == null || p.getStatus() != 1) {
            throw new BusinessException(400, "商品暂不可购买: " + p.getTitle());
        }
        int qty = req.quantity() == null || req.quantity() <= 0 ? 1 : req.quantity();
        if (p.getStock() != null && p.getStock() < qty) {
            throw new BusinessException(400, "库存不足: " + p.getTitle());
        }
        // ③ 生成订单（阶段 2：事务扣库存 + 明细快照 + 积分抵现 1000 分=1 元）
        BigDecimal amount = p.getPrice().multiply(BigDecimal.valueOf(qty));
        OrderDTO o = new OrderDTO();
        o.setOrderNo(genOrderNo());
        o.setUserId(req.userId());
        o.setTotalAmount(amount);
        o.setPayStatus(0); // 0=待支付
        store.put(o.getOrderNo(), o);
        return R.ok("下单成功", o);
    }

    @GetMapping("/{orderNo}")
    public R<OrderDTO> getByOrderNo(@PathVariable String orderNo) {
        OrderDTO o = store.get(orderNo);
        if (o == null) throw new BusinessException(404, "订单不存在: " + orderNo);
        return R.ok(o);
    }

    /** 支付成功回写（payment-service 通过 Feign 调用） */
    @PutMapping("/{orderNo}/paid")
    public R<Void> markPaid(@PathVariable String orderNo,
                            @RequestParam String channel,
                            @RequestParam BigDecimal amount) {
        OrderDTO o = store.get(orderNo);
        if (o == null) throw new BusinessException(404, "订单不存在: " + orderNo);
        if (o.getTotalAmount() != null && o.getTotalAmount().compareTo(amount) != 0) {
            throw new BusinessException(400, "支付金额与订单金额不一致");
        }
        o.setPayStatus(1); // 1=已支付
        return R.ok(null);
    }

    private String genOrderNo() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    public record CheckoutRequest(Long userId, Long prodId, Integer quantity, String payType) {}
}
