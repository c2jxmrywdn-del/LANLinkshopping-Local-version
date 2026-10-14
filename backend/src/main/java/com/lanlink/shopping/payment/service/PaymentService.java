package com.lanlink.shopping.payment.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.payment.PaymentProperties;
import com.lanlink.shopping.payment.alipay.AlipayPayClient;
import com.lanlink.shopping.payment.dto.RefundRequest;
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
    private final OrderService orderService;
    private final OrderMapper orderMapper;
    private final PaymentLogService payLog;
    private final WalletService walletService;

    public PaymentService(PaymentProperties props, WechatPayClient wechat, AlipayPayClient alipay,
                          OrderService orderService, OrderMapper orderMapper, PaymentLogService payLog,
                          WalletService walletService) {
        this.props = props;
        this.wechat = wechat;
        this.alipay = alipay;
        this.orderService = orderService;
        this.orderMapper = orderMapper;
        this.payLog = payLog;
        this.walletService = walletService;
    }

    // ===== 发起支付 =====

    public PayCreateVO create(String orderNo, Long userId, String channelReq) {
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

    /** 仅开发/演示环境允许的模拟支付确认入口 */
    public boolean mockConfirm(String orderNo, Long userId) {
        if (!props.isMock()) {
            throw new BusinessException("模拟支付未启用");
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
                    orderService.settlePaid(orderNo, "wechat", data.get("transaction_id"));
                    payLog.info(orderNo, "wechat", "NOTIFY", "IN", true, "支付成功 txn=" + data.get("transaction_id"));
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
                Order o = orderMapper.selectById(orderNo);
                if (o != null && o.getTotalAmount().compareTo(total.setScale(2, RoundingMode.HALF_UP)) == 0) {
                    orderService.settlePaid(orderNo, "alipay", params.get("trade_no"));
                    payLog.info(orderNo, "alipay", "NOTIFY", "IN", true, "支付成功 trade_no=" + params.get("trade_no"));
                } else {
                    payLog.error(orderNo, "alipay", "NOTIFY", "金额不匹配 total=" + total, null);
                    return "failure";
                }
            }
            return "success";
        } catch (Exception e) {
            payLog.error(orderNo, "alipay", "NOTIFY", "回调处理异常", e);
            return "failure";
        }
    }

    // ===== 查单对账 =====

    public PayStatusVO query(String orderNo, Long userId) {
        return query(orderNo, userId, false);
    }

    public PayStatusVO query(String orderNo, Long userId, boolean isAdmin) {
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
        BigDecimal already = o.getRefundAmount() == null ? BigDecimal.ZERO : o.getRefundAmount();
        BigDecimal remain = o.getTotalAmount().subtract(already);
        BigDecimal amount = req.getAmount() == null ? remain : req.getAmount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("退款金额需大于 0");
        if (amount.compareTo(remain) > 0) throw new BusinessException("退款金额超出可退余额");
        boolean full = amount.compareTo(remain) == 0;
        String outRefundNo = "RF" + ts() + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String channel = o.getPayChannel() == null ? "mock" : o.getPayChannel();

        boolean ok;
        String refundId;
        switch (channel) {
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
            default -> throw new BusinessException("不支持的退款渠道: " + channel);
        }

        orderService.applyRefundResult(o.getOrderNo(), refundId, ok ? already.add(amount) : already, ok);
        if (ok && full) {
            // 全额退款：恢复库存 + 积分双向回退 + 订单状态回退
            orderService.fullRefundSettle(orderMapper.selectById(o.getOrderNo()));
        }
        payLog.info(o.getOrderNo(), channel, "REFUND", "OUT", ok,
                "admin=" + adminId + " amount=" + amount + (full ? " FULL" : " PARTIAL") + " refundNo=" + outRefundNo);
        return ok;
    }

    // ===== 内部工具 =====

    /** 渠道决策：wallet 为本地渠道任何模式可用；结算选择"企业钱包"的订单默认钱包支付 */
    private String decideChannel(String req, Order o) {
        String wanted = req == null || req.isBlank() ? null : req.trim().toLowerCase();
        if (wanted == null && "balance".equals(o.getPayType())) return "wallet";
        if (wanted == null) return props.isMock() ? "mock" : "wechat";

        if (!List.of("wallet", "wechat", "alipay", "mock").contains(wanted)) {
            throw new BusinessException("不支持的支付渠道: " + wanted);
        }
        if ("wallet".equals(wanted)) return "wallet";
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

    private boolean isPaid(Order o) { return o.getPayStatus() != null && o.getPayStatus() == 1; }

    private boolean amountMatches(String orderNo, String totalFeeFen) {
        if (totalFeeFen == null) return false;
        Order o = orderMapper.selectById(orderNo);
        if (o == null) return false;
        long expect = o.getTotalAmount().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValue();
        return expect == Long.parseLong(totalFeeFen);
    }

    private static String ts() { return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")); }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String firstNonBlank(String a, String b) { return notBlank(a) ? a : b; }
}
