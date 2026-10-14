package com.lanlink.shopping.payment;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.payment.wechat.WechatPayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 微信支付 APIv2 回调验签单测（按官方 V2 规范手工构造签名报文，不触网）。
 * 签名算法：参数按 ASCII 字典序 k=v& 拼接，末尾拼 key=商户密钥，MD5 后大写。
 */
class WechatPayClientTest {

    private static final String KEY = "LANLinkTestApiKey0123456789abcdef01"; // 32位测试密钥
    private PaymentProperties props;
    private WechatPayClient client;

    @BeforeEach
    void setUp() {
        props = new PaymentProperties();
        props.setMock(false);
        props.getWechat().setApiKey(KEY);
        props.getWechat().setSignType("MD5");
        client = new WechatPayClient(props);
    }

    /** 按微信 APIv2 规范生成 MD5 签名 */
    private static String md5Sign(Map<String, String> data, String key) {
        TreeMap<String, String> sorted = new TreeMap<>(data);
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> {
            if (!"sign".equals(k) && v != null && !v.isEmpty()) sb.append(k).append('=').append(v).append('&');
        });
        sb.append("key=").append(key);
        return DigestUtils.md5DigestAsHex(sb.toString().getBytes(StandardCharsets.UTF_8)).toUpperCase();
    }

    private static String toXml(Map<String, String> data) {
        StringBuilder sb = new StringBuilder("<xml>");
        data.forEach((k, v) -> sb.append('<').append(k).append('>').append(v).append("</").append(k).append('>'));
        return sb.append("</xml>").toString();
    }

    private String signedXml(Map<String, String> data) {
        data.put("sign", md5Sign(data, KEY));
        return toXml(data);
    }

    @Test
    void verifyValidCallback() {
        Map<String, String> data = new TreeMap<>();
        data.put("return_code", "SUCCESS");
        data.put("result_code", "SUCCESS");
        data.put("appid", "wx1234567890");
        data.put("mch_id", "1746178875");
        data.put("out_trade_no", "20261004123456");
        data.put("transaction_id", "WX4200001234202610040000");
        data.put("total_fee", "100");
        String xml = signedXml(data);

        Map<String, String> parsed = client.parseAndVerifyNotify(xml);
        assertEquals("20261004123456", parsed.get("out_trade_no"));
        assertEquals("SUCCESS", parsed.get("result_code"));
        assertEquals("WX4200001234202610040000", parsed.get("transaction_id"));
        assertEquals("100", parsed.get("total_fee"));
    }

    @Test
    void rejectTamperedCallback() {
        Map<String, String> data = new TreeMap<>();
        data.put("return_code", "SUCCESS");
        data.put("result_code", "SUCCESS");
        data.put("out_trade_no", "ORDER_A");
        data.put("total_fee", "100");
        String xml = signedXml(data);
        // 篡改金额（签名不再匹配）
        String tampered = xml.replace("<total_fee>100</total_fee>", "<total_fee>999999</total_fee>");
        assertThrows(BusinessException.class, () -> client.parseAndVerifyNotify(tampered));
    }

    @Test
    void rejectUnsignedCallback() {
        String xml = toXml(Map.of("return_code", "SUCCESS", "out_trade_no", "ORDER_B"));
        assertThrows(BusinessException.class, () -> client.parseAndVerifyNotify(xml));
    }

    @Test
    void notifyReplyFormat() {
        String ok = WechatPayClient.notifyReply(true, "OK");
        assertTrue(ok.contains("<return_code><![CDATA[SUCCESS]]></return_code>"));
        String fail = WechatPayClient.notifyReply(false, "FAIL");
        assertTrue(fail.contains("<return_code><![CDATA[FAIL]]></return_code>"));
    }

    @Test
    void configuredRequiresCredentialsAndNoMock() {
        // mock=true 时真实渠道不可用（回退模拟）
        props.setMock(true);
        assertFalse(client.configured());
        // 未配置 appId 同样不可用
        props.setMock(false);
        assertFalse(client.configured());
        props.getWechat().setAppId("wx123");
        props.getWechat().setMchId("1746178875");
        assertTrue(client.configured());
    }
}
