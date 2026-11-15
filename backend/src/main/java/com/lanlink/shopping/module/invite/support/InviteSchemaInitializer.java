package com.lanlink.shopping.module.invite.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 邀请表结构（存量库幂等升级；与 sql/schema.sql 双写一致）。
 *
 * <p>两张表均 CREATE TABLE IF NOT EXISTS，重复启动只跳过、不改结构、不丢数据。
 * 无外键（与全库风格一致），邀请人/被邀请人有效性由 {@code InviteService} 在应用层保证。
 */
@Component
@Order(22)
public class InviteSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public InviteSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_invite_code (
                  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
                  code        VARCHAR(32) NOT NULL COMMENT '邀请码(大写Base32,去易混字符)',
                  inviter_id  BIGINT NOT NULL COMMENT '邀请人用户ID',
                  invite_type VARCHAR(24) NOT NULL DEFAULT 'buyer' COMMENT 'buyer=注册为买家 merchant_apply=商家入驻邀请码',
                  scene       VARCHAR(24) NOT NULL DEFAULT 'frontend' COMMENT 'frontend前台/admin_console运营后台',
                  inviter_role_id BIGINT NULL COMMENT '邀请人发起时角色ID(审计)',
                  status      TINYINT NOT NULL DEFAULT 1 COMMENT '1有效 2已失效',
                  used_count  INT NOT NULL DEFAULT 0 COMMENT '已使用次数',
                  max_uses    INT NOT NULL DEFAULT 0 COMMENT '最大使用次数,0=不限',
                  expires_at  DATETIME NULL COMMENT '过期时间,NULL=永不过期',
                  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
                  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_invite_code (code),
                  KEY idx_invite_inviter_status (inviter_id, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邀请码'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_invite_relation (
                  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
                  invite_code VARCHAR(32) NOT NULL COMMENT '注册时使用的邀请码',
                  inviter_id  BIGINT NOT NULL COMMENT '邀请人用户ID',
                  invitee_id  BIGINT NOT NULL COMMENT '被邀请人用户ID',
                  invite_type VARCHAR(24) NOT NULL DEFAULT 'buyer' COMMENT '邀请来源类型',
                  scene       VARCHAR(24) NOT NULL DEFAULT 'frontend' COMMENT '邀请场景',
                  granted_role_id BIGINT NULL COMMENT '被邀请人实际获得角色ID(审计)',
                  status      VARCHAR(16) NOT NULL DEFAULT 'bound' COMMENT 'bound/pending',
                  bound_at    DATETIME NULL COMMENT '绑定时间',
                  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_invite_invitee_type (invitee_id, invite_type),
                  KEY idx_invite_relation_inviter (inviter_id, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邀请关系'
                """);

        // 存量库补列（CREATE TABLE IF NOT EXISTS 不会给已存在的表加列）：
        // 先建过 t_invite_code/t_invite_relation 的环境缺 invite_type/used_count/max_uses 等字段，
        // 这里用 information_schema 守卫逐列补齐，重复启动只跳过。
        addColumnIfMissing("t_invite_code", "invite_type",
                "VARCHAR(24) NOT NULL DEFAULT 'buyer' COMMENT 'buyer=注册为买家 merchant_apply=商家入驻邀请码'");
        addColumnIfMissing("t_invite_code", "scene",
                "VARCHAR(24) NOT NULL DEFAULT 'frontend' COMMENT 'frontend前台/admin_console运营后台'");
        addColumnIfMissing("t_invite_code", "inviter_role_id",
                "BIGINT NULL COMMENT '邀请人发起时角色ID(审计)'");
        addColumnIfMissing("t_invite_code", "used_count",
                "INT NOT NULL DEFAULT 0 COMMENT '已使用次数'");
        addColumnIfMissing("t_invite_code", "max_uses",
                "INT NOT NULL DEFAULT 0 COMMENT '最大使用次数,0=不限'");
        addColumnIfMissing("t_invite_relation", "invite_type",
                "VARCHAR(24) NOT NULL DEFAULT 'buyer' COMMENT '邀请来源类型'");
        addColumnIfMissing("t_invite_relation", "scene",
                "VARCHAR(24) NOT NULL DEFAULT 'frontend' COMMENT '邀请场景'");
        addColumnIfMissing("t_invite_relation", "granted_role_id",
                "BIGINT NULL COMMENT '被邀请人实际获得角色ID(审计)'");

        // 唯一键升级：一个用户可能同时有「注册邀请」与「商家入驻邀约」两条关系
        // （先被邀请注册、后被运营邀约入驻），因此唯一性必须按 (invitee_id, invite_type) 收敛。
        migrateInviteeUniqueKey();
    }

    /**
     * 把旧的 {@code uk_invite_invitee(invitee_id)} 换成 {@code uk_invite_invitee_type(invitee_id, invite_type)}。
     *
     * <p>旧键会让「已通过注册邀请进来的用户」无法再被运营邀约入驻（唯一键冲突），
     * 因此需要升级；已有新键时直接跳过（幂等）。
     */
    private void migrateInviteeUniqueKey() {
        Integer hasNew = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_invite_relation'
                   AND INDEX_NAME = 'uk_invite_invitee_type'
                """, Integer.class);
        if (hasNew != null && hasNew > 0) return;
        Integer hasOld = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_invite_relation'
                   AND INDEX_NAME = 'uk_invite_invitee'
                """, Integer.class);
        if (hasOld != null && hasOld > 0) {
            jdbcTemplate.execute("ALTER TABLE t_invite_relation DROP INDEX uk_invite_invitee");
        }
        jdbcTemplate.execute(
                "ALTER TABLE t_invite_relation ADD UNIQUE KEY uk_invite_invitee_type (invitee_id, invite_type)");
    }

    /** 列不存在时补列（MySQL 8 无 ADD COLUMN IF NOT EXISTS，需查 information_schema）。 */
    private void addColumnIfMissing(String table, String column, String definition) {
        Integer exists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """, Integer.class, table, column);
        if (exists != null && exists > 0) return;
        jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }
}
