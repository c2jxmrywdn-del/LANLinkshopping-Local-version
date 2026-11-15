package com.lanlink.shopping.support.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.storage.StorageProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预签名策略与签名字段（纯本地计算，不联网）。
 */
class SupportMediaPresignServiceTest {

    private static final String VISITOR = "2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f";
    private static final Instant NOW = Instant.parse("2026-11-08T04:05:06Z");
    private static final String ORIGIN = "http://localhost:5173";

    private final ObjectMapper json = new ObjectMapper();

    private static StorageProperties props() {
        StorageProperties props = new StorageProperties();
        props.setEnabled(true);
        props.setAccessKey("AKIDEXAMPLE");
        props.setSecretKey("test-secret-key");
        props.setRegion("us-east-1");
        props.setSupportBucket("support-media");
        props.setPresignOrigins(List.of(ORIGIN, "http://127.0.0.1:5173/"));
        props.setPresignTtlSeconds(600);
        return props;
    }

    @Test
    void buildsPolicyWithAllRequiredConditions() throws Exception {
        SupportMediaPresignService service = new SupportMediaPresignService(props());
        SupportMediaPresignService.PresignResponse response = service.presign(
                ORIGIN, "SM20261108040506ABCDEF", SupportMediaKind.IMAGE, "png", "image/png",
                10L * 1024 * 1024, VISITOR, NOW);

        assertEquals("http://localhost:5173/support-media", response.action());
        assertEquals("SM20261108040506ABCDEF", response.mediaNo());
        assertEquals(10L * 1024 * 1024, response.maxBytes());
        assertEquals("2026-11-08T04:15:06Z", response.expiresAt());

        Map<String, String> fields = response.fields();
        assertTrue(fields.containsKey("key"));
        assertEquals("image/png", fields.get("Content-Type"));
        assertEquals("AWS4-HMAC-SHA256", fields.get("x-amz-algorithm"));
        assertEquals("AKIDEXAMPLE/20261108/us-east-1/s3/aws4_request", fields.get("x-amz-credential"));
        assertEquals("20261108T040506Z", fields.get("x-amz-date"));
        assertFalse(fields.containsValue("test-secret-key"), "响应不得包含 Secret Key");

        String key = fields.get("key");
        assertTrue(key.startsWith("support/202611/"));
        assertTrue(key.endsWith(".png"));

        String policyJson = new String(Base64.getDecoder().decode(fields.get("policy")), StandardCharsets.UTF_8);
        JsonNode policy = json.readTree(policyJson);
        assertEquals("2026-11-08T04:15:06Z", policy.path("expiration").asText());
        JsonNode conditions = policy.path("conditions");
        assertEquals(7, conditions.size());
        assertEquals("support-media", conditions.get(0).path("bucket").asText());
        assertEquals("starts-with", conditions.get(1).get(0).asText());
        assertEquals("$key", conditions.get(1).get(1).asText());
        assertTrue(key.startsWith(conditions.get(1).get(2).asText()));
        assertEquals("content-length-range", conditions.get(2).get(0).asText());
        assertEquals(1, conditions.get(2).get(1).asLong());
        assertEquals(10L * 1024 * 1024, conditions.get(2).get(2).asLong());
        // MinIO 要求请求里出现的每个 x-amz-* 表单字段都能在策略条件中找到，缺一即 403
        assertEquals("AWS4-HMAC-SHA256", conditions.get(3).path("x-amz-algorithm").asText());
        assertEquals("AKIDEXAMPLE/20261108/us-east-1/s3/aws4_request",
                conditions.get(4).path("x-amz-credential").asText());
        assertEquals("20261108T040506Z", conditions.get(5).path("x-amz-date").asText());
        assertEquals("starts-with", conditions.get(6).get(0).asText());
        assertEquals("$Content-Type", conditions.get(6).get(1).asText());
        assertEquals("image/", conditions.get(6).get(2).asText());
    }

    @Test
    void signatureMatchesIndependentSigV4Computation() throws Exception {
        StorageProperties props = props();
        SupportMediaPresignService service = new SupportMediaPresignService(props);
        SupportMediaPresignService.PresignResponse response = service.presign(
                ORIGIN, "SM20261108040506ABCDEF", SupportMediaKind.IMAGE, "png", "image/png",
                1024L, VISITOR, NOW);

        Map<String, String> fields = response.fields();
        byte[] signingKey = hmac(hmac(hmac(hmac(
                ("AWS4" + props.getSecretKey()).getBytes(StandardCharsets.UTF_8), "20261108"),
                props.getRegion()), "s3"), "aws4_request");
        String expected = HexFormat.of().formatHex(
                hmac(signingKey, fields.get("policy")));
        assertEquals(expected, fields.get("x-amz-signature"));
        assertEquals(64, fields.get("x-amz-signature").length());
        assertNotNull(fields.get("policy"));
    }

    @Test
    void rejectsOriginsOutsideWhitelist() {
        SupportMediaPresignService service = new SupportMediaPresignService(props());
        assertThrows(BusinessException.class, () -> service.presign(
                "http://evil.example.com", "SM1", SupportMediaKind.IMAGE, "png", "image/png",
                1024L, VISITOR, NOW));
        // 带路径的 Origin 一律拒绝
        assertThrows(BusinessException.class, () -> service.presign(
                "http://localhost:5173/app", "SM1", SupportMediaKind.IMAGE, "png", "image/png",
                1024L, VISITOR, NOW));
    }

    @Test
    void objectKeyUsesMonthlyAndVisitorHashPrefix() {
        SupportMediaPresignService service = new SupportMediaPresignService(props());
        String key = service.objectKey(VISITOR, "SM1", "png", NOW);
        String expectedPrefix = "support/202611/" + sha256Hex(VISITOR).substring(0, 16) + "/";
        assertTrue(key.startsWith(expectedPrefix));
        assertEquals(expectedPrefix + "SM1.png", key);
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
