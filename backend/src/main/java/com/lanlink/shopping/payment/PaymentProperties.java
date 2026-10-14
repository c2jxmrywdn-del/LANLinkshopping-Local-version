package com.lanlink.shopping.payment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 支付配置（前缀 lanlink.payment）。
 * 安全约定：所有密钥/证书路径仅从环境变量或 gitignore 的本地配置文件读取，
 * 默认关闭模拟支付；只在开发配置中显式开启，避免生产环境意外接受模拟支付。
 */
@Data
@ConfigurationProperties(prefix = "lanlink.payment")
public class PaymentProperties {

    /** 全局模拟开关：true 时不请求真实渠道，本地直接生成支付参数并支持手动确认回调 */
    private boolean mock = false;

    /** 回调/跳转的公网基础地址（真实联调需部署到 HTTPS 域名，例：https://shop.example.com/api） */
    private String notifyBaseUrl = "http://localhost:8080/api";

    private Wechat wechat = new Wechat();
    private Alipay alipay = new Alipay();

    @Data
    public static class Wechat {
        /** 公众号/小程序/开放平台 AppID */
        private String appId = "";
        /** 商户号 */
        private String mchId = "";
        /** APIv2 商户密钥（32 位，商户平台设置；用于签名，切勿入库） */
        private String apiKey = "";
        /** 商户 API 证书 apiclient_cert.p12 绝对路径 */
        private String certPath = "";
        /** 签名类型 MD5 | HMAC-SHA256 */
        private String signType = "MD5";
        /** 交易类型：NATIVE(扫码) / JSAPI / MWEB */
        private String tradeType = "NATIVE";
    }

    @Data
    public static class Alipay {
        /** 网关：正式 https://openapi.alipay.com/gateway.do 沙箱 https://openapi-sandbox.dl.alipaydev.com/gateway.do */
        private String gatewayUrl = "https://openapi.alipay.com/gateway.do";
        private String appId = "";
        /** 应用私钥（RSA2，PKCS8 base64，切勿入库） */
        private String privateKey = "";
        /** 签名类型 */
        private String signType = "RSA2";
        /** 异步通知地址（留空则用 notifyBaseUrl 拼接） */
        private String notifyUrl = "";
        /** 同步跳转地址（留空则用 notifyBaseUrl 拼接） */
        private String returnUrl = "";

        /** 公钥模式：false 用 alipayPublicKey 验签（普通公钥） */
        private boolean certMode = false;
        /** 支付宝公钥（普通公钥模式验签用，PKCS8 base64；certMode=false 时必填） */
        private String alipayPublicKey = "";
        /** 证书模式：应用公钥证书 appCertPublicKey_xxx.crt（certMode=true 时必填） */
        private String appCertPath = "";
        /** 证书模式：支付宝公钥证书 alipayCertPublicKey_RSA2.crt */
        private String alipayPublicCertPath = "";
        /** 证书模式：支付宝根证书 alipayRootCert.crt */
        private String rootCertPath = "";
    }
}
