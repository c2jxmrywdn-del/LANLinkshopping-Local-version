package com.lanlink.shopping.payment.wechat;

import com.github.binarywang.wxpay.bean.notify.WxPayOrderNotifyResult;
import com.github.binarywang.wxpay.bean.request.WxPayRefundRequest;
import com.github.binarywang.wxpay.bean.request.WxPayUnifiedOrderRequest;
import com.github.binarywang.wxpay.bean.result.WxPayOrderQueryResult;
import com.github.binarywang.wxpay.bean.result.WxPayRefundResult;
import com.github.binarywang.wxpay.bean.result.WxPayUnifiedOrderResult;
import com.github.binarywang.wxpay.config.WxPayConfig;
import com.github.binarywang.wxpay.constant.WxPayConstants;
import com.github.binarywang.wxpay.exception.WxPayException;
import com.github.binarywang.wxpay.service.WxPayService;
import com.github.binarywang.wxpay.service.impl.WxPayServiceImpl;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.payment.PaymentProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 微信支付 APIv2 客户端（基于 GitHub 开源 weixin-java-pay 二次封装）。
 * 能力：Native 统一下单（扫码）、订单查询、退款（双向证书）、异步通知验签。
 * 安全：APIv2 密钥仅保存在内存配置（环境变量注入）；退款所需的商户证书
 * apiclient_cert.p12 从本地受控路径加载，密钥/证书均不落库、不入代码仓库。
 * 仅当未开启 mock 且 appid/mchid/apiKey 齐备时可用；否则由门面回退 mock 渠道。
 */
@Component
public class WechatPayClient {

    private final PaymentProperties props;

    public WechatPayClient(PaymentProperties props) {
        this.props = props;
    }

    public boolean configured() {
        PaymentProperties.Wechat w = props.getWechat();
        return !props.isMock()
                && notBlank(w.getAppId()) && notBlank(w.getMchId()) && notBlank(w.getApiKey());
    }

    /** 每次调用按当前配置构建服务：配置热更新（环境变量）后无需重启即可生效 */
    private WxPayService newService() {
        PaymentProperties.Wechat w = props.getWechat();
        WxPayConfig cfg = new WxPayConfig();
        cfg.setAppId(w.getAppId());
        cfg.setMchId(w.getMchId());
        cfg.setMchKey(w.getApiKey());                       // APIv2 签名密钥
        cfg.setSignType(w.getSignType());                   // MD5 | HMAC-SHA256
        if (notBlank(w.getCertPath())) {
            // 商户 API 证书 p12（退款等敏感接口需双向证书），密码默认商户号
            cfg.setKeyPath(w.getCertPath());
        }
        WxPayService svc = new WxPayServiceImpl();
        svc.setConfig(cfg);
        return svc;
    }

    /** Native 下单，返回二维码 code_url */
    public String nativeOrder(String orderNo, BigDecimal amountYuan, String body, String notifyUrl) {
        try {
            WxPayUnifiedOrderRequest req = WxPayUnifiedOrderRequest.newBuilder()
                    .body(body)
                    .outTradeNo(orderNo)
                    .totalFee(toFen(amountYuan))
                    .spbillCreateIp("127.0.0.1")
                    .notifyUrl(notifyUrl)
                    .tradeType(WxPayConstants.TradeType.NATIVE)
                    .productId(orderNo)
                    .build();
            WxPayUnifiedOrderResult r = newService().unifiedOrder(req);
            if (r.getCodeURL() == null || r.getCodeURL().isBlank()) {
                throw new BusinessException("微信下单未返回 code_url: " + safe(r.getReturnCode(), r.getReturnMsg()));
            }
            return r.getCodeURL();
        } catch (WxPayException e) {
            throw new BusinessException("微信支付下单异常: " + e.getMessage());
        }
    }

    /** 查单：返回 trade_state / transaction_id / total_fee 等字段（与门面约定以 Map 解耦 SDK 类型） */
    public Map<String, String> query(String orderNo) {
        try {
            WxPayOrderQueryResult r = newService().queryOrder(null, orderNo);
            Map<String, String> out = new HashMap<>();
            out.put("trade_state", r.getTradeState());
            out.put("transaction_id", r.getTransactionId());
            out.put("total_fee", r.getTotalFee() == null ? null : String.valueOf(r.getTotalFee()));
            return out;
        } catch (WxPayException e) {
            throw new BusinessException("微信支付查单异常: " + e.getMessage());
        }
    }

    /** 退款（需商户证书），返回 result_code / refund_id */
    public Map<String, String> refund(String orderNo, String outRefundNo, BigDecimal totalYuan,
                                      BigDecimal refundYuan, String reason) {
        try {
            WxPayRefundRequest req = WxPayRefundRequest.newBuilder()
                    .outTradeNo(orderNo)
                    .outRefundNo(outRefundNo)
                    .totalFee(toFen(totalYuan))
                    .refundFee(toFen(refundYuan))
                    .refundDesc(reason == null || reason.isBlank() ? "用户退款" : reason)
                    .build();
            WxPayRefundResult r = newService().refund(req);
            Map<String, String> out = new HashMap<>();
            out.put("result_code", r.getResultCode());
            out.put("refund_id", r.getRefundId());
            return out;
        } catch (WxPayException e) {
            throw new BusinessException("微信退款异常: " + e.getMessage());
        }
    }

    /** 解析并验签回调 XML（验签失败抛业务异常）：返回 return_code/result_code/out_trade_no/transaction_id/total_fee */
    public Map<String, String> parseAndVerifyNotify(String xmlBody) {
        try {
            WxPayOrderNotifyResult n = newService().parseOrderNotifyResult(xmlBody);
            Map<String, String> out = new HashMap<>();
            out.put("return_code", n.getReturnCode());
            out.put("result_code", n.getResultCode());
            out.put("out_trade_no", n.getOutTradeNo());
            out.put("transaction_id", n.getTransactionId());
            out.put("total_fee", n.getTotalFee() == null ? null : String.valueOf(n.getTotalFee()));
            return out;
        } catch (WxPayException e) {
            throw new BusinessException("微信回调验签/解析失败: " + e.getMessage());
        }
    }

    /** 回调应答 XML */
    public static String notifyReply(boolean success, String msg) {
        String rc = success ? "SUCCESS" : "FAIL";
        return "<xml><return_code><![CDATA[" + rc + "]]></return_code>"
                + "<return_msg><![CDATA[" + (msg == null ? "OK" : msg) + "]]></return_msg></xml>";
    }

    private static int toFen(BigDecimal yuan) {
        return yuan.multiply(BigDecimal.valueOf(100)).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
    }

    private static String safe(String code, String msg) {
        return (code == null ? "" : code) + "/" + (msg == null ? "" : msg);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
