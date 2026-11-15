package com.lanlink.shopping.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 加密字段容量迁移：JWE compact 比原 AES 密文长得多。
 *
 * <p>JWE(RSA-OAEP-256 + A256GCM) 的固定开销约为：header ~180 字符 + 加密后的 CEK 342 字符
 * + IV/Tag 约 40 字符，再叠加明文本身的 Base64 膨胀。原来 255 长度的字段在明文超过约
 * 40 字时就会截断（实测 t_user_totp.secret 写入直接报 Data too long）。
 *
 * <p>因此这里把所有走 EncryptTypeHandler / CryptoService 的字段统一扩到 VARCHAR(2048)：
 * 可容纳约 1200 字符明文，足够地址、备注等场景；只扩不缩，幂等执行。
 * 版本与 KeyId 仍在 JWE header 中，不新增 key_id / cipher_version 列。
 */
@Component
// 必须最先执行：DataInitializer(@Order 1) 会写入 JWE 密文，若列仍是 VARCHAR(255) 会 Data too long。
// 用 -1 而不是 0，避免与 MerchantExitSchemaInitializer(@Order 0) 同值（同值顺序未定义）。
@Order(-1)
public class CryptoFieldSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CryptoFieldSchemaInitializer.class);

    private record ColumnRef(String table, String column) {
    }

    private static final List<ColumnRef> ENCRYPTED_COLUMNS = List.of(
            new ColumnRef("t_user_profile", "real_name"),
            new ColumnRef("t_user_profile", "phone"),
            new ColumnRef("t_user_profile", "email"),
            new ColumnRef("t_user_profile", "id_card"),
            new ColumnRef("t_user_profile", "bank_account"),
            new ColumnRef("t_user_profile", "address"),
            new ColumnRef("t_merchant", "legal_person"),
            new ColumnRef("t_merchant", "legal_id_card"),
            new ColumnRef("t_merchant", "bank_account"),
            new ColumnRef("t_merchant", "bank_name"),
            new ColumnRef("t_merchant", "contact_phone"),
            new ColumnRef("t_merchant", "contact_email"),
            new ColumnRef("t_merchant", "business_address"),
            new ColumnRef("t_user_totp", "secret")
    );

    private final JdbcTemplate jdbcTemplate;

    public CryptoFieldSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (ColumnRef ref : ENCRYPTED_COLUMNS) {
            try {
                widenIfNeeded(ref);
            } catch (Exception e) {
                // 单列失败不应阻断启动：其他字段仍可工作，启动日志给出明确表列名
                log.warn("[crypto-schema] 扩容跳过 {}.{}：{}", ref.table(), ref.column(), e.getMessage());
            }
        }
    }

    private void widenIfNeeded(ColumnRef ref) {
        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT DATA_TYPE AS dataType, IS_NULLABLE AS nullable "
                        + "FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                ref.table(), ref.column());
        if (rows.isEmpty()) {
            return;
        }
        String dataType = String.valueOf(rows.get(0).get("dataType")).toLowerCase();
        // 已经是大文本类型就跳过；TEXT 走行外存储，避免 t_merchant 多列同时放大触发 65535 行上限
        if ("text".equals(dataType) || "mediumtext".equals(dataType) || "longtext".equals(dataType)) {
            return;
        }
        String nullable = String.valueOf(rows.get(0).get("nullable"));
        String nullSql = "YES".equalsIgnoreCase(nullable) ? "NULL" : "NOT NULL";
        jdbcTemplate.execute("ALTER TABLE " + ref.table() + " MODIFY COLUMN " + ref.column()
                + " TEXT " + nullSql);
        log.info("[crypto-schema] {}.{} 已从 {} 扩容为 TEXT（JWE 密文长度需要，避免行大小超限）",
                ref.table(), ref.column(), dataType);
    }
}
