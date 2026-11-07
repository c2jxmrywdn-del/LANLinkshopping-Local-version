package com.lanlink.shopping.integration.mq;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.entity.CreditBill;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.mapper.CreditBillMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.service.AuditService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * order.paid 载荷真伪校验（security-hardening-plan.md P1-6.1，SR-5 伪造铸积分收口）。
 *
 * <p>消费者在入账（earnGrowth/autoJoinPurchase）之前调用 {@link #requireLegitOrderPaid}：
 * 反查 t_order 确认 ①订单存在 ②userId 与载荷一致 ③totalAmount 与载荷一致 ④pay_status=已支付。
 * 任一不符即拒绝——幂等键（userId,changeType,refOrderNo）只防重放不防伪造，伪造的
 * 全新 orderNo 只有命中真实订单/真实账单才能入账。
 *
 * <p>账期回退分支：t_credit_bill 的 orderNo 允许是物流结算单号（createBillForSettlement，
 * CreditTermService.settleBill 结清时发布 OrderPaidEvent），该路径没有 t_order 行——
 * 仅当订单查无此号时才走第二查（真实账单 + 同 userId + 金额一致 + 状态 PAID），
 * 伪造载荷两个表都命中不了，伪造面同样闭合。
 *
 * <p>拒绝路径：先审计（MQ_CONSUME_REJECTED，request=null 旁路写）再抛
 * {@link MessageIntegrityException}；spring-retry 3 次会各留一条审计（含重试噪音，可接受），
 * 最终经 lanlink.dlx 落 DLQ 取证。
 */
@Component
public class OrderPaidMessageValidator {

    private final OrderMapper orderMapper;
    private final CreditBillMapper creditBillMapper;
    private final AuditService auditService;

    public OrderPaidMessageValidator(OrderMapper orderMapper, CreditBillMapper creditBillMapper,
                                     AuditService auditService) {
        this.orderMapper = orderMapper;
        this.creditBillMapper = creditBillMapper;
        this.auditService = auditService;
    }

    public void requireLegitOrderPaid(OrderPaidMessage message) {
        Long userId = message.userId();
        String orderNo = message.orderNo();
        BigDecimal amount = message.amount();

        Order order = orderNo == null || orderNo.isBlank() ? null : orderMapper.selectByOrderNo(orderNo);
        if (order != null) {
            if (userId == null || !userId.equals(order.getUserId())) {
                reject(userId, orderNo, "userId 与订单归属不一致");
            }
            if (amount == null || order.getTotalAmount() == null
                    || order.getTotalAmount().compareTo(amount) != 0) {
                reject(userId, orderNo, "amount 与订单 totalAmount 不一致");
            }
            if (order.getPayStatus() == null || order.getPayStatus() != 1) {
                reject(userId, orderNo, "订单未支付（pay_status != 1）");
            }
            return;
        }

        // 无 t_order 行：只允许真实账期账单（物流结算单/账期单边缘形态）
        if (orderNo == null || orderNo.isBlank()) {
            reject(userId, orderNo, "orderNo 为空");
        }
        CreditBill bill = userId == null ? null : creditBillMapper.selectOne(Wrappers.<CreditBill>lambdaQuery()
                .eq(CreditBill::getOrderNo, orderNo)
                .eq(CreditBill::getUserId, userId)
                .last("LIMIT 1"));
        if (bill == null) {
            reject(userId, orderNo, "订单不存在（t_order/t_credit_bill 均无此单号）");
        }
        if (amount == null || bill.getAmount() == null || bill.getAmount().compareTo(amount) != 0) {
            reject(userId, orderNo, "amount 与账单金额不一致");
        }
        if (!"PAID".equals(bill.getStatus())) {
            reject(userId, orderNo, "账单未结清（status != PAID）");
        }
    }

    private void reject(Long userId, String orderNo, String reason) {
        // 审计旁路写入（AuditService 失败不反噬）；请求上下文在消费线程不存在，request=null
        auditService.record(userId, "MQ_CONSUME_REJECTED", "orderNo=" + orderNo + " reason=" + reason, null);
        throw new MessageIntegrityException("order.paid 载荷校验失败: " + reason + " orderNo=" + orderNo);
    }
}
