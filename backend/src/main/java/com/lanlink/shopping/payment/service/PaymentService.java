package com.lanlink.shopping.payment.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.RefundApproval;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.mapper.RefundApprovalMapper;
import com.lanlink.shopping.service.AuditService;
import com.lanlink.shopping.module.membership.entity.SubscriptionOrder;
import com.lanlink.shopping.module.membership.mapper.SubscriptionOrderMapper;
import com.lanlink.shopping.payment.PaymentProperties;
import com.lanlink.shopping.payment.alipay.AlipayPayClient;
import com.lanlink.shopping.payment.conflict.dto.SettlePaidResult;
import com.lanlink.shopping.payment.conflict.entity.PaymentConflict;
import com.lanlink.shopping.payment.conflict.service.PaymentConflictService;
import com.lanlink.shopping.payment.dto.RefundRequest;
import com.lanlink.shopping.payment.stripe.StripePayClient;
import com.lanlink.shopping.payment.vo.PayCreateVO;
import com.lanlink.shopping.payment.vo.PayStatusVO;
import com.lanlink.shopping.payment.wallet.entity.WalletLog;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import com.lanlink.shopping.payment.wechat.WechatPayClient;
import com.lanlink.shopping.service.OrderService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * 运维指标（可选注入：单测直接 new 时为空，所有上报点必须判空）。
     *
     * <p>支付入口门禁被拒次数 {@code lanlink.payment.gate.rejected{reason=order_status|window}}：
     * 持续增长通常意味着「超时关单与用户支付意愿赛跑」或前端对已关单仍在展示支付按钮。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lanlink.shopping.service.CreditTermService creditTermService;

    /** 订阅单支付成功回调（P1-3）：setter 注入避免与 SubscriptionService（依赖本类发起支付）构造器循环 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SubscriptionPayHandler subscriptionPayHandler;

    /** 订阅单访问：按 SUB 前缀分流时反查订阅单（金额校验/归属/结算），与 t_order 互不干扰 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SubscriptionOrderMapper subscriptionOrderMapper;

    /**
     * P0-C 退款出款收口：平台复核凭证 Mapper。
     *
     * <p>出款前必须存在一条未消费的 {@code PLATFORM/APPROVE} 审批行，并在出款前原子消费；
     * 用 setter 注入以保持既有构造器与单测不变。未注入时 {@link #refund} 走 fail-closed（禁止出款），
     * 绝不退化为“无审批也能出款”。
     */
    private volatile RefundApprovalMapper refundApprovalMapper;

    /** P0-C：出款动作落 t_audit_log（可选依赖，缺省时只记支付日志，不阻断资金动作）。 */
    private volatile AuditService auditService;

    /** 出款守卫允许的退款状态：只有走完「商户初审 + 平台复核」前置链的状态才可能出款。 */
    private static final Set<String> PAYOUT_ALLOWED_REFUND_STATUS =
            Set.of("merchant_approved", "shipping_back", "received", "partial");

    /**
     * 自身代理：`create()` 内部需要调用带 {@code @Transactional} 的 `walletPay()`，
     * 直接用 `this` 会绕过 Spring 事务代理，导致扣款与结算不在同一事务。
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private PaymentService self;

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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRefundApprovalMapper(RefundApprovalMapper refundApprovalMapper) {
        this.refundApprovalMapper = refundApprovalMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAuditService(AuditService auditService) {
        this.auditService = auditService;
    }

    /** 事务代理调用：未注入代理时（单测直接 new）退化为自身调用。 */
    private PaymentService transactionalSelf() {
        return self != null ? self : this;
    }

    // ===== P0-B：收款冲突处置接线 =====

    /**
     * 收款冲突处（挂账 + 自动退款）：可选注入以保持既有构造器与单测装配不变。
     * 未注入时冲突路径 fail-closed（抛错让渠道重试），绝不"装作成功"。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PaymentConflictService paymentConflictService;

    /** 与关单任务同源：渠道单过期时间 = 订单创建时间 + 该分钟数（app.order.auto-close-timeout-minutes） */
    @Value("${app.order.auto-close-timeout-minutes:30}")
    private long autoCloseMinutes = 30;

    /** 建单时至少保留的支付窗口（微信 time_expire 最小提前量），避免渠道窗口超出本地关单窗口 */
    @Value("${app.payment.channel-expire-safety-margin-minutes:5}")
    private long expireSafetyMarginMinutes = 5;

    /**
     * P0-B：把"只退渠道、不改订单"的冲突退款注册为补偿 job 的退款网关。
     * 无冲突服务（骨架期/单测）时保持空转，不改变任何既有行为。
     */
    @PostConstruct
    void wirePaymentConflictGateway() {
        if (paymentConflictService != null) {
            paymentConflictService.setRefundGateway(this::refundForConflict);
        }
    }

    /** 单测/手工装配入口：注入冲突服务时同步注册退款网关 */
    public void setPaymentConflictService(PaymentConflictService paymentConflictService) {
        this.paymentConflictService = paymentConflictService;
        if (paymentConflictService != null) {
            paymentConflictService.setRefundGateway(this::refundForConflict);
        }
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
        assertPayable(o);
        String channel = decideChannel(channelReq, o);

        PayCreateVO vo = new PayCreateVO();
        vo.setOrderNo(orderNo);
        vo.setChannel(channel);
        Map<String, Object> info = new HashMap<>();

        switch (channel) {
            case "wallet" -> {
                // 钱包余额支付：扣款 + 结算同一事务（任一步失败整体回滚）
                // 必须走代理，否则 @Transactional 不生效（自调用绕过 Spring AOP）
                info.putAll(transactionalSelf().walletPay(orderNo, userId, o.getTotalAmount()));
            }
            case "wechat" -> {
                if (!wechat.configured()) throw new BusinessException("微信支付未配置（或处于 mock 模式）");
                String notifyUrl = props.getNotifyBaseUrl() + "/payment/notify/wechat";
                String codeUrl = wechat.nativeOrder(orderNo, o.getTotalAmount(), "LANLinkshopping-" + orderNo, notifyUrl, wechatTimeExpire(o));
                orderService.setPrepay(orderNo, "wechat", null);
                info.put("codeUrl", codeUrl);
            }
            case "alipay" -> {
                if (!alipay.configured()) throw new BusinessException("支付宝未配置（或处于 mock 模式）");
                String notifyUrl = firstNonBlank(props.getAlipay().getNotifyUrl(), props.getNotifyBaseUrl() + "/payment/notify/alipay");
                String returnUrl = firstNonBlank(props.getAlipay().getReturnUrl(), props.getNotifyBaseUrl() + "/payment/alipay/return");
                String form = alipay.pagePayForm(orderNo, o.getTotalAmount(), "LANLinkshopping-" + orderNo, notifyUrl, returnUrl, alipayTimeoutMinutes(o));
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
            case "wallet" -> info.putAll(transactionalSelf().walletPaySubscription(orderNo, userId, amountYuan));
            case "wechat" -> {
                if (!wechat.configured()) throw new BusinessException("微信支付未配置（或处于 mock 模式）");
                String notifyUrl = props.getNotifyBaseUrl() + "/payment/notify/wechat";
                String codeUrl = wechat.nativeOrder(orderNo, amountYuan, "LANLink-Subscription-" + orderNo, notifyUrl, wechatTimeExpire(null));
                subscriptionOrderMapper.markChannel(orderNo, "wechat");
                info.put("codeUrl", codeUrl);
            }
            case "alipay" -> {
                if (!alipay.configured()) throw new BusinessException("支付宝未配置（或处于 mock 模式）");
                String notifyUrl = firstNonBlank(props.getAlipay().getNotifyUrl(), props.getNotifyBaseUrl() + "/payment/notify/alipay");
                String returnUrl = firstNonBlank(props.getAlipay().getReturnUrl(), props.getNotifyBaseUrl() + "/payment/alipay/return");
                String form = alipay.pagePayForm(orderNo, amountYuan, "LANLink-Subscription-" + orderNo, notifyUrl, returnUrl, alipayTimeoutMinutes(null));
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
        SettlePaidResult result = orderService.settlePaid(orderNo, "wallet", "W" + wl.getId());
        if (!result.settled()) {
            // 并发下订单已被支付/已关闭：抛异常回滚本次扣款。
            // 钱包扣款与结算同事务，回滚后没有任何外部资金变动，因此不写冲突挂账。
            if (result.outcome() == SettlePaidResult.Outcome.ALREADY_PAID) {
                throw new BusinessException("订单已支付，钱包扣款已撤销");
            }
            throw new BusinessException("订单状态已变更，钱包扣款已撤销");
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
        SettlePaidResult result = orderService.settlePaid(orderNo, "mock", "MOCKTXN" + ts());
        afterSettle(result, "mock", result.transactionId(), "mock_confirm");
        payLog.info(orderNo, "mock", "NOTIFY", "IN", result.settled(), "模拟支付确认 settled=" + result.settled());
        return result.settled();
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
                        SettlePaidResult result = orderService.settlePaid(orderNo, "wechat", data.get("transaction_id"));
                        afterSettle(result, "wechat", data.get("transaction_id"), "wechat_notify");
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
                        SettlePaidResult result = orderService.settlePaid(orderNo, "alipay", params.get("trade_no"));
                        afterSettle(result, "alipay", params.get("trade_no"), "alipay_notify");
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
            com.stripe.model.PaymentIntent intent =
                    (com.stripe.model.PaymentIntent) deserializer.getObject().orElse(null);
            if (intent == null) {
                // 账号默认 API 版本可能高于 SDK 内置版本（本机实测：Stripe 事件 api_version=2026-05-27.dahlia，
                // 而 stripe-java 28.2.0 期望 2024-12-18）。版本不匹配时 getObject() 返回 empty，
                // 若此处直接放弃，真实 webhook 会被**静默忽略**、订单永不置为已支付（资金已扣但状态不改）。
                // 因此降级为 deserializeUnsafe()：只解析本机已验签通过的 payload，不降低验签强度。
                try {
                    intent = (com.stripe.model.PaymentIntent) deserializer.deserializeUnsafe();
                    payLog.warn(null, "stripe", "NOTIFY", "IN", true,
                            "Stripe event api_version=" + event.getApiVersion()
                                    + " 与 SDK 期望版本不一致，已降级 deserializeUnsafe 处理 type=" + event.getType());
                } catch (Exception ex) {
                    payLog.error(null, "stripe", "DESERIAL_FAIL",
                            "已验签但事件体反序列化失败 api_version=" + event.getApiVersion()
                                    + " type=" + event.getType() + " err=" + ex.getMessage(), ex);
                    return "{\"received\": true}";
                }
            }
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
                        SettlePaidResult result = orderService.settlePaid(orderNo, "stripe", txnId);
                        afterSettle(result, "stripe", txnId, "stripe_notify");
                    }
                } else {
                    payLog.error(orderNo, "stripe", "NOTIFY", "金额不匹配 amount=" + amountCents, null);
                }
            }
        } else if ("payment_intent.payment_failed".equals(event.getType())
                || "payment_intent.canceled".equals(event.getType())) {
            /*
             * 支付失败/取消：只记录，不改订单状态。
             *
             * 为什么不改：用户可能换一张卡重试（同一 PaymentIntent 或新建一个），
             * 此时把订单置为「已取消」会让重试无法进行；订单最终由超时关单任务回收。
             * 但必须留痕——否则运营排查「用户说付了钱但没到账」时，无法区分
             * 「卡被拒」和「回调丢了」。
             */
            try {
                com.stripe.model.EventDataObjectDeserializer d = event.getDataObjectDeserializer();
                com.stripe.model.PaymentIntent failed =
                        (com.stripe.model.PaymentIntent) d.getObject().orElse(null);
                if (failed == null) {
                    failed = (com.stripe.model.PaymentIntent) d.deserializeUnsafe();
                }
                String orderNo = failed != null && failed.getMetadata() != null
                        ? failed.getMetadata().get("orderNo") : null;
                String reason = failed != null && failed.getLastPaymentError() != null
                        ? failed.getLastPaymentError().getMessage() : null;
                payLog.warn(orderNo, "stripe", "NOTIFY", "IN", false,
                        "Stripe " + event.getType() + " txn=" + (failed == null ? "?" : failed.getId())
                                + " reason=" + reason);
            } catch (Exception ex) {
                payLog.error(null, "stripe", "FAILED_EVENT_PARSE", ex.getMessage(), ex);
            }
        }
        return "{\"received\": true}";
    }

    // ===== 查单对账 =====

    /** Stripe 账户已激活的支付方式（供前端能力探测；未配置返回空集） */
    public java.util.Set<String> stripeActivePaymentMethods() {
        return stripe == null ? java.util.Set.of() : stripe.activePaymentMethodTypes();
    }

    /** Apple Pay 已注册域名数量（本地 IP 无法验证，正常为 0） */
    public int stripeApplePayDomainCount() {
        return stripe == null ? 0 : stripe.applePayDomainCount();
    }

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
                    if ("SUCCESS".equals(r.get("trade_state"))) {
                        SettlePaidResult result = orderService.settlePaid(orderNo, "wechat", r.get("transaction_id"));
                        afterSettle(result, "wechat", r.get("transaction_id"), "wechat_query");
                    }
                } else if ("alipay".equals(o.getPayChannel())) {
                    String st = alipay.queryTradeStatus(orderNo);
                    if ("TRADE_SUCCESS".equals(st) || "TRADE_FINISHED".equals(st)) {
                        SettlePaidResult result = orderService.settlePaid(orderNo, "alipay", null);
                        afterSettle(result, "alipay", null, "alipay_query");
                    }
                } else if ("stripe".equals(o.getPayChannel())) {
                    /*
                     * Stripe 主动查单兜底。
                     *
                     * 原来这里没有 stripe 分支：一旦 webhook 丢失（网络抖动、端点短暂 5xx、
                     * 密钥轮换期间验签失败），订单就永久停在未支付，而钱在 Stripe 侧已经扣了，
                     * 用户只能看到「已付款但订单未支付」。用户点「刷新支付状态」时走这条路径，
                     * 用 PaymentIntent 的真实 status 回填，是最直接的补偿手段。
                     */
                    if (stripe.configured() && notBlank(o.getTransactionId())) {
                        com.stripe.model.PaymentIntent pi = stripe.retrievePaymentIntent(o.getTransactionId());
                        if (pi != null && "succeeded".equals(pi.getStatus())) {
                            // 金额一致性校验：防止拿 A 单的 PI 回填 B 单
                            long expectCents = o.getTotalAmount() == null ? 0L
                                    : o.getTotalAmount().multiply(java.math.BigDecimal.valueOf(100))
                                            .setScale(0, java.math.RoundingMode.HALF_UP).longValue();
                            long actualCents = pi.getAmount() == null ? 0L : pi.getAmount();
                            if (expectCents == actualCents) {
                                SettlePaidResult result = orderService.settlePaid(orderNo, "stripe", pi.getId());
                                afterSettle(result, "stripe", pi.getId(), "stripe_query");
                            } else {
                                payLog.error(orderNo, "stripe", "QUERY", "金额不匹配 expect="
                                        + expectCents + " actual=" + actualCents, null);
                            }
                        }
                    }
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
     * 退款出款（平台运营入口，P0-C 退款出款收口）。
     *
     * <p>资金路径按原支付渠道退回：wallet 原路入账钱包；wechat/alipay 调渠道退款接口；mock 仅记录。
     * 全额退款额外触发回退结算：恢复库存、扣回支付所得积分、返还抵现积分、订单置为已退款。
     *
     * <p><b>守卫矩阵（状态 × 角色 × 入口）</b>：
     * <ol>
     *   <li><b>角色</b>：本方法只允许平台运营调用（Controller 上 {@code @RequirePerm("admin:all")}），
     *       商户/买家侧没有任何路径能到这里；RMA 侧商户动作只改状态，出款必须等平台复核；</li>
     *   <li><b>状态</b>：仅 {@code merchant_approved / shipping_back / received / partial} 可出款；
     *       {@code rejected} 永久禁止（出款不得推翻驳回），{@code success/processing/applied/none} 均拒绝；</li>
     *   <li><b>审批链</b>：必须存在 t_refund_approval 中最新一条 {@code PLATFORM/APPROVE} 且未被消费，
     *       金额与复核锁定金额一致；出款前 {@code consume} 原子消费，重复复核/并发只会有一次出款。</li>
     * </ol>
     *
     * <p>任何一项不满足都在触达渠道之前抛业务异常（403/409/400），渠道侧不会发生资金变动。
     */
    public boolean refund(RefundRequest req, Long adminId) {
        Order o = orderMapper.selectById(req.getOrderNo());
        if (o == null) throw new BusinessException("订单不存在");
        if (!isPaid(o)) throw new BusinessException("未支付订单不可退款");
        String refundStatus = o.getRefundStatus() == null ? "none" : o.getRefundStatus();
        if ("success".equals(refundStatus)) throw new BusinessException("该订单已全额退款");

        // ===== P0-C 守卫矩阵第 1 道：订单退款状态 =====
        // 驳回是终态：旧 SQL 把 rejected/none/applied 也放进出款白名单，等于用出款推翻驳回、跳过商户初审。
        if ("rejected".equals(refundStatus)) {
            throw new BusinessException(403,
                    "退款申请已被驳回，禁止出款；如有异议请走售后工单申诉，由平台重开复核");
        }
        if (!PAYOUT_ALLOWED_REFUND_STATUS.contains(refundStatus)) {
            throw new BusinessException(403, "当前退款状态不可出款（" + refundStatus
                    + "）：须完成商户初审与平台复核后再出款");
        }

        BigDecimal already = o.getRefundAmount() == null ? BigDecimal.ZERO : o.getRefundAmount();
        // P1-6：退款上限 = 可退总额（未发货 goods+freight / 已发货仅 goods），与 OrderService/OrderRmaService 同源。
        boolean shipped = o.getOrderStatus() != null && (o.getOrderStatus() == 1 || o.getOrderStatus() == 4);
        BigDecimal remain = com.lanlink.shopping.integration.pricing.OrderAmounts
                .refundableTotal(o, shipped).subtract(already);
        BigDecimal amount = req.getAmount() == null ? remain : req.getAmount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("退款金额需大于 0");
        if (amount.compareTo(remain) > 0) throw new BusinessException("退款金额超出可退余额");

        // ===== P0-C 守卫矩阵第 2 道：平台复核凭证（双人留痕 + 一次复核一次出款）=====
        if (refundApprovalMapper == null) {
            // fail-closed：审批组件缺失时宁可拒绝出款，也不退化成“无审批直接打款”
            throw new BusinessException(500, "退款审批组件未就绪，禁止出款（fail-closed）");
        }
        RefundApproval approval = refundApprovalMapper.findLatestPlatform(o.getOrderNo());
        if (approval == null || !"APPROVE".equals(approval.getAction())) {
            throw new BusinessException(403, "缺少平台复核通过记录，禁止出款；请先提交平台复核");
        }
        if (approval.getConsumed() != null && approval.getConsumed() == 1) {
            throw new BusinessException(409, "平台复核凭证已使用，禁止重复出款；如需再次退款请重新复核");
        }
        if (approval.getAmount() != null && approval.getAmount().compareTo(amount) != 0) {
            throw new BusinessException(403, "出款金额与平台复核锁定金额不一致（复核 ¥"
                    + approval.getAmount().toPlainString() + "，本次 ¥" + amount.toPlainString() + "）");
        }
        // 原子消费：并发重复复核/重复点击只有一路能拿到出款权；先消费再触达渠道，失败也不可重放。
        if (refundApprovalMapper.consume(approval.getId()) == 0) {
            throw new BusinessException(409, "平台复核凭证已被并发使用，禁止重复出款");
        }

        // ===== P0-C 守卫矩阵第 3 道：订单状态原子占位（旧 SQL 已收口，rejected 等不会再命中）=====
        int guarded = orderMapper.markRefundProcessingAtomic(o.getOrderNo());
        if (guarded == 0) {
            // 复核凭证已消费但状态闸没抢到（被并发处理/状态已变）：凭证作废，要求重新复核；
            // 此时尚未触达任何渠道，不会产生资金变动。
            throw new BusinessException(409,
                    "退款状态已变化或已被并发处理，平台复核凭证已作废，请刷新后重新复核");
        }
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
                    /*
                     * 只有 succeeded 才算退款成功。
                     *
                     * 原来把 pending 也当成功：本地会立即恢复库存、扣回积分、把订单置为已退款，
                     * 但 Stripe 侧退款此时可能还在处理中，甚至最终 failed——结果是
                     * 「本地已退款、资金没退出」，库存与积分被凭空回滚。
                     *
                     * pending 现在走 false：订单停在 processing，由
                     * PaymentReconciliationWatchdog#syncRefundStatus 轮询 queryRefundStatus
                     * 拿到最终态后再回写（那里已按金额口径区分部分/全额退款）。
                     */
                    ok = "succeeded".equals(ref.getStatus());
                    refundId = ref.getId();
                } else {
                    // 通道未配置时绝不能伪造成功：否则订单被置为已退款、恢复库存、回退积分，
                    // 但真实资金从未退出。抛出后由调用方回滚 processing 占位。
                    throw new BusinessException("Stripe 退款通道未配置，无法完成退款");
                }
            }
            default -> throw new BusinessException("不支持的退款渠道: " + channel);
        }

        // 渠道调用已在事务外完成；本地状态回写（退款单号 + 订单状态 + 全额退款结算）必须原子，
        // 否则渠道已出款、订单状态却可能只写了一半。
        o.setOutRefundNo(outRefundNo);
        transactionalSelf().applyRefundLocal(o, refundId, ok, already, amount, full);
        payLog.info(o.getOrderNo(), channel, "REFUND", "OUT", ok,
                "admin=" + adminId + " amount=" + amount + (full ? " FULL" : " PARTIAL") + " refundNo=" + outRefundNo);
        if (auditService != null) {
            auditService.record(adminId, "REFUND_PAYOUT",
                    "订单 " + o.getOrderNo() + " 平台出款 " + (ok ? "成功" : "失败")
                            + "，金额 ¥" + amount.toPlainString() + "，渠道=" + channel
                            + "，复核单=" + approval.getId() + "，退款单号=" + outRefundNo,
                    null);
        }
        return ok;
    }

    /**
     * 退款本地状态回写（事务边界）。
     *
     * <p>只做数据库写入，不再调用任何渠道接口，因此可以安全地包在一个事务里。
     */
    @Transactional
    public void applyRefundLocal(Order order, String refundId, boolean ok,
                                 BigDecimal already, BigDecimal amount, boolean full) {
        orderMapper.updateById(order);
        orderService.applyRefundResult(order.getOrderNo(), refundId,
                ok ? already.add(amount) : already, ok);
        if (ok && full) {
            // 全额退款：恢复库存 + 积分双向回退 + 订单状态回退
            orderService.fullRefundSettle(orderMapper.selectById(order.getOrderNo()));
        }
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
            /*
             * 金额口径必须是「本次已退累计额」，不是订单总额。
             *
             * 原实现无条件用 o.getTotalAmount() 回写，并对每一笔补偿都调用 fullRefundSettle，
             * 后果是：一笔 100 元订单只退了 30 元（部分退款），看门狗确认到账后会把它改写成
             * 「已退 100 元 + 全额退款」——库存全量回补、支付积分全量扣回、优惠券全量退回，
             * 一次部分退款被放大成一次全额退款。这是资金与库存的双重错账。
             *
             * 现在改为：沿用订单上已记录的累计退款额（refundAmount），只在「累计额已达可退总额」
             * 时才走 fullRefundSettle；部分退款只更新状态，不回滚库存/积分/券。
             */
            BigDecimal already = o.getRefundAmount() == null ? BigDecimal.ZERO : o.getRefundAmount();
            orderService.applyRefundResult(o.getOrderNo(), refundId, already, true);
            // 复用 applyRefundResult 的同一套全额判定（按可退总额，而非含运费总额），
            // 只有它判为 success（= 累计已退达可退上限）才回滚库存/积分/券。
            Order settled = orderMapper.selectById(o.getOrderNo());
            if (settled != null && "success".equals(settled.getRefundStatus())) {
                orderService.fullRefundSettle(settled);
            }
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

    // ===== P0-B：收款冲突处置（三态收口 / 冲突退款 / 入口门禁 / 渠道过期） =====

    /**
     * 结算三态收口：渠道回调无论哪种结局都必须 ACK（SETTLED / ALREADY_PAID / ORDER_CONFLICT）；
     * 只有"挂账写失败"才允许异常冒泡，由各 notify 入口返回 FAIL 让渠道重试——
     * 绝不能像旧实现那样"settled=false 仍然固定记 success 并 ACK"。
     */
    private void afterSettle(SettlePaidResult result, String channel, String transactionId, String source) {
        if (result == null) {
            throw new BusinessException("结算结果缺失，回调暂不确认（等待渠道重试）");
        }
        String orderNo = result.order() != null ? result.order().getOrderNo() : null;
        switch (result.outcome()) {
            case SETTLED -> payLog.info(orderNo, channel, "SETTLE", "IN", true,
                    "settled txn=" + transactionId + " source=" + source);
            case ALREADY_PAID -> {
                payLog.info(orderNo, channel, "SETTLE", "IN", true,
                        "duplicate ack txn=" + transactionId + " secondCharge=" + result.secondCharge()
                                + " source=" + source);
                if (result.secondCharge()) {
                    // 同一订单第二笔渠道流水：钱被扣了两次，不能只 ACK
                    recordConflict(result, channel, transactionId,
                            PaymentConflictService.TYPE_DUPLICATE_TXN, source);
                }
            }
            case ORDER_CONFLICT -> recordConflict(result, channel, transactionId,
                    PaymentConflictService.TYPE_LATE_PAY_AFTER_CLOSE, source);
            default -> throw new BusinessException("未知结算结果，回调暂不确认（等待渠道重试）");
        }
    }

    /**
     * 写收款冲突挂账（独立事务）。写失败必须冒泡：各 notify 入口会返回 FAIL，渠道重试把事件再送一次；
     * 系统绝不"没挂上账却假装成功"。
     */
    private void recordConflict(SettlePaidResult result, String channel, String transactionId,
                                String conflictType, String source) {
        if (paymentConflictService == null) {
            // fail-closed：冲突处置组件未就绪时宁可让渠道重试，也不吞掉资金事件
            throw new BusinessException("收款冲突挂账组件未就绪，回调暂不确认（等待重试）");
        }
        boolean created = paymentConflictService.record(result.order(), channel, transactionId,
                result.order() != null ? result.order().getTotalAmount() : null,
                conflictType, source);
        payLog.warn(result.order() != null ? result.order().getOrderNo() : null, channel,
                "CONFLICT", "IN", false,
                "conflict recorded=" + created + " txn=" + transactionId
                        + " orderStatus=" + (result.order() == null ? null : result.order().getOrderStatus())
                        + " type=" + conflictType + " source=" + source);
    }

    /**
     * 收款冲突退款（P0-B 补偿 job 的退款网关）：<b>只退渠道，不动订单</b>。
     *
     * <p>与 {@link #refund(RefundRequest, Long)} 的关键区别：
     * <ul>
     *   <li>不做 {@code isPaid} 前置（冲突单恰恰 pay_status=0，人工退款入口被它挡住）；</li>
     *   <li>不做平台复核凭证/退款状态占位（这不是订单退款申请，是关单后收款的事后冲正）；</li>
     *   <li><b>不调用 {@code applyRefundLocal} / {@code fullRefundSettle}</b>——关单时已恢复库存、
     *       返还积分/券，再走一次全额退款结算会造成库存虚增、积分双退、pay_status 被改。</li>
     * </ul>
     *
     * <p>返回 {@link PaymentConflictService.RefundAttempt}：成功=渠道已受理/已退款；
     * 失败=转交状态机退避重试，达上限转人工。渠道异常一律收敛为失败，不向上抛。
     */
    public PaymentConflictService.RefundAttempt refundForConflict(PaymentConflict conflict) {
        if (conflict == null) {
            return PaymentConflictService.RefundAttempt.failure("conflict required");
        }
        String orderNo = conflict.getOrderNo();
        String channel = conflict.getChannel() == null ? "" : conflict.getChannel().trim().toLowerCase();
        BigDecimal amount = conflict.getAmount() == null ? BigDecimal.ZERO : conflict.getAmount();
        String outRefundNo = conflict.getOutRefundNo();
        try {
            switch (channel) {
                case "wechat": {
                    Order o = orderMapper.selectById(orderNo);
                    BigDecimal total = o != null && o.getTotalAmount() != null ? o.getTotalAmount() : amount;
                    if (total.compareTo(amount) < 0) {
                        total = amount; // 防御：refundFee 不得大于 totalFee
                    }
                    Map<String, String> r = wechat.refund(orderNo, outRefundNo, total, amount,
                            "关单后收款自动原路退回");
                    return "SUCCESS".equals(r.get("result_code"))
                            ? PaymentConflictService.RefundAttempt.success(r.get("refund_id"))
                            : PaymentConflictService.RefundAttempt.failure(
                                    "wechat refund result_code=" + r.get("result_code"));
                }
                case "alipay": {
                    boolean ok = alipay.refund(orderNo, amount, "关单后收款自动原路退回");
                    return ok ? PaymentConflictService.RefundAttempt.success("ALI" + outRefundNo)
                            : PaymentConflictService.RefundAttempt.failure("alipay refund returned false");
                }
                case "stripe": {
                    if (!stripe.configured()) {
                        return PaymentConflictService.RefundAttempt.failure("Stripe 退款通道未配置");
                    }
                    var ref = stripe.refund(conflict.getTransactionId(), amount, "关单后收款自动原路退回");
                    boolean ok = "succeeded".equals(ref.getStatus()) || "pending".equals(ref.getStatus());
                    return ok ? PaymentConflictService.RefundAttempt.success(ref.getId())
                            : PaymentConflictService.RefundAttempt.failure("stripe refund status=" + ref.getStatus());
                }
                case "mock":
                    // 本地模拟渠道无真实资金：直接视为退款成功，便于真机复现关单后补回调链路
                    return PaymentConflictService.RefundAttempt.success("MOCK" + outRefundNo);
                default:
                    // wallet/term/corporate/transfer 不产生迟到渠道收款，自动退款不可用 → 交给人工
                    return PaymentConflictService.RefundAttempt.failure("渠道不支持自动退款: " + channel);
            }
        } catch (Exception e) {
            return PaymentConflictService.RefundAttempt.failure(
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * P0-B 支付入口门禁：只允许「待发货 order_status=0」的订单发起渠道收款。
     * 文案刻意通用，不暴露订单关闭/取消/退款等状态机细节（避免被用来探测订单状态）。
     * 账期单 pay_status=0 语义是"占用授信"，本门禁不读 pay_status，不会误伤；时间闸对账期单跳过。
     */
    private void assertPayable(Order o) {
        if (o.getOrderStatus() == null || o.getOrderStatus() != 0) {
            countGateRejected("order_status");
            payLog.warn(o.getOrderNo(), o.getPayChannel(), "CREATE", "OUT", false,
                    "payment gate rejected orderStatus=" + o.getOrderStatus()
                            + " payStatus=" + o.getPayStatus());
            throw new BusinessException("订单当前不可支付，请返回订单列表查看；如已付款请联系客服处理");
        }
        if ("term".equalsIgnoreCase(o.getPayType()) || o.getCreateTime() == null) {
            return;
        }
        LocalDateTime deadline = o.getCreateTime().plusMinutes(autoCloseMinutes);
        if (!LocalDateTime.now().isBefore(deadline.minusMinutes(expireSafetyMarginMinutes))) {
            countGateRejected("window");
            payLog.warn(o.getOrderNo(), o.getPayChannel(), "CREATE", "OUT", false,
                    "payment window nearly closed createTime=" + o.getCreateTime());
            throw new BusinessException("订单支付窗口即将结束，请重新下单");
        }
    }

    /** 门禁拒绝计数（可选指标：单测直接 new 时 meterRegistry 为空，静默跳过）。 */
    private void countGateRejected(String reason) {
        if (meterRegistry != null) {
            meterRegistry.counter("lanlink.payment.gate.rejected", "reason", reason).increment();
        }
    }

    /**
     * 微信 time_expire：与本地关单单源（createTime + app.order.auto-close-timeout-minutes），
     * clamp 到 [now+5m, now+2h]（微信 APIv2 最短 5 分钟；上限保守按 2 小时）。
     */
    private LocalDateTime wechatTimeExpire(Order o) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime desired = o != null && o.getCreateTime() != null
                ? o.getCreateTime().plusMinutes(autoCloseMinutes)
                : now.plusMinutes(autoCloseMinutes);
        LocalDateTime min = now.plusMinutes(5);
        LocalDateTime max = now.plusHours(2);
        if (desired.isBefore(min)) {
            return min;
        }
        return desired.isAfter(max) ? max : desired;
    }

    /** 支付宝 timeout_express：距本地关单 deadline 的剩余分钟，clamp 到 [1,120]（支付宝最小 1 分钟） */
    private Long alipayTimeoutMinutes(Order o) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = o != null && o.getCreateTime() != null
                ? o.getCreateTime().plusMinutes(autoCloseMinutes)
                : now.plusMinutes(autoCloseMinutes);
        long minutes = Duration.between(now, deadline).toMinutes();
        return Math.min(Math.max(minutes, 1L), 120L);
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
