-- =====================================================
-- LANLinkshopping 账号中心扩展脚本（个人信息 + 系统设置）
-- 与 schema.sql 相辅相成：schema.sql 建电商核心 11 张表，本脚本建账号中心相关表。
--
-- 【幂等承诺】与 schema.sql 一致：只做建库 CREATE DATABASE IF NOT EXISTS、
--   建表 CREATE TABLE IF NOT EXISTS、条件加列（information_schema 判断）、
--   不包含任何 DROP / TRUNCATE / DELETE。可被反复执行，已产生数据不受影响。
--
-- 【加载方式】application-dev.yml 的 spring.sql.init.schema-locations
--   已追加 classpath:sql/account.sql，启动后端时自动执行（排在 schema.sql 之后）。
-- =====================================================

CREATE DATABASE IF NOT EXISTS lanlink_shopping DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE lanlink_shopping;

-- ==================== 1. 用户资料表（敏感字段 AES-GCM 加密） ====================
-- 全新库：一次建出完整结构（含旧版 security_config.sql 缺失的列）
CREATE TABLE IF NOT EXISTS t_user_profile (
  user_id      BIGINT PRIMARY KEY COMMENT '用户ID',
  real_name    VARCHAR(255) COMMENT '真实姓名(AES密文)',
  id_card      VARCHAR(255) COMMENT '身份证号(AES密文)',
  bank_account VARCHAR(255) COMMENT '银行账号(AES密文)',
  address      VARCHAR(255) COMMENT '详细地址(AES密文)',
  gender       VARCHAR(16)  DEFAULT '' COMMENT '性别 male/female/secret',
  birthday     VARCHAR(16)  DEFAULT '' COMMENT '出生日期 yyyy-MM-dd',
  avatar       VARCHAR(255) DEFAULT '' COMMENT '头像地址 /api/uploads/...',
  bio          VARCHAR(1000) DEFAULT '' COMMENT '个人简介(已净化, 最多500字)',
  phone        VARCHAR(255) DEFAULT '' COMMENT '手机号(AES密文)',
  email        VARCHAR(255) DEFAULT '' COMMENT '邮箱(AES密文)',
  update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='用户资料(敏感字段AES-GCM加密)';

-- 已存在的旧版残缺表：逐列条件加列（MySQL 无 ADD COLUMN IF NOT EXISTS，用 information_schema + PREPARE）
SET @d1 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_user_profile ADD COLUMN gender VARCHAR(16) DEFAULT "" COMMENT "性别"', 'SELECT 1')
           FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_user_profile' AND COLUMN_NAME='gender');
PREPARE s1 FROM @d1; EXECUTE s1; DEALLOCATE PREPARE s1;
SET @d2 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_user_profile ADD COLUMN birthday VARCHAR(16) DEFAULT "" COMMENT "出生日期"', 'SELECT 1')
           FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_user_profile' AND COLUMN_NAME='birthday');
PREPARE s2 FROM @d2; EXECUTE s2; DEALLOCATE PREPARE s2;
SET @d3 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_user_profile ADD COLUMN avatar VARCHAR(255) DEFAULT "" COMMENT "头像"', 'SELECT 1')
           FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_user_profile' AND COLUMN_NAME='avatar');
PREPARE s3 FROM @d3; EXECUTE s3; DEALLOCATE PREPARE s3;
SET @d4 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_user_profile ADD COLUMN bio VARCHAR(1000) DEFAULT "" COMMENT "个人简介"', 'SELECT 1')
           FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_user_profile' AND COLUMN_NAME='bio');
PREPARE s4 FROM @d4; EXECUTE s4; DEALLOCATE PREPARE s4;
SET @d5 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_user_profile ADD COLUMN phone VARCHAR(255) DEFAULT "" COMMENT "手机号(AES密文)"', 'SELECT 1')
           FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_user_profile' AND COLUMN_NAME='phone');
PREPARE s5 FROM @d5; EXECUTE s5; DEALLOCATE PREPARE s5;
SET @d6 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_user_profile ADD COLUMN email VARCHAR(255) DEFAULT "" COMMENT "邮箱(AES密文)"', 'SELECT 1')
           FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_user_profile' AND COLUMN_NAME='email');
PREPARE s6 FROM @d6; EXECUTE s6; DEALLOCATE PREPARE s6;

-- ==================== 2. 系统设置表（settings_json 整存 notify/appearance/privacy） ====================
CREATE TABLE IF NOT EXISTS t_user_settings (
  user_id       BIGINT PRIMARY KEY COMMENT '用户ID',
  settings_json TEXT COMMENT '系统设置整体JSON',
  update_time   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='用户系统设置';

-- ==================== 3. TOTP 两步验证表 ====================
CREATE TABLE IF NOT EXISTS t_user_totp (
  user_id           BIGINT PRIMARY KEY COMMENT '用户ID',
  secret            VARCHAR(255) COMMENT 'TOTP密钥(Base32, AES密文)',
  enabled           TINYINT DEFAULT 0 COMMENT '0未启用 1已启用',
  first_verify_time DATETIME COMMENT '首次验证启用时间',
  update_time       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='用户TOTP两步验证';

-- ==================== 4. 第三方授权表 ====================
CREATE TABLE IF NOT EXISTS t_third_auth (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL COMMENT '用户ID',
  app_name    VARCHAR(64) NOT NULL COMMENT '应用名称',
  app_icon    VARCHAR(255) DEFAULT '' COMMENT '应用图标',
  scopes      VARCHAR(255) DEFAULT '' COMMENT '授权范围(逗号分隔)',
  auth_time   DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='第三方应用授权';

-- ==================== 5. 审计日志表 ====================
CREATE TABLE IF NOT EXISTS t_audit_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL COMMENT '用户ID',
  action      VARCHAR(64) NOT NULL COMMENT '动作编码, 如 CHANGE_PASSWORD/REVOKE_THIRD_AUTH/CACHE_CLEAR',
  detail      VARCHAR(500) DEFAULT '' COMMENT '操作摘要',
  client_ip   VARCHAR(64) DEFAULT '' COMMENT '客户端IP',
  user_agent  VARCHAR(255) DEFAULT '' COMMENT '设备/浏览器信息',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间'
) ENGINE=InnoDB COMMENT='用户审计日志(保留不少于180天)';

-- ==================== 6. 站内消息表 ====================
CREATE TABLE IF NOT EXISTS t_message (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL COMMENT '接收用户ID',
  type        VARCHAR(16) NOT NULL COMMENT '类型 order/promotion/system',
  title       VARCHAR(128) NOT NULL COMMENT '标题',
  content     VARCHAR(500) DEFAULT '' COMMENT '内容',
  related_no  VARCHAR(40) DEFAULT '' COMMENT '关联订单号等',
  read_flag   TINYINT DEFAULT 0 COMMENT '0未读 1已读',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '状态更新时间',
  KEY idx_user (user_id, read_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='站内消息';

-- ==================== 7. 登录安全记录表 ====================
CREATE TABLE IF NOT EXISTS t_login_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT DEFAULT NULL COMMENT '识别到的用户ID(失败且手机号不存在可为空)',
  phone       VARCHAR(20) NOT NULL COMMENT '尝试登录的手机号',
  success     TINYINT DEFAULT 0 COMMENT '0失败 1成功',
  reason      VARCHAR(64) DEFAULT '' COMMENT '成功/手机号或密码错误/动态验证码错误等',
  client_ip   VARCHAR(64) DEFAULT '' COMMENT '客户端IP',
  user_agent  VARCHAR(255) DEFAULT '' COMMENT '设备/浏览器信息',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='登录安全记录';

-- ==================== 8. 收货地址表 ====================
CREATE TABLE IF NOT EXISTS t_address (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL COMMENT '用户ID',
  receiver    VARCHAR(64) NOT NULL COMMENT '收货人',
  phone       VARCHAR(20) NOT NULL COMMENT '联系电话',
  region      VARCHAR(64) DEFAULT '' COMMENT '省市区',
  detail      VARCHAR(255) NOT NULL COMMENT '详细地址',
  is_default  TINYINT DEFAULT 0 COMMENT '是否默认地址 0否 1是',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_user (user_id),
  KEY idx_user_default (user_id, is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='收货地址';