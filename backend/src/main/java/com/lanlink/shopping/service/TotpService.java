package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.crypto.CryptoService;
import com.lanlink.shopping.entity.UserTotp;
import com.lanlink.shopping.mapper.UserTotpMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TOTP 两步验证服务（RFC 6238，HmacSHA1 / 30s / 6 位）：
 * - 密钥 20 字节随机 → Base32（RFC4648 无填充，32 字符）
 * - 密钥 AES-GCM 密文入库（CryptoService）
 * - 登录票据：两步验证登录第一阶段的临时凭证（5 分钟过期、单次使用）
 */
@Service
public class TotpService {

    private static final char[] B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int WINDOW = 30;
    private static final long TICKET_TTL_MS = 5 * 60 * 1000L;

    private final UserTotpMapper mapper;
    private final Map<String, TicketBox> loginTickets = new ConcurrentHashMap<>();

    private static class TicketBox {
        Long userId;
        long exp;
    }

    public TotpService(UserTotpMapper mapper) {
        this.mapper = mapper;
    }

    // ===== 设置 / 启用 / 禁用 =====

    /** 生成新密钥与 otpauthUrl（草稿态：未启用；重复 setup 会重置旧密钥） */
    public Map<String, String> setup(Long userId, String account) {
        byte[] raw = new byte[20];
        new SecureRandom().nextBytes(raw);
        String secret = base32Encode(raw);
        UserTotp row = mapper.selectById(userId);
        if (row == null) {
            row = new UserTotp();
            row.setUserId(userId);
            row.setSecret(CryptoService.inst().encrypt(secret));
            row.setEnabled(0);
            mapper.insert(row);
        } else {
            row.setSecret(CryptoService.inst().encrypt(secret));
            row.setEnabled(0);
            row.setFirstVerifyTime(null);
            mapper.updateById(row);
        }
        String accountSafe = account == null || account.isBlank() ? "user" : account.replaceAll("[^\\w.@-]", "_");
        String otpauthUrl = "otpauth://totp/LANLink:" + accountSafe + "?secret=" + secret + "&issuer=LANLinkshopping";
        return Map.of("secret", secret, "otpauthUrl", otpauthUrl);
    }

    /** 启用：校验当前动态码正确后置 enabled=1 并记录首次验证时间（启用前校验不依赖 enabled 状态） */
    public void enable(Long userId, String code) {
        if (!verifySecret(userId, code)) throw new BusinessException("动态验证码错误");
        UserTotp row = mustRow(userId);
        row.setEnabled(1);
        row.setFirstVerifyTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        mapper.updateById(row);
    }

    /** 禁用：校验当前动态码正确后清除密钥并关闭 */
    public void disable(Long userId, String code) {
        if (!verify(userId, code)) throw new BusinessException("动态验证码错误");
        mapper.deleteById(userId);
    }

    public boolean isEnabled(Long userId) {
        UserTotp row = mapper.selectById(userId);
        return row != null && row.getEnabled() != null && row.getEnabled() == 1;
    }

    public LocalDateTime firstVerifyTime(Long userId) {
        UserTotp row = mapper.selectById(userId);
        return (row == null || row.getEnabled() == null || row.getEnabled() != 1) ? null : row.getFirstVerifyTime();
    }

    // ===== TOTP 校验（±1 个时间窗口容忍时钟偏差） =====

    /** 已启用场景校验（登录 / 禁用） */
    public boolean verify(Long userId, String code) {
        UserTotp row = mapper.selectById(userId);
        if (row == null || row.getEnabled() == null || row.getEnabled() != 1) return false;
        return verifySecret(userId, code);
    }

    /** 仅校验动态码与密钥是否匹配（不关心启用状态，供启用流程使用） */
    public boolean verifySecret(Long userId, String code) {
        UserTotp row = mapper.selectById(userId);
        if (row == null || code == null) return false;
        String secret = CryptoService.inst().decrypt(row.getSecret());
        if (secret == null || secret.isBlank()) return false;
        try {
            byte[] key = base32Decode(secret);
            long counter = System.currentTimeMillis() / 1000L / WINDOW;
            for (int w = -1; w <= 1; w++) {
                if (genCode(key, counter + w).equals(code)) return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private String genCode(byte[] key, long counter) throws Exception {
        byte[] data = new byte[8];
        for (int i = 7; i >= 0; i--) { data[i] = (byte) counter; counter >>= 8; }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(data);
        int offset = hash[hash.length - 1] & 0x0f;
        long bin = ((hash[offset] & 0x7fL) << 24) | ((hash[offset + 1] & 0xffL) << 16)
                 | ((hash[offset + 2] & 0xffL) << 8) | (hash[offset + 3] & 0xffL);
        return String.format("%06d", bin % 1_000_000L);
    }

    // ===== 登录票据（二步验证登录第一阶段） =====

    public String issueTicket(Long userId) {
        String ticket = UUID.randomUUID().toString().replace("-", "");
        TicketBox box = new TicketBox();
        box.userId = userId;
        box.exp = System.currentTimeMillis() + TICKET_TTL_MS;
        loginTickets.put(ticket, box);
        return ticket;
    }

    /** 消费票据：无效/过期返回 null（单次使用，消费后立即移除） */
    public Long consumeTicket(String ticket) {
        if (ticket == null) return null;
        TicketBox box = loginTickets.remove(ticket);
        if (box == null || box.exp < System.currentTimeMillis()) return null;
        return box.userId;
    }

    private UserTotp mustRow(Long userId) {
        UserTotp row = mapper.selectById(userId);
        if (row == null) throw new BusinessException("请先完成两步验证设置");
        return row;
    }

    // ===== Base32（RFC4648） =====

    private String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte value : data) {
            buffer = (buffer << 8) | (value & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(B32[(buffer >> (bitsLeft - 5)) & 0x1f]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) sb.append(B32[(buffer << (5 - bitsLeft)) & 0x1f]);
        return sb.toString();
    }

    private byte[] base32Decode(String s) {
        String clean = s.replace("=", "").trim().toUpperCase();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0, bitsLeft = 0;
        for (char c : clean.toCharArray()) {
            int v = indexOf(c);
            if (v < 0) throw new IllegalArgumentException("invalid base32 char: " + c);
            buffer = (buffer << 5) | v;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }

    private int indexOf(char c) {
        for (int i = 0; i < B32.length; i++) if (B32[i] == c) return i;
        return -1;
    }
}