package com.lanlink.shopping.payment.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.module.membership.entity.SubscriptionOrder;
import com.lanlink.shopping.module.membership.mapper.SubscriptionOrderMapper;
import com.lanlink.shopping.payment.PaymentProperties;
import com.lanlink.shopping.payment.alipay.AlipayPayClient;
import com.lanlink.shopping.payment.dto.RefundRequest;
import com.lanlink.shopping.payment.stripe.StripePayClient;
import com.lanlink.shopping.payment.vo.PayCreateVO;
import com.lanlink.shopping.payment.vo.PayStatusVO;
import com.lanlink.shopping.payment.wallet.entity.WalletLog;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import com.lanlink.shopping.payment.wechat.WechatPayClient;
import com.lanlink.shopping.service.OrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 支付门面：统一发起、回调验签与幂等结算、查单对账、退款。
 * 渠道：wallet（钱包余额，同步完成）/ wechat（Native 扫码）/ alipay（电脑网站）/ mock（本地模拟）。
 * mock=true（默认）时不触达真实渠道，本地即可完成全流程，便于 localhost 演示与测试；
 * 关闭 mock 且配置真实凭证后，走微信 APIv2 / 支付宝真实链路。
 */
@Service
public class PaymentService {

    private final PaymentProperties props;
    private final WechatPayClient wechat;
    private final AlipayPayClient alipay;
    private final StripePayClient stripe;
    private final OrderService orderService;
    private final OrderMapper orderMapper;
    private final PaymentLogService payLog;
    private final WalletService walletService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lanlink.shopping.service.CreditTermService creditTermService;

    /** 订阅单支付成功回调（P1-3）：setter 注入避免与 SubscriptionService（依赖本类发起支付）构造器循环 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SubscriptionPayHandler subscriptionPayHandler;

    /** 订阅单访问：按 SUB 前缀分流时反查订阅单（金额校验/归属/结算），与 t_order 互不干扰 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SubscriptionOrderMapper subscriptionOrderMapper;

    public PaymentService(PaymentProperties props, WechatPayClient wechat, AlipayPayClient alipay,
                          StripePayClient stripe, OrderService orderService, OrderMapper orderMapper,
                          PaymentLogService payLog, WalletService walletService) {
        this.props = props;
        this.wechat = wechat;
        this.alipay = alipay;
        this.stripe = stripe;
        this.orderService = orderService;
        this.orderMapper = orderMapper;
        this.payLog = payLog;
        this.walletService = walletService;
    }

    public void setCreditTermService(com.lanlink.shopping.service.CreditTermService creditTermService) {
        this.creditTermService = creditTermService;
    }

    public void setSubscriptionPayHandler(SubscriptionPayHandler subscriptionPayHandler) {
        this.subscriptionPayHandler = subscriptionPayHandler;
    }

    public void setSubscriptionOrderMapper(SubscriptionOrderMapper subscriptionOrderMapper) {
        this.subscriptionOrderMapper = subscriptionOrderMapper;
    }

    // ===== 发起支付 =====

    /**
     * 发起支付（单据抽象入口）：按 order_no 前缀分流单据类型——
     * 「SUB」前缀走订阅单分支（P1-2），纯数字单号走既有 t_order 路径（行为不变）。
     */
    public PayCreateVO create(String orderNo, Long userId, String channelReq) {
        if (SubscriptionOrderNos.isSubscriptionOrderNo(orderNo)) {
            return createSubscription(orderNo, userId, channelReq);
        }
        Order o = mustOwn(orderNo, userId);
        if (isPaid(o)) throw new BusinessException("订单已支付");
        String channel = decideChannel(channelReq, o);

        PayCreateVO vo = new PayCreateVO();
        vo.setOrderNo(orderNo);
        vo.setChannel(channel);
        Map<String, Object> info = new HashMap<>();

        switch (channel) {
            case "wallet" -> {
                // 钱包余额支付：扣款 + 结算同一事务（任一步失败整体回滚）
                info.putAll(walletPay(orderNo, userId, o.getTotalAmount()));
            }
            case "wechat" -> {
                if (!wechat.configured()) throw new BusinessException("微信支付未配置（或处于 mock 模式）");
                String notifyUrl = props.getNotifyBaseUrl() + "/payment/notify/wechat";
                String codeUrl = wechat.nativeOrder(orderNo, o.getTotalAmount(), "LANLinkshopping-" + orderNo, notifyUrl);
                orderService.setPrepay(orderNo, "wechat", null);
                info.put("codeUrl", codeUrl);
            }
            case "alipay" -> {
                if (!alipay.configured()) throw new BusinessException("支付宝未配置（或处于 mock 模式）");
                String notifyUrl = firstNonBlank(props.getAlipay().getNotifyUrl(), props.getNotifyBaseUrl() + "/payment/notify/alipay");
                String returnUrl = firstNonBlank(props.getAlipay().getReturnUrl(), props.getNotifyBaseUrl() + "/payment/alipay/return");
                String form = alipay.pagePayForm(orderNo, o.getTotalAmount(), "LANLinkshopping-" + orderNo, notifyUrl, returnUrl);
                orderService.setPrepay(orderNo, "alipay", null);
                info.put("form", form);
            }
            case "stripe" -> {
                if (!stripe.configured()) throw new BusinessException("Stripe 支付通道未配置（或处于 mock 模式）");
                var pi = stripe.createPaymentIntent(orderNo, o.getTotalAmount(), userId);
                orderService.setPrepay(orderNo, "stripe", pi.getId());
                info.put("clientSecret", pi.getClientSecret());
                info.put("paymentIntentId", pi.getId());
                info.put("publishableKey", props.getStripe().getPublishableKey());
                info.put("currency", props.getStripe().getCurrency());
            }
            case "mock" -> {
                if (!props.isMock()) {
                    throw new BusinessException("模拟支付未启用");
                }
                orderService.setPrepay(orderNo, "mock", null);
                info.put("token", "MOCK-" + UUID.randomUUID().toString().replace("-", ""));
                vo.setMock(true);
            }
            default -> throw new BusinessException("不支持的支付渠道: " + channel);
        }
        vo.setPayInfo(info);
        payLog.info(orderNo, channel, "CREATE", "OUT", true, "amount=" + o.getTotalAmount());
        return vo;
    }

    /**
     * 订阅单发起支付（P1-2 重载分支）：归属校验反查 t_subscription_order，
     * 金额取 amount_cents（整数元语义）转 BigDecimal 元后与订单侧 totalAmount 同口径参与渠道换算。
     * 渠道分发复用同一套 client/decideChannel；结算目标为订阅单状态机而非 t_order。
     */
    private PayCreateVO createSubscription(String orderNo, Long userId, String channelReq) {
        SubscriptionOrder so = mustOwnSubscription(orderNo, userId);
        if (so.getPayStatus() != null && so.getPayStatus() == 1) throw new BusinessException("订阅单已支付");
        // 金额单位（钉死）：amount_cents 为整数元（8888=¥8,888），此处转「元」后交渠道换算（微信/Stripe 内部 ×100 成分）
        BigDecimal amountYuan = BigDecimal.valueOf(so.getAmountCents());
        String channel = decideChannel(channelReq, null);

        PayCreateVO vo = new PayCreateVO();
        vo.setOrderNo(orderNo);
        vo.setChannel(channel);
        Map<String, Object> info = new HashMap<>();
        switch (channel) {
            case "wallet" -> info.putAll(walletPaySubscription(orderNo, userId, amountYuan));
            case "wechat" -> {
                if (!wechat.configured()) throw new BusinessException("微信支付未配置（或处于 mock 模式）");
                String notifyUrl = props.getNotifyBaseUrl() + "/payment/notify/wechat";
                String codeUrl = wechat.nativeOrder(orderNo, amountYuan, "LANLink-Subscription-" + orderNo, notifyUrl);
                subscriptionOrderMapper.markChannel(orderNo, "wechat");
                info.put("codeUrl", codeUrl);
            }
            case "alipay" -> {
                if (!alipay.configured()) throw new BusinessException("支付宝未配置（或处于 mock 模式）");
                String notifyUrl = firstNonBlank(props.getAlipay().getNotifyUrl(), props.getNotifyBaseUrl() + "/payment/notify/alipay");
                String returnUrl = firstNonBlank(props.getAlipay().getReturnUrl(), props.getNotifyBaseUrl() + "/payment/alipay/return");
                String form = alipay.pagePayForm(orderNo, amountYuan, "LANLink-Subscription-" + orderNo, notifyUrl, returnUrl);
                subscriptionOrderMapper.markChannel(orderNo, "alipay");
                info.put("form", form);
            }
            case "stripe" -> {
                if (!stripe.configured()) throw new BusinessException("Stripe 支付通道未配置（或处于 mock 模式）");
                var pi = stripe.createPaymentIntent(orderNo, amountYuan, userId);
                subscriptionOrderMapper.markChannel(orderNo, "stripe");
                info.put("clientSecret", pi.getClientSecret());
                info.put("paymentIntentId", pi.getId());
                info.put("publishableKey", props.getStripe().getPublishableKey());
                info.put("currency", props.getStripe().getCurrency());
            }
            case "mock" -> {
                if (!props.isMock()) {
                    throw new BusinessException("模拟支付未启用");
                }
                subscriptionOrderMapper.markChannel(orderNo, "mock");
                info.put("token", "MOCK-" + UUID.randomUUID().toString().replace("-", ""));
                vo.setMock(true);
            }
            default -> throw new BusinessException("不支持的支付渠道: " + channel);
        }
        vo.setPayInfo(info);
        payLog.info(orderNo, channel, "CREATE", "OUT", true, "subscription amount=" + amountYuan);
        return vo;
    }

    /** 钱包渠道支付：扣款与结算同事务，并发重复发起时仅一次生效 */
    @Transactional
    public Map<String, Object> walletPay(String orderNo, Long userId, BigDecimal amount) {
        WalletLog wl = walletService.payFromWallet(userId, amount, orderNo);
        boolean settled = orderService.settlePaid(orderNo, "wallet", "W" + wl.getId());
        if (!settled) {
            // 并发下订单已被支付：抛异常回滚本次扣款
            throw new BusinessException("订单已支付，钱包扣款已撤销");
        }
        Map<String, Object> m = new HashMap<>();
        m.put("paid", true);
        m.put("balance", walletService.balanceOf(userId));
        return m;
    }

    /** 钱包渠道支付（订阅单）：扣款与订阅单结算同事务；结算被并发抢占时抛异常回滚扣款 */
    @Transactional
    public Map<String, Object> walletPaySubscription(String orderNo, Long userId, BigDecimal amountYuan) {
        WalletLog wl = walletService.payFromWallet(userId, amountYuan, orderNo);
        boolean settled = settleSubscriptionOrder(orderNo, "wallet", "W" + wl.getId());
        if (!settled) {
            throw new BusinessException("订阅单已支付，钱包扣款已撤销");
        }
        Map<String, Object> m = new HashMap<>();
        m.put("paid", true);
        m.put("balance", walletService.balanceOf(userId));
        return m;
    }

    /**
     * 订阅单结算（P1-3 幂等核心）：pay_status 0→1 条件更新为状态机闸，重复回调/重放返回 false 不再驱动；
     * 已支付但 apply_status=0（上一轮回调 apply 中断）时仍触发回调补偿，由订阅模块按生效闸幂等补齐。
     */
    public boolean settleSubscriptionOrder(String orderNo, String channel, String transactionId) {
        int updated = subscriptionOrderMapper.markPaidAtomic(orderNo, channel, transactionId, java.time.LocalDateTime.now());
        if (updated == 0) {
            SubscriptionOrder so = subscriptionOrderMapper.selectById(orderNo);
            if (so == null || so.getPayStatus() == null || so.getPayStatus() != 1) return false;
        }
        payLog.info(orderNo, channel, "SETTLE", "IN", true, "订阅单结算 transactionId=" + transactionId);
        if (subscriptionPayHandler != null) {
            subscriptionPayHandler.onSubscriptionOrderPaid(orderNo);
        }
        return true;
    }

    /** 仅开发/演示环境允许的模拟支付确认入口 */
    public boolean mockConfirm(String orderNo, Long userId) {
        if (!props.isMock()) {
            throw new BusinessException("模拟支付未启用");
        }
        if (SubscriptionOrderNos.isSubscriptionOrderNo(orderNo)) {
            SubscriptionOrder so = mustOwnSubscription(orderNo, userId);
            if (!"mock".equals(so.getChannel())) {
                throw new BusinessException("该订阅单不支持模拟支付");
            }
            if (so.getPayStatus() != null && so.getPayStatus() == 1) {
                throw new BusinessException("订阅单已支付");
            }
            boolean settled = settleSubscriptionOrder(orderNo, "mock", "MOCKTXN" + ts());
            payLog.info(orderNo, "mock", "NOTIFY", "IN", settled, "订阅单模拟支付确认 settled=" + settled);
            return settled;
        }
        Order o = mustOwn(orderNo, userId);
        if (!"mock".equals(o.getPayChannel())) {
            throw new BusinessException("该订单不支持模拟支付");
        }
        if (isPaid(o)) {
            throw new BusinessException("订单已支付");
        }
        boolean settled = orderService.settlePaid(orderNo, "mock", "MOCKTXN" + ts());
        payLog.info(orderNo, "mock", "NOTIFY", "IN", settled, "模拟支付确认 settled=" + settled);
        return settled;
    }

    // ===== 回调处理 =====

    /** 微信 APIv2 异步通知（XML）。返回应答 XML。 */
    public String handleWechatNotify(String xmlBody) {
        try {
            Map<String, String> data = wechat.parseAndVerifyNotify(xmlBody);
            if (!"SUCCESS".equals(data.get("return_code"))) {
                payLog.info(data.get("out_trade_no"), "wechat", "NOTIFY", "IN", false, "return_code!=SUCCESS");
                return WechatPayClient.notifyReply(true, "OK"); // 已收到，停止重试
            }
            String orderNo = data.get("out_trade_no");
            if ("SUCCESS".equals(data.get("result_code"))) {
                if (amountMatches(orderNo, data.get("total_fee"))) {
                    if (SubscriptionOrderNos.isSubscriptionOrderNo(orderNo)) {
                        // 订阅单分支（P1-3）：金额校验反查订阅单；幂等=订阅单 pay_status 状态机（防重放重复顺延）
                        boolean settled = settleSubscriptionOrder(orderNo, "wechat", data.get("transaction_id"));
                        payLog.info(orderNo, "wechat", "NOTIFY", "IN", settled, "订阅单支付成功 txn=" + data.get("transaction_id") + " settled=" + settled);
                    } else {
                        orderService.settlePaid(orderNo, "wechat", data.get("transaction_id"));
                        payLog.info(orderNo, "wechat", "NOTIFY", "IN", true, "支付成功 txn=" + data.get("transaction_id"));
                    }
                } else {
                    payLog.error(orderNo, "wechat", "NOTIFY", "金额不匹配 total_fee=" + data.get("total_fee"), null);
                    return WechatPayClient.notifyReply(false, "金额校验失败");
                }
            }
            return WechatPayClient.notifyReply(true, "OK");
        } catch (Exception e) {
            payLog.error(null, "wechat", "VERIFY_FAIL", "回调验签/处理失败", e);
            return WechatPayClient.notifyReply(false, "FAIL");
        }
    }

    /** 支付宝异步通知（form 参数）。返回 "success" 停止重试。 */
    public String handleAlipayNotify(Map<String, String> params) {
        String orderNo = params.get("out_trade_no");
        try {
            if (!alipay.verifyNotify(params)) {
                payLog.error(orderNo, "alipay", "VERIFY_FAIL", "签名校验失败", null);
                return "failure";
            }
            String expectAppId = props.getAlipay().getAppId();
            if (notBlank(expectAppId) && !expectAppId.equals(params.get("app_id"))) {
                payLog.error(orderNo, "alipay", "VERIFY_FAIL", "app_id 不匹配", null);
                return "failure";
            }
            String status = params.get("trade_status");
            if ("TRADE_SUCCESS".equals(status) || "TRADE_FINISHED".equals(status)) {
                BigDecimal total = new BigDecimal(params.getOrDefault("total_amount", "0"));
                if (SubscriptionOrderNos.isSubscriptionOrderNo(orderNo)) {
                    // 订阅单分支（P1-3）：应收金额反查订阅单（amount_cents 整数元语义，比较单位为元）
                    SubscriptionOrder so = subscriptionOrderMapper.selectById(orderNo);
                    if (so != null && BigDecimal.valueOf(so.getAmountCents())
                            .compareTo(total.setScale(2, RoundingMode.HALF_UP)) == 0) {
                        boolean settled = settleSubscriptionOrder(orderNo, "alipay", params.get("trade_no"));
                        payLog.info(orderNo, "alipay", "NOTIFY", "IN", settled, "订阅单支付成功 trade_no=" + params.get("trade_no") + " settled=" + settled);
                    } else {
                        payLog.error(orderNo, "alipay", "NOTIFY", "订阅单金额不匹配 total=" + total, null);
                        return "failure";
                    }
                } else {
                    Order o = orderMapper.selectById(orderNo);
                    if (o != null && o.getTotalAmount().compareTo(total.setScale(2, RoundingMode.HALF_UP)) == 0) {
                        orderService.settlePaid(orderNo, "alipay", params.get("trade_no"));
                        payLog.info(orderNo, "alipay", "NOTIFY", "IN", true, "支付成功 trade_no=" + params.get("trade_no"));
                    } else {
                        payLog.error(orderNo, "alipay", "NOTIFY", "金额不匹配 total=" + total, null);
                        return "failure";
                    }
                }
            }
            return "success";
        } catch (Exception e) {
            payLog.error(orderNo, "alipay", "NOTIFY", "回调处理异常", e);
            return "failure";
        }
    }

    /** Stripe Webhook 异步通知（JSON + 签名） */
    public String handleStripeNotify(String payload, String sigHeader) {
        com.stripe.model.Event event;
        try {
            event = stripe.verifyAndConstructEvent(payload, sigHeader);
        } catch (Exception e) {
            payLog.error(null, "stripe", "VERIFY_FAIL", e.getMessage(), e);
            return "Signature verification failed";
        }

        if ("payment_intent.succeeded".equals(event.getType())) {
            com.stripe.model.EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
            com.stripe.model.PaymentIntent intent = (com.stripe.model.PaymentIntent) deserializer.getObject().orElse(null);
            if (intent != null) {
                String orderNo = intent.getMetadata() != null ? intent.getMetadata().get("orderNo") : null;
                String txnId = intent.getId();
                long amountCents = intent.getAmount() == null ? 0L : intent.getAmount();

                if (orderNo != null && amountMatches(orderNo, String.valueOf(amountCents))) {
                    if (SubscriptionOrderNos.isSubscriptionOrderNo(orderNo)) {
                        // 订阅单分支（P1-3）：金额（分）×100 同源校验后走订阅单状态机
                        boolean settled = settleSubscriptionOrder(orderNo, "stripe", txnId);
                        payLog.info(orderNo, "stripe", "NOTIFY", "IN", true, "Stripe订阅单支付成功 txn=" + txnId + " settled=" + settled);
                    } else {
                        boolean settled = orderService.settlePaid(orderNo, "stripe", txnId);
                        payLog.info(orderNo, "stripe", "NOTIFY", "IN", true, "Stripe支付成功 txn=" + txnId + " settled=" + settled);
                    }
                } else {
                    payLog.error(orderNo, "stripe", "NOTIFY", "金额不匹配 amount=" + amountCents, null);
                }
            }
        }
        return "{\"received\": true}";
    }

    // ===== 查单对账 =====

    public PayStatusVO query(String orderNo, Long userId) {
        return query(orderNo, userId, false);
    }

    public PayStatusVO query(String orderNo, Long userId, boolean isAdmin) {
        if (SubscriptionOrderNos.isSubscriptionOrderNo(orderNo)) {
            return querySubscription(orderNo, userId, isAdmin);
        }
        Order o = orderMapper.selectById(orderNo);
        if (o == null || (!isAdmin && !o.getUserId().equals(userId))) throw new BusinessException("订单不存在");
        if (!isPaid(o) && !props.isMock() && notBlank(o.getPayChannel())) {
            try {
                if ("wechat".equals(o.getPayChannel())) {
                    Map<String, String> r = wechat.query(orderNo);
                    if ("SUCCESS".equals(r.get("trade_state"))) orderService.settlePaid(orderNo, "wechat", r.get("transaction_id"));
                } else if ("alipay".equals(o.getPayChannel())) {
                    String st = alipay.queryTradeStatus(orderNo);
                    if ("TRADE_SUCCESS".equals(st) || "TRADE_FINISHED".equals(st)) orderService.settlePaid(orderNo, "alipay", null);
                }
            } catch (Exception e) {
                payLog.error(orderNo, o.getPayChannel(), "QUERY", "查单异常", e);
            }
            o = orderMapper.selectById(orderNo); // 重新读取
        }
        return toVO(o);
    }

    /**
     * 订阅单支付状态查询（P1-2/P1-8）：供前端 paymentApi.query 轮询。
     * 反查 t_subscription_order 并做归属校验；结果仅反映订阅单状态机（渠道侧以异步 notify 落账）。
     */
    private PayStatusVO querySubscription(String orderNo, Long userId, boolean isAdmin) {
        SubscriptionOrder so = subscriptionOrderMapper.selectById(orderNo);
        if (so == null || (!isAdmin && !so.getUserId().equals(userId))) throw new BusinessException("订单不存在");
        PayStatusVO vo = new PayStatusVO();
        vo.setOrderNo(so.getOrderNo());
        vo.setPayStatus(so.getPayStatus());
        vo.setOrderStatus(null); // 订阅单无履约状态位
        vo.setPayChannel(so.getChannel());
        vo.setTransactionId(so.getTransactionId());
        // 金额单位：amount_cents 整数元语义 → 出参为「元」BigDecimal（与订单侧 totalAmount 同口径）
        vo.setTotalAmount(BigDecimal.valueOf(so.getAmountCents()));
        vo.setPayTime(so.getPaidAt());
        return vo;
    }

    /** 我的交易记录：全部订单的支付状态视图（按下单时间倒序） */
    public List<PayStatusVO> myPayments(Long userId) {
        List<Order> orders = orderMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Order>lambdaQuery()
                        .eq(Order::getUserId, userId)
                        .orderByDesc(Order::getCreateTime)
                        .last("LIMIT 100"));
        List<PayStatusVO> out = new ArrayList<>();
        for (Order o : orders) out.add(toVO(o));
        return out;
    }

    private PayStatusVO toVO(Order o) {
        PayStatusVO vo = new PayStatusVO();
        vo.setOrderNo(o.getOrderNo());
        vo.setPayStatus(o.getPayStatus());
        vo.setOrderStatus(o.getOrderStatus());
        vo.setPayChannel(o.getPayChannel());
        vo.setTransactionId(o.getTransactionId());
        vo.setTotalAmount(o.getTotalAmount());
        vo.setPayTime(o.getPayTime());
        vo.setRefundStatus(o.getRefundStatus());
        vo.setRefundAmount(o.getRefundAmount());
        return vo;
    }

    // ===== 退款 =====

    /**
     * 退款（管理端发起）。
     * 资金路径按原支付渠道退回：wallet 原路入账钱包；wechat/alipay 调渠道退款接口；mock 仅记录。
     * 全额退款额外触发回退结算：恢复库存、扣回支付所得积分、返还抵现积分、订单置为已退款。
     */
    public boolean refund(RefundRequest req, Long adminId) {
        Order o = orderMapper.selectById(req.getOrderNo());
        if (o == null) throw new BusinessException("订单不存在");
        if (!isPaid(o)) throw new BusinessException("未支付订单不可退款");
        if ("success".equals(o.getRefundStatus())) throw new BusinessException("该订单已全额退款");
        // 并发防护：applied/none/rejected → processing 原子占位，防双请求同时打款双退
        int guarded = orderMapper.markRefundProcessingAtomic(o.getOrderNo());
        if (guarded == 0) {
            throw new BusinessException("该订单没有待处理的退款申请或已被并发处理");
        }
        BigDecimal already = o.getRefundAmount() == null ? BigDecimal.ZERO : o.getRefundAmount();
        BigDecimal remain = o.getTotalAmount().subtract(already);
        BigDecimal amount = req.getAmount() == null ? remain : req.getAmount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("退款金额需大于 0");
        if (amount.compareTo(remain) > 0) throw new BusinessException("退款金额超出可退余额");
        boolean full = amount.compareTo(remain) == 0;
        String outRefundNo = "RF" + ts() + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String channel = o.getPayChannel();
        if (channel == null || channel.isBlank()) {
            if ("term".equalsIgnoreCase(o.getPayType())) {
                channel = "term";
            } else if ("balance".equalsIgnoreCase(o.getPayType())) {
                channel = "wallet";
            } else if ("corporate".equalsIgnoreCase(o.getPayType()) || "transfer".equalsIgnoreCase(o.getPayType())) {
                channel = "corporate";
            } else {
                channel = "mock";
            }
        }

        boolean ok;
        String refundId;
        switch (channel) {
            case "term" -> {
                // 账期支付：冲减未还账单释放授信，已还现金退回企业钱包（资产守恒）
                if (creditTermService != null) {
                    creditTermService.refundOrderBill(o.getUserId(), o.getOrderNo(), amount);
                }
                ok = true;
                refundId = "TERM" + outRefundNo;
            }
            case "corporate", "transfer" -> {
                // 企业对公电汇通道：生成电汇退款批单流水号，进入线下财务电汇排程
                ok = true;
                refundId = "CORP" + outRefundNo;
            }
            case "wallet" -> {
                // 钱包原路退回：直接入账（并发退款由订单退款状态检查 + 金额守恒约束）
                WalletLog wl = walletService.refundToWallet(o.getUserId(), amount, o.getOrderNo(),
                        "订单退款（管理员 " + adminId + "）");
                ok = true;
                refundId = "W" + wl.getId();
            }
            case "mock" -> {
                // 模拟渠道：无真实资金，仅记录退款结果
                ok = true;
                refundId = "MOCK" + outRefundNo;
            }
            case "wechat" -> {
                Map<String, String> r = wechat.refund(o.getOrderNo(), outRefundNo, o.getTotalAmount(), amount, req.getReason());
                ok = "SUCCESS".equals(r.get("result_code"));
                refundId = r.get("refund_id");
            }
            case "alipay" -> {
                ok = alipay.refund(o.getOrderNo(), amount, req.getReason());
                refundId = "ALI" + outRefundNo;
            }
            case "stripe" -> {
                if (stripe.configured()) {
                    var ref = stripe.refund(o.getTransactionId(), amount, req.getReason());
                    ok = "succeeded".equals(ref.getStatus()) || "pending".equals(ref.getStatus());
                    refundId = ref.getId();
                } else {
                    ok = true;
                    refundId = "STRIPE" + outRefundNo;
                }
            }
            default -> throw new BusinessException("不支持的退款渠道: " + channel);
        }

        o.setOutRefundNo(outRefundNo);
        orderMapper.updateById(o);
        orderService.applyRefundResult(o.getOrderNo(), refundId, ok ? already.add(amount) : already, ok);
        if (ok && full) {
            // 全额退款：恢复库存 + 积分双向回退 + 订单状态回退
            orderService.fullRefundSettle(orderMapper.selectById(o.getOrderNo()));
        }
        payLog.info(o.getOrderNo(), channel, "REFUND", "OUT", ok,
                "admin=" + adminId + " amount=" + amount + (full ? " FULL" : " PARTIAL") + " refundNo=" + outRefundNo);
        return ok;
    }

    /**
     * 看门狗对账补偿：主动轮询第三方渠道并同步退款结果
     */
    @Transactional
    public boolean syncRefundStatus(String orderNo) {
        Order o = orderMapper.selectById(orderNo);
        if (o == null || !"processing".equals(o.getRefundStatus())) {
            return false;
        }
        String channel = o.getPayChannel();
        if (channel == null || channel.isBlank()) {
            if ("term".equalsIgnoreCase(o.getPayType())) channel = "term";
            else if ("balance".equalsIgnoreCase(o.getPayType())) channel = "wallet";
            else if ("corporate".equalsIgnoreCase(o.getPayType()) || "transfer".equalsIgnoreCase(o.getPayType())) channel = "corporate";
            else channel = "mock";
        }

        boolean confirmed = false;
        boolean failed = false;

        switch (channel) {
            case "mock", "wallet", "term", "corporate", "transfer" -> {
                // 本地或线下渠道，processing 超过超时阈值视为已出账闭环
                confirmed = true;
            }
            case "wechat" -> {
                String status = wechat.queryRefundStatus(o.getOrderNo(), o.getOutRefundNo());
                if ("SUCCESS".equalsIgnoreCase(status)) {
                    confirmed = true;
                } else if ("REFUNDCLOSE".equalsIgnoreCase(status) || "CHANGE".equalsIgnoreCase(status)) {
                    failed = true;
                }
            }
            case "alipay" -> {
                String status = alipay.queryRefundStatus(o.getOrderNo(), o.getOutRefundNo());
                if ("REFUND_SUCCESS".equalsIgnoreCase(status)) {
                    confirmed = true;
                }
            }
            case "stripe" -> {
                String status = stripe.queryRefundStatus(o.getRefundId());
                if ("succeeded".equalsIgnoreCase(status)) {
                    confirmed = true;
                } else if ("failed".equalsIgnoreCase(status) || "canceled".equalsIgnoreCase(status)) {
                    failed = true;
                }
            }
            default -> {}
        }

        if (confirmed) {
            String refundId = o.getRefundId() != null ? o.getRefundId() : ("SYNC-" + o.getOrderNo());
            BigDecimal refundAmt = o.getTotalAmount();
            orderService.applyRefundResult(o.getOrderNo(), refundId, refundAmt, true);
            orderService.fullRefundSettle(orderMapper.selectById(o.getOrderNo()));
            payLog.info(o.getOrderNo(), channel, "RECON_REFUND_SUCCESS", "SYNC", true, "看门狗对账补偿确认到账");
            return true;
        } else if (failed) {
            // 渠道明确退款失败：安全回滚至 merchant_approved，解除 processing 卡单
            o.setRefundStatus("merchant_approved");
            o.setUpdateTime(LocalDateTime.now());
            orderMapper.updateById(o);
            payLog.warn(o.getOrderNo(), channel, "RECON_REFUND_FAIL", "SYNC", false, "渠道退款失败，状态回退至商户已审待重试");
            return false;
        }
        return false;
    }

    // ===== 内部工具 =====

    /** 渠道决策：wallet 为本地渠道任何模式可用；结算选择"企业钱包"的订单默认钱包支付 */
    private String decideChannel(String req, Order o) {
        String wanted = req == null || req.isBlank() ? null : req.trim().toLowerCase();
        if (wanted == null && "balance".equals(o.getPayType())) return "wallet";
        if (wanted == null) return props.isMock() ? "mock" : "wechat";

        if (!List.of("wallet", "wechat", "alipay", "mock", "stripe", "apple_pay", "google_pay").contains(wanted)) {
            throw new BusinessException("不支持的支付渠道: " + wanted);
        }
        if ("wallet".equals(wanted)) return "wallet";
        if ("stripe".equals(wanted) || "apple_pay".equals(wanted) || "google_pay".equals(wanted)) {
            return props.isMock() ? "mock" : "stripe";
        }
        if ("mock".equals(wanted)) {
            if (!props.isMock()) throw new BusinessException("模拟支付未启用");
            return "mock";
        }
        return props.isMock() ? "mock" : wanted;
    }

    private Order mustOwn(String orderNo, Long userId) {
        Order o = orderMapper.selectById(orderNo);
        if (o == null || !o.getUserId().equals(userId)) throw new BusinessException("订单不存在");
        return o;
    }

    /** 订阅单归属校验（SUB 命名空间）：反查 t_subscription_order，非本人按不存在处理 */
    private SubscriptionOrder mustOwnSubscription(String orderNo, Long userId) {
        if (subscriptionOrderMapper == null) throw new BusinessException("订阅支付通道不可用");
        SubscriptionOrder so = subscriptionOrderMapper.selectById(orderNo);
        if (so == null || !so.getUserId().equals(userId)) throw new BusinessException("订阅单不存在");
        return so;
    }

    private boolean isPaid(Order o) { return o.getPayStatus() != null && o.getPayStatus() == 1; }

    /**
     * 金额校验（分）：按单据类型反查应收金额——订单 DECIMAL totalAmount（元）×100；
     * 订阅单 amount_cents（整数元语义）转 BigDecimal 元后 ×100，两侧换算同源（P1-3 金额口径）。
     * 单据不存在返回 -1（校验失败）。
     */
    private boolean amountMatches(String orderNo, String totalFeeFen) {
        if (totalFeeFen == null) return false;
        long expect = expectedFeeFen(orderNo);
        if (expect < 0) return false;
        return expect == Long.parseLong(totalFeeFen);
    }

    /** 应收金额（分）= 元 × 100；订单/订阅单按前缀分流 */
    private long expectedFeeFen(String orderNo) {
        if (SubscriptionOrderNos.isSubscriptionOrderNo(orderNo)) {
            if (subscriptionOrderMapper == null) return -1;
            SubscriptionOrder so = subscriptionOrderMapper.selectById(orderNo);
            if (so == null || so.getAmountCents() == null) return -1;
            return BigDecimal.valueOf(so.getAmountCents())
                    .multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValue();
        }
        Order o = orderMapper.selectById(orderNo);
        if (o == null) return -1;
        return o.getTotalAmount().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private static String ts() { return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")); }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String firstNonBlank(String a, String b) { return notBlank(a) ? a : b; }
}
