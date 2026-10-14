package com.lanlink.shopping.service;

import com.lanlink.shopping.entity.UserTotp;
import com.lanlink.shopping.mapper.UserTotpMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.lanlink.shopping.crypto.CryptoService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TOTP 服务：密钥生成/Base32、启用校验（RFC 6238 算法在测试内复算）、禁用、登录票据。
 */
class TotpServiceTest {

    private UserTotpMapper mapper;
    private TotpService service;
    private UserTotp row;

    @BeforeAll
    static void initCrypto() {
        new CryptoService("lanlink-test-secret");
    }

    @BeforeEach
    void setUp() {
        mapper = mock(UserTotpMapper.class);
        service = new TotpService(mapper);
        row = null; // 会话内按行状态切换
        when(mapper.selectById(1L)).thenAnswer(inv -> row);
        doAnswer(inv -> { row = null; return 1; }).when(mapper).deleteById(1L);
    }

    private UserTotp captureRow() {
        ArgumentCaptor<UserTotp> captor = ArgumentCaptor.forClass(UserTotp.class);
        verify(mapper).insert(captor.capture());
        row = captor.getValue();
        return row;
    }

    @Test
    void setupReturnsSecretAndUrl() {
        Map<String, String> setup = service.setup(1L, "13900000001");
        assertEquals(32, setup.get("secret").length(), "Base32 密钥应为 32 字符");
        assertTrue(setup.get("otpauthUrl").startsWith("otpauth://totp/LANLink:13900000001?secret="));
        assertTrue(setup.get("otpauthUrl").endsWith("&issuer=LANLinkshopping"));

        UserTotp saved = captureRow();
        assertNotEquals(setup.get("secret"), saved.getSecret(), "入库密钥应为密文");
        assertEquals(0, saved.getEnabled());
    }

    @Test
    void enableWithValidCodeAndDisable() throws Exception {
        Map<String, String> setup = service.setup(1L, "13900000001");
        captureRow(); // 让 mock 的 selectById 返回刚插入的行
        // 测试内用同一 RFC 6238 算法复算当前动态码
        String secret = setup.get("secret");
        String code = totp(secret, System.currentTimeMillis());

        // 未启用前 isEnabled=false
        assertFalse(service.isEnabled(1L));

        service.enable(1L, code);
        assertTrue(service.isEnabled(1L));
        assertNotNull(service.firstVerifyTime(1L), "启用后应记录首次验证时间");
        assertTrue(service.verify(1L, code), "启用后动态码应可校验通过");

        // 禁用：同样需要动态码
        service.disable(1L, totp(secret, System.currentTimeMillis() + 30_000));
        assertFalse(service.isEnabled(1L));
        assertNull(service.firstVerifyTime(1L));
    }

    @Test
    void enableRejectsWrongCode() {
        service.setup(1L, "13900000001");
        captureRow();
        assertThrows(com.lanlink.shopping.common.BusinessException.class,
                () -> service.enable(1L, "000000"));
        assertFalse(service.isEnabled(1L));
    }

    @Test
    void verifyWindowTolerance() throws Exception {
        Map<String, String> setup = service.setup(1L, "13900000001");
        captureRow();
        service.enable(1L, totp(setup.get("secret"), System.currentTimeMillis()));
        // 上一窗口（-30s）与下一窗口（+30s）的动态码都应被容忍
        assertTrue(service.verify(1L, totp(setup.get("secret"), System.currentTimeMillis() - 30_000)));
        assertTrue(service.verify(1L, totp(setup.get("secret"), System.currentTimeMillis() + 30_000)));
    }

    @Test
    void loginTicketSingleUse() {
        String t1 = service.issueTicket(1L);
        assertEquals(1L, service.consumeTicket(t1));
        assertNull(service.consumeTicket(t1), "票据应单次使用");
        assertNull(service.consumeTicket("not-exist"));
        assertNull(service.consumeTicket(null));
    }

    // ===== 测试用 RFC 6238 计算（HmacSHA1 / 30s / 6 位） =====

    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private String totp(String secret, long timeMs) throws Exception {
        byte[] key = base32Decode(secret);
        long counter = timeMs / 1000L / 30L;
        byte[] data = new byte[8];
        long c = counter;
        for (int i = 7; i >= 0; i--) { data[i] = (byte) c; c >>= 8; }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(data);
        int off = hash[hash.length - 1] & 0x0f;
        long bin = ((hash[off] & 0x7fL) << 24) | ((hash[off + 1] & 0xffL) << 16)
                 | ((hash[off + 2] & 0xffL) << 8) | (hash[off + 3] & 0xffL);
        return String.format("%06d", bin % 1_000_000L);
    }

    private byte[] base32Decode(String s) {
        s = s.replace("=", "").trim().toUpperCase();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char ch : s.toCharArray()) {
            int v = B32.indexOf(ch);
            if (v < 0) throw new IllegalArgumentException("bad char " + ch);
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) { out.write((buffer >> (bits - 8)) & 0xff); bits -= 8; }
        }
        return out.toByteArray();
    }
}