package com.lanlink.shopping.payment.alipay;

import com.alipay.api.AlipayClient;
import com.alipay.api.CertAlipayRequest;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.payment.PaymentProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 支付宝客户端（电脑网站支付 / 查询 / 退款 / 回调验签）。
 * 凭证未配置时 configured() 返回 false，由门面回退 mock；本类不含任何真实密钥。
 */
@Component
public class AlipayPayClient {

    private final PaymentProperties props;

    public AlipayPayClient(PaymentProperties props) {
        this.props = props;
    }

    public boolean configured() {
        PaymentProperties.Alipay a = props.getAlipay();
        if (props.isMock() || !notBlank(a.getAppId()) || !notBlank(a.getPrivateKey())) return false;
        if (a.isCertMode()) {
            return notBlank(a.getAppCertPath()) && notBlank(a.getAlipayPublicCertPath()) && notBlank(a.getRootCertPath());
        }
        return notBlank(a.getAlipayPublicKey());
    }

    private AlipayClient client() {
        PaymentProperties.Alipay a = props.getAlipay();
        try {
            if (a.isCertMode()) {
                CertAlipayRequest cert = new CertAlipayRequest();
                cert.setServerUrl(a.getGatewayUrl());
                cert.setAppId(a.getAppId());
                cert.setPrivateKey(a.getPrivateKey());
                cert.setFormat("json");
                cert.setCharset("UTF-8");
                cert.setSignType(a.getSignType());
                cert.setCertPath(a.getAppCertPath());
                cert.setAlipayPublicCertPath(a.getAlipayPublicCertPath());
                cert.setRootCertPath(a.getRootCertPath());
                return new DefaultAlipayClient(cert);
            }
            return new DefaultAlipayClient(a.getGatewayUrl(), a.getAppId(), a.getPrivateKey(),
                    "json", "UTF-8", a.getAlipayPublicKey(), a.getSignType());
        } catch (Exception e) {
            throw new BusinessException("支付宝客户端初始化失败: " + e.getMessage());
        }
    }

    /** 电脑网站支付，返回自动提交表单 HTML（前端渲染后跳转支付宝） */
    public String pagePayForm(String orderNo, BigDecimal amountYuan, String subject, String notifyUrl, String returnUrl) {
        try {
            AlipayTradePagePayRequest req = new AlipayTradePagePayRequest();
            req.setNotifyUrl(notifyUrl);
            req.setReturnUrl(returnUrl);
            req.setBizContent("{" +
                    "\"out_trade_no\":\"" + orderNo + "\"," +
                    "\"total_amount\":\"" + amountYuan.setScale(2, java.math.RoundingMode.HALF_UP) + "\"," +
                    "\"subject\":\"" + escape(subject) + "\"," +
                    "\"product_code\":\"FAST_INSTANT_TRADE_PAY\"" +
                    "}");
            return client().pageExecute(req).getBody();
        } catch (Exception e) {
            throw new BusinessException("支付宝下单异常: " + e.getMessage());
        }
    }

    /** 查询交易状态：TRADE_SUCCESS / TRADE_FINISHED / WAIT_BUYER_PAY / TRADE_CLOSED */
    public String queryTradeStatus(String orderNo) {
        try {
            AlipayTradeQueryRequest req = new AlipayTradeQueryRequest();
            req.setBizContent("{\"out_trade_no\":\"" + orderNo + "\"}");
            AlipayTradeQueryResponse resp = client().execute(req);
            return resp.isSuccess() ? resp.getTradeStatus() : null;
        } catch (Exception e) {
            throw new BusinessException("支付宝查单异常: " + e.getMessage());
        }
    }

    /** 退款 */
    public boolean refund(String orderNo, BigDecimal refundYuan, String reason) {
        try {
            AlipayTradeRefundRequest req = new AlipayTradeRefundRequest();
            req.setBizContent("{\"out_trade_no\":\"" + orderNo + "\"," +
                    "\"refund_amount\":\"" + refundYuan.setScale(2, java.math.RoundingMode.HALF_UP) + "\"," +
                    "\"refund_reason\":\"" + escape(reason == null ? "用户退款" : reason) + "\"}");
            AlipayTradeRefundResponse resp = client().execute(req);
            return resp.isSuccess();
        } catch (Exception e) {
            throw new BusinessException("支付宝退款异常: " + e.getMessage());
        }
    }

    /** 异步通知验签（form 参数） */
    public boolean verifyNotify(Map<String, String> params) {
        try {
            PaymentProperties.Alipay a = props.getAlipay();
            if (a.isCertMode()) {
                return AlipaySignature.rsaCertCheckV1(params, a.getAlipayPublicCertPath(), "UTF-8", a.getSignType());
            }
            return AlipaySignature.rsaCheckV1(params, a.getAlipayPublicKey(), "UTF-8", a.getSignType());
        } catch (Exception e) {
            return false;
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
