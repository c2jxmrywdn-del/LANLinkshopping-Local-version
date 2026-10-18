package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.DesensitizeUtil;
import com.lanlink.shopping.dto.CheckoutDTO;
import com.lanlink.shopping.entity.*;
import com.lanlink.shopping.integration.event.OrderPaidEvent;
import com.lanlink.shopping.integration.pricing.PricingFacade;
import com.lanlink.shopping.module.membership.constant.MemberRule;
import com.lanlink.shopping.mapper.*;
import com.lanlink.shopping.vo.OrderDetailVO;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单服务(下单主流程含事务: 校验库存 -> 生成订单+明细 -> 扣库存 -> 清购物车)
 */
@Service
public class OrderService {

    private final CartMapper cartMapper;
    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final UserMapper userMapper;
    private final MessageService messageService;
    private final IdentityService identityService;
    private final AuditService auditService;
    private final PricingFacade pricingFacade;
    private final ApplicationEventPublisher eventPublisher;
    private final com.lanlink.shopping.module.membership.service.MembershipService membershipService;
    private final CreditTermService creditTermService;
    private final IdempotentService idempotentService;
    private final TamperGuardService tamperGuardService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AddressMapper addressMapper;

    public void setAddressMapper(AddressMapper addressMapper) {
        this.addressMapper = addressMapper;
    }

    /** 支付方式白名单（需求 1/3：与前端 validationRules.js 同契约，见 ValidationRules） */
    private static final java.util.Set<String> ALLOWED_PAY_TYPES =
            com.lanlink.shopping.common.ValidationRules.ALLOWED_PAY_TYPES;

    public OrderService(CartMapper cartMapper, ProductMapper productMapper, OrderMapper orderMapper,
                        OrderItemMapper orderItemMapper, UserMapper userMapper, MessageService messageService,
                        IdentityService identityService, AuditService auditService,
                        PricingFacade pricingFacade, ApplicationEventPublisher eventPublisher,
                        com.lanlink.shopping.module.membership.service.MembershipService membershipService,
                        CreditTermService creditTermService, IdempotentService idempotentService,
                        TamperGuardService tamperGuardService) {
        this.cartMapper = cartMapper;
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.userMapper = userMapper;
        this.messageService = messageService;
        this.identityService = identityService;
        this.auditService = auditService;
        this.pricingFacade = pricingFacade;
        this.eventPublisher = eventPublisher;
        this.membershipService = membershipService;
        this.creditTermService = creditTermService;
        this.idempotentService = idempotentService;
        this.tamperGuardService = tamperGuardService;
    }

    @Transactional
    public Order checkout(Long userId, CheckoutDTO dto) {
        // ===== 需求 2：幂等校验（防重复提交/并发重放） =====
        Long idemToken = idempotentService.reserve("checkout", dto.getIdempotentKey(), userId, null);
        try {
            Order order = doCheckout(userId, dto);
            idempotentService.bound(idemToken, order.getOrderNo());
            return order;
        } catch (RuntimeException e) {
            idempotentService.release(idemToken); // 失败释放，允许携带同一幂等键重试
            throw e;
        }
    }

    private Order doCheckout(Long userId, CheckoutDTO dto) {
        // ===== 需求 1：敏感字段后端独立校验，不信任前端 =====
        if (!ALLOWED_PAY_TYPES.contains(dto.getPayType() == null ? "" : dto.getPayType())) {
            // 需求 4：异常篡改请求记录告警（绕过前端校验/非法枚举值）
            tamperGuardService.report(userId, "order", "payType", dto.getPayType(),
                    String.join("/", ALLOWED_PAY_TYPES), "非法支付方式尝试(白名单外)", null);
            throw new BusinessException("非法支付方式");
        }
        if (dto.getUsePoints() != null && dto.getUsePoints() < 0) {
            tamperGuardService.report(userId, "order", "usePoints", String.valueOf(dto.getUsePoints()),
                    ">=0", "使用积分数为负数(参数篡改)", null);
            throw new BusinessException("使用积分数不能为负");
        }
        User user = userMapper.selectById(userId);
        List<Cart> carts;
        if (dto.getCartIds() != null && !dto.getCartIds().isEmpty()) {
            carts = cartMapper.selectList(Wrappers.<Cart>lambdaQuery()
                    .eq(Cart::getUserId, userId).in(Cart::getCartId, dto.getCartIds()));
        } else {
            carts = cartMapper.selectList(Wrappers.<Cart>lambdaQuery()
                    .eq(Cart::getUserId, userId).eq(Cart::getChecked, 1));
        }
        if (carts.isEmpty()) throw new BusinessException("购物车没有可结算的商品");

        Order order = new Order();
        order.setOrderNo(genOrderNo());
        order.setUserId(userId);
        order.setEntId(user == null ? null : user.getEntId());
        order.setPayType(dto.getPayType());
        order.setPayStatus("term".equals(dto.getPayType()) ? 1 : 0);
        order.setOrderStatus(0);
        String receiver = dto.getReceiver();
        String phone = dto.getPhone();
        if (DesensitizeUtil.isMasked(phone) || DesensitizeUtil.isMasked(receiver)) {
            if (addressMapper != null) {
                List<Address> addresses = addressMapper.selectList(Wrappers.<Address>lambdaQuery().eq(Address::getUserId, userId));
                for (Address a : addresses) {
                    if (DesensitizeUtil.isMasked(phone) && a.getPhone() != null
                            && (DesensitizeUtil.maskPhone(a.getPhone()).equals(phone) || a.getPhone().equals(phone))) {
                        phone = a.getPhone();
                    }
                    if (DesensitizeUtil.isMasked(receiver) && a.getReceiver() != null
                            && (DesensitizeUtil.maskRealName(a.getReceiver()).equals(receiver) || a.getReceiver().equals(receiver))) {
                        receiver = a.getReceiver();
                    }
                }
            }
            if (DesensitizeUtil.isMasked(phone) && user != null && user.getPhone() != null
                    && (DesensitizeUtil.maskPhone(user.getPhone()).equals(phone) || user.getPhone().equals(phone))) {
                phone = user.getPhone();
            }
        }
        order.setReceiver(receiver);
        order.setPhone(phone);
        order.setAddress(dto.getAddress());
        order.setRemark(dto.getRemark());
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        if ("term".equals(dto.getPayType())) order.setPayTime(LocalDateTime.now());

        // 先校验库存（并确认商品可售：仅审核通过的商品可下单），累计原价与行业
        // 需求 1：价格/金额由后端按商品当前价重算，禁止信任前端传入金额
        BigDecimal total = BigDecimal.ZERO;
        Long orderIndId = null;
        for (Cart c : carts) {
            Product p = productMapper.selectById(c.getProdId());
            if (p == null) throw new BusinessException("商品不存在: " + c.getProdId());
            if (p.getStatus() == null || p.getStatus() != 1) {
                throw new BusinessException("商品暂不可购买(未通过审核或已下架): " + p.getTitle());
            }
            if (p.getStock() < c.getQuantity()) throw new BusinessException("库存不足: " + p.getTitle());
            if (p.getPrice() == null || p.getPrice().signum() <= 0) {
                throw new BusinessException("商品价格异常: " + p.getTitle());
            }
            total = total.add(p.getPrice().multiply(BigDecimal.valueOf(c.getQuantity())));
            if (orderIndId == null) orderIndId = p.getIndId();
        }
        // 营销中台价格编排：促销满减/折扣 与 会员等级折扣 互斥取优（采购方享有会员折扣）
        boolean memberEligible = user != null && user.getRoleId() != null && user.getRoleId() == 1L;
        PricingFacade.Quote quote = pricingFacade.quote(userId, total, orderIndId, memberEligible);
        BigDecimal payable = quote.finalAmount();
        // 积分抵现（会员系统）：1000 积分 = 1 元，整元抵扣；抵扣明细记录在订单上供详情展示
        BigDecimal pointDeductAmount = BigDecimal.ZERO;
        int pointDeductPoints = 0;
        if (dto.getUsePoints() != null && dto.getUsePoints() > 0) {
            pointDeductAmount = membershipService.redeemPoints(userId, dto.getUsePoints(), payable, order.getOrderNo());
            // 抵扣金额为整元（1000 积分 = 1 元），消耗积分 = 金额 × 1000
            pointDeductPoints = pointDeductAmount.multiply(BigDecimal.valueOf(MemberRule.REDEEM_POINTS_PER_YUAN)).intValue();
            payable = payable.subtract(pointDeductAmount);
            if (payable.compareTo(BigDecimal.ZERO) < 0) payable = BigDecimal.ZERO;
        }
        order.setPointDeductAmount(pointDeductAmount);
        order.setPointDeductPoints(pointDeductPoints);
        order.setTotalAmount(payable);
        // 需求 1+4：防篡改比对（客户端传入 expectedTotal 时与后端重算总额一致性核验，不一致则告警并拒绝）
        if (dto.getExpectedTotal() != null) {
            if (!tamperGuardService.checkNumeric(userId, "order", "totalAmount", dto.getExpectedTotal(), payable, null)) {
                throw new BusinessException("订单金额异常(客户端传入金额与后端重算不一致)");
            }
        }
        // 账期订单在结算时直接形成授信应付账单，不再进入普通支付弹窗
        orderMapper.insert(order);
        if ("term".equals(dto.getPayType())) {
            creditTermService.createBillForOrder(userId, order.getOrderNo(), payable);
        }

        // 明细 + 扣库存（需求 2：数据库原子扣减防并发超卖）
        for (Cart c : carts) {
            Product p = productMapper.selectById(c.getProdId());
            BigDecimal subtotal = p.getPrice().multiply(BigDecimal.valueOf(c.getQuantity()));
            OrderItem item = new OrderItem();
            item.setOrderNo(order.getOrderNo());
            item.setProdId(p.getProdId());
            item.setProdName(p.getTitle());     // 冗余快照
            item.setPrice(p.getPrice());        // 冗余快照
            item.setQuantity(c.getQuantity());
            item.setSubtotal(subtotal);
            item.setCoverUrl(p.getCoverUrl());
            orderItemMapper.insert(item);
            // 原子扣库存 + 加销量（条件更新 stock >= quantity，失败回滚整个事务）
            int affected = productMapper.deductStockAtomic(p.getProdId(), c.getQuantity());
            if (affected == 0) {
                throw new BusinessException("库存不足(并发扣减冲突): " + p.getTitle());
            }
            // 移除已购购物车项
            cartMapper.deleteById(c.getCartId());
        }
        messageService.send(userId, "order", "下单成功",
                "term".equals(order.getPayType())
                        ? "订单 " + order.getOrderNo() + " 已使用企业账期，账单已进入账期中心。"
                        : "订单 " + order.getOrderNo() + " 已提交，待支付。",
                order.getOrderNo());
        return order;
    }

    public List<Order> myOrders(Long userId) {
        return orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, userId).orderByDesc(Order::getCreateTime));
    }

    public OrderDetailVO detail(Long userId, String orderNo) {
        return detail(userId, orderNo, false);
    }

    public OrderDetailVO detail(Long userId, String orderNo, boolean isAdmin) {
        Order o = orderMapper.selectById(orderNo);
        if (o == null || (!isAdmin && !o.getUserId().equals(userId))) throw new BusinessException("订单不存在");
        OrderDetailVO vo = new OrderDetailVO();
        vo.setOrder(o);
        vo.setItems(orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                .eq(OrderItem::getOrderNo, orderNo)));
        return vo;
    }

    public Order pay(Long userId, String orderNo) {
        Order o = orderMapper.selectById(orderNo);
        if (o == null || !o.getUserId().equals(userId)) throw new BusinessException("订单不存在");
        // 需求 2：支付状态原子流转（防并发重复支付/防状态篡改，移除前置Java状态判断消除竞态）
        int affected = orderMapper.markPaidAtomic(orderNo);
        if (affected == 0) {
            // 需求 4：并发/重放支付尝试告警（payStatus 状态机非法流转）
            tamperGuardService.report(userId, "order", "payStatus", "pay", "already-paid-or-invalid",
                    "支付状态原子流转失败(重复支付/并发篡改)", null);
            throw new BusinessException("订单已支付或当前状态不允许支付");
        }
        o = orderMapper.selectById(orderNo); // 刷新最新状态
        messageService.send(userId, "order", "支付成功",
                "订单 " + orderNo + " 已支付，商家将尽快发货。", orderNo);
        // 跨模块事件：通知营销中台（会员累计积分/成长值、消费活动自动参与）
        eventPublisher.publishEvent(new OrderPaidEvent(this, userId, orderNo, o.getTotalAmount()));
        // 身份平滑切换检测：支付后若累计已支付金额首次达标，采购方自动升级为 VIP（实时生效，无需重新登录）
        User u = userMapper.selectById(userId);
        if (u != null && u.getRoleId() != null && u.getRoleId() == 1L) {
            java.math.BigDecimal before = identityService.paidAmount(userId).subtract(o.getTotalAmount());
            if (before.compareTo(IdentityService.VIP_THRESHOLD) < 0
                    && identityService.paidAmount(userId).compareTo(IdentityService.VIP_THRESHOLD) >= 0) {
                auditService.record(userId, "IDENTITY_CHANGE", "身份升级: buyer -> vip（累计已支付达标）", null);
            }
        }
        return o;
    }

    /**
     * 支付回调结算（幂等）：仅当订单处于未支付时置为已支付，并复用 pay() 的消息/事件/身份升级副作用。
     * @return true 表示本次完成结算；false 表示订单此前已支付（重复回调）。
     */
    @Transactional
    public boolean settlePaid(String orderNo, String channel, String transactionId) {
        Order o = orderMapper.selectById(orderNo);
        if (o == null) throw new BusinessException("订单不存在: " + orderNo);
        if (o.getPayStatus() != null && o.getPayStatus() == 1) return false; // 幂等：已支付
        o.setPayStatus(1);
        o.setPayTime(LocalDateTime.now());
        o.setPayChannel(channel);
        if (transactionId != null && !transactionId.isBlank()) o.setTransactionId(transactionId);
        o.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(o);
        messageService.send(o.getUserId(), "order", "支付成功",
                "订单 " + orderNo + " 已支付，商家将尽快发货。", orderNo);
        eventPublisher.publishEvent(new OrderPaidEvent(this, o.getUserId(), orderNo, o.getTotalAmount()));
        User u = userMapper.selectById(o.getUserId());
        if (u != null && u.getRoleId() != null && u.getRoleId() == 1L) {
            BigDecimal before = identityService.paidAmount(o.getUserId()).subtract(o.getTotalAmount());
            if (before.compareTo(IdentityService.VIP_THRESHOLD) < 0
                    && identityService.paidAmount(o.getUserId()).compareTo(IdentityService.VIP_THRESHOLD) >= 0) {
                auditService.record(o.getUserId(), "IDENTITY_CHANGE", "身份升级: buyer -> vip（累计已支付达标）", null);
            }
        }
        return true;
    }

    /** 发起支付后回写渠道预下单信息（微信 prepay_id / 渠道标识） */
    @Transactional
    public void setPrepay(String orderNo, String channel, String prepayId) {
        Order o = orderMapper.selectById(orderNo);
        if (o == null) throw new BusinessException("订单不存在: " + orderNo);
        o.setPayChannel(channel);
        if (prepayId != null) o.setPrepayId(prepayId);
        if (o.getRefundStatus() == null) o.setRefundStatus("none");
        o.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(o);
    }

    /** 记录退款结果 */
    @Transactional
    public void applyRefundResult(String orderNo, String refundId, BigDecimal refundAmount, boolean success) {
        Order o = orderMapper.selectById(orderNo);
        if (o == null) throw new BusinessException("订单不存在: " + orderNo);
        o.setRefundStatus(success ? "success" : "processing");
        if (refundId != null) o.setRefundId(refundId);
        if (refundAmount != null) o.setRefundAmount(refundAmount);
        if (success) o.setRefundTime(LocalDateTime.now());
        o.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(o);
    }

    public void cancel(Long userId, String orderNo) {
        Order o = orderMapper.selectById(orderNo);
        if (o == null || !o.getUserId().equals(userId)) throw new BusinessException("订单不存在");
        if (o.getPayStatus() != null && o.getPayStatus() == 1 && !"term".equals(o.getPayType())) throw new BusinessException("已支付订单不可取消，请前往订单列表申请退款");
        // 幂等：已取消订单直接返回，防止重复恢复库存/重复返还积分
        if (o.getOrderStatus() != null && o.getOrderStatus() == 3) return;
        // 账期订单先释放未结清授信
        if ("term".equals(o.getPayType())) creditTermService.cancelOrderBill(userId, orderNo);
        // 恢复库存
        restoreStock(orderNo);
        o.setOrderStatus(3);
        if ("term".equals(o.getPayType())) o.setPayStatus(2);
        o.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(o);
        // 积分抵现返还（幂等：按流水判定，无抵现记录返回 0）
        int refunded = membershipService.refundPoints(userId, orderNo);
        if (refunded > 0) {
            messageService.send(userId, "order", "积分返还",
                    "订单 " + orderNo + " 已取消，抵扣的 " + refunded + " 积分已返还。", orderNo);
        }
        messageService.send(userId, "order", "订单已取消", "订单 " + orderNo + " 已取消，库存已恢复。", orderNo);
    }

    /**
     * 用户申请退款：进入平台审核队列（refundStatus=applied），由平台在交易管理中受理。
     * 规则：
     *  - 仅"已支付"且非账期订单可申请（账期走账单还款）；
     *  - 订单已取消/已退款不可申请；
     *  - 申请中/处理中/已退款不可重复申请；已驳回可重新申请；
     *  - 申请按"可退余额全额"提交，平台实际退款时可选全额或部分。
     */
    @Transactional
    public void applyRefund(Long userId, String orderNo, String reason) {
        Order o = orderMapper.selectById(orderNo);
        if (o == null || !o.getUserId().equals(userId)) throw new BusinessException("订单不存在");
        if (o.getPayStatus() == null || o.getPayStatus() != 1) throw new BusinessException("仅已支付订单可申请退款");
        if ("term".equals(o.getPayType())) throw new BusinessException("账期订单请前往账期账单处理还款，不支持退款申请");
        if (o.getOrderStatus() != null && (o.getOrderStatus() == 3 || o.getOrderStatus() == 4)) {
            throw new BusinessException("该订单已结束，无法申请退款");
        }
        if (reason == null || reason.isBlank()) throw new BusinessException("请填写退款原因");
        BigDecimal already = o.getRefundAmount() == null ? BigDecimal.ZERO : o.getRefundAmount();
        if (o.getTotalAmount().subtract(already).compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("该订单已无可退金额");
        }
        // 需求 2：退款状态原子流转（防并发重复申请/防退款状态篡改，移除前置Java状态判断消除竞态）
        int affected = orderMapper.applyRefundAtomic(orderNo, reason.trim());
        if (affected == 0) {
            tamperGuardService.report(userId, "order", "refundStatus", "apply", o.getRefundStatus() == null ? "none" : o.getRefundStatus(),
                    "退款状态原子流转失败(并发重复申请/状态篡改)", null);
            throw new BusinessException("退款申请已提交或订单状态不允许申请，请等待平台处理，请勿重复操作");
        }
        messageService.send(userId, "order", "退款申请已提交",
                "订单 " + orderNo + " 的退款申请已提交，平台将尽快处理。", orderNo);
    }

    /**
     * 平台驳回退款申请：状态置 rejected 并保留驳回原因（回显给用户），用户可重新申请。
     */
    @Transactional
    public void rejectRefund(String orderNo, String reason) {
        Order o = orderMapper.selectById(orderNo);
        if (o == null) throw new BusinessException("订单不存在");
        String why = reason == null || reason.isBlank() ? "不符合退款条件" : reason.trim();
        // 需求 2：驳回状态原子流转（防并发竞态覆盖其他并行操作的结果，移除前置Java状态判断消除竞态）
        int affected = orderMapper.rejectRefundAtomic(orderNo, why);
        if (affected == 0) {
            throw new BusinessException("该订单没有待处理的退款申请或状态已变更，请刷新后重试");
        }
        messageService.send(o.getUserId(), "order", "退款申请未通过",
                "订单 " + orderNo + " 的退款申请未通过：" + why, orderNo);
    }

    /** 恢复订单占用库存并回退销量（取消 / 全额退款共用；需求 2：原子回滚防并发丢失更新） */
    private void restoreStock(String orderNo) {
        List<OrderItem> items = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                .eq(OrderItem::getOrderNo, orderNo));
        for (OrderItem it : items) {
            // 原子回滚库存/销量（单条 UPDATE 消除并发竞态）
            productMapper.restoreStockAtomic(it.getProdId(), it.getQuantity());
        }
    }

    /**
     * 全额退款回退结算：恢复库存、扣回支付所得积分、返还抵现积分、订单置为已退款。
     * payStatus 置 2（已退款）——VIP 累计已支付金额按 pay_status=1 统计，该单自动剔除。
     * 由 PaymentService 在渠道退款成功后调用；积分回退各方法均幂等。
     */
    @Transactional
    public void fullRefundSettle(Order o) {
        if ("term".equals(o.getPayType())) creditTermService.cancelOrderBill(o.getUserId(), o.getOrderNo());
        restoreStock(o.getOrderNo());
        o.setOrderStatus(4);
        o.setPayStatus(2);
        o.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(o);
        int earnedBack = membershipService.reverseEarn(o.getUserId(), o.getOrderNo());
        int redeemed = membershipService.refundPoints(o.getUserId(), o.getOrderNo());
        String detail = "订单 " + o.getOrderNo() + " 已全额退款，退款金额 ¥" + o.getTotalAmount();
        if (earnedBack > 0) detail += "，支付所得 " + earnedBack + " 积分已扣回";
        if (redeemed > 0) detail += "，抵扣积分 " + redeemed + " 已返还";
        messageService.send(o.getUserId(), "order", "退款成功", detail + "。", o.getOrderNo());
    }

    /** 管理端订单分页（交易管理，可按支付状态过滤） */
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<Order> adminOrders(
            long page, long size, Integer payStatus) {
        return orderMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size),
                Wrappers.<Order>lambdaQuery()
                        .eq(payStatus != null, Order::getPayStatus, payStatus)
                        .orderByDesc(Order::getCreateTime));
    }

    private String genOrderNo() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return ts + ThreadLocalRandom.current().nextInt(100000, 999999);
    }
}
