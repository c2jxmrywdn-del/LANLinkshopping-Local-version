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

    /**
     * 平台对公收款账户（线下电汇用）。
     *
     * <p>历史缺陷：前端硬编码示例账号并标注"已企业认证"，用户可能按错误账户汇款。
     * 账户必须由部署配置下发；未配置时前端明确提示"平台暂未配置"。
     */
    private CorporateBank corporateBank = new CorporateBank();

    private Wechat wechat = new Wechat();
    private Alipay alipay = new Alipay();
    private Stripe stripe = new Stripe();

    @Data
    public static class CorporateBank {
        /** 开户行全称，如"中国工商银行股份有限公司深圳科技园支行" */
        private String bankName = "";
        /** 对公账号（明文或掩码均可，前端原样展示） */
        private String bankAccount = "";
        /** 收款户名 */
        private String accountName = "";
        /** 纳税人识别号（可选） */
        private String taxRegNo = "";
        /** 是否已完成企业认证（决定前端是否展示"已认证"徽标） */
        private boolean verified = false;
    }

    @Data
    public static class Stripe {
        /** API 私钥：sk_test_... 或 sk_live_...（切勿入库） */
        private String secretKey = "";
        /** 前端公钥：pk_test_... 或 pk_live_...（用于 Elements / Apple Pay 挂载） */
        private String publishableKey = "";
        /** Webhook 异步回调验签密钥：whsec_... */
        private String webhookSecret = "";
        /** 默认结算货币代码（小写 ISO 代码，如 cny, usd, eur） */
        private String currency = "cny";
        /**
         * 显式启用的支付方式（逗号分隔）。留空 = 使用 automatic_payment_methods，
         * 由 Stripe Dashboard 的开关决定可用方式（最安全，默认值）。
         *
         * <p>需要前端 Express Checkout 稳定出现钱包按钮时才配置为
         * {@code card,apple_pay,google_pay}。**前提**：Dashboard 已激活对应方式
         * 并完成 Apple Pay 域名验证，否则 Stripe 会拒绝整笔预下单。
         */
        private java.util.List<String> walletPaymentMethods = new java.util.ArrayList<>();
    }

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
        /** 网关：本地兜底指向沙箱；生产部署请通过配置显式指定正式网关 https://openapi.alipay.com/gateway.do */
        private String gatewayUrl = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
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
