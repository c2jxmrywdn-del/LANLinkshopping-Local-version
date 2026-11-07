package com.lanlink.shopping.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesensitizeUtilTest {

    @Test
    void masksCommonPiiInFreeText() {
        String masked = DesensitizeUtil.maskPiiInText(
                "联系 13812345678，邮箱 zhangsan@example.com，身份证 110101199001011234，卡号 6222021234567890123");
        assertFalse(masked.contains("13812345678"));
        assertFalse(masked.contains("zhangsan@example.com"));
        assertFalse(masked.contains("110101199001011234"));
        assertFalse(masked.contains("6222021234567890123"));
        assertTrue(masked.contains("138****"));
    }

    @Test
    void leavesMaskedValuesIdempotent() {
        String once = DesensitizeUtil.maskPiiInText("13812345678");
        assertEquals(once, DesensitizeUtil.maskPiiInText(once));
    }
}
