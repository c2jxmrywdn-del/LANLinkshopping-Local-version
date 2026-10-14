package com.lanlink.cloud.payment;

import com.lanlink.cloud.api.OrderApi;
import com.lanlink.cloud.api.dto.OrderDTO;
import com.lanlink.cloud.api.dto.PaymentCreateDTO;
import com.lanlink.cloud.api.dto.PaymentDTO;
import com.lanlink.cloud.common.BusinessException;
import com.lanlink.cloud.common.R;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 支付契约实现（PaymentApi 提供方）。
 * 支付链路（跨服务契约调用）：
 *   Feign OrderApi  取应付金额并校验 → 支付成功后 Feign 回写订单支付状态
 * 阶段 1 mock 支付（本地全链路可跑通），阶段 2 接入微信/支付宝沙箱 + 异步回调。
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final OrderApi orderApi;
    private final Map<String, PaymentDTO> store = new ConcurrentHashMap<>();

    public PaymentController(OrderApi orderApi) {
        this.orderApi = orderApi;
    }

    /** 发起支付：Feign 取订单金额 → mock 渠道立即支付 → Feign 回写订单状态 */
    @PostMapping
    public R<PaymentDTO> create(@RequestBody PaymentCreateDTO dto) {
        // ① Feign 取订单（金额以 order-service 为准）
        R<OrderDTO> or = orderApi.getByOrderNo(dto.getOrderNo());
        if (or == null || !or.isSuccess() || or.getData() == null) {
            throw new BusinessException(404, "订单不存在: " + dto.getOrderNo());
        }
        OrderDTO o = or.getData();
        if (o.getPayStatus() != null && o.getPayStatus() == 1) {
            throw new BusinessException(400, "订单已支付，请勿重复支付");
        }
        // ② mock 渠道：本地立即支付成功（阶段 2：真实渠道发起 + 异步回调）
        BigDecimal amount = o.getTotalAmount();
        PaymentDTO p = new PaymentDTO();
        p.setPayNo("PAY" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999));
        p.setOrderNo(o.getOrderNo());
        p.setChannel("mock");
        p.setAmount(amount);
        p.setStatus(1); // 1=支付成功
        p.setMock(true);
        store.put(p.getPayNo(), p);

        // ③ Feign 回写订单支付状态（闭环）
        R<Void> mr = orderApi.markPaid(o.getOrderNo(), "mock", amount);
        if (mr == null || !mr.isSuccess()) {
            throw new BusinessException(500, "订单支付状态回写失败");
        }
        return R.ok("支付成功（mock）", p);
    }

    /** 查单 */
    @GetMapping("/{payNo}")
    public R<PaymentDTO> query(@PathVariable String payNo) {
        PaymentDTO p = store.get(payNo);
        if (p == null) throw new BusinessException(404, "支付单不存在: " + payNo);
        return R.ok(p);
    }
}
