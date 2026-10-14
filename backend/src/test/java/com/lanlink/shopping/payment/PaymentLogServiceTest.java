package com.lanlink.shopping.payment;

import com.lanlink.shopping.payment.service.PaymentLogService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 支付日志脱敏：签名/openid/密钥等敏感值不得出现在落库摘要中 */
class PaymentLogServiceTest {

    @Test
    void masksJsonSignAndOpenid() {
        String raw = "{\"out_trade_no\":\"A1\",\"openid\":\"oSECRET123\",\"sign\":\"ABCDEF123456\",\"total_fee\":\"100\"}";
        String masked = PaymentLogService.mask(raw);
        assertFalse(masked.contains("oSECRET123"), "openid 应被脱敏");
        assertFalse(masked.contains("ABCDEF123456"), "sign 应被脱敏");
        assertTrue(masked.contains("A1"), "非敏感字段应保留");
    }

    @Test
    void masksXmlSignTag() {
        String raw = "<xml><out_trade_no>A1</out_trade_no><sign>SHOULD_HIDE</sign></xml>";
        String masked = PaymentLogService.mask(raw);
        assertFalse(masked.contains("SHOULD_HIDE"));
        assertTrue(masked.contains("<sign>***</sign>"));
    }
}
