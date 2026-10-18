package com.lanlink.shopping.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesensitizeUtilTest {

    @Test
    void testMaskPhone() {
        assertEquals("138****1234", DesensitizeUtil.maskPhone("13812341234"));
        assertEquals("138****1234", DesensitizeUtil.maskPhone("138****1234"));
        assertEquals("", DesensitizeUtil.maskPhone(""));
        assertNull(DesensitizeUtil.maskPhone(null));
        assertEquals("****", DesensitizeUtil.maskPhone("123"));
    }

    @Test
    void testMaskEmail() {
        assertEquals("u***r@example.com", DesensitizeUtil.maskEmail("user@example.com"));
        assertEquals("a*@test.org", DesensitizeUtil.maskEmail("ab@test.org"));
        assertEquals("***@test.org", DesensitizeUtil.maskEmail("a@test.org"));
        assertEquals("u***r@example.com", DesensitizeUtil.maskEmail("u***r@example.com"));
        assertNull(DesensitizeUtil.maskEmail(null));
    }

    @Test
    void testMaskRealName() {
        assertEquals("张*", DesensitizeUtil.maskRealName("张三"));
        assertEquals("李*龙", DesensitizeUtil.maskRealName("李小龙"));
        assertEquals("诸**明", DesensitizeUtil.maskRealName("诸葛孔明"));
        assertEquals("李*", DesensitizeUtil.maskRealName("李*"));
        assertNull(DesensitizeUtil.maskRealName(null));
    }

    @Test
    void testMaskIdCard() {
        assertEquals("110101********1234", DesensitizeUtil.maskIdCard("110101199001011234"));
        assertEquals("110101********1234", DesensitizeUtil.maskIdCard("110101********1234"));
        assertNull(DesensitizeUtil.maskIdCard(null));
    }

    @Test
    void testMaskBankCard() {
        assertEquals("6222***********1234", DesensitizeUtil.maskBankCard("6222021234567891234"));
        assertEquals("6222***********1234", DesensitizeUtil.maskBankCard("6222***********1234"));
        assertNull(DesensitizeUtil.maskBankCard(null));
    }

    @Test
    void testMaskApiKey() {
        assertEquals("sk-****1234", DesensitizeUtil.maskApiKey("sk-live_secret_key_1234"));
        assertEquals("sk-****1234", DesensitizeUtil.maskApiKey("sk-****1234"));
        assertEquals("sk-****", DesensitizeUtil.maskApiKey("short"));
        assertEquals("SF1****5678", DesensitizeUtil.maskApiKey("SF12345678"));
        assertNull(DesensitizeUtil.maskApiKey(null));
    }

    @Test
    void testMaskAddress() {
        assertEquals("北京市海淀区中关村南******", DesensitizeUtil.maskAddress("北京市海淀区中关村南大街1号院2号楼301"));
        assertEquals("北京市海淀区中关村南******", DesensitizeUtil.maskAddress("北京市海淀区中关村南******"));
        assertNull(DesensitizeUtil.maskAddress(null));
    }

    @Test
    void testIsMasked() {
        assertTrue(DesensitizeUtil.isMasked("138****1234"));
        assertTrue(DesensitizeUtil.isMasked("张*"));
        assertTrue(DesensitizeUtil.isMasked("sk-****abcd"));
        assertFalse(DesensitizeUtil.isMasked("13812341234"));
        assertFalse(DesensitizeUtil.isMasked("张三"));
        assertFalse(DesensitizeUtil.isMasked("sk-live_1234567890"));
        assertFalse(DesensitizeUtil.isMasked(null));
        assertFalse(DesensitizeUtil.isMasked(""));
    }
}
