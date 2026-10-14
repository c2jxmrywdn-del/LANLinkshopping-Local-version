-- 安全与动态配置迁移
SET NAMES utf8mb4;
USE lanlink_shopping;

-- 系统配置表（运行时热更新，无需重启）
CREATE TABLE IF NOT EXISTS t_sys_config (
  cfg_key     VARCHAR(64) PRIMARY KEY COMMENT '配置键',
  cfg_value   VARCHAR(255) COMMENT '配置值',
  remark      VARCHAR(255) COMMENT '说明',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='系统动态配置';

INSERT INTO t_sys_config(cfg_key,cfg_value,remark) VALUES
 ('site.maintenance','false','维护模式 true=暂停对外服务(后台/登录不受影响)'),
 ('merchant.min.capital','100000','商户入驻最低注册资本(元)'),
 ('home.hot.limit','8','首页热销推荐数量'),
 ('site.name','LANLinkshopping','站点名称')
ON DUPLICATE KEY UPDATE remark=VALUES(remark);

-- 用户敏感信息表（字段级 AES-GCM 加密存储）
CREATE TABLE IF NOT EXISTS t_user_profile (
  user_id      BIGINT PRIMARY KEY COMMENT '用户ID',
  real_name    VARCHAR(255) COMMENT '真实姓名(密文)',
  id_card      VARCHAR(255) COMMENT '身份证号(密文)',
  bank_account VARCHAR(255) COMMENT '银行账号(密文)',
  address      VARCHAR(255) COMMENT '详细地址(密文)',
  update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='用户敏感信息(加密存储)';
