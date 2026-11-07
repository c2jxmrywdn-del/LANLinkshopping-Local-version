package com.lanlink.shopping.module.techservice.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 技术交易存证哈希：对交易关键字段生成 SHA-256 摘要，用于事后核对内容是否被改动。
 * 注意：哈希只证明「内容一致」，不等于区块链存证，也不构成法律意义上的电子签名。
 */
public final class TechEvidenceHasher {

    private TechEvidenceHasher() {
    }

    public static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成存证哈希", e);
        }
    }
}
