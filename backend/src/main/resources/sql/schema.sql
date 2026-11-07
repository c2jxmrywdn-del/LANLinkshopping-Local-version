-- 强制客户端以 utf8mb4 通信：MySQL 官方镜像的 docker-entrypoint-initdb.d 执行本脚本时，
-- 容器内 mysql 客户端默认字符集是 latin1，会把脚本中的中文种子数据写成双重编码乱码。
SET NAMES utf8mb4;
-- =====================================================
-- LANLinkshopping 聚合型B2B电商平台 数据库脚本
-- 库名: lanlink_shopping  字符集: utf8mb4  排序规则: utf8mb4_general_ci  引擎: InnoDB
--
-- 【幂等承诺】本脚本只做三件事: 建库 CREATE DATABASE IF NOT EXISTS,
--   建表 CREATE TABLE IF NOT EXISTS, 写字典 INSERT IGNORE 加显式主键。
--   全文不含 DROP DATABASE / DROP TABLE / TRUNCATE / DELETE 等任何会删库删表或清空业务数据的语句,
--   因此可以被反复执行: 第一次建好结构, 之后每次都只是跳过, 已产生的订单/商品/用户数据不会丢。
-- =====================================================
-- 【引导式执行顺序 · 从零到可运行】
--   第 1 步 创建数据库: 见本文件 第 1 步 段落, 一句 CREATE DATABASE IF NOT EXISTS 加一句 USE,
--          之后所有建表与插入语句都确定作用在 lanlink_shopping 上, 不依赖客户端是否已选库。
--   第 2 步 建表 共 11 张, 顺序按依赖关系排列(先字典后业务, 仅逻辑顺序, 无外键约束):
--          t_role 角色, t_enterprise 企业, t_user 用户, t_merchant 商户, t_qualification 资质,
--          t_industry 行业, t_category 分类, t_product 商品, t_cart 购物车, t_order 订单, t_order_item 订单明细。
--   第 3 步 插入基础字典数据: 角色 3 条, 行业 4 条, 分类 11 条,
--          全部显式指定主键并用 INSERT IGNORE, 保证重复执行不报错、不产生重复行, 且 ID 稳定
--          (后端 DataInitializer 与示例商品按 cat_id 1-11, ind_id 1-4 硬编码映射, 依赖这份稳定性)。
--   第 4 步 示例业务数据(用户/企业/商户/商品): 不在本脚本内, 由后端 DataInitializer 启动时写入,
--          密码用 BCrypt 加密, 仅当 t_user 为空时执行一次, 详见 README 第二节 演示账号。
-- =====================================================
-- 【两种执行方式 · 与 application-dev.yml 的实际配置一致】
--   方式 A 自动执行(推荐, dev 环境默认):
--     application-dev.yml 已配置 spring.sql.init.mode 为 always,
--     spring.sql.init.encoding 为 UTF-8, spring.sql.init.schema-locations 为 classpath 下的 sql/schema.sql,
--     所以只要启动后端(端口 8080, 上下文 /api), 本脚本的第 1 到第 3 步就会自动跑完, 无需手动导入。
--     关于谁来真正建库: 数据源 URL 上的 createDatabaseIfNotExist=true 才是自动路径下真正建库的那一步,
--     JDBC 驱动必须先连上(或先建好)目标库, 连接建立成功之后才轮到 spring.sql.init 执行本脚本,
--     因此脚本里的 CREATE DATABASE 在自动路径下是幂等的双保险, 执行时会直接跳过。
--     需要留意的是 createDatabaseIfNotExist=true 有一个前置条件: 当前账号必须能连上服务器,
--     且该参数只对 URL 中指定的那个库名生效; 若 URL 里库名被改成别的(例如换测试库),
--     请同步修改本脚本 CREATE DATABASE 与 USE 两行的库名, 或者删掉这两行完全交给 URL 决定。
--     需要手动先建库的情况只有两种: 一是 URL 去掉了 createDatabaseIfNotExist=true,
--     二是执行账号没有 CREATE DATABASE 权限而由 DBA 代为建库。
--   方式 B 手动导入(适合装环境、交给评审复现、或不想依赖自动执行时):
--     直接执行本脚本即可, 第 1 步语句会自己建库, 不需要提前手建; 仅当执行账号无建库权限时才需先请 DBA 建库。
--     命令行写法(PowerShell 与 CMD)以及 Navicat / DataGrip / IDEA Database 的图形化做法统一写在 README 第二节,
--     那里给了可直接复制的完整命令(注意 PowerShell 不支持 小于号 重定向, 需用 cmd /c 或管道方式)。
--   防中文乱码三件套: 本文件以 UTF-8 无 BOM 保存, 库表字符集为 utf8mb4,
--     客户端连接加 --default-character-set=utf8mb4, Spring 侧由 spring.sql.init.encoding 指定 UTF-8。
--     导入后若中文显示为问号或乱码, 优先检查文件是否被另存为 GBK 以及客户端字符集参数。
-- =====================================================

-- ==================== 第 1 步 创建数据库 ====================
CREATE DATABASE IF NOT EXISTS lanlink_shopping DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE lanlink_shopping;

-- ==================== 第 2 步 建表 共 11 张 ====================
-- 角色表
CREATE TABLE IF NOT EXISTS t_role (
  role_id   BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '角色ID',
  role_name VARCHAR(32) NOT NULL COMMENT '角色名称',
  role_code VARCHAR(32) NOT NULL COMMENT '角色编码 buyer/merchant/admin'
) ENGINE=InnoDB COMMENT='角色';

-- 企业表
CREATE TABLE IF NOT EXISTS t_enterprise (
  ent_id      BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '企业ID',
  name        VARCHAR(128) NOT NULL COMMENT '企业名称',
  credit_code VARCHAR(64)  COMMENT '统一社会信用代码',
  member_level INT DEFAULT 1 COMMENT '会员等级',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='企业';

-- 用户表
CREATE TABLE IF NOT EXISTS t_user (
  user_id     BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
  phone       VARCHAR(20) NOT NULL UNIQUE COMMENT '手机号(登录账号)',
  password    VARCHAR(100) NOT NULL COMMENT 'BCrypt密码',
  nickname    VARCHAR(64) COMMENT '昵称',
  role_id     BIGINT NOT NULL COMMENT '角色ID',
  ent_id      BIGINT COMMENT '所属企业ID',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted     TINYINT DEFAULT 0 COMMENT '逻辑删除'
) ENGINE=InnoDB COMMENT='用户';

-- 商户表
CREATE TABLE IF NOT EXISTS t_merchant (
  mer_id        BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '商户ID',
  ent_id        BIGINT NOT NULL COMMENT '企业ID',
  user_id       BIGINT NOT NULL COMMENT '商户负责人用户ID',
  reg_type      VARCHAR(32) COMMENT '注册类型 公司/个体工商户',
  reg_capital   DECIMAL(14,2) COMMENT '注册资本(元)',
  tax_status    TINYINT DEFAULT 0 COMMENT '纳税记录 0未知 1稳定',
  join_type     VARCHAR(16) COMMENT '入驻方式 加盟/入驻/邀约',
  review_status TINYINT DEFAULT 0 COMMENT '审核状态 0待审 1通过 2驳回',
  reject_reason VARCHAR(255) COMMENT '驳回原因',
  license_url   VARCHAR(255) COMMENT '营业执照图片URL(JPG/PNG)',
  tax_proof_urls VARCHAR(1000) COMMENT '近3个月税务缴纳证明URL(逗号分隔,PDF/JPG)',
  tax_reg_no    VARCHAR(32) COMMENT '税务登记号(查询纳税记录用)',
  legal_person  VARCHAR(255) COMMENT '法人真实姓名(AES密文)',
  legal_id_card VARCHAR(255) COMMENT '法人身份证号(AES密文)',
  bank_account  VARCHAR(255) COMMENT '对公银行账号(AES密文)',
  bank_name     VARCHAR(255) COMMENT '开户银行及支行(AES密文)',
  contact_phone VARCHAR(255) COMMENT '负责人联系电话(AES密文)',
  contact_email VARCHAR(255) COMMENT '负责人联系邮箱(AES密文)',
  business_address VARCHAR(255) COMMENT '经营场所/注册地址(AES密文)',
  join_code        VARCHAR(64) COMMENT '平台邀约码/特约加盟码',
  has_special_permit TINYINT DEFAULT 0 COMMENT '是否有特殊经营许可证 0无 1有',
  special_permit_name VARCHAR(128) COMMENT '特殊经营许可证名称',
  special_permit_url VARCHAR(255) COMMENT '特殊经营许可证附件URL',
  create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT DEFAULT 0
) ENGINE=InnoDB COMMENT='商户';

-- 幂等升级：历史库 t_merchant 补齐营业执照/税务记录三列（列不存在时才 ADD）
SET @mc1 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_merchant ADD COLUMN license_url VARCHAR(255) COMMENT ''营业执照图片URL(JPG/PNG)''', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_merchant' AND COLUMN_NAME='license_url');
PREPARE mc_stmt1 FROM @mc1; EXECUTE mc_stmt1; DEALLOCATE PREPARE mc_stmt1;
SET @mc2 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_merchant ADD COLUMN tax_proof_urls VARCHAR(1000) COMMENT ''近3个月税务缴纳证明URL(逗号分隔,PDF/JPG)''', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_merchant' AND COLUMN_NAME='tax_proof_urls');
PREPARE mc_stmt2 FROM @mc2; EXECUTE mc_stmt2; DEALLOCATE PREPARE mc_stmt2;
SET @mc3 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_merchant ADD COLUMN tax_reg_no VARCHAR(32) COMMENT ''税务登记号(查询纳税记录用)''', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_merchant' AND COLUMN_NAME='tax_reg_no');
PREPARE mc_stmt3 FROM @mc3; EXECUTE mc_stmt3; DEALLOCATE PREPARE mc_stmt3;

-- 幂等升级：t_merchant 补齐商户重要信息加密字段（法人姓名/身份证/对公银行/联系方式/经营地址，列不存在时才 ADD）
SET @mc4 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_merchant ADD COLUMN legal_person VARCHAR(255) COMMENT ''法人真实姓名(AES密文)''', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_merchant' AND COLUMN_NAME='legal_person');
PREPARE mc_stmt4 FROM @mc4; EXECUTE mc_stmt4; DEALLOCATE PREPARE mc_stmt4;
SET @mc5 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_merchant ADD COLUMN legal_id_card VARCHAR(255) COMMENT ''法人身份证号(AES密文)''', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_merchant' AND COLUMN_NAME='legal_id_card');
PREPARE mc_stmt5 FROM @mc5; EXECUTE mc_stmt5; DEALLOCATE PREPARE mc_stmt5;
SET @mc6 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_merchant ADD COLUMN bank_account VARCHAR(255) COMMENT ''对公银行账号(AES密文)''', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_merchant' AND COLUMN_NAME='bank_account');
PREPARE mc_stmt6 FROM @mc6; EXECUTE mc_stmt6; DEALLOCATE PREPARE mc_stmt6;
SET @mc7 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_merchant ADD COLUMN bank_name VARCHAR(255) COMMENT ''开户银行及支行(AES密文)''', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_merchant' AND COLUMN_NAME='bank_name');
PREPARE mc_stmt7 FROM @mc7; EXECUTE mc_stmt7; DEALLOCATE PREPARE mc_stmt7;
SET @mc8 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_merchant ADD COLUMN contact_phone VARCHAR(255) COMMENT ''负责人联系电话(AES密文)''', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_merchant' AND COLUMN_NAME='contact_phone');
PREPARE mc_stmt8 FROM @mc8; EXECUTE mc_stmt8; DEALLOCATE PREPARE mc_stmt8;
SET @mc9 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_merchant ADD COLUMN contact_email VARCHAR(255) COMMENT ''负责人联系邮箱(AES密文)''', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_merchant' AND COLUMN_NAME='contact_email');
PREPARE mc_stmt9 FROM @mc9; EXECUTE mc_stmt9; DEALLOCATE PREPARE mc_stmt9;
SET @mc10 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_merchant ADD COLUMN business_address VARCHAR(255) COMMENT ''经营场所/注册地址(AES密文)''', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_merchant' AND COLUMN_NAME='business_address');
PREPARE mc_stmt10 FROM @mc10; EXECUTE mc_stmt10; DEALLOCATE PREPARE mc_stmt10;

-- 幂等升级：t_merchant 补齐入驻邀约码/加盟码及特殊经营许可字段
SET @mc11 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_merchant ADD COLUMN join_code VARCHAR(64) COMMENT ''平台邀约码/特约加盟码''', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_merchant' AND COLUMN_NAME='join_code');
PREPARE mc_stmt11 FROM @mc11; EXECUTE mc_stmt11; DEALLOCATE PREPARE mc_stmt11;

SET @mc12 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_merchant ADD COLUMN has_special_permit TINYINT DEFAULT 0 COMMENT ''是否有特殊经营许可证 0无 1有''', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_merchant' AND COLUMN_NAME='has_special_permit');
PREPARE mc_stmt12 FROM @mc12; EXECUTE mc_stmt12; DEALLOCATE PREPARE mc_stmt12;

SET @mc13 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_merchant ADD COLUMN special_permit_name VARCHAR(128) COMMENT ''特殊经营许可证名称''', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_merchant' AND COLUMN_NAME='special_permit_name');
PREPARE mc_stmt13 FROM @mc13; EXECUTE mc_stmt13; DEALLOCATE PREPARE mc_stmt13;

SET @mc14 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_merchant ADD COLUMN special_permit_url VARCHAR(255) COMMENT ''特殊经营许可证附件URL''', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_merchant' AND COLUMN_NAME='special_permit_url');
PREPARE mc_stmt14 FROM @mc14; EXECUTE mc_stmt14; DEALLOCATE PREPARE mc_stmt14;

-- 资质表
CREATE TABLE IF NOT EXISTS t_qualification (
  q_id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  mer_id      BIGINT NOT NULL COMMENT '商户ID',
  q_type      VARCHAR(64) COMMENT '资质类型',
  q_name      VARCHAR(128) COMMENT '资质名称',
  file_url    VARCHAR(255) COMMENT '附件地址',
  expire_date DATE COMMENT '有效期至',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='商户资质';

-- 行业表
CREATE TABLE IF NOT EXISTS t_industry (
  ind_id      BIGINT PRIMARY KEY AUTO_INCREMENT,
  name        VARCHAR(64) NOT NULL COMMENT '行业名称',
  code        VARCHAR(32) NOT NULL COMMENT '行业编码',
  config_json TEXT COMMENT '行业配置(资质要求等)',
  sort        INT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='行业';

-- 分类表
CREATE TABLE IF NOT EXISTS t_category (
  cat_id    BIGINT PRIMARY KEY AUTO_INCREMENT,
  parent_id BIGINT DEFAULT 0 COMMENT '父级分类,0为顶级',
  ind_id    BIGINT COMMENT '所属行业ID',
  name      VARCHAR(64) NOT NULL,
  level     INT DEFAULT 1,
  sort      INT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='商品分类';

-- 商品表
CREATE TABLE IF NOT EXISTS t_product (
  prod_id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  mer_id         BIGINT NOT NULL COMMENT '所属商户ID',
  cat_id         BIGINT COMMENT '分类ID',
  ind_id         BIGINT COMMENT '行业ID',
  title          VARCHAR(200) NOT NULL COMMENT '商品名称',
  brand          VARCHAR(64) COMMENT '品牌',
  spec           VARCHAR(128) COMMENT '规格',
  price          DECIMAL(12,2) NOT NULL COMMENT '销售单价',
  tier_price_json VARCHAR(500) COMMENT '阶梯价JSON [{qty,price}]',
  stock          INT DEFAULT 0 COMMENT '库存',
  cover_url      VARCHAR(255) COMMENT '主图',
  detail         TEXT COMMENT '详情',
  status         TINYINT DEFAULT 1 COMMENT '上架状态 1可售(已审核) 0不可售',
  review_status  TINYINT DEFAULT 0 COMMENT '审核状态 0待审核 1通过 2驳回',
  reject_reason  VARCHAR(255) COMMENT '驳回原因',
  review_source  VARCHAR(16) DEFAULT 'MANUAL' COMMENT '审核来源 MANUAL人工 AUTO自动',
  auto_score     INT COMMENT '自动审核评分 0-100',
  auto_reviewed_at DATETIME COMMENT '最近自动审核时间',
  sales          INT DEFAULT 0 COMMENT '销量',
  create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted        TINYINT DEFAULT 0,
  KEY idx_product_list_default (status, review_status, deleted, create_time),
  KEY idx_product_list_sales (status, review_status, deleted, sales),
  KEY idx_product_list_price (status, review_status, deleted, price),
  KEY idx_product_mer (mer_id, status)
) ENGINE=InnoDB COMMENT='商品';

-- 幂等升级：历史库 t_product 补齐审核状态两列（列不存在时才 ADD）
SET @pd1 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_product ADD COLUMN review_status TINYINT DEFAULT 0 COMMENT ''审核状态 0待审核 1通过 2驳回'' AFTER status', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_product' AND COLUMN_NAME='review_status');
PREPARE pd_stmt1 FROM @pd1; EXECUTE pd_stmt1; DEALLOCATE PREPARE pd_stmt1;
SET @pd2 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_product ADD COLUMN reject_reason VARCHAR(255) COMMENT ''驳回原因'' AFTER review_status', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_product' AND COLUMN_NAME='reject_reason');
PREPARE pd_stmt2 FROM @pd2; EXECUTE pd_stmt2; DEALLOCATE PREPARE pd_stmt2;
-- 自动审核：历史库幂等补审核来源/评分/审核时间三列
SET @pd3 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_product ADD COLUMN review_source VARCHAR(16) DEFAULT ''MANUAL'' COMMENT ''审核来源 MANUAL人工 AUTO自动'' AFTER reject_reason', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_product' AND COLUMN_NAME='review_source');
PREPARE pd_stmt3 FROM @pd3; EXECUTE pd_stmt3; DEALLOCATE PREPARE pd_stmt3;
SET @pd4 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_product ADD COLUMN auto_score INT COMMENT ''自动审核评分 0-100'' AFTER review_source', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_product' AND COLUMN_NAME='auto_score');
PREPARE pd_stmt4 FROM @pd4; EXECUTE pd_stmt4; DEALLOCATE PREPARE pd_stmt4;
SET @pd5 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_product ADD COLUMN auto_reviewed_at DATETIME COMMENT ''最近自动审核时间'' AFTER auto_score', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_product' AND COLUMN_NAME='auto_reviewed_at');
PREPARE pd_stmt5 FROM @pd5; EXECUTE pd_stmt5; DEALLOCATE PREPARE pd_stmt5;
-- 存量数据补齐：历史已上架商品视为审核通过（新发布的待审核商品 status=0 不受影响）
UPDATE t_product SET review_status = 1 WHERE status = 1 AND review_status = 0;

-- 商品自动审核配置（开关/阈值；运营可在管理端调整）
CREATE TABLE IF NOT EXISTS t_product_review_config (
  config_key   VARCHAR(64) PRIMARY KEY,
  config_value VARCHAR(255) NOT NULL,
  update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='商品自动审核配置';
INSERT IGNORE INTO t_product_review_config (config_key, config_value) VALUES
 ('auto_enabled', 'true'),
 ('ai_enabled', 'true'),
 ('vision_enabled', 'true'),
 ('auto_approve_score', '85'),
 ('daily_ai_budget', '300');

-- 商品自动审核记录（每次自动审核/重跑留痕，供人工复核与申诉追溯）
CREATE TABLE IF NOT EXISTS t_product_review_record (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  prod_id     BIGINT NOT NULL COMMENT '商品ID',
  decision    VARCHAR(24) NOT NULL COMMENT 'AUTO_APPROVE/AUTO_REJECT/MANUAL/SUGGEST',
  source      VARCHAR(16) NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO/MANUAL',
  score       INT COMMENT '综合评分 0-100',
  rule_result TEXT COMMENT '规则引擎结果 JSON',
  ai_result   TEXT COMMENT '文本AI结果 JSON',
  vision_result TEXT COMMENT '图像AI结果 JSON',
  model       VARCHAR(64) COMMENT '使用的模型/渠道',
  elapsed_ms  INT COMMENT '耗时毫秒',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_review_prod (prod_id, id)
) ENGINE=InnoDB COMMENT='商品自动审核记录';

-- 购物车表
CREATE TABLE IF NOT EXISTS t_cart (
  cart_id     BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL,
  prod_id     BIGINT NOT NULL,
  quantity    INT DEFAULT 1,
  checked     TINYINT DEFAULT 1 COMMENT '是否勾选结算',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='购物车';

-- 订单表
CREATE TABLE IF NOT EXISTS t_order (
  order_no     VARCHAR(40) PRIMARY KEY COMMENT '订单号',
  user_id      BIGINT NOT NULL COMMENT '采购方用户ID',
  ent_id       BIGINT COMMENT '采购方企业ID',
  total_amount DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '订单总额',
  pay_type     VARCHAR(16) COMMENT '支付方式 balance/corporate/term(账期)',
  pay_status   TINYINT DEFAULT 0 COMMENT '0未支付 1已支付',
  order_status TINYINT DEFAULT 0 COMMENT '0待发货 1已发货 2已完成 3已取消',
  receiver     VARCHAR(64) COMMENT '收货人',
  phone        VARCHAR(20) COMMENT '联系电话',
  address      VARCHAR(255) COMMENT '收货地址',
  remark       VARCHAR(255) COMMENT '备注',
  create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
  pay_time     DATETIME,
  pay_channel  VARCHAR(16) COMMENT '支付渠道 wechat/alipay/mock',
  transaction_id VARCHAR(64) COMMENT '第三方渠道交易号',
  prepay_id    VARCHAR(64) COMMENT '微信预下单号',
  refund_status VARCHAR(32) DEFAULT 'none' COMMENT '退款状态 none/applied/merchant_approved/rejected/processing/partial/success',
  refund_id    VARCHAR(64) COMMENT '渠道退款单号',
  refund_amount DECIMAL(14,2) COMMENT '已退金额',
  refund_time  DATETIME COMMENT '退款完成时间',
  update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_order_user_time (user_id, create_time),
  KEY idx_order_pay_time (pay_status, create_time),
  KEY idx_order_time (create_time)
) ENGINE=InnoDB COMMENT='订单';

-- 幂等兜底：为历史库中已存在、无默认值的 t_order.total_amount 补 DEFAULT 0
-- （代码层已保证插入前写入总额；此处确保任何遗漏路径都不会整单失败）
SET @ord1 = (SELECT IF(COUNT(*) = 1 AND MAX(COLUMN_DEFAULT) IS NULL,
    'ALTER TABLE t_order MODIFY total_amount DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT ''订单总额''', 'SELECT 1')
    FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='total_amount');
PREPARE ord_stmt FROM @ord1; EXECUTE ord_stmt; DEALLOCATE PREPARE ord_stmt;

-- 订单明细表
CREATE TABLE IF NOT EXISTS t_order_item (
  item_id    BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no   VARCHAR(40) NOT NULL,
  prod_id    BIGINT NOT NULL,
  prod_name  VARCHAR(200) COMMENT '下单时商品名(冗余快照)',
  price      DECIMAL(12,2) COMMENT '下单时单价(冗余快照)',
  quantity   INT,
  subtotal   DECIMAL(14,2),
  cover_url  VARCHAR(255)
) ENGINE=InnoDB COMMENT='订单明细';

-- ==================== 营销中台：活动系统（统一促销/领券/激励） ====================
-- 原 t_activity(注册/消费有礼) 与 t_promotion(满减/折扣) 已统一并入 t_campaign，
-- 由 type 列区分活动形态；旧表如仍存在不受影响，但业务读写一律走 t_campaign。
CREATE TABLE IF NOT EXISTS t_campaign (
  campaign_id   BIGINT PRIMARY KEY AUTO_INCREMENT,
  title         VARCHAR(100) NOT NULL COMMENT '活动名称',
  type          VARCHAR(20) NOT NULL COMMENT 'coupon领券 full_reduce满减 discount折扣 flash_sale限时秒杀 register注册有礼 purchase消费有礼 merchant_incentive商户激励',
  description   VARCHAR(500) COMMENT '活动说明',
  accent        VARCHAR(16) DEFAULT 'blue' COMMENT '卡片主题色(blue/red/gold/green/purple)',
  start_time    DATETIME COMMENT '开始时间',
  end_time      DATETIME COMMENT '结束时间',
  status        TINYINT DEFAULT 0 COMMENT '0已下架 1已上架(生效=上架且在时间窗内)',
  -- 促销/领券规则（full_reduce discount flash_sale coupon）
  threshold       DECIMAL(12,2) DEFAULT 0 COMMENT '门槛金额(满X可用)',
  benefit_amount  DECIMAL(12,2) COMMENT '优惠金额(满减/满减券)；折扣类为优惠封顶金额',
  discount_rate   DECIMAL(4,3) COMMENT '折扣率(0.95=95折)',
  scope           VARCHAR(16) DEFAULT 'all' COMMENT '适用范围 all全场 ind行业',
  ind_id          BIGINT COMMENT '限定行业(scope=ind时)',
  -- 领券配置（coupon）
  per_user_limit  INT DEFAULT 1 COMMENT '每人限领张数',
  total_quota     INT DEFAULT 0 COMMENT '发放总量(0=不限量)',
  claimed_count   INT DEFAULT 0 COMMENT '已领取张数',
  -- 激励配置（register purchase merchant_incentive）
  reward_points     INT DEFAULT 0 COMMENT '用户参与奖励积分',
  mer_target_amount DECIMAL(12,2) COMMENT '商户激励目标销售额',
  mer_reward_points INT DEFAULT 0 COMMENT '商户激励奖励积分',
  joinable       TINYINT DEFAULT 0 COMMENT '商户可报名(1开放报名)',
  -- 商户自建促销（mer_id 非空=商户活动；平台活动 review_status 恒 1）
  mer_id         BIGINT COMMENT '创建商户(NULL=平台活动)',
  prod_ids       VARCHAR(500) COMMENT '指定商品ID逗号分隔(scope=prod时)',
  review_status  TINYINT NOT NULL DEFAULT 1 COMMENT '审核状态 0待审 1通过 2驳回(平台活动恒1)',
  review_note    VARCHAR(200) COMMENT '审核意见(驳回时填写)',
  create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_campaign_type_status (type, status)
) ENGINE=InnoDB COMMENT='活动(统一营销：促销/领券/激励)';

-- 幂等补列：历史库中已存在的 t_campaign 缺少商户促销审核字段时逐一补齐
-- （CREATE TABLE IF NOT EXISTS 不会为已存在的表加列；与下方 t_order 兜底同理）
SET @campcol = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_campaign ADD COLUMN mer_id BIGINT COMMENT ''创建商户(NULL=平台活动)''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_campaign' AND COLUMN_NAME='mer_id');
PREPARE camp_stmt FROM @campcol; EXECUTE camp_stmt; DEALLOCATE PREPARE camp_stmt;
SET @campcol = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_campaign ADD COLUMN prod_ids VARCHAR(500) COMMENT ''指定商品ID逗号分隔(scope=prod时)''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_campaign' AND COLUMN_NAME='prod_ids');
PREPARE camp_stmt FROM @campcol; EXECUTE camp_stmt; DEALLOCATE PREPARE camp_stmt;
SET @campcol = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_campaign ADD COLUMN review_status TINYINT NOT NULL DEFAULT 1 COMMENT ''审核状态 0待审 1通过 2驳回(平台活动恒1)''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_campaign' AND COLUMN_NAME='review_status');
PREPARE camp_stmt FROM @campcol; EXECUTE camp_stmt; DEALLOCATE PREPARE camp_stmt;
SET @campcol = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_campaign ADD COLUMN review_note VARCHAR(200) COMMENT ''审核意见(驳回时填写)''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_campaign' AND COLUMN_NAME='review_note');
PREPARE camp_stmt FROM @campcol; EXECUTE camp_stmt; DEALLOCATE PREPARE camp_stmt;

CREATE TABLE IF NOT EXISTS t_user_coupon (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  campaign_id  BIGINT NOT NULL COMMENT '活动ID',
  user_id      BIGINT NOT NULL COMMENT '持券用户',
  status       TINYINT DEFAULT 0 COMMENT '0未使用 1已使用 2已过期',
  receive_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  used_time    DATETIME COMMENT '核销时间',
  order_no     VARCHAR(40) COMMENT '核销订单号',
  KEY idx_user_coupon_user (user_id, status)
) ENGINE=InnoDB COMMENT='用户券包';

CREATE TABLE IF NOT EXISTS t_campaign_participant (
  participant_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  campaign_id    BIGINT NOT NULL COMMENT '活动ID',
  user_id        BIGINT NOT NULL COMMENT '参与用户',
  bonus_points   INT DEFAULT 0 COMMENT '参与奖励积分',
  join_time      DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_cp_user (user_id)
) ENGINE=InnoDB COMMENT='活动参与记录(激励类)';

CREATE TABLE IF NOT EXISTS t_campaign_merchant (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  campaign_id BIGINT NOT NULL COMMENT '活动ID',
  mer_id      BIGINT NOT NULL COMMENT '商户ID',
  status      TINYINT DEFAULT 1 COMMENT '1已报名',
  rewarded    TINYINT DEFAULT 0 COMMENT '激励奖励已发放',
  join_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_campaign_mer (campaign_id, mer_id)
) ENGINE=InnoDB COMMENT='活动商户报名/激励';

-- ==================== 价格智能监控（pricemonitor：价格留痕/行业基准/预警） ====================
CREATE TABLE IF NOT EXISTS t_price_history (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  prod_id     BIGINT NOT NULL COMMENT '商品ID',
  mer_id      BIGINT COMMENT '商户ID',
  ind_id      BIGINT COMMENT '行业ID(冗余,基准对比用)',
  old_price   DECIMAL(12,2) COMMENT '调整前价格',
  new_price   DECIMAL(12,2) COMMENT '调整后价格',
  change_pct  DECIMAL(8,2) COMMENT '变动幅度%(新-旧)/旧',
  source      VARCHAR(16) DEFAULT 'MERCHANT' COMMENT '来源 MERCHANT/ADMIN/SEED',
  operator_id BIGINT COMMENT '操作人',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_ph_prod (prod_id, create_time),
  KEY idx_ph_ind (ind_id, create_time),
  KEY idx_ph_time (create_time)
) ENGINE=InnoDB COMMENT='商品价格变动留痕';

CREATE TABLE IF NOT EXISTS t_market_benchmark (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  ind_id     BIGINT NOT NULL COMMENT '行业ID',
  day        DATE NOT NULL COMMENT '基准日',
  benchmark  DECIMAL(12,2) NOT NULL COMMENT '行业基准均价(元)',
  change_pct DECIMAL(8,2) COMMENT '较前一日变动%',
  UNIQUE KEY uk_bench_ind_day (ind_id, day)
) ENGINE=InnoDB COMMENT='行业行情基准价(每日)';

CREATE TABLE IF NOT EXISTS t_price_alert (
  id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id            BIGINT NOT NULL COMMENT '订阅用户',
  prod_id            BIGINT NOT NULL COMMENT '商品ID',
  condition_type     VARCHAR(16) NOT NULL COMMENT 'BELOW低于/ABOVE高于/DROP_PCT降幅%/RISE_PCT涨幅%',
  threshold          DECIMAL(12,2) NOT NULL COMMENT '阈值(价格或百分比)',
  status             TINYINT DEFAULT 1 COMMENT '1启用 0停用',
  last_triggered_time DATETIME COMMENT '最近触发时间',
  create_time        DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_alert_user_prod_cond (user_id, prod_id, condition_type),
  KEY idx_alert_prod (prod_id, status)
) ENGINE=InnoDB COMMENT='价格预警订阅';

-- 市场资讯：价格情报台的信息流（政策/供给/需求/价格/行业），按发布时间倒序展示。
CREATE TABLE IF NOT EXISTS t_market_news (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  title        VARCHAR(200) NOT NULL COMMENT '标题',
  summary      VARCHAR(1000) COMMENT '摘要',
  category     VARCHAR(24) NOT NULL DEFAULT 'market' COMMENT 'policy/supply/demand/price/tech/market/international',
  source       VARCHAR(64) COMMENT '来源',
  url          VARCHAR(500) COMMENT '原文链接(可空)',
  tags         VARCHAR(200) COMMENT '标签，逗号分隔',
  importance   TINYINT DEFAULT 1 COMMENT '1普通 2重要 3头条',
  published_at DATETIME NOT NULL COMMENT '发布时间',
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_news_title (title),
  KEY idx_news_time (published_at),
  KEY idx_news_cat (category, published_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='B2B市场资讯';

INSERT IGNORE INTO t_market_news (title, summary, category, source, tags, importance, published_at) VALUES
 ('建材行业：水泥价格连续三周上行，华东地区领涨', '受错峰生产与基建开工回暖影响，P.O42.5 袋装水泥现货均价环比上涨 3.2%，华东区域涨幅居前。', 'price', '中国建材报', '水泥,建材,华东', 3, DATE_SUB(NOW(), INTERVAL 2 HOUR)),
 ('多地发布秋冬错峰生产通知，部分化工品供给收紧', '北方多地启动错峰生产，纯碱、甲醇等基础化工品开工率下降，短期供给偏紧。', 'policy', '行业观察', '错峰生产,化工,供给', 3, DATE_SUB(NOW(), INTERVAL 5 HOUR)),
 ('钢材市场库存回落，螺纹钢现货报价小幅走强', '主要城市建材库存连续两周下降，螺纹钢现货报价上调 20-40 元/吨，贸易商出货节奏加快。', 'supply', '钢铁资讯', '螺纹钢,库存,钢材', 2, DATE_SUB(NOW(), INTERVAL 9 HOUR)),
 ('电子元器件交期缩短，MLCC 现货价格趋稳', '消费电子与工控需求温和复苏，MLCC 交期由 12 周缩短至 8-10 周，现货价格止跌企稳。', 'demand', '电子供应链周刊', 'MLCC,元器件,交期', 2, DATE_SUB(NOW(), INTERVAL 14 HOUR)),
 ('纺织原料：涤纶长丝价格回调，下游补库放缓', '涤纶 POY 报价小幅回落 1.5%，织造企业开工率下降，短期以刚需采购为主。', 'price', '纺织信息网', '涤纶,纺织,原料', 1, DATE_SUB(NOW(), INTERVAL 20 HOUR)),
 ('平台采购指数：本周 B2B 工业品询盘量环比上升 8.6%', '建筑、机械与电子三大行业询盘活跃，工业品采购指数连续第四周回升。', 'market', 'LANLink 数据', '采购指数,询盘,工业品', 2, DATE_SUB(NOW(), INTERVAL 26 HOUR)),
 ('有色金属：铜价高位震荡，线缆企业按需采购', '沪铜主力合约维持高位，线缆企业以销定产，现货升水小幅收窄。', 'price', '有色行情', '铜价,线缆,有色', 2, DATE_SUB(NOW(), INTERVAL 32 HOUR)),
 ('包装材料：纸浆价格回落，瓦楞纸成本压力缓解', '进口纸浆报价下调，瓦楞纸与箱板纸成本端压力减轻，包装企业利润空间小幅修复。', 'supply', '包装产业网', '纸浆,瓦楞纸,包装', 1, DATE_SUB(NOW(), INTERVAL 38 HOUR)),
 ('新能源：光伏组件价格企稳，分布式项目开工提速', '组件价格止跌，分布式光伏备案与开工节奏加快，逆变器与支架需求同步回暖。', 'demand', '新能源观察', '光伏,组件,分布式', 2, DATE_SUB(NOW(), INTERVAL 44 HOUR)),
 ('政策解读：中小企业数字化采购补贴申报指南发布', '多地对中小企业数字化采购平台使用给予补贴，符合条件的企业可在线申报。', 'policy', '政策速递', '补贴,数字化,中小企业', 2, DATE_SUB(NOW(), INTERVAL 52 HOUR)),
 ('机械装备：工程机械销量回暖，零部件订单增加', '挖掘机与装载机销量同比转正，液压件、轴承等零部件订单排产周期延长。', 'demand', '机械工业报', '工程机械,零部件,订单', 1, DATE_SUB(NOW(), INTERVAL 60 HOUR)),
 ('化工：纯碱现货偏紧，部分地区报价上调', '纯碱厂家库存低位，沙河、华东地区轻质纯碱报价上调 30-50 元/吨。', 'price', '化工市场', '纯碱,现货,涨价', 2, DATE_SUB(NOW(), INTERVAL 68 HOUR)),
 ('交通运输部：主要港口吞吐量保持增长', '外贸集装箱与大宗散货吞吐量同比增长，物流时效整体稳定。', 'market', '交通发布', '港口,物流,吞吐量', 1, DATE_SUB(NOW(), INTERVAL 76 HOUR)),
 ('平台公告：价格监控上线，支持行业基准与波动预警', '价格情报台现已支持商品价格走势、行业基准对比、异常波动预警与 AI 因果分析。', 'market', '平台公告', '价格监控,上线,AI', 3, DATE_SUB(NOW(), INTERVAL 1 DAY)),
 ('人民币对美元汇率波动加大，进口设备与原材料采购成本承压', '汇率双向波动幅度扩大，进口设备、芯片与部分化工原料的采购成本抬升，企业加快远期锁汇。', 'international', '外汇观察', '汇率,美元,进口', 2, DATE_SUB(NOW(), INTERVAL 3 HOUR)),
 ('全球航运运价指数回升，欧美线舱位趋紧', '受旺季补库与运力调配影响，欧线与美西线即期运价连续两周回升，出口企业提前订舱。', 'international', '航运日报', '海运,运价,出口', 3, DATE_SUB(NOW(), INTERVAL 7 HOUR)),
 ('欧盟碳边境调节机制进入过渡期，出口企业加速核算碳成本', '钢铁、铝与化工类出口企业开始核算产品碳足迹，部分订单报价已计入碳成本。', 'policy', '外贸政策观察', '碳关税,欧盟,出口', 2, DATE_SUB(NOW(), INTERVAL 12 HOUR)),
 ('地缘局势扰动能源市场，国际油价宽幅震荡', '原油供应预期反复，布伦特原油价格宽幅震荡，化工与物流企业成本端不确定性上升。', 'international', '能源参考', '油价,能源,地缘', 2, DATE_SUB(NOW(), INTERVAL 18 HOUR)),
 ('铜精矿现货偏紧，冶炼端采购成本抬升', '铜精矿现货流通量收紧，冶炼企业原料采购成本抬升，线缆与电机企业报价随之上调。', 'supply', '有色行情', '铜精矿,冶炼,供给', 2, DATE_SUB(NOW(), INTERVAL 24 HOUR)),
 ('国内铝锭社会库存下降，铝加工材报价坚挺', '铝锭社会库存连续三周下降，建筑型材与工业型材报价小幅上调，下游按需补库。', 'supply', '有色金属网', '铝锭,库存,铝材', 2, DATE_SUB(NOW(), INTERVAL 30 HOUR)),
 ('工程塑料原料价格上行，家电与汽车零部件成本抬升', 'PA6、PC 等工程塑料原料报价上调 2%-4%，家电与汽车零部件企业启动成本重算。', 'price', '塑料工业', '工程塑料,原料,成本', 2, DATE_SUB(NOW(), INTERVAL 36 HOUR)),
 ('多部门部署环保督察，部分高耗能行业阶段性限产', '环保督察覆盖化工、建材与有色行业，部分地区高耗能产线执行阶段性限产。', 'policy', '环保观察', '环保,限产,高耗能', 3, DATE_SUB(NOW(), INTERVAL 42 HOUR)),
 ('制造业PMI重回扩张区间，企业补库意愿增强', '制造业采购经理指数回到荣枯线上方，原材料库存与采购量分项同步改善。', 'market', '宏观数据', 'PMI,补库,制造业', 2, DATE_SUB(NOW(), INTERVAL 48 HOUR)),
 ('碳酸锂价格止跌企稳，电池材料采购回暖', '碳酸锂现货报价止跌，正极材料企业询盘增加，电池产业链采购节奏回暖。', 'price', '新能源材料', '碳酸锂,电池,采购', 1, DATE_SUB(NOW(), INTERVAL 54 HOUR)),
 ('出口退税流程优化，外贸企业现金流改善', '出口退税申报环节精简，平均办理时长缩短，外贸企业资金周转效率提升。', 'policy', '政策速递', '出口退税,外贸,政策', 1, DATE_SUB(NOW(), INTERVAL 62 HOUR)),
 ('市场情绪调查：超六成采购经理预计下季度工业品价格温和上行', '平台调研显示，采购经理对下季度工业品价格预期偏乐观，补库与备货意愿增强。', 'market', 'LANLink 调研', '情绪,采购经理,预期', 2, DATE_SUB(NOW(), INTERVAL 70 HOUR));

-- ==================== 营销中台：会员系统 ====================
CREATE TABLE IF NOT EXISTS t_member_level (
  level_id     BIGINT PRIMARY KEY AUTO_INCREMENT,
  level_name   VARCHAR(32) NOT NULL COMMENT '等级名称',
  min_growth   INT NOT NULL DEFAULT 0 COMMENT '成长值门槛',
  discount_rate DECIMAL(4,3) DEFAULT 1.000 COMMENT '等级折扣率(0.95=95折)',
  description  VARCHAR(200) COMMENT '等级权益说明',
  sort         INT DEFAULT 0
) ENGINE=InnoDB COMMENT='会员等级';

CREATE TABLE IF NOT EXISTS t_member_card (
  card_id    BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id    BIGINT NOT NULL UNIQUE COMMENT '用户ID',
  level_id   BIGINT COMMENT '当前等级',
  growth     INT DEFAULT 0 COMMENT '成长值',
  points     INT DEFAULT 0 COMMENT '积分',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='会员卡';

CREATE TABLE IF NOT EXISTS t_member_point_log (
  log_id      BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL,
  change_type VARCHAR(32) COMMENT '积分变动类型 earn_consume 消费获得 adjust 调整',
  change_val  INT COMMENT '变动数值(正负)',
  ref_order_no VARCHAR(40) COMMENT '关联订单号',
  remark      VARCHAR(200),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  -- 激励幂等唯一键：同用户 + 同变动类型 + 同业务引用号只允许一条流水，
  -- 并发重复发奖由数据库唯一键兜底（ref_order_no 为 NULL 的调整流水不受限）
  UNIQUE KEY uk_point_user_type_ref (user_id, change_type, ref_order_no)
) ENGINE=InnoDB COMMENT='会员积分流水';

CREATE TABLE IF NOT EXISTS t_checkin (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id      BIGINT NOT NULL COMMENT '签到用户',
  checkin_date DATE NOT NULL COMMENT '签到日期',
  points       INT NOT NULL COMMENT '当日获得积分',
  streak       INT NOT NULL DEFAULT 1 COMMENT '连续签到天数',
  create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_checkin_user_date (user_id, checkin_date)
) ENGINE=InnoDB COMMENT='每日签到';

-- 任务激励流水（F3 任务体系；任务非促销，不进 t_campaign 七类，奖励经 awardPoints refNo 幂等落账）
CREATE TABLE IF NOT EXISTS t_task_record (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL COMMENT '用户',
  task_key    VARCHAR(32) NOT NULL COMMENT '任务标识(first_order/share_product)',
  ref_no      VARCHAR(64) NOT NULL COMMENT '幂等引用(orderNo/shareId:yyyyMMdd)',
  points      INT NOT NULL DEFAULT 0,
  growth      INT NOT NULL DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_task_user_key_ref (user_id, task_key, ref_no)
) ENGINE=InnoDB COMMENT='任务激励流水';

-- 幂等种子：会员等级（七档；重复执行时按主键更新，确保政策调整能生效）
-- 普通会员无门槛（注册即享）；青铜会员 10 万成长值，作为"第一个需要订阅解锁"的档位；
-- 其后 50万 / 100万 / 500万 / 1000万 / 2000万 依次顺延。
-- 升级需「有效订阅 + 成长值达标」双条件；折扣按 0 / 0 / 1% / 3% / 5% / 7% / 10% 递增。
-- 青铜刻意不给价格折扣：它的权益是订阅带来的积分翻倍与身份标识，价格让利留给白银以上。
--
-- 一次性迁移（幂等）：老库的 2..6 档要整体上移一位，把 2 号让给青铜。
-- 判据是"库里还没有青铜"；迁移过或全新库（等级表为空、会员卡也为空）都会安全跳过/空跑。
SET @need_bronze_shift = (SELECT IF(COUNT(*) = 0,
    'UPDATE t_member_card SET level_id = level_id + 1 WHERE level_id BETWEEN 2 AND 6',
    'SELECT 1')
  FROM t_member_level WHERE level_name = '青铜会员');
PREPARE bronze_stmt FROM @need_bronze_shift; EXECUTE bronze_stmt; DEALLOCATE PREPARE bronze_stmt;

INSERT INTO t_member_level (level_id, level_name, min_growth, discount_rate, description, sort) VALUES
 (1,'普通会员', 0,       1.000, '注册即享，无门槛',      1),
 (2,'青铜会员', 100000,  1.000, '订阅解锁 · 积分翻倍',  2),
 (3,'白银会员', 500000,  0.990, '99折 起',              3),
 (4,'黄金会员', 1000000, 0.970, '97折 特权',            4),
 (5,'铂金会员', 5000000, 0.950, '95折 尊享',            5),
 (6,'钻石会员', 10000000,0.930, '93折 尊享',            6),
 (7,'黑钻会员', 20000000,0.900, '9折 至臻',             7)
ON DUPLICATE KEY UPDATE
 level_name=VALUES(level_name), min_growth=VALUES(min_growth),
 discount_rate=VALUES(discount_rate), description=VALUES(description), sort=VALUES(sort);

-- 买家商品评价（一单一品一评）与评分聚合（独立表，避免与商品扣库存热点争锁）
CREATE TABLE IF NOT EXISTS t_product_review (
  review_id      BIGINT PRIMARY KEY AUTO_INCREMENT,
  item_id        BIGINT NOT NULL COMMENT '订单行ID（一单一品一评）',
  order_no       VARCHAR(40) NOT NULL COMMENT '订单号',
  user_id        BIGINT NOT NULL COMMENT '评价人',
  mer_id         BIGINT COMMENT '商户ID',
  prod_id        BIGINT NOT NULL COMMENT '商品ID',
  rating         TINYINT NOT NULL COMMENT '1-5 星',
  content        VARCHAR(500) COMMENT '评价内容',
  anonymous      TINYINT DEFAULT 0 COMMENT '1=匿名展示',
  status         TINYINT DEFAULT 1 COMMENT '0待审 1通过 2隐藏',
  merchant_reply VARCHAR(500) COMMENT '商家回复',
  reply_time     DATETIME COMMENT '回复时间',
  create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_review_item (item_id),
  KEY idx_review_prod (prod_id, status, create_time),
  KEY idx_review_user (user_id)
) ENGINE=InnoDB COMMENT='买家商品评价';

CREATE TABLE IF NOT EXISTS t_product_rating_stat (
  prod_id      BIGINT PRIMARY KEY,
  review_count INT NOT NULL DEFAULT 0,
  rating_sum   BIGINT NOT NULL DEFAULT 0,
  good_count   INT NOT NULL DEFAULT 0 COMMENT '4-5 星数量',
  update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='商品评分聚合';

-- ==================== 第 3 步 基础字典数据 ====================
-- 显式主键加 INSERT IGNORE: 重复执行时主键冲突被忽略, 既不报错也不会插出重复行,
-- 同时把 ID 钉死, 与后端 DataInitializer 里按 cat_id 与 ind_id 的硬编码映射保持一致。
INSERT IGNORE INTO t_role (role_id, role_name, role_code) VALUES
 (1,'采购方','buyer'), (2,'商户','merchant'), (3,'平台运营','admin');

INSERT IGNORE INTO t_industry (ind_id, name, code, config_json, sort) VALUES
 (1,'建筑行业','construction','{"needQual":["建筑业企业资质证书","安全生产许可证"]}',1),
 (2,'纺织行业','textile','{"needQual":["纺织品检测报告"]}',2),
 (3,'石化行业','petrochemical','{"needQual":["危险化学品经营许可证"]}',3),
 (4,'电子行业','electronics','{"needQual":["3C认证","RoHS报告"]}',4);

INSERT IGNORE INTO t_category (cat_id, parent_id, ind_id, name, level, sort) VALUES
 (1,0,1,'建筑钢材',1,1),(2,0,1,'水泥混凝土',1,2),(3,0,1,'管材管件',1,3),
 (4,0,2,'面料坯布',1,1),(5,0,2,'纱线',1,2),(6,0,2,'辅料',1,3),
 (7,0,3,'基础化工原料',1,1),(8,0,3,'润滑油品',1,2),
 (9,0,4,'电子元器件',1,1),(10,0,4,'连接器线束',1,2),(11,0,4,'显示面板',1,3);

-- ==================== 第 4 步 交由后端完成 ====================
-- 本脚本到此结束。用户/企业/商户/商品等示例数据由 DataInitializer 在启动时以 BCrypt 写入,
-- 且只在 t_user 为空时执行一次, 所以重启后端不会重复插入, 也不会覆盖你改过的数据。
-- 执行后的自检 SQL 与预期结果写在 README 第二节 初始化数据库 小节。

-- ==================== 扩展平台采购行业目录（动态分级） ====================
-- 保留历史 1-4 行业及 1-11 分类 ID，追加 5-16 行业；前端行业菜单完全由接口数据驱动。
INSERT IGNORE INTO t_industry (ind_id, name, code, config_json, sort) VALUES
 (5,'钢铁冶金','metallurgy','{}',5),
 (6,'机械装备','machinery','{}',6),
 (7,'汽车及零部件','automotive','{}',7),
 (8,'电力能源','energy','{}',8),
 (9,'食品饮料','food-beverage','{}',9),
 (10,'医药医疗','healthcare','{}',10),
 (11,'包装印刷','packaging-printing','{}',11),
 (12,'物流仓储','logistics-warehousing','{}',12),
 (13,'新能源','new-energy','{}',13),
 (14,'环保水务','environmental-water','{}',14),
 (15,'农业及农产品','agriculture','{}',15),
 (16,'家居家装','home-building','{}',16);

INSERT IGNORE INTO t_category (cat_id, parent_id, ind_id, name, level, sort) VALUES
 (12,0,5,'钢坯生铁',1,1),
 (13,12,5,'连铸坯',2,1),
 (14,12,5,'生铁',2,2),
 (15,12,5,'合金钢坯',2,3),
 (16,0,5,'有色金属',1,2),
 (17,16,5,'铝材',2,1),
 (18,16,5,'铜材',2,2),
 (19,16,5,'锌材',2,3),
 (20,0,5,'金属制品',1,3),
 (21,20,5,'钢丝制品',2,1),
 (22,20,5,'金属板材',2,2),
 (23,20,5,'金属加工件',2,3),
 (24,0,6,'机床工具',1,1),
 (25,24,6,'数控机床',2,1),
 (26,24,6,'加工中心',2,2),
 (27,24,6,'刀具量具',2,3),
 (28,0,6,'工程机械',1,2),
 (29,28,6,'挖掘设备',2,1),
 (30,28,6,'起重设备',2,2),
 (31,28,6,'装载设备',2,3),
 (32,0,6,'工业零部件',1,3),
 (33,32,6,'轴承',2,1),
 (34,32,6,'齿轮',2,2),
 (35,32,6,'泵阀',2,3),
 (36,0,7,'汽车零部件',1,1),
 (37,36,7,'发动机零件',2,1),
 (38,36,7,'底盘零件',2,2),
 (39,36,7,'车身零件',2,3),
 (40,0,7,'新能源汽车部件',1,2),
 (41,40,7,'驱动电机',2,1),
 (42,40,7,'电控系统',2,2),
 (43,40,7,'热管理',2,3),
 (44,0,7,'维修用品',1,3),
 (45,44,7,'轮胎',2,1),
 (46,44,7,'润滑用品',2,2),
 (47,44,7,'汽车工具',2,3),
 (48,0,8,'输配电设备',1,1),
 (49,48,8,'变压器',2,1),
 (50,48,8,'高低压开关柜',2,2),
 (51,48,8,'配电箱',2,3),
 (52,0,8,'电线电缆',1,2),
 (53,52,8,'电力电缆',2,1),
 (54,52,8,'控制电缆',2,2),
 (55,52,8,'特种电缆',2,3),
 (56,0,8,'能源设备',1,3),
 (57,56,8,'发电设备',2,1),
 (58,56,8,'储能设备',2,2),
 (59,56,8,'充换电设备',2,3),
 (60,0,9,'粮油食品',1,1),
 (61,60,9,'大米杂粮',2,1),
 (62,60,9,'食用油',2,2),
 (63,60,9,'淀粉糖类',2,3),
 (64,0,9,'食品原料',1,2),
 (65,64,9,'调味料',2,1),
 (66,64,9,'食品添加剂',2,2),
 (67,64,9,'烘焙原料',2,3),
 (68,0,9,'饮料原料',1,3),
 (69,68,9,'茶饮原料',2,1),
 (70,68,9,'果汁原料',2,2),
 (71,68,9,'功能饮料原料',2,3),
 (72,0,10,'原料药',1,1),
 (73,72,10,'合成原料药',2,1),
 (74,72,10,'中药材',2,2),
 (75,72,10,'药用辅料',2,3),
 (76,0,10,'医疗器械',1,2),
 (77,76,10,'诊断设备',2,1),
 (78,76,10,'监护设备',2,2),
 (79,76,10,'康复设备',2,3),
 (80,0,10,'医用耗材',1,3),
 (81,80,10,'注射耗材',2,1),
 (82,80,10,'手术耗材',2,2),
 (83,80,10,'护理耗材',2,3),
 (84,0,11,'纸包装',1,1),
 (85,84,11,'瓦楞纸箱',2,1),
 (86,84,11,'彩盒',2,2),
 (87,84,11,'纸袋',2,3),
 (88,0,11,'塑料包装',1,2),
 (89,88,11,'PE包装膜',2,1),
 (90,88,11,'塑料瓶',2,2),
 (91,88,11,'编织袋',2,3),
 (92,0,11,'印刷材料',1,3),
 (93,92,11,'油墨',2,1),
 (94,92,11,'印版',2,2),
 (95,92,11,'标签材料',2,3),
 (96,0,12,'仓储设备',1,1),
 (97,96,12,'货架系统',2,1),
 (98,96,12,'输送设备',2,2),
 (99,96,12,'仓储容器',2,3),
 (100,0,12,'包装耗材',1,2),
 (101,100,12,'打包带',2,1),
 (102,100,12,'缠绕膜',2,2),
 (103,100,12,'缓冲材料',2,3),
 (104,0,12,'物流服务',1,3),
 (105,104,12,'干线运输',2,1),
 (106,104,12,'仓配一体',2,2),
 (107,104,12,'国际物流',2,3),
 (108,0,13,'锂电材料',1,1),
 (109,108,13,'正极材料',2,1),
 (110,108,13,'负极材料',2,2),
 (111,108,13,'电解液',2,3),
 (112,0,13,'光伏材料',1,2),
 (113,112,13,'硅料硅片',2,1),
 (114,112,13,'光伏辅材',2,2),
 (115,112,13,'组件材料',2,3),
 (116,0,13,'储能设备',1,3),
 (117,116,13,'储能电池',2,1),
 (118,116,13,'PCS设备',2,2),
 (119,116,13,'能量管理系统',2,3),
 (120,0,14,'水处理',1,1),
 (121,120,14,'膜处理设备',2,1),
 (122,120,14,'加药设备',2,2),
 (123,120,14,'过滤材料',2,3),
 (124,0,14,'环保设备',1,2),
 (125,124,14,'废气处理',2,1),
 (126,124,14,'固废处理',2,2),
 (127,124,14,'噪声治理',2,3),
 (128,0,14,'环保耗材',1,3),
 (129,128,14,'滤芯滤材',2,1),
 (130,128,14,'吸附材料',2,2),
 (131,128,14,'环保药剂',2,3),
 (132,0,15,'农资',1,1),
 (133,132,15,'化肥',2,1),
 (134,132,15,'农药',2,2),
 (135,132,15,'种子',2,3),
 (136,0,15,'农产品',1,2),
 (137,136,15,'粮食作物',2,1),
 (138,136,15,'果蔬',2,2),
 (139,136,15,'畜禽产品',2,3),
 (140,0,15,'冷链农业',1,3),
 (141,140,15,'预制食材',2,1),
 (142,140,15,'冷藏包装',2,2),
 (143,140,15,'冷链物流',2,3),
 (144,0,16,'家装材料',1,1),
 (145,144,16,'板材',2,1),
 (146,144,16,'涂料',2,2),
 (147,144,16,'瓷砖地材',2,3),
 (148,0,16,'家具家居',1,2),
 (149,148,16,'办公家具',2,1),
 (150,148,16,'酒店家具',2,2),
 (151,148,16,'家居用品',2,3),
 (152,0,16,'五金工具',1,3),
 (153,152,16,'门窗五金',2,1),
 (154,152,16,'手动工具',2,2),
 (155,152,16,'电动工具',2,3),
 (156,1,1,'螺纹钢',2,1),
 (157,1,1,'热轧螺纹钢',2,2),
 (158,2,1,'盘圆线材',2,1),
 (159,2,1,'高线',2,2),
 (160,3,1,'工字钢',2,1),
 (161,3,1,'角钢',2,2),
 (162,3,1,'槽钢',2,3),
 (163,4,2,'纯棉布',2,1),
 (164,4,2,'混纺棉布',2,2),
 (165,5,2,'涤纶面料',2,1),
 (166,5,2,'锦纶面料',2,2),
 (167,6,2,'防水面料',2,1),
 (168,6,2,'阻燃面料',2,2),
 (169,7,3,'碳酸盐系列',2,1),
 (170,7,3,'碱类原料',2,2),
 (171,7,3,'有机基础原料',2,3),
 (172,8,3,'液压油',2,1),
 (173,8,3,'齿轮油',2,2),
 (174,8,3,'金属加工液',2,3),
 (175,9,4,'贴片电阻',2,1),
 (176,9,4,'铝电解电容',2,2),
 (177,9,4,'传感器',2,3),
 (178,10,4,'板端连接器',2,1),
 (179,10,4,'线对板连接器',2,2),
 (180,10,4,'线束组件',2,3),
 (181,11,4,'LCD',2,1),
 (182,11,4,'OLED',2,2),
 (183,11,4,'显示模组',2,3);

-- ==================== 支付流水日志表 ====================
CREATE TABLE IF NOT EXISTS t_payment_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no    VARCHAR(40) COMMENT '订单号',
  channel     VARCHAR(16) COMMENT 'wechat/alipay/mock/wallet',
  action      VARCHAR(16) COMMENT 'CREATE/NOTIFY/QUERY/REFUND/VERIFY_FAIL/ERROR',
  direction   VARCHAR(8)  COMMENT 'OUT 请求渠道 / IN 渠道回调',
  success     TINYINT DEFAULT 0 COMMENT '0失败 1成功',
  detail      VARCHAR(2000) COMMENT '脱敏后的关键信息',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_order (order_no),
  KEY idx_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='支付流水日志';

-- 幂等补列：历史库中已存在的 t_order 缺少支付渠道/退款字段时逐一补齐
-- （CREATE TABLE IF NOT EXISTS 不会为已存在的表加列，与上方 total_amount 兜底同理）
SET @paycol = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN pay_channel VARCHAR(16) COMMENT ''支付渠道 wechat/alipay/mock/wallet''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='pay_channel');
PREPARE pay_stmt FROM @paycol; EXECUTE pay_stmt; DEALLOCATE PREPARE pay_stmt;
SET @paycol = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN transaction_id VARCHAR(64) COMMENT ''渠道交易号''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='transaction_id');
PREPARE pay_stmt FROM @paycol; EXECUTE pay_stmt; DEALLOCATE PREPARE pay_stmt;
SET @paycol = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN prepay_id VARCHAR(64) COMMENT ''预下单号''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='prepay_id');
PREPARE pay_stmt FROM @paycol; EXECUTE pay_stmt; DEALLOCATE PREPARE pay_stmt;
SET @paycol = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN refund_status VARCHAR(32) DEFAULT ''none'' COMMENT ''退款状态 none/applied/merchant_approved/rejected/processing/partial/success''', 'ALTER TABLE t_order MODIFY COLUMN refund_status VARCHAR(32) DEFAULT ''none'' COMMENT ''退款状态''')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='refund_status');
PREPARE pay_stmt FROM @paycol; EXECUTE pay_stmt; DEALLOCATE PREPARE pay_stmt;
SET @paycol = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN refund_id VARCHAR(64) COMMENT ''渠道退款单号''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='refund_id');
PREPARE pay_stmt FROM @paycol; EXECUTE pay_stmt; DEALLOCATE PREPARE pay_stmt;
SET @paycol = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN refund_amount DECIMAL(14,2) COMMENT ''已退金额''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='refund_amount');
PREPARE pay_stmt FROM @paycol; EXECUTE pay_stmt; DEALLOCATE PREPARE pay_stmt;
SET @paycol = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN refund_time DATETIME COMMENT ''退款完成时间''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='refund_time');
PREPARE pay_stmt FROM @paycol; EXECUTE pay_stmt; DEALLOCATE PREPARE pay_stmt;
-- 退款申请闭环：用户申请原因/申请时间 + 平台驳回原因（退款状态扩展 applied/rejected/partial）
SET @paycol = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN refund_reason VARCHAR(200) COMMENT ''退款申请原因(用户填写)''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='refund_reason');
PREPARE pay_stmt FROM @paycol; EXECUTE pay_stmt; DEALLOCATE PREPARE pay_stmt;
SET @paycol = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN refund_apply_time DATETIME COMMENT ''退款申请时间''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='refund_apply_time');
PREPARE pay_stmt FROM @paycol; EXECUTE pay_stmt; DEALLOCATE PREPARE pay_stmt;
SET @paycol = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN refund_reject_reason VARCHAR(200) COMMENT ''退款申请驳回原因(平台填写)''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='refund_reject_reason');
PREPARE pay_stmt FROM @paycol; EXECUTE pay_stmt; DEALLOCATE PREPARE pay_stmt;

-- ==================== 钱包（支付系统） ====================
CREATE TABLE IF NOT EXISTS t_wallet (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL COMMENT '所属用户',
  balance     DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '余额(元)',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_wallet_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户钱包';

CREATE TABLE IF NOT EXISTS t_wallet_log (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id       BIGINT NOT NULL COMMENT '所属用户',
  change_type   VARCHAR(16) NOT NULL COMMENT 'recharge充值/pay消费/refund退款入账',
  amount        DECIMAL(14,2) NOT NULL COMMENT '变动金额(带符号, 消费为负)',
  balance_after DECIMAL(14,2) NOT NULL COMMENT '变动后余额',
  ref_order_no  VARCHAR(40) COMMENT '关联订单号',
  remark        VARCHAR(255) COMMENT '备注',
  create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_wallet_user (user_id),
  KEY idx_wallet_order (ref_order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='钱包流水';


-- ==================== 商户流量中心：行为事件采集 ====================
CREATE TABLE IF NOT EXISTS t_traffic_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  merchant_id BIGINT NOT NULL COMMENT '商户ID',
  product_id BIGINT NULL COMMENT '商品ID',
  user_id BIGINT NULL COMMENT '用户ID，可为空',
  session_id VARCHAR(128) NOT NULL COMMENT '匿名会话标识',
  event_type VARCHAR(32) NOT NULL COMMENT 'VISIT/VIEW_PRODUCT/CLICK_PRODUCT/FAVORITE/ADD_CART/CHECKOUT/PAY',
  source_type VARCHAR(32) DEFAULT 'direct' COMMENT 'direct/search/category/activity/external/referral',
  source_detail VARCHAR(255) COMMENT '来源细分，如搜索词/活动ID',
  page_url VARCHAR(500) COMMENT '发生页面',
  device_type VARCHAR(16) DEFAULT 'unknown' COMMENT 'pc/mobile/tablet/unknown',
  event_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_traffic_merchant_time (merchant_id,event_time),
  KEY idx_traffic_product_time (product_id,event_time),
  KEY idx_traffic_session_time (session_id,event_time),
  KEY idx_traffic_source_time (merchant_id,source_type,event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商户流量行为事件';

-- Shared cross-instance authentication rate-limit counters. Values contain hashed keys only.
CREATE TABLE IF NOT EXISTS t_auth_rate_limit_counter (
  rate_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_count INT UNSIGNED NOT NULL DEFAULT 0,
  expires_at_ms BIGINT NOT NULL,
  PRIMARY KEY (rate_key),
  KEY idx_auth_rate_limit_expires (expires_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='认证接口跨实例限流计数';

-- ==================== 企业账期管理 ====================
CREATE TABLE IF NOT EXISTS t_credit_account (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL UNIQUE COMMENT '采购方用户ID',
  ent_id BIGINT COMMENT '企业ID',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待审 ACTIVE生效 REJECTED驳回',
  requested_limit DECIMAL(14,2) NOT NULL DEFAULT 0,
  credit_limit DECIMAL(14,2) NOT NULL DEFAULT 0,
  available_limit DECIMAL(14,2) NOT NULL DEFAULT 0,
  used_limit DECIMAL(14,2) NOT NULL DEFAULT 0,
  term_days INT NOT NULL DEFAULT 30,
  purpose VARCHAR(255),
  risk_level VARCHAR(32) COMMENT '风险等级',
  review_remark VARCHAR(255),
  apply_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  approved_time DATETIME,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_credit_account_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业账期授信账户';

CREATE TABLE IF NOT EXISTS t_credit_bill (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  account_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  order_no VARCHAR(40) NOT NULL,
  amount DECIMAL(14,2) NOT NULL,
  paid_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  outstanding_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  due_date DATE NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'UNPAID' COMMENT 'UNPAID未结清 OVERDUE逾期 PAID已结清 CANCELLED已取消',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  settled_time DATETIME,
  UNIQUE KEY uk_credit_order (order_no),
  KEY idx_credit_bill_user_due (user_id,due_date,status),
  KEY idx_credit_bill_account (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业账期账单';

CREATE TABLE IF NOT EXISTS t_credit_repayment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  bill_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  amount DECIMAL(14,2) NOT NULL,
  method VARCHAR(16) DEFAULT 'wallet' COMMENT 'wallet企业钱包 transfer对公转账',
  reference_no VARCHAR(64),
  remark VARCHAR(255),
  status VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED' COMMENT 'PENDING待确认 CONFIRMED已入账 REJECTED已驳回',
  confirmed_time DATETIME,
  confirm_remark VARCHAR(255),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_credit_repayment_bill (bill_id),
  KEY idx_credit_repayment_bill_status (bill_id,status),
  KEY idx_credit_repayment_status_time (status,create_time),
  KEY idx_credit_repayment_user (user_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业账期还款记录';

-- 调额申请：已生效授信账户可提交调额/调账期申请，审核期间原额度继续可用
CREATE TABLE IF NOT EXISTS t_credit_adjust_request (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  account_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  requested_limit DECIMAL(14,2) NOT NULL,
  term_days INT NOT NULL DEFAULT 30,
  purpose VARCHAR(255),
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待审 APPROVED通过 REJECTED驳回 WITHDRAWN已撤回',
  approved_limit DECIMAL(14,2),
  approved_term_days INT,
  review_remark VARCHAR(255),
  apply_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  reviewed_time DATETIME,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_credit_adjust_account_status (account_id,status),
  KEY idx_credit_adjust_user_time (user_id,apply_time),
  KEY idx_credit_adjust_status_time (status,apply_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业账期调额申请';

-- 存量库幂等补列：对公转账待确认核销
SET @cr1 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_credit_repayment ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT ''CONFIRMED'' COMMENT ''PENDING待确认 CONFIRMED已入账 REJECTED已驳回'' AFTER remark', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_credit_repayment' AND COLUMN_NAME='status');
PREPARE cr_stmt1 FROM @cr1; EXECUTE cr_stmt1; DEALLOCATE PREPARE cr_stmt1;

SET @cr2 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_credit_repayment ADD COLUMN confirmed_time DATETIME NULL AFTER status', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_credit_repayment' AND COLUMN_NAME='confirmed_time');
PREPARE cr_stmt2 FROM @cr2; EXECUTE cr_stmt2; DEALLOCATE PREPARE cr_stmt2;

SET @cr3 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_credit_repayment ADD COLUMN confirm_remark VARCHAR(255) NULL AFTER confirmed_time', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_credit_repayment' AND COLUMN_NAME='confirm_remark');
PREPARE cr_stmt3 FROM @cr3; EXECUTE cr_stmt3; DEALLOCATE PREPARE cr_stmt3;

SET @cr4 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_credit_repayment ADD INDEX idx_credit_repayment_bill_status (bill_id,status)', 'SELECT 1')
  FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_credit_repayment' AND INDEX_NAME='idx_credit_repayment_bill_status');
PREPARE cr_stmt4 FROM @cr4; EXECUTE cr_stmt4; DEALLOCATE PREPARE cr_stmt4;

SET @cr5 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_credit_repayment ADD INDEX idx_credit_repayment_status_time (status,create_time)', 'SELECT 1')
  FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_credit_repayment' AND INDEX_NAME='idx_credit_repayment_status_time');
PREPARE cr_stmt5 FROM @cr5; EXECUTE cr_stmt5; DEALLOCATE PREPARE cr_stmt5;

-- ============================================================
-- 积分抵扣明细：t_order 补积分抵扣字段（幂等补列，历史库可重复执行）
--   point_deduct_amount 抵扣金额（元，整元；1000 积分 = 1 元）
--   point_deduct_points 消耗积分数
-- ============================================================
SET @pt1 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN point_deduct_amount DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT ''积分抵扣金额(元,整元)''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='point_deduct_amount');
PREPARE pt_stmt1 FROM @pt1; EXECUTE pt_stmt1; DEALLOCATE PREPARE pt_stmt1;

SET @pt2 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN point_deduct_points INT NOT NULL DEFAULT 0 COMMENT ''积分抵扣消耗积分数(1000积分=1元)''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='point_deduct_points');
PREPARE pt_stmt2 FROM @pt2; EXECUTE pt_stmt2; DEALLOCATE PREPARE pt_stmt2;

-- ============================================================
-- 活动优惠明细：t_order 补促销/优惠券字段（幂等补列，历史库可重复执行）
--   promo_discount  促销/会员最优优惠金额（结算自动应用）
--   coupon_discount 优惠券优惠金额（用户持券核销）
--   coupon_id       使用的用户券ID（t_user_coupon.id，取消/退款回退依据）
--   promo_title     生效促销标题快照（会员折扣生效或无促销时为 NULL，纯展示）
--   promo_source    生效来源快照 promotion 促销 / member 会员折扣
-- ============================================================
SET @cp1 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN promo_discount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT ''促销/会员最优优惠金额''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='promo_discount');
PREPARE cp_stmt1 FROM @cp1; EXECUTE cp_stmt1; DEALLOCATE PREPARE cp_stmt1;

SET @cp2 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN coupon_discount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT ''优惠券优惠金额''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='coupon_discount');
PREPARE cp_stmt2 FROM @cp2; EXECUTE cp_stmt2; DEALLOCATE PREPARE cp_stmt2;

SET @cp3 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN coupon_id BIGINT NULL COMMENT ''使用的用户券ID(t_user_coupon.id)''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='coupon_id');
PREPARE cp_stmt3 FROM @cp3; EXECUTE cp_stmt3; DEALLOCATE PREPARE cp_stmt3;

SET @cp4 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN promo_title VARCHAR(120) NULL COMMENT ''生效促销标题快照(会员折扣生效或无促销时为NULL)''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='promo_title');
PREPARE cp_stmt4 FROM @cp4; EXECUTE cp_stmt4; DEALLOCATE PREPARE cp_stmt4;

SET @cp5 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN promo_source VARCHAR(16) NULL COMMENT ''生效来源 promotion促销/member会员折扣''', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='promo_source');
PREPARE cp_stmt5 FROM @cp5; EXECUTE cp_stmt5; DEALLOCATE PREPARE cp_stmt5;

-- ============================================================
-- 物流模块（logistics）：服务商 / 运费模板 / 运单 / 轨迹 / 告警 / 售后 / 结算
-- 设计文档：docs/物流板块业务设计文档.md（幂等建表，可重复执行）
-- ============================================================

-- 物流服务商
CREATE TABLE IF NOT EXISTS t_logistics_provider (
  provider_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider_name VARCHAR(64) NOT NULL COMMENT '服务商名称',
  provider_code VARCHAR(32) NOT NULL COMMENT '编码 SF/DB/JD/ZT/SELF，接口对接标识',
  contact_name VARCHAR(32) COMMENT '对接人',
  contact_phone VARCHAR(20) COMMENT '联系电话',
  api_type VARCHAR(16) DEFAULT 'mock' COMMENT 'mock=沙箱模拟 api=真实接口 manual=手工录入',
  api_endpoint VARCHAR(255) COMMENT '轨迹查询接口地址',
  api_key VARCHAR(255) COMMENT '接口密钥（加密存储）',
  capability_json VARCHAR(500) COMMENT '能力标记 JSON',
  status TINYINT DEFAULT 1 COMMENT '0停用 1启用',
  settle_cycle VARCHAR(16) DEFAULT 'monthly' COMMENT '结算周期 monthly/weekly/realtime',
  sort INT DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  UNIQUE KEY uk_provider_code (provider_code),
  KEY idx_provider_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='物流服务商';

-- 运费模板
CREATE TABLE IF NOT EXISTS t_logistics_template (
  template_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_name VARCHAR(64) NOT NULL COMMENT '模板名称',
  provider_id BIGINT NOT NULL COMMENT '归属服务商',
  pricing_type VARCHAR(16) DEFAULT 'weight' COMMENT 'weight按重量 volume按体积 fixed固定 piece按件',
  first_weight DECIMAL(10,2) DEFAULT 1 COMMENT '首重(kg/件)',
  first_price DECIMAL(10,2) DEFAULT 0 COMMENT '首重费用(元)',
  continued_weight DECIMAL(10,2) DEFAULT 1 COMMENT '续重单位',
  continued_price DECIMAL(10,2) DEFAULT 0 COMMENT '续重费用(元/单位)',
  volume_factor DECIMAL(10,2) DEFAULT 6000 COMMENT '体积重系数(长*宽*高cm/系数)',
  free_threshold DECIMAL(12,2) DEFAULT 0 COMMENT '满额包邮门槛(0不启用)',
  region_rule_json TEXT COMMENT '区域加价规则 JSON',
  remote_surcharge DECIMAL(10,2) DEFAULT 0 COMMENT '偏远地区附加费',
  status TINYINT DEFAULT 1 COMMENT '0停用 1启用',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  KEY idx_template_provider (provider_id),
  KEY idx_template_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='运费模板';

-- 运单
CREATE TABLE IF NOT EXISTS t_logistics_waybill (
  waybill_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  waybill_no VARCHAR(32) NOT NULL COMMENT '平台运单号 LL+日期+序列',
  order_no VARCHAR(32) NOT NULL COMMENT '关联订单号',
  order_item_ids VARCHAR(255) COMMENT '分包裹订单明细ID(逗号分隔)',
  provider_id BIGINT NOT NULL COMMENT '物流服务商',
  template_id BIGINT COMMENT '运费模板',
  logistics_no VARCHAR(64) COMMENT '第三方物流单号(快递单号)',
  freight DECIMAL(10,2) DEFAULT 0 COMMENT '实际运费(元)',
  freight_calc_json VARCHAR(500) COMMENT '计费快照明细(留痕可审计)',
  weight DECIMAL(10,3) COMMENT '计费重量(kg)',
  volume DECIMAL(10,3) COMMENT '体积(m³)',
  sender_name VARCHAR(32) COMMENT '寄件人',
  sender_phone VARCHAR(20) COMMENT '寄件电话',
  sender_address VARCHAR(255) COMMENT '寄件地址',
  receiver_name VARCHAR(32) COMMENT '收件人',
  receiver_phone VARCHAR(20) COMMENT '收件电话',
  receiver_province VARCHAR(32) COMMENT '收件省份',
  receiver_city VARCHAR(32) COMMENT '收件城市',
  receiver_district VARCHAR(32) COMMENT '收件区县',
  receiver_address VARCHAR(255) COMMENT '收件详细地址',
  status VARCHAR(24) DEFAULT 'pending_ship' COMMENT 'pending_ship/picked_up/in_transit/out_for_delivery/delivered/exception/returned/lost/cancelled',
  exception_flag TINYINT DEFAULT 0 COMMENT '0正常 1有活跃异常告警',
  -- 热增坐标列（LogisticsSplitSchemaInitializer 同源定义，修复 mode:never 手工建库路径漏列）
  sender_lng DECIMAL(10,6) COMMENT '发货地经度',
  sender_lat DECIMAL(10,6) COMMENT '发货地纬度',
  receiver_lng DECIMAL(10,6) COMMENT '目的地经度',
  receiver_lat DECIMAL(10,6) COMMENT '目的地纬度',
  current_lng DECIMAL(10,6) COMMENT '当前在途经度',
  current_lat DECIMAL(10,6) COMMENT '当前在途纬度',
  current_location VARCHAR(128) COMMENT '当前在途位置',
  ship_time DATETIME COMMENT '发货时间',
  delivered_time DATETIME COMMENT '签收时间',
  created_by BIGINT COMMENT '操作人',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  UNIQUE KEY uk_waybill_no (waybill_no),
  UNIQUE KEY uk_logistics_no (logistics_no),
  KEY idx_waybill_order (order_no),
  KEY idx_waybill_status (status),
  KEY idx_waybill_provider (provider_id),
  KEY idx_waybill_provider_ship (provider_id, ship_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='物流运单';

-- 结算查询索引优化：结算生成按 provider_id 等值 + ship_time 区间扫描运单，
-- 建联合索引 (provider_id, ship_time) 走索引区间定位，避免服务商维度全量时间扫描（幂等，可重复执行）
SET @ws_idx = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_logistics_waybill ADD INDEX idx_waybill_provider_ship (provider_id, ship_time)', 'SELECT 1')
  FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_logistics_waybill' AND INDEX_NAME='idx_waybill_provider_ship');
PREPARE ws_stmt FROM @ws_idx; EXECUTE ws_stmt; DEALLOCATE PREPARE ws_stmt;

-- 轨迹节点
CREATE TABLE IF NOT EXISTS t_logistics_trace (
  trace_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  waybill_id BIGINT NOT NULL COMMENT '运单ID',
  trace_time DATETIME NOT NULL COMMENT '节点时间',
  location VARCHAR(128) COMMENT '节点地点',
  status VARCHAR(24) COMMENT '节点状态',
  description VARCHAR(255) COMMENT '轨迹描述原文',
  is_abnormal TINYINT DEFAULT 0 COMMENT '0正常 1异常节点',
  source VARCHAR(16) DEFAULT 'api' COMMENT 'api=接口同步 manual=人工录入',
  -- 热增坐标列（LogisticsSplitSchemaInitializer 同源定义，修复 mode:never 手工建库路径漏列）
  longitude DECIMAL(10,6) COMMENT '经度',
  latitude DECIMAL(10,6) COMMENT '纬度',
  city VARCHAR(32) COMMENT '城市',
  hub_type VARCHAR(16) DEFAULT 'hub' COMMENT 'warehouse/hub/transfer/terminal/client',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_trace_unique (waybill_id, trace_time, status, description(100)),
  KEY idx_trace_waybill (waybill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='物流轨迹节点';

-- 异常告警
CREATE TABLE IF NOT EXISTS t_logistics_alert (
  alert_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  waybill_id BIGINT NOT NULL COMMENT '运单ID',
  alert_type VARCHAR(24) COMMENT 'delay/reject/return/damage/lost/timeout_no_update',
  alert_level VARCHAR(12) DEFAULT 'medium' COMMENT 'low/medium/high',
  content VARCHAR(255) COMMENT '告警内容',
  status VARCHAR(12) DEFAULT 'pending' COMMENT 'pending/processing/resolved',
  handled_by BIGINT COMMENT '处理人',
  handled_at DATETIME COMMENT '处理时间',
  handle_note VARCHAR(255) COMMENT '处理备注',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_alert_waybill (waybill_id),
  KEY idx_alert_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='物流异常告警';

-- 物流售后
CREATE TABLE IF NOT EXISTS t_logistics_aftersale (
  aftersale_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  aftersale_no VARCHAR(32) NOT NULL COMMENT '售后单号 LS+日期+序列',
  waybill_id BIGINT NOT NULL COMMENT '运单ID',
  order_no VARCHAR(32) COMMENT '关联订单号',
  aftersale_type VARCHAR(16) COMMENT 'expedite催件 trace查件 claim赔付 reship补发 return_freight退回运费',
  description VARCHAR(500) COMMENT '诉求描述',
  evidence_json VARCHAR(500) COMMENT '凭证(图片URL列表)',
  claim_amount DECIMAL(10,2) DEFAULT 0 COMMENT '赔付金额',
  status VARCHAR(16) DEFAULT 'pending' COMMENT 'pending/processing/approved/rejected/done',
  processor_id BIGINT COMMENT '处理人',
  process_note VARCHAR(255) COMMENT '处理备注',
  process_time DATETIME COMMENT '处理时间',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  UNIQUE KEY uk_aftersale_no (aftersale_no),
  KEY idx_aftersale_waybill (waybill_id),
  KEY idx_aftersale_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='物流售后';

-- 物流结算主表
CREATE TABLE IF NOT EXISTS t_logistics_settlement (
  settlement_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  settlement_no VARCHAR(32) NOT NULL COMMENT '结算单号 JS+年月+序列',
  provider_id BIGINT NOT NULL COMMENT '物流服务商',
  period_start DATE COMMENT '账期起',
  period_end DATE COMMENT '账期止',
  waybill_count INT DEFAULT 0 COMMENT '运单数',
  total_freight DECIMAL(12,2) DEFAULT 0 COMMENT '运费合计',
  deduction DECIMAL(12,2) DEFAULT 0 COMMENT '扣减(赔付冲抵)',
  extra_fee DECIMAL(12,2) DEFAULT 0 COMMENT '附加费',
  payable_amount DECIMAL(12,2) DEFAULT 0 COMMENT '应付金额',
  status VARCHAR(16) DEFAULT 'draft' COMMENT 'draft/pending_review/approved/paid',
  reviewer_id BIGINT COMMENT '审核人',
  review_note VARCHAR(255) COMMENT '审核备注',
  pay_method VARCHAR(16) DEFAULT NULL COMMENT '支付方式 corporate/credit',
  credit_bill_id BIGINT DEFAULT NULL COMMENT '账期支付生成的账单ID',
  paid_time DATETIME COMMENT '打款时间',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  UNIQUE KEY uk_settlement_no (settlement_no),
  KEY idx_settlement_provider (provider_id),
  KEY idx_settlement_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='物流结算单';

-- 物流结算明细
CREATE TABLE IF NOT EXISTS t_logistics_settlement_item (
  item_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  settlement_id BIGINT NOT NULL COMMENT '结算单ID',
  waybill_id BIGINT NOT NULL COMMENT '运单ID',
  waybill_no VARCHAR(32) COMMENT '运单号',
  freight DECIMAL(10,2) DEFAULT 0 COMMENT '该单运费',
  remark VARCHAR(255) COMMENT '备注(差异说明)',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_item_settlement (settlement_id),
  KEY idx_item_waybill (waybill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='物流结算明细';

-- 字典：默认物流服务商（INSERT IGNORE 幂等）
INSERT IGNORE INTO t_logistics_provider (provider_id, provider_name, provider_code, api_type, capability_json, status, settle_cycle, sort) VALUES
  (1, '顺丰速运', 'SF', 'mock', '{"insure":true,"signBack":true}', 1, 'monthly', 1),
  (2, '德邦物流', 'DB', 'mock', '{"cod":true,"insure":true}', 1, 'monthly', 2),
  (3, '京东物流', 'JD', 'mock', '{"insure":true,"signBack":true}', 1, 'monthly', 3),
  (4, '中通快递', 'ZT', 'mock', '{"insure":false}', 1, 'monthly', 4),
  (5, '商家自送', 'SELF', 'manual', '{}', 1, 'realtime', 5);

-- 字典：默认运费模板（INSERT IGNORE 幂等）
INSERT IGNORE INTO t_logistics_template (template_id, template_name, provider_id, pricing_type, first_weight, first_price, continued_weight, continued_price, volume_factor, free_threshold, remote_surcharge, status) VALUES
  (1, '顺丰标快-全国', 1, 'weight', 1, 12, 1, 5, 6000, 0, 0, 1),
  (2, '德邦大件-全国', 2, 'weight', 5, 30, 1, 6, 6000, 0, 15, 1),
  (3, '京东特快-全国', 3, 'weight', 1, 15, 1, 6, 6000, 299, 10, 1),
  (4, '中通经济-全国', 4, 'weight', 1, 8, 1, 3, 6000, 199, 8, 1),
  (5, '商家自送-固定', 5, 'fixed', 0, 0, 0, 0, 6000, 0, 0, 1);

-- ============================================================
-- 自建物流跟踪（module/tracking，out/logistics-tracking-plan.md P1）：
--   渠道事件存档 / 补拉任务 / 回调审计三表。幂等建表，可重复执行。
--   轨迹唯一事实源仍是 t_logistics_trace（规划 D2 单一写路径：规范化节点一律经
--   LogisticsWaybillService.syncTrace() 落库，本组表不存第二份规范化轨迹）。
--   热扩列（t_logistics_waybill.track_channel/track_subscribe_status、t_logistics_alert.ticket_id）
--   由 TrackingSchemaInitializer 幂等补齐，与 LogisticsSplitSchemaInitializer 同一模式。
-- ============================================================

-- 渠道事件存档（uk_event 幂等键：同一渠道同一事件只落一行，重复推送 INSERT 冲突即按渠道语义幂等返回）
CREATE TABLE IF NOT EXISTS tracking_event (
  event_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider_code VARCHAR(32) NOT NULL COMMENT '渠道编码 mock/kuaidi100/kdniao/track17/aftership',
  external_event_id VARCHAR(64) NOT NULL COMMENT '渠道侧事件唯一ID(推送eventId；无显式ID取正文摘要哈希)',
  waybill_id BIGINT COMMENT '运单ID',
  logistics_no VARCHAR(64) COMMENT '第三方物流单号(快递单号)',
  event_type VARCHAR(16) DEFAULT 'callback' COMMENT 'poll=补拉 callback=推送 manual=人工',
  status VARCHAR(16) DEFAULT 'received' COMMENT 'received/normalized/failed/duplicate/skipped',
  raw_body MEDIUMTEXT COMMENT '渠道原文(落库前经 CallbackDesensitizer 脱敏)',
  event_time DATETIME NULL COMMENT '渠道侧最新节点时间(入库延迟P95指标=create_time-event_time)',
  normalized_count INT DEFAULT 0 COMMENT '解析出的规范化节点数',
  error_msg VARCHAR(500) COMMENT '解析/入库失败原因',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_event (provider_code, external_event_id),
  KEY idx_event_waybill (waybill_id),
  KEY idx_event_create (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='物流跟踪渠道事件存档';

-- 补拉任务（uk_task_waybill 一单一任务；owner/claimed_at 供 outbox 式 CAS 行级认领，多副本部署防重）
CREATE TABLE IF NOT EXISTS tracking_sync_task (
  task_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  waybill_id BIGINT NOT NULL COMMENT '运单ID(一单一任务)',
  provider_code VARCHAR(32) NOT NULL COMMENT '渠道编码(TrackingChannel 补拉路由键)',
  logistics_no VARCHAR(64) COMMENT '第三方物流单号(快递单号)',
  status VARCHAR(16) DEFAULT 'pending' COMMENT 'pending/running/done/failed/dead',
  next_poll_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '下次补拉时间(成功回调顺延/失败指数退避)',
  retry_count INT DEFAULT 0,
  fail_count INT DEFAULT 0 COMMENT '当前生效通道连续失败次数(ProviderRouter 累计，达阈值切备用通道，成功清零)',
  active_channel VARCHAR(16) COMMENT '当前生效通道(主备切换后为备用渠道编码)',
  owner VARCHAR(64) COMMENT 'CAS 认领者(实例标识)',
  claimed_at DATETIME COMMENT '认领时间',
  finished_time DATETIME COMMENT '终态时间',
  last_error VARCHAR(500) COMMENT '最近一次失败原因',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_task_waybill (waybill_id),
  KEY idx_task_claim (status, next_poll_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='物流跟踪补拉任务';

-- 回调审计（验签失败也记脱敏原文，供追查伪造来源与渠道联调排障；按 callback-log-retention-days 定期清理）
CREATE TABLE IF NOT EXISTS tracking_callback_log (
  log_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider_code VARCHAR(32) NOT NULL COMMENT '渠道编码',
  remote_ip VARCHAR(64) COMMENT '来源IP',
  verify_result VARCHAR(8) NOT NULL COMMENT 'PASS/FAIL',
  http_status INT COMMENT '响应码(验签失败401)',
  body_raw MEDIUMTEXT COMMENT '请求原文(落库前脱敏；PASS 仅存前2KB摘要，FAIL 存全量)',
  fail_reason VARCHAR(255) COMMENT '验签失败原因',
  event_id BIGINT COMMENT '关联 tracking_event.event_id(验签失败时为空)',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_cblog (provider_code, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='物流跟踪回调审计日志';

-- ============================================================
-- 数据校验与防篡改：幂等令牌表（下单/支付/退款等核心接口重复提交拦截）
--   唯一键(biz_type, biz_key) 保证同一业务键只允许一次成功执行；
--   基于数据库唯一约束实现跨实例互斥，无需引入 Redis 即可防止并发重复提交。
-- ============================================================
CREATE TABLE IF NOT EXISTS t_idempotent_token (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  biz_type VARCHAR(32) NOT NULL COMMENT '业务类型 checkout/pay/refund/settle',
  biz_key VARCHAR(64) NOT NULL COMMENT '业务幂等键(客户端传入或服务端生成)',
  user_id BIGINT COMMENT '发起用户',
  result_ref VARCHAR(64) COMMENT '首次执行结果引用(如订单号)',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  expire_time DATETIME COMMENT '过期时间(可清理)',
  UNIQUE KEY uk_biz (biz_type, biz_key),
  KEY idx_idem_user (user_id),
  KEY idx_idem_expire (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='幂等令牌表';

-- ============================================================
-- 数据校验与防篡改：敏感字段篡改告警表
--   后端重算值与客户端传入值不一致、状态非法流转等异常请求落库告警，
--   供运营追溯恶意攻击行为（配合 t_audit_log 审计链）。
-- ============================================================
CREATE TABLE IF NOT EXISTS t_tamper_alert (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT COMMENT '发起用户',
  biz_type VARCHAR(32) COMMENT '业务类型 order/payment/product',
  field_name VARCHAR(48) COMMENT '被篡改的敏感字段',
  client_value VARCHAR(255) COMMENT '客户端传入值',
  server_value VARCHAR(255) COMMENT '后端重算/期望值',
  reason VARCHAR(255) COMMENT '告警原因',
  client_ip VARCHAR(64) COMMENT '客户端IP',
  user_agent VARCHAR(255) COMMENT 'UA',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_tamper_user (user_id),
  KEY idx_tamper_biz (biz_type),
  KEY idx_tamper_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='敏感字段篡改告警';

-- ============================================================
-- 业务单号序列号表：运单号/结算单号/售后单号等统一定长序号取号
--   替代 selectCount()+1 取号在并发下的竞态（同序号撞唯一约束）；
--   UPDATE ... LAST_INSERT_ID(current_val + 1) 行锁内原子自增，并发安全。
--   序列键内含周期（waybill:20261016 / settlement:202610），天然按日/月重置。
-- ============================================================
CREATE TABLE IF NOT EXISTS t_sys_sequence (
  seq_key VARCHAR(48) PRIMARY KEY COMMENT '序列键(业务前缀:周期)',
  current_val BIGINT NOT NULL DEFAULT 0 COMMENT '当前值(取号后即为已分配值)',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='业务单号序列';

-- ============================================================
-- 社交登录绑定：渠道内唯一标识 → 平台账号
--   与 t_third_auth（授权管理展示）不同，本表承载「登录」所需的稳定映射；
--   (provider, subject) 唯一，保证同一第三方账号只对应一个平台账号。
-- ============================================================
CREATE TABLE IF NOT EXISTS t_social_binding (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL COMMENT '平台账号ID',
  provider VARCHAR(24) NOT NULL COMMENT 'wechat/alipay/apple/google',
  subject VARCHAR(128) NOT NULL COMMENT '渠道内唯一标识(openid/sub/user_id)',
  union_id VARCHAR(128) COMMENT '微信开放平台 unionid',
  nickname VARCHAR(64) COMMENT '第三方昵称(展示用)',
  avatar VARCHAR(255) COMMENT '第三方头像(展示用)',
  email VARCHAR(128) COMMENT '第三方邮箱',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_social_provider_subject (provider, subject),
  KEY idx_social_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='社交登录绑定';

-- ==================== 营销中台：订阅系统 ====================
-- 订阅方案：价格以「元」为单位（企业级 B2B 定价）；duration_days 为有效天数；plan_code 全局唯一(幂等种子业务键)。
CREATE TABLE IF NOT EXISTS t_subscription_plan (
  plan_id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_name      VARCHAR(32) NOT NULL COMMENT '方案名称',
  plan_code      VARCHAR(32) NOT NULL UNIQUE COMMENT '方案编码',
  price_cents    INT NOT NULL DEFAULT 0 COMMENT '价格(元)',
  duration_days  INT NOT NULL DEFAULT 0 COMMENT '有效天数',
  auto_renew     TINYINT DEFAULT 0 COMMENT '0单次 1自动续费',
  discount_label VARCHAR(32) COMMENT '优惠标签(展示用)',
  sort           INT DEFAULT 0 COMMENT '展示排序',
  enabled        TINYINT DEFAULT 1 COMMENT '0下架 1上架',
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='订阅方案';

-- 用户订阅记录：status active 生效 / cancelled 已取消 / expired 已过期；生效口径 status=active 且 end_time>now。
-- D3 cancel 语义：取消=撤销 auto_renew（到期失效），权益保留至 end_time，记录不删除（历史可见、可续费恢复）。
CREATE TABLE IF NOT EXISTS t_user_subscription (
  sub_id       BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id      BIGINT NOT NULL COMMENT '用户ID',
  plan_id      BIGINT COMMENT '订阅方案',
  start_time   DATETIME COMMENT '生效时间',
  end_time     DATETIME COMMENT '到期时间',
  status       VARCHAR(16) DEFAULT 'active' COMMENT 'active/cancelled/expired',
  auto_renew   TINYINT DEFAULT 0 COMMENT '0单次 1自动续费',
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_sub_user (user_id),
  KEY idx_sub_status_end (status, end_time)
) ENGINE=InnoDB COMMENT='用户订阅记录';

-- 订阅订单（购买/续费支付流水，P1-1）：order_no 以「SUB」前缀与商品订单（纯数字单号）做单据类型命名空间区分，
-- /payment/create、/payment/query、三渠道 notify 回调按该前缀分流到订阅单（PaymentService 单据抽象）。
CREATE TABLE IF NOT EXISTS t_subscription_order (
  order_no       VARCHAR(32) PRIMARY KEY COMMENT '订阅单号(SUB+yyyyMMdd+8位序列)',
  user_id        BIGINT NOT NULL COMMENT '用户ID',
  plan_id        BIGINT NOT NULL COMMENT '订阅方案ID',
  plan_code      VARCHAR(32) COMMENT '方案编码快照',
  plan_name      VARCHAR(32) COMMENT '方案名称快照',
  -- 金额单位（钉死）：amount_cents 沿用 t_subscription_plan.price_cents 的「整数元」语义，8888 = ¥8,888，不是分；
  -- 渠道换算（微信 total_fee / Stripe amount 的「分」= 元×100）统一在 PaymentService 以 BigDecimal 元进行，
  -- 与订单侧 t_order.total_amount(DECIMAL 元) 同一换算口径，禁止在任何调用点直接把本字段当分传渠道。
  amount_cents   INT NOT NULL DEFAULT 0 COMMENT '实付金额(整数元, 8888=¥8,888)',
  duration_days  INT NOT NULL DEFAULT 0 COMMENT '有效天数快照(方案下架后结算仍可依此顺延)',
  auto_renew     TINYINT DEFAULT 0 COMMENT '下单时方案 auto_renew 快照(0单次 1自动续费)',
  pay_status     TINYINT DEFAULT 0 COMMENT '0待支付 1已支付 2已取消(支付状态机)',
  channel        VARCHAR(16) COMMENT '支付渠道 wallet/mock/wechat/alipay/stripe',
  transaction_id VARCHAR(64) COMMENT '渠道交易号',
  paid_at        DATETIME COMMENT '支付完成时间',
  apply_status   TINYINT DEFAULT 0 COMMENT '0未生效 1已驱动订阅生效/顺延(回调重放幂等闸)',
  renew_sub_id   BIGINT COMMENT '自动续费来源订阅ID(手动购买为 NULL)',
  retry_count    INT NOT NULL DEFAULT 0 COMMENT '自动续费扣款失败次数(达上限置 cancelled)',
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_subord_user (user_id, created_at),
  KEY idx_subord_pay (pay_status),
  KEY idx_subord_renew (renew_sub_id, pay_status)
) ENGINE=InnoDB COMMENT='订阅订单（购买/续费流水）';

-- 幂等种子：订阅方案（重复执行时按唯一键 plan_code 更新，确保价格/天数调整能生效）
-- 价格以「元」为单位（企业级 B2B 定价，整数无小数）：月度 8888 / 连续包月 8299 / 季度 23999 / 年度 88888。
INSERT INTO t_subscription_plan (plan_id, plan_name, plan_code, price_cents, duration_days, auto_renew, discount_label, sort, enabled) VALUES
 (1, '月度订阅', 'monthly',      8888,  30,  0, NULL,      1, 1),
 (2, '连续包月', 'auto_monthly', 8299,  30,  1, '省7%',    2, 1),
 (3, '季度订阅', 'quarterly',    23999, 90,  0, '省10%',   3, 1),
 (4, '年度订阅', 'yearly',       88888, 365, 0, '省17%',   4, 1)
ON DUPLICATE KEY UPDATE
 plan_name=VALUES(plan_name), price_cents=VALUES(price_cents), duration_days=VALUES(duration_days),
 auto_renew=VALUES(auto_renew), discount_label=VALUES(discount_label), sort=VALUES(sort), enabled=VALUES(enabled);

-- ==================== 关于我们：加入我们（求职 / 渠道合作） ====================
-- 闭环：用户提交 → PENDING 待审核 → 运营审核（APPROVED/REJECTED + 备注）→ 站内消息通知 → 个人中心查看进度。
CREATE TABLE IF NOT EXISTS t_join_application (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id       BIGINT NOT NULL COMMENT '申请人用户ID',
  apply_type    VARCHAR(16) NOT NULL COMMENT 'job 求职 / partner 渠道合作',
  position      VARCHAR(64) COMMENT '求职：应聘职位',
  company_name  VARCHAR(128) COMMENT '合作：公司/机构名称',
  contact_name  VARCHAR(32) NOT NULL COMMENT '联系人姓名',
  phone         VARCHAR(32) NOT NULL COMMENT '联系电话',
  email         VARCHAR(128) COMMENT '邮箱',
  city          VARCHAR(64) COMMENT '城市/意向区域',
  experience    VARCHAR(32) COMMENT '求职：工作年限',
  message       VARCHAR(1000) COMMENT '自我介绍/合作说明',
  status        VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待审核 APPROVED通过 REJECTED驳回',
  review_remark VARCHAR(255) COMMENT '审核备注/驳回原因',
  review_by     BIGINT COMMENT '审核人用户ID',
  apply_time    DATETIME NOT NULL COMMENT '提交时间',
  reviewed_time DATETIME COMMENT '审核时间',
  update_time   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_join_user (user_id, apply_time),
  KEY idx_join_type_status (apply_type, status, apply_time),
  KEY idx_join_status (status, apply_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='加入我们申请（求职/渠道合作）';

-- ==================== 广告推广（商户流量推广计划） ====================
-- 闭环：商户选品+预算 → 钱包支付冻结 → 投放(商城广告位) → 点击计费(CPC 原子扣减) →
--       预算耗尽/到期/手动结束 → 剩余金额结算退回钱包 → 报表 + 续费再来一单。
CREATE TABLE IF NOT EXISTS t_ad_campaign (
  campaign_id  BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '推广计划ID',
  mer_id       BIGINT NOT NULL COMMENT '商户ID',
  user_id      BIGINT NOT NULL COMMENT '创建人(商户账号)用户ID',
  prod_id      BIGINT NOT NULL COMMENT '推广商品ID',
  plan_name    VARCHAR(64) COMMENT '计划名称',
  total_budget DECIMAL(12,2) NOT NULL COMMENT '总预算(支付时冻结)',
  spent        DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '已花费(点击计费累计)',
  clicks       BIGINT NOT NULL DEFAULT 0 COMMENT '点击数',
  impressions  BIGINT NOT NULL DEFAULT 0 COMMENT '曝光数',
  cpc_price    DECIMAL(8,2) NOT NULL DEFAULT 0.50 COMMENT '点击单价(元)',
  duration_days INT NOT NULL DEFAULT 7 COMMENT '投放天数',
  status       VARCHAR(20) NOT NULL COMMENT 'pending_payment待支付/active投放中/paused已暂停/ended已结束',
  end_reason   VARCHAR(20) COMMENT 'expired到期/budget_exhausted预算耗尽/manual手动结束',
  start_time   DATETIME COMMENT '投放开始时间',
  end_time     DATETIME COMMENT '投放结束时间(开始+天数)',
  settled      DECIMAL(12,2) COMMENT '结算退回金额',
  settle_time  DATETIME COMMENT '结算时间',
  create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted      TINYINT DEFAULT 0 COMMENT '逻辑删除',
  KEY idx_ad_mer (mer_id, status),
  KEY idx_ad_status (status, end_time),
  KEY idx_ad_prod (prod_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='广告推广计划';

-- ==================== 商品直播（B2B 实景带货 / 技术研讨直播间） ====================
CREATE TABLE IF NOT EXISTS t_live_room (
  room_id        BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '直播间ID',
  mer_id         BIGINT NOT NULL COMMENT '主办商户ID',
  user_id        BIGINT NOT NULL COMMENT '主播/创建人用户ID',
  title          VARCHAR(128) NOT NULL COMMENT '直播主题',
  cover_url      VARCHAR(512) COMMENT '封面图URL',
  stream_url     VARCHAR(512) COMMENT '视频流/演示流URL',
  status         TINYINT NOT NULL DEFAULT 1 COMMENT '0预告 1直播中 2已结束',
  viewer_count   INT NOT NULL DEFAULT 1 COMMENT '在线观众数',
  like_count     BIGINT NOT NULL DEFAULT 0 COMMENT '点赞数',
  pinned_prod_id BIGINT COMMENT '当前讲解商品ID',
  notice         VARCHAR(255) COMMENT '置顶公告',
  start_time     DATETIME COMMENT '开播时间',
  end_time       DATETIME COMMENT '结束时间',
  create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_live_status (status, start_time),
  KEY idx_live_mer (mer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商品直播间';

CREATE TABLE IF NOT EXISTS t_live_product (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  room_id      BIGINT NOT NULL COMMENT '直播间ID',
  prod_id      BIGINT NOT NULL COMMENT '商品ID',
  live_price   DECIMAL(10,2) COMMENT '直播专属特惠价',
  live_stock   INT DEFAULT 100 COMMENT '直播专供库存',
  sort_order   INT DEFAULT 0 COMMENT '排序',
  create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_lp_room (room_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='直播间关联商品';

CREATE TABLE IF NOT EXISTS t_live_comment (
  comment_id   BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '弹幕消息ID',
  room_id      BIGINT NOT NULL COMMENT '直播间ID',
  user_id      BIGINT COMMENT '发送人用户ID',
  nickname     VARCHAR(64) NOT NULL COMMENT '发送人昵称',
  avatar       VARCHAR(255) COMMENT '头像',
  content      VARCHAR(500) NOT NULL COMMENT '弹幕内容',
  msg_type     VARCHAR(16) NOT NULL DEFAULT 'chat' COMMENT 'chat聊天 buy购买 system系统 gift点赞',
  create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_lc_room (room_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='直播间互动弹幕';

-- ============================================================
-- 直播商业闭环补列（幂等，历史库可重复执行）：
--   t_live_room.peak_viewer_count  观看峰值（进出场维护）
--   t_live_product.click_count     讲解商品点击数（归因漏斗）
--   t_order.live_id                下单归因直播间（复盘 GMV 依据）
-- ============================================================
SET @lv1 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_live_room ADD COLUMN peak_viewer_count INT NOT NULL DEFAULT 0 COMMENT ''观看峰值'' AFTER viewer_count', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_live_room' AND COLUMN_NAME='peak_viewer_count');
PREPARE lv_stmt1 FROM @lv1; EXECUTE lv_stmt1; DEALLOCATE PREPARE lv_stmt1;

SET @lv5 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_live_room ADD COLUMN review_status TINYINT NOT NULL DEFAULT 1 COMMENT ''审核状态 0待审核 1通过 2驳回'' AFTER status', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_live_room' AND COLUMN_NAME='review_status');
PREPARE lv_stmt5 FROM @lv5; EXECUTE lv_stmt5; DEALLOCATE PREPARE lv_stmt5;

SET @lv6 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_live_room ADD COLUMN reject_reason VARCHAR(255) NULL COMMENT ''驳回原因'' AFTER review_status', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_live_room' AND COLUMN_NAME='reject_reason');
PREPARE lv_stmt6 FROM @lv6; EXECUTE lv_stmt6; DEALLOCATE PREPARE lv_stmt6;

SET @lv2 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_live_product ADD COLUMN click_count INT NOT NULL DEFAULT 0 COMMENT ''点击数'' AFTER live_stock', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_live_product' AND COLUMN_NAME='click_count');
PREPARE lv_stmt2 FROM @lv2; EXECUTE lv_stmt2; DEALLOCATE PREPARE lv_stmt2;

SET @lv3 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN live_id BIGINT NULL COMMENT ''下单归因直播间ID'' AFTER coupon_id', 'SELECT 1')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND COLUMN_NAME='live_id');
PREPARE lv_stmt3 FROM @lv3; EXECUTE lv_stmt3; DEALLOCATE PREPARE lv_stmt3;

SET @lv4 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD INDEX idx_order_live (live_id)', 'SELECT 1')
  FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order' AND INDEX_NAME='idx_order_live');
PREPARE lv_stmt4 FROM @lv4; EXECUTE lv_stmt4; DEALLOCATE PREPARE lv_stmt4;

SET @lv7 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order_item ADD INDEX idx_oi_order (order_no)', 'SELECT 1')
  FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_order_item' AND INDEX_NAME='idx_oi_order');
PREPARE lv_stmt7 FROM @lv7; EXECUTE lv_stmt7; DEALLOCATE PREPARE lv_stmt7;

SET @lv8 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_live_product ADD INDEX idx_lp_prod (prod_id)', 'SELECT 1')
  FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_live_product' AND INDEX_NAME='idx_lp_prod');
PREPARE lv_stmt8 FROM @lv8; EXECUTE lv_stmt8; DEALLOCATE PREPARE lv_stmt8;

SET @lv9 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_live_comment ADD INDEX idx_lc_room_cid (room_id, comment_id)', 'SELECT 1')
  FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_live_comment' AND INDEX_NAME='idx_lc_room_cid');
PREPARE lv_stmt9 FROM @lv9; EXECUTE lv_stmt9; DEALLOCATE PREPARE lv_stmt9;

-- 初始示例直播间种子数据 (幂等写入)
INSERT IGNORE INTO t_live_room (room_id, mer_id, user_id, title, cover_url, stream_url, status, viewer_count, like_count, pinned_prod_id, notice, start_time)
VALUES
(1, 1, 3, '工业物联网边缘网关与传感器直发现场评测', 'https://images.unsplash.com/photo-1581092160607-ee22621dd758?q=80&w=1200&auto=format&fit=crop', 'https://assets.mixkit.co/videos/preview/mixkit-circuit-board-details-4835-large.mp4', 1, 1280, 5620, 1, '【官方采购通知】本场直播支持对公集中打款与 30-60 天企业信用账期，下单即赠备件包！', NOW()),
(2, 1, 3, '企业数字化办公与智能工位全生态专场品鉴', 'https://images.unsplash.com/photo-1524758631624-e2822e304c36?q=80&w=1200&auto=format&fit=crop', 'https://assets.mixkit.co/videos/preview/mixkit-modern-office-with-working-people-42966-large.mp4', 0, 350, 890, 2, '【预告】今晚 20:00 准时开播，前 50 单企业采购客户赠送一年上门原厂巡检服务。', DATE_ADD(NOW(), INTERVAL 4 HOUR));

INSERT IGNORE INTO t_live_product (id, room_id, prod_id, live_price, live_stock, sort_order)
VALUES
(1, 1, 1, 1088.00, 200, 1),
(2, 1, 2, 799.00, 150, 2),
(3, 2, 2, 799.00, 300, 1);

INSERT IGNORE INTO t_live_comment (comment_id, room_id, user_id, nickname, avatar, content, msg_type, create_time)
VALUES
(1, 1, null, '系统助手', '', '欢迎来到 LANLink 直播间，严禁违规广告，支持 13% 增值税专票开具与对公账期。', 'system', DATE_SUB(NOW(), INTERVAL 5 MINUTE)),
(2, 1, 2, '中车智能制造采购部', '', '请问边缘网关支持多少路 Modbus RTU 串行总线同时轮询？', 'chat', DATE_SUB(NOW(), INTERVAL 3 MINUTE)),
(3, 1, 3, '主播·官方技术工程师', '', '支持双路隔离 RS485，支持 64 台从站设备毫秒级并发轮询！', 'chat', DATE_SUB(NOW(), INTERVAL 2 MINUTE)),
(4, 1, null, '德邦精工采购代表', '', '已成功采购下单【工业网关】× 20 台，已选账期结算！', 'buy', DATE_SUB(NOW(), INTERVAL 1 MINUTE));


-- ==================== 价格监控：日 K 线演示数据（仅空表时生成，避免重启重复插入） ====================
-- 以一个交易周期 30 天、每天 1 条调价记录生成连续 OHLC；最后一天收盘价严格等于商品现价，
-- 保证首页 K 线、当前价、区间高低价三者口径一致。成交量由前端按商品销量估算，未写入此表。
INSERT INTO t_price_history (prod_id, mer_id, ind_id, old_price, new_price, change_pct, source, operator_id, create_time)
SELECT x.prod_id, x.mer_id, x.ind_id, x.old_price, x.new_price,
       ROUND((x.new_price - x.old_price) * 100 / NULLIF(x.old_price, 0), 2), 'SEED', NULL, x.create_time
FROM (
  SELECT p.prod_id, p.mer_id, p.ind_id,
         CASE WHEN d.n = 0 THEN ROUND(p.price * (1 + 0.045 * SIN((-1 + p.prod_id * 7) * 0.62)
              + 0.018 * COS((-1 * 1.37 + p.prod_id * 2))), 2)
              ELSE ROUND(p.price * (1 + 0.045 * SIN((d.n - 1 + p.prod_id * 7) * 0.62)
              + 0.018 * COS(((d.n - 1) * 1.37 + p.prod_id * 2))), 2)
         END AS old_price,
         CASE WHEN d.n = 89 THEN p.price
              ELSE ROUND(p.price * (1 + 0.045 * SIN((d.n + p.prod_id * 7) * 0.62)
              + 0.018 * COS((d.n * 1.37 + p.prod_id * 2))), 2)
         END AS new_price,
         DATE_SUB(NOW(), INTERVAL (89 - d.n) DAY) AS create_time
  FROM t_product p
  JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15 UNION ALL SELECT 16 UNION ALL SELECT 17 UNION ALL SELECT 18 UNION ALL SELECT 19 UNION ALL SELECT 20 UNION ALL SELECT 21 UNION ALL SELECT 22 UNION ALL SELECT 23 UNION ALL SELECT 24 UNION ALL SELECT 25 UNION ALL SELECT 26 UNION ALL SELECT 27 UNION ALL SELECT 28 UNION ALL SELECT 29 UNION ALL SELECT 30 UNION ALL SELECT 31 UNION ALL SELECT 32 UNION ALL SELECT 33 UNION ALL SELECT 34 UNION ALL SELECT 35 UNION ALL SELECT 36 UNION ALL SELECT 37 UNION ALL SELECT 38 UNION ALL SELECT 39 UNION ALL SELECT 40 UNION ALL SELECT 41 UNION ALL SELECT 42 UNION ALL SELECT 43 UNION ALL SELECT 44 UNION ALL SELECT 45 UNION ALL SELECT 46 UNION ALL SELECT 47 UNION ALL SELECT 48 UNION ALL SELECT 49 UNION ALL SELECT 50 UNION ALL SELECT 51 UNION ALL SELECT 52 UNION ALL SELECT 53 UNION ALL SELECT 54 UNION ALL SELECT 55 UNION ALL SELECT 56 UNION ALL SELECT 57 UNION ALL SELECT 58 UNION ALL SELECT 59 UNION ALL SELECT 60 UNION ALL SELECT 61 UNION ALL SELECT 62 UNION ALL SELECT 63 UNION ALL SELECT 64 UNION ALL SELECT 65 UNION ALL SELECT 66 UNION ALL SELECT 67 UNION ALL SELECT 68 UNION ALL SELECT 69 UNION ALL SELECT 70 UNION ALL SELECT 71 UNION ALL SELECT 72 UNION ALL SELECT 73 UNION ALL SELECT 74 UNION ALL SELECT 75 UNION ALL SELECT 76 UNION ALL SELECT 77 UNION ALL SELECT 78 UNION ALL SELECT 79 UNION ALL SELECT 80 UNION ALL SELECT 81 UNION ALL SELECT 82 UNION ALL SELECT 83 UNION ALL SELECT 84 UNION ALL SELECT 85 UNION ALL SELECT 86 UNION ALL SELECT 87 UNION ALL SELECT 88 UNION ALL SELECT 89) d
  WHERE p.prod_id IN (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 14, 15)
    AND p.price >= 1
) x
WHERE NOT EXISTS (SELECT 1 FROM t_price_history LIMIT 1);


-- ==================== 人工客服工单（设置页「人工客服」留言 → 运营处理） ====================
CREATE TABLE IF NOT EXISTS t_support_ticket (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL COMMENT '提交用户',
  contact     VARCHAR(120) COMMENT '回电/邮箱(可空)',
  category    VARCHAR(32) NOT NULL DEFAULT 'other' COMMENT 'account/order/payment/merchant/other',
  title       VARCHAR(200) NOT NULL COMMENT '标题',
  content     VARCHAR(2000) NOT NULL COMMENT '问题描述',
  status      VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/PROCESSING/REPLIED/CLOSED',
  priority    VARCHAR(8) NOT NULL DEFAULT 'NORMAL' COMMENT '处理优先级 NORMAL/VIP(有效订阅专属客服通道 D7)',
  reply       VARCHAR(2000) COMMENT '运营回复',
  reply_by    BIGINT COMMENT '回复运营用户ID',
  reply_time  DATETIME COMMENT '回复时间',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_st_user (user_id, create_time),
  KEY idx_st_status (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工客服工单';

-- ==================== 买家↔商家 联系商家对话 ====================
-- 闭环：商品详情页「联系商家」(携带 merId+prodId) → find-or-create 会话 → 双方互发消息 →
--       未读数（对方维度）→ 商户中心「客户消息」收发；买家未读独立于 t_message 平台通知角标。
CREATE TABLE IF NOT EXISTS t_merchant_chat (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '会话ID',
  buyer_id      BIGINT NOT NULL COMMENT '买家用户ID',
  mer_id        BIGINT NOT NULL COMMENT '商户ID(t_merchant.mer_id)',
  prod_id       BIGINT NULL COMMENT '最近咨询商品ID',
  last_msg_content VARCHAR(200) DEFAULT '' COMMENT '最后一条消息摘要',
  last_sender_role VARCHAR(10) DEFAULT '' COMMENT 'buyer/merchant',
  unread_count  INT NOT NULL DEFAULT 0 COMMENT '对方未读数(结合last_sender_role判定归属)',
  status        VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT 'active/closed',
  create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY ux_chat_buyer_mer (buyer_id, mer_id),
  KEY idx_chat_mer (mer_id, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='买家商家会话';

CREATE TABLE IF NOT EXISTS t_merchant_chat_msg (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id  BIGINT NOT NULL COMMENT '会话ID',
  sender_role VARCHAR(10) NOT NULL COMMENT 'buyer/merchant',
  sender_id   BIGINT NOT NULL COMMENT '发送者用户ID',
  content     VARCHAR(500) NOT NULL COMMENT '文本内容；媒体消息存占位([图片]/[文件] 名/[语音])',
  msg_type    VARCHAR(16) NOT NULL DEFAULT 'text' COMMENT '消息类型：text/image/file/voice',
  media_url   VARCHAR(300) NULL COMMENT '媒体URL(/api/uploads/chat/{yyyyMM}/{userId}_{ts}.{ext})',
  media_name  VARCHAR(200) NULL COMMENT '媒体原始文件名(仅回显落库，不参与路径)',
  media_size  BIGINT NULL COMMENT '媒体字节数',
  voice_seconds INT NULL COMMENT '语音时长(秒，仅voice，1-300)',
  read_flag   TINYINT DEFAULT 0 COMMENT '对方是否已读',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_chat_msg_session (session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='买家商家消息';

-- ==================== 用户习惯画像（分析类 Cookie 收集的服务端同步） ====================
CREATE TABLE IF NOT EXISTS t_user_habit (
  user_id BIGINT PRIMARY KEY COMMENT '用户ID（登录用户跨设备续用）',
  consent TINYINT NOT NULL DEFAULT 0 COMMENT '上报时的 analytics 授权快照(1=已授权)',
  consent_at DATETIME NULL COMMENT '最近一次授权上报时间',
  payload LONGTEXT NOT NULL COMMENT '画像 JSON：搜索词/类目/最近浏览/访问次数（前端限条目，后端限 8KB）',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户习惯画像（analytics 授权后采集）';

-- ==================== 商品推荐：共现矩阵 + 热度日榜（定时物化） ====================
CREATE TABLE IF NOT EXISTS t_prod_cooccur (
  prod_a     BIGINT NOT NULL COMMENT '来源商品',
  prod_b     BIGINT NOT NULL COMMENT '共现商品',
  cnt        INT    NOT NULL DEFAULT 0 COMMENT '加权共现次数(VIEW=1,CLICK=2,ADD_CART=3,PAY=5)',
  score      DECIMAL(10,6) NOT NULL DEFAULT 0 COMMENT '余弦归一分 cnt/sqrt(total_a*total_b)',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (prod_a, prod_b),
  KEY idx_cooccur_b (prod_b)
) ENGINE=InnoDB COMMENT='商品共现矩阵(session 内加权, 定时物化)';

CREATE TABLE IF NOT EXISTS t_prod_hot_stat (
  prod_id    BIGINT PRIMARY KEY,
  ind_id     BIGINT,
  cat_id     BIGINT,
  view_cnt   INT DEFAULT 0,
  cart_cnt   INT DEFAULT 0,
  pay_cnt    INT DEFAULT 0,
  hot_score  DECIMAL(10,6) NOT NULL DEFAULT 0 COMMENT 'Wilson 下界*时间衰减',
  stat_date  DATE NOT NULL,
  KEY idx_hot_ind (ind_id, hot_score)
) ENGINE=InnoDB COMMENT='商品热度日榜(物化)';

-- ==================== 用户违规风控：命中事件 + 风险总分（打分卡，只记录不动真格） ====================
CREATE TABLE IF NOT EXISTS t_user_risk_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  rule_code VARCHAR(32) NOT NULL COMMENT 'R01高频取消/R02高频下单/R03批量下单/R04重复退款/R05篡改/R06登录异常/R07客服灌水',
  score INT NOT NULL,
  detail VARCHAR(500),
  ref_type VARCHAR(24),
  ref_id VARCHAR(40) COMMENT 'order_no/user 等引用',
  reviewed_status TINYINT DEFAULT NULL COMMENT 'NULL未复核 0误报(白名单) 1确认',
  reviewed_by BIGINT,
  reviewed_time DATETIME,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_risk_user_time (user_id, create_time),
  KEY idx_risk_rule (rule_code, create_time)
) ENGINE=InnoDB COMMENT='用户风控命中事件';

CREATE TABLE IF NOT EXISTS t_user_risk_summary (
  user_id BIGINT PRIMARY KEY,
  total_score INT NOT NULL DEFAULT 0,
  level VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/WATCH/REVIEW',
  hit_count INT DEFAULT 0,
  last_event_time DATETIME,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='用户风险总分(30天窗口, 定时物化)';

-- ==================== A/B 实验平台（最小版：分流哈希 + 曝光/转化日志 + 指标检验） ====================
CREATE TABLE IF NOT EXISTS t_ab_experiment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  exp_key VARCHAR(64) NOT NULL COMMENT '实验键（分流哈希输入）',
  name VARCHAR(128) NOT NULL COMMENT '实验名称',
  status VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT 'draft/running/paused/finished',
  traffic_pct INT NOT NULL DEFAULT 100 COMMENT '进入实验的流量百分比 0-100',
  variants_json VARCHAR(500) NOT NULL COMMENT '变体名数组 JSON，如 ["control","treatment"]',
  metric VARCHAR(32) NOT NULL DEFAULT 'ctr' COMMENT '主指标 ctr/cvr/gmv',
  description VARCHAR(500),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_ab_key (exp_key)
) ENGINE=InnoDB COMMENT='A/B 实验定义';

CREATE TABLE IF NOT EXISTS t_ab_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  exp_key VARCHAR(64) NOT NULL,
  variant VARCHAR(32) NOT NULL,
  user_id BIGINT NULL,
  session_id VARCHAR(128) NULL,
  event_type VARCHAR(16) NOT NULL COMMENT 'expose/convert',
  value DECIMAL(12,2) NULL COMMENT '转化金额（gmv 指标用）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_ab_exp_variant (exp_key, variant, event_type),
  KEY idx_ab_user (exp_key, user_id, event_type)
) ENGINE=InnoDB COMMENT='A/B 曝光与转化日志';

-- ==================== RFM 用户分层（每日 04:00 物化，固定阈值版） ====================
CREATE TABLE IF NOT EXISTS t_user_rfm (
  user_id BIGINT PRIMARY KEY,
  r_score INT NOT NULL,
  f_score INT NOT NULL,
  m_score INT NOT NULL,
  r_days INT,
  f_cnt INT,
  m_amount DECIMAL(12,2),
  layer VARCHAR(16) NOT NULL DEFAULT '一般挽留' COMMENT '八层经典矩阵',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_rfm_layer (layer)
) ENGINE=InnoDB COMMENT='RFM 用户分层(物化, 只读)';

-- ==================== 商品品类扩充种子（每类目≥2，prod_id 1001-1366；INSERT IGNORE 幂等，回滚见平台记忆/按段 DELETE） ====================
SET NAMES utf8mb4;
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1001, 1, 1, 1, '热轧带肋钢筋 HRB400E Φ12mm×9m 定尺', '华冶工贸', 'HRB400E Φ12mm×9m 定尺', 3350, '[{"qty":5,"price":3350},{"qty":1000,"price":3216}]', 247, '', '品牌：华冶工贸
规格：HRB400E Φ12mm×9m 定尺
计量单位：吨（现货 247 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 189, DATE_SUB(NOW(), INTERVAL 47 DAY), NOW(), 0),
(1002, 2, 1, 1, '热轧带肋钢筋 HRB400E Φ25mm×12m 定尺', '澜建新材', 'HRB400E Φ25mm×12m 定尺', 3260, '[{"qty":5,"price":3260}]', 137, '', '品牌：澜建新材
规格：HRB400E Φ25mm×12m 定尺
计量单位：吨（现货 137 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 28, DATE_SUB(NOW(), INTERVAL 54 DAY), NOW(), 0),
(1003, 1, 2, 1, '硅酸盐水泥 P.O42.5 散装', '澜建新材', 'P.O42.5 散装', 395, '[{"qty":100,"price":395},{"qty":1000,"price":379.2}]', 3106, '', '品牌：澜建新材
规格：P.O42.5 散装
计量单位：吨（现货 3106 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 794, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW(), 0),
(1004, 2, 2, 1, '普通硅酸盐水泥 P.O42.5R 袋装 50kg', '华冶工贸', 'P.O42.5R 袋装 50kg', 425, '[{"qty":100,"price":425}]', 582, '', '品牌：华冶工贸
规格：P.O42.5R 袋装 50kg
计量单位：吨（现货 582 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 77, DATE_SUB(NOW(), INTERVAL 8 DAY), NOW(), 0),
(1005, 1, 3, 1, 'PPR 冷热水管 S2.5 DN20×4m', '华冶工贸', '冷热水管 S2.5 DN20×4m', 26.5, '[{"qty":100,"price":26.5},{"qty":1000,"price":25.44}]', 3159, '', '品牌：华冶工贸
规格：冷热水管 S2.5 DN20×4m
计量单位：根（现货 3159 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 891, DATE_SUB(NOW(), INTERVAL 15 DAY), NOW(), 0),
(1006, 2, 3, 1, '镀锌钢管 SC100×4m DN100', '澜建新材', 'SC100×4m DN100', 168, '[{"qty":100,"price":168}]', 623, '', '品牌：澜建新材
规格：SC100×4m DN100
计量单位：根（现货 623 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 108, DATE_SUB(NOW(), INTERVAL 22 DAY), NOW(), 0),
(1007, 1, 4, 2, '全棉坯布 32S×32S 130×70 63 英寸', '锦纺新材', '32S×32S 130×70 63 英寸', 8.2, '[{"qty":100,"price":8.2},{"qty":1000,"price":7.87}]', 3212, '', '品牌：锦纺新材
规格：32S×32S 130×70 63 英寸
计量单位：米（现货 3212 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 988, DATE_SUB(NOW(), INTERVAL 29 DAY), NOW(), 0),
(1008, 2, 4, 2, '涤棉坯布 T65/C35 45S×45S 96×72', '恒溢纺织', 'T65/C35 45S×45S 96×72', 6.8, '[{"qty":100,"price":6.8}]', 664, '', '品牌：恒溢纺织
规格：T65/C35 45S×45S 96×72
计量单位：米（现货 664 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 139, DATE_SUB(NOW(), INTERVAL 36 DAY), NOW(), 0),
(1009, 1, 5, 2, '涤纶短纤 1.56D×38mm 大有光', '恒溢纺织', '1.56D×38mm 大有光', 6850, '[{"qty":5,"price":6850},{"qty":1000,"price":6576}]', 435, '', '品牌：恒溢纺织
规格：1.56D×38mm 大有光
计量单位：吨（现货 435 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 545, DATE_SUB(NOW(), INTERVAL 43 DAY), NOW(), 0),
(1010, 2, 5, 2, '精梳纯棉纱 JC40S 单股筒纱', '锦纺新材', 'JC40S 单股筒纱', 24500, '[{"qty":2,"price":24500}]', 8, '', '品牌：锦纺新材
规格：JC40S 单股筒纱
计量单位：吨（现货 8 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 18, DATE_SUB(NOW(), INTERVAL 50 DAY), NOW(), 0),
(1011, 1, 6, 2, '涤纶缝纫线 40S/2 5000 码宝塔线', '锦纺新材', '40S/2 5000 码宝塔线', 7.5, '[{"qty":100,"price":7.5},{"qty":1000,"price":7.2}]', 3318, '', '品牌：锦纺新材
规格：40S/2 5000 码宝塔线
计量单位：个（现货 3318 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1182, DATE_SUB(NOW(), INTERVAL 57 DAY), NOW(), 0),
(1012, 2, 6, 2, '树脂衬布 有胶衬 幅宽 100cm', '恒溢纺织', '有胶衬 幅宽 100cm', 4.2, '[{"qty":100,"price":4.2}]', 746, '', '品牌：恒溢纺织
规格：有胶衬 幅宽 100cm
计量单位：米（现货 746 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 201, DATE_SUB(NOW(), INTERVAL 4 DAY), NOW(), 0),
(1013, 1, 7, 3, '工业级碳酸钠 99.2% 重质', '汇润化工', '99.2% 重质', 1780, '[{"qty":5,"price":1780},{"qty":1000,"price":1708.8}]', 529, '', '品牌：汇润化工
规格：99.2% 重质
计量单位：吨（现货 529 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 723, DATE_SUB(NOW(), INTERVAL 11 DAY), NOW(), 0),
(1014, 2, 7, 3, '工业级氢氧化钠 99% 片碱', '澜化科技', '99% 片碱', 2850, '[{"qty":5,"price":2850}]', 359, '', '品牌：澜化科技
规格：99% 片碱
计量单位：吨（现货 359 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 70, DATE_SUB(NOW(), INTERVAL 18 DAY), NOW(), 0),
(1015, 1, 8, 3, '抗磨液压油 L-HM46 18L/桶', '澜化科技', 'L-HM46 18L/桶', 358, '[{"qty":100,"price":358},{"qty":1000,"price":343.68}]', 3424, '', '品牌：澜化科技
规格：L-HM46 18L/桶
计量单位：桶（现货 3424 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1376, DATE_SUB(NOW(), INTERVAL 25 DAY), NOW(), 0),
(1016, 2, 8, 3, '重负荷齿轮油 L-CKD220 18L/桶', '汇润化工', 'L-CKD220 18L/桶', 465, '[{"qty":100,"price":465}]', 828, '', '品牌：汇润化工
规格：L-CKD220 18L/桶
计量单位：桶（现货 828 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 263, DATE_SUB(NOW(), INTERVAL 32 DAY), NOW(), 0),
(1017, 1, 9, 4, '贴片电阻 0805 10KΩ ±1% 1/8W', '启明微电', '0805 10KΩ ±1% 1/8W', 0.02, '[{"qty":100,"price":0.02},{"qty":1000,"price":0.02}]', 3477, '', '品牌：启明微电
规格：0805 10KΩ ±1% 1/8W
计量单位：颗（现货 3477 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1473, DATE_SUB(NOW(), INTERVAL 39 DAY), NOW(), 0),
(1018, 2, 9, 4, '贴片电容 0805 100nF 50V X7R', '澜芯电子', '0805 100nF 50V X7R', 0.035, '[{"qty":100,"price":0.035}]', 869, '', '品牌：澜芯电子
规格：0805 100nF 50V X7R
计量单位：颗（现货 869 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 294, DATE_SUB(NOW(), INTERVAL 46 DAY), NOW(), 0),
(1019, 1, 10, 4, '板对板连接器 BTB 0.8mm 40P', '澜芯电子', 'BTB 0.8mm 40P', 1.85, '[{"qty":100,"price":1.85},{"qty":1000,"price":1.78}]', 3530, '', '品牌：澜芯电子
规格：BTB 0.8mm 40P
计量单位：个（现货 3530 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1570, DATE_SUB(NOW(), INTERVAL 53 DAY), NOW(), 0),
(1020, 2, 10, 4, '端子线束 UL1007 22AWG 150mm 双头', '启明微电', 'UL1007 22AWG 150mm 双头', 0.86, '[{"qty":100,"price":0.86}]', 910, '', '品牌：启明微电
规格：UL1007 22AWG 150mm 双头
计量单位：条（现货 910 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 325, DATE_SUB(NOW(), INTERVAL 0 DAY), NOW(), 0);
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1021, 1, 11, 4, 'IPS 硬屏 10.1 寸 1280×800', '启明微电', '硬屏 10.1 寸 1280×800', 158, '[{"qty":100,"price":158},{"qty":1000,"price":151.68}]', 3583, '', '品牌：启明微电
规格：硬屏 10.1 寸 1280×800
计量单位：片（现货 3583 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1667, DATE_SUB(NOW(), INTERVAL 7 DAY), NOW(), 0),
(1022, 2, 11, 4, 'TFT LCD 4.3 寸 480×272', '澜芯电子', 'LCD 4.3 寸 480×272', 52, '[{"qty":100,"price":52}]', 951, '', '品牌：澜芯电子
规格：LCD 4.3 寸 480×272
计量单位：片（现货 951 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 356, DATE_SUB(NOW(), INTERVAL 14 DAY), NOW(), 0),
(1023, 1, 12, 5, 'Q235 连铸方坯 150×150', '澜冶金属', '连铸方坯 150×150', 3180, '[{"qty":5,"price":3180},{"qty":1000,"price":3052.8}]', 764, '', '品牌：澜冶金属
规格：连铸方坯 150×150
计量单位：吨（现货 764 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 468, DATE_SUB(NOW(), INTERVAL 21 DAY), NOW(), 0),
(1024, 2, 12, 5, 'Z18 铸造生铁 10kg 锭', '鑫峰冶金', '铸造生铁 10kg 锭', 2980, '[{"qty":5,"price":2980}]', 544, '', '品牌：鑫峰冶金
规格：铸造生铁 10kg 锭
计量单位：吨（现货 544 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 89, DATE_SUB(NOW(), INTERVAL 28 DAY), NOW(), 0),
(1025, 1, 13, 5, 'Q235B 连铸板坯 200×1250', '鑫峰冶金', '连铸板坯 200×1250', 3250, '[{"qty":5,"price":3250},{"qty":1000,"price":3120}]', 811, '', '品牌：鑫峰冶金
规格：连铸板坯 200×1250
计量单位：吨（现货 811 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 557, DATE_SUB(NOW(), INTERVAL 35 DAY), NOW(), 0),
(1026, 2, 13, 5, '45# 连铸圆坯 Φ180', '澜冶金属', '连铸圆坯 Φ180', 3580, '[{"qty":5,"price":3580}]', 581, '', '品牌：澜冶金属
规格：连铸圆坯 Φ180
计量单位：吨（现货 581 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 16, DATE_SUB(NOW(), INTERVAL 42 DAY), NOW(), 0),
(1027, 1, 14, 5, '炼钢生铁 L08', '澜冶金属', 'L08', 2860, '[{"qty":5,"price":2860},{"qty":1000,"price":2745.6}]', 858, '', '品牌：澜冶金属
规格：L08
计量单位：吨（现货 858 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 646, DATE_SUB(NOW(), INTERVAL 49 DAY), NOW(), 0),
(1028, 2, 14, 5, '球墨铸铁用生铁 Q12', '鑫峰冶金', 'Q12', 3050, '[{"qty":5,"price":3050}]', 618, '', '品牌：鑫峰冶金
规格：Q12
计量单位：吨（现货 618 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 39, DATE_SUB(NOW(), INTERVAL 56 DAY), NOW(), 0),
(1029, 1, 15, 5, '40Cr 合金钢坯 90×90', '鑫峰冶金', '合金钢坯 90×90', 4280, '[{"qty":5,"price":4280},{"qty":1000,"price":4108.8}]', 905, '', '品牌：鑫峰冶金
规格：合金钢坯 90×90
计量单位：吨（现货 905 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 735, DATE_SUB(NOW(), INTERVAL 3 DAY), NOW(), 0),
(1030, 2, 15, 5, '20CrMnTi 齿轮钢坯', '澜冶金属', '齿轮钢坯', 4680, '[{"qty":5,"price":4680}]', 655, '', '品牌：澜冶金属
规格：齿轮钢坯
计量单位：吨（现货 655 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 62, DATE_SUB(NOW(), INTERVAL 10 DAY), NOW(), 0),
(1031, 1, 16, 5, 'A00 铝锭 99.7%', '澜冶金属', '铝锭 99.7%', 19800, '[{"qty":2,"price":19800},{"qty":5,"price":19008}]', 19, '', '品牌：澜冶金属
规格：铝锭 99.7%
计量单位：吨（现货 19 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 196, DATE_SUB(NOW(), INTERVAL 17 DAY), NOW(), 0),
(1032, 2, 16, 5, '1# 电解铜 99.95%', '鑫峰冶金', '电解铜 99.95%', 68500, '[{"qty":2,"price":68500}]', 31, '', '品牌：鑫峰冶金
规格：电解铜 99.95%
计量单位：吨（现货 31 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 32, DATE_SUB(NOW(), INTERVAL 24 DAY), NOW(), 0),
(1033, 1, 17, 5, '6063-T5 铝型材 幕墙料', '鑫峰冶金', '铝型材 幕墙料', 21500, '[{"qty":2,"price":21500},{"qty":5,"price":20640}]', 48, '', '品牌：鑫峰冶金
规格：铝型材 幕墙料
计量单位：吨（现货 48 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 27, DATE_SUB(NOW(), INTERVAL 31 DAY), NOW(), 0),
(1034, 2, 17, 5, '1060 铝板 2.0×1220×2440', '澜冶金属', '铝板 2.0×1220×2440', 19850, '[{"qty":2,"price":19850}]', 14, '', '品牌：澜冶金属
规格：铝板 2.0×1220×2440
计量单位：吨（现货 14 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 12, DATE_SUB(NOW(), INTERVAL 38 DAY), NOW(), 0),
(1035, 1, 18, 5, 'T2 紫铜排 TMY 100×10', '澜冶金属', '紫铜排 TMY 100×10', 69200, '[{"qty":2,"price":69200},{"qty":5,"price":66432}]', 32, '', '品牌：澜冶金属
规格：紫铜排 TMY 100×10
计量单位：吨（现货 32 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 98, DATE_SUB(NOW(), INTERVAL 45 DAY), NOW(), 0),
(1036, 2, 18, 5, 'H62 黄铜棒 Φ20', '鑫峰冶金', '黄铜棒 Φ20', 46500, '[{"qty":2,"price":46500}]', 27, '', '品牌：鑫峰冶金
规格：黄铜棒 Φ20
计量单位：吨（现货 27 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 31, DATE_SUB(NOW(), INTERVAL 52 DAY), NOW(), 0),
(1037, 1, 19, 5, '0# 锌锭 99.995%', '鑫峰冶金', '锌锭 99.995%', 21800, '[{"qty":2,"price":21800},{"qty":5,"price":20928}]', 16, '', '品牌：鑫峰冶金
规格：锌锭 99.995%
计量单位：吨（现货 16 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 169, DATE_SUB(NOW(), INTERVAL 59 DAY), NOW(), 0),
(1038, 2, 19, 5, '锌合金 ZAMAK-3 锭', '澜冶金属', 'ZAMAK-3 锭', 22300, '[{"qty":2,"price":22300}]', 10, '', '品牌：澜冶金属
规格：ZAMAK-3 锭
计量单位：吨（现货 10 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 11, DATE_SUB(NOW(), INTERVAL 6 DAY), NOW(), 0),
(1039, 1, 20, 5, '镀锌钢丝 70# Φ2.8mm', '澜冶金属', '70# Φ2.8mm', 4850, '[{"qty":5,"price":4850},{"qty":1000,"price":4656}]', 1140, '', '品牌：澜冶金属
规格：70# Φ2.8mm
计量单位：吨（现货 1140 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 480, DATE_SUB(NOW(), INTERVAL 13 DAY), NOW(), 0),
(1040, 2, 20, 5, '不锈钢冷轧板 304 2B 1.5×1219×2438', '鑫峰冶金', '304 2B 1.5×1219×2438', 15200, '[{"qty":2,"price":15200}]', 23, '', '品牌：鑫峰冶金
规格：304 2B 1.5×1219×2438
计量单位：吨（现货 23 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 30, DATE_SUB(NOW(), INTERVAL 20 DAY), NOW(), 0);
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1041, 1, 21, 5, '镀锌铁丝 BWG16 50kg/捆', '鑫峰冶金', 'BWG16 50kg/捆', 4650, '[{"qty":5,"price":4650},{"qty":1000,"price":4464}]', 1187, '', '品牌：鑫峰冶金
规格：BWG16 50kg/捆
计量单位：吨（现货 1187 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 569, DATE_SUB(NOW(), INTERVAL 27 DAY), NOW(), 0),
(1042, 2, 21, 5, '不锈钢丝 304 Φ1.0mm 软态', '澜冶金属', '304 Φ1.0mm 软态', 16800, '[{"qty":2,"price":16800}]', 6, '', '品牌：澜冶金属
规格：304 Φ1.0mm 软态
计量单位：吨（现货 6 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 10, DATE_SUB(NOW(), INTERVAL 34 DAY), NOW(), 0),
(1043, 1, 22, 5, 'Q235B 热轧开平板 δ6×1500×3000', '澜冶金属', '热轧开平板 δ6×1500×3000', 3990, '[{"qty":5,"price":3990},{"qty":1000,"price":3830.4}]', 1234, '', '品牌：澜冶金属
规格：热轧开平板 δ6×1500×3000
计量单位：吨（现货 1234 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 658, DATE_SUB(NOW(), INTERVAL 41 DAY), NOW(), 0),
(1044, 2, 22, 5, 'SPCC 冷轧板 1.0×1250×2500', '鑫峰冶金', '冷轧板 1.0×1250×2500', 4680, '[{"qty":5,"price":4680}]', 914, '', '品牌：鑫峰冶金
规格：冷轧板 1.0×1250×2500
计量单位：吨（现货 914 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 31, DATE_SUB(NOW(), INTERVAL 48 DAY), NOW(), 0),
(1045, 1, 23, 5, 'Q235 法兰盘 DN50 PN16 锻打', '鑫峰冶金', '法兰盘 DN50 PN16 锻打', 46, '[{"qty":100,"price":46},{"qty":1000,"price":44.16}]', 4219, '', '品牌：鑫峰冶金
规格：法兰盘 DN50 PN16 锻打
计量单位：个（现货 4219 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2831, DATE_SUB(NOW(), INTERVAL 55 DAY), NOW(), 0),
(1046, 2, 23, 5, '精密 CNC 加工件 304 按图定制', '澜冶金属', 'CNC 加工件 304 按图定制', 12.5, '[{"qty":100,"price":12.5}]', 1443, '', '品牌：澜冶金属
规格：CNC 加工件 304 按图定制
计量单位：件（现货 1443 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 728, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW(), 0),
(1047, 1, 24, 6, '数控车床 CK6140 广数系统', '澜机装备', 'CK6140 广数系统', 68500, '[{"qty":2,"price":68500},{"qty":5,"price":65760}]', 26, '', '品牌：澜机装备
规格：CK6140 广数系统
计量单位：台（现货 26 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 44, DATE_SUB(NOW(), INTERVAL 9 DAY), NOW(), 0),
(1048, 2, 24, 6, '摇臂钻床 Z3040×13', '力锋机械', 'Z3040×13', 28500, '[{"qty":2,"price":28500}]', 15, '', '品牌：力锋机械
规格：Z3040×13
计量单位：台（现货 15 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 28, DATE_SUB(NOW(), INTERVAL 16 DAY), NOW(), 0),
(1049, 1, 25, 6, '数控车床 CK6150 FANUC 系统', '力锋机械', 'CK6150 FANUC 系统', 98500, '[{"qty":2,"price":98500},{"qty":5,"price":94560}]', 10, '', '品牌：力锋机械
规格：CK6150 FANUC 系统
计量单位：台（现货 10 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 115, DATE_SUB(NOW(), INTERVAL 23 DAY), NOW(), 0),
(1050, 2, 25, 6, '数控铣床 XK7124 三轴', '澜机装备', 'XK7124 三轴', 78500, '[{"qty":2,"price":78500}]', 28, '', '品牌：澜机装备
规格：XK7124 三轴
计量单位：台（现货 28 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 8, DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
(1051, 1, 26, 6, '立式加工中心 VMC850 线轨圆盘刀库', '澜机装备', 'VMC850 线轨圆盘刀库', 138000, '[{"qty":2,"price":138000},{"qty":5,"price":132480}]', 39, '', '品牌：澜机装备
规格：VMC850 线轨圆盘刀库
计量单位：台（现货 39 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 186, DATE_SUB(NOW(), INTERVAL 37 DAY), NOW(), 0),
(1052, 2, 26, 6, '卧式加工中心 HMC500', '力锋机械', 'HMC500', 365000, '[{"qty":2,"price":365000}]', 11, '', '品牌：力锋机械
规格：HMC500
计量单位：台（现货 11 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 27, DATE_SUB(NOW(), INTERVAL 44 DAY), NOW(), 0),
(1053, 1, 27, 6, '硬质合金立铣刀 Φ12×45 四刃', '力锋机械', 'Φ12×45 四刃', 32, '[{"qty":100,"price":32},{"qty":1000,"price":30.72}]', 4431, '', '品牌：力锋机械
规格：Φ12×45 四刃
计量单位：支（现货 4431 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 3219, DATE_SUB(NOW(), INTERVAL 51 DAY), NOW(), 0),
(1054, 2, 27, 6, '0-150mm 数显卡尺 0.01mm', '澜机装备', '数显卡尺 0.01mm', 46, '[{"qty":100,"price":46}]', 1607, '', '品牌：澜机装备
规格：数显卡尺 0.01mm
计量单位：把（现货 1607 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 66, DATE_SUB(NOW(), INTERVAL 58 DAY), NOW(), 0),
(1055, 1, 28, 6, '履带式液压挖掘机 20 吨级', '澜机装备', '20 吨级', 628000, '[{"qty":2,"price":628000},{"qty":5,"price":602880}]', 7, '', '品牌：澜机装备
规格：20 吨级
计量单位：台（现货 7 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 88, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW(), 0),
(1056, 2, 28, 6, '轮式装载机 ZL50 5 吨级', '力锋机械', 'ZL50 5 吨级', 268000, '[{"qty":2,"price":268000}]', 7, '', '品牌：力锋机械
规格：ZL50 5 吨级
计量单位：台（现货 7 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 26, DATE_SUB(NOW(), INTERVAL 12 DAY), NOW(), 0),
(1057, 1, 29, 6, '小型液压挖掘机 6 吨级 无尾回转', '力锋机械', '6 吨级 无尾回转', 198000, '[{"qty":2,"price":198000},{"qty":5,"price":190080}]', 36, '', '品牌：力锋机械
规格：6 吨级 无尾回转
计量单位：台（现货 36 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 159, DATE_SUB(NOW(), INTERVAL 19 DAY), NOW(), 0),
(1058, 2, 29, 6, '轮式挖掘机 8 吨级', '澜机装备', '8 吨级', 236000, '[{"qty":2,"price":236000}]', 20, '', '品牌：澜机装备
规格：8 吨级
计量单位：台（现货 20 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 6, DATE_SUB(NOW(), INTERVAL 26 DAY), NOW(), 0),
(1059, 1, 30, 6, '汽车起重机 8 吨四节臂', '澜机装备', '8 吨四节臂', 298000, '[{"qty":2,"price":298000},{"qty":5,"price":286080}]', 20, '', '品牌：澜机装备
规格：8 吨四节臂
计量单位：台（现货 20 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 230, DATE_SUB(NOW(), INTERVAL 33 DAY), NOW(), 0),
(1060, 2, 30, 6, '电动葫芦 CD1 2t×9m', '力锋机械', 'CD1 2t×9m', 2350, '[{"qty":5,"price":2350}]', 310, '', '品牌：力锋机械
规格：CD1 2t×9m
计量单位：台（现货 310 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 23, DATE_SUB(NOW(), INTERVAL 40 DAY), NOW(), 0);
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1061, 1, 31, 6, '滑移装载机 0.6m3 快换属具', '力锋机械', '0.6m3 快换属具', 218000, '[{"qty":2,"price":218000},{"qty":5,"price":209280}]', 49, '', '品牌：力锋机械
规格：0.6m3 快换属具
计量单位：台（现货 49 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 61, DATE_SUB(NOW(), INTERVAL 47 DAY), NOW(), 0),
(1062, 2, 31, 6, '小型装载机 ZL18 1 吨级', '澜机装备', 'ZL18 1 吨级', 58600, '[{"qty":2,"price":58600}]', 16, '', '品牌：澜机装备
规格：ZL18 1 吨级
计量单位：台（现货 16 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 5, DATE_SUB(NOW(), INTERVAL 54 DAY), NOW(), 0),
(1063, 1, 32, 6, '深沟球轴承 6205-2RS GCr15', '澜机装备', '6205-2RS GCr15', 3.2, '[{"qty":100,"price":3.2},{"qty":1000,"price":3.07}]', 4696, '', '品牌：澜机装备
规格：6205-2RS GCr15
计量单位：个（现货 4696 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 3704, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW(), 0),
(1064, 2, 32, 6, '摆线针轮减速机 XW4-43-2.2kW', '力锋机械', 'XW4-43-2.2kW', 1280, '[{"qty":5,"price":1280}]', 384, '', '品牌：力锋机械
规格：XW4-43-2.2kW
计量单位：台（现货 384 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 69, DATE_SUB(NOW(), INTERVAL 8 DAY), NOW(), 0),
(1065, 1, 33, 6, '深沟球轴承 6204-2Z 开式', '力锋机械', '6204-2Z 开式', 2.1, '[{"qty":100,"price":2.1},{"qty":1000,"price":2.02}]', 4749, '', '品牌：力锋机械
规格：6204-2Z 开式
计量单位：个（现货 4749 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 3801, DATE_SUB(NOW(), INTERVAL 15 DAY), NOW(), 0),
(1066, 2, 33, 6, '圆锥滚子轴承 30205 单列', '澜机装备', '30205 单列', 6.8, '[{"qty":100,"price":6.8}]', 1853, '', '品牌：澜机装备
规格：30205 单列
计量单位：个（现货 1853 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 252, DATE_SUB(NOW(), INTERVAL 22 DAY), NOW(), 0),
(1067, 1, 34, 6, '直齿圆柱齿轮 45# 调质 m3 z45', '澜机装备', '45# 调质 m3 z45', 86, '[{"qty":100,"price":86},{"qty":1000,"price":82.56}]', 4802, '', '品牌：澜机装备
规格：45# 调质 m3 z45
计量单位：件（现货 4802 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 3898, DATE_SUB(NOW(), INTERVAL 29 DAY), NOW(), 0),
(1068, 2, 34, 6, '螺旋锥齿轮 20CrMnTi 渗碳 m5', '力锋机械', '20CrMnTi 渗碳 m5', 365, '[{"qty":100,"price":365}]', 1894, '', '品牌：力锋机械
规格：20CrMnTi 渗碳 m5
计量单位：件（现货 1894 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 283, DATE_SUB(NOW(), INTERVAL 36 DAY), NOW(), 0),
(1069, 1, 35, 6, '不锈钢离心泵 IH80-65-160 15kW', '力锋机械', 'IH80-65-160 15kW', 2350, '[{"qty":5,"price":2350},{"qty":1000,"price":2256}]', 1845, '', '品牌：力锋机械
规格：IH80-65-160 15kW
计量单位：台（现货 1845 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 415, DATE_SUB(NOW(), INTERVAL 43 DAY), NOW(), 0),
(1070, 2, 35, 6, '气动球阀 Q611F-16P DN25', '澜机装备', 'Q611F-16P DN25', 285, '[{"qty":100,"price":285}]', 1935, '', '品牌：澜机装备
规格：Q611F-16P DN25
计量单位：个（现货 1935 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 314, DATE_SUB(NOW(), INTERVAL 50 DAY), NOW(), 0),
(1071, 1, 36, 7, '铸铁制动盘 前轴 通风盘 Φ280', '澜途部件', '前轴 通风盘 Φ280', 86, '[{"qty":100,"price":86},{"qty":1000,"price":82.56}]', 4908, '', '品牌：澜途部件
规格：前轴 通风盘 Φ280
计量单位：件（现货 4908 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4092, DATE_SUB(NOW(), INTERVAL 57 DAY), NOW(), 0),
(1072, 2, 36, 7, '减震器总成 前双筒式 麦弗逊', '骏驰汽配', '前双筒式 麦弗逊', 126, '[{"qty":100,"price":126}]', 1976, '', '品牌：骏驰汽配
规格：前双筒式 麦弗逊
计量单位：支（现货 1976 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 345, DATE_SUB(NOW(), INTERVAL 4 DAY), NOW(), 0),
(1073, 1, 37, 7, '铝活塞 42CrMo 缸径 100mm 镶圈', '骏驰汽配', '42CrMo 缸径 100mm 镶圈', 52, '[{"qty":100,"price":52},{"qty":1000,"price":49.92}]', 4961, '', '品牌：骏驰汽配
规格：42CrMo 缸径 100mm 镶圈
计量单位：只（现货 4961 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4189, DATE_SUB(NOW(), INTERVAL 11 DAY), NOW(), 0),
(1074, 2, 37, 7, '缸套 硼铸铁 湿式 φ105', '澜途部件', '硼铸铁 湿式 φ105', 36, '[{"qty":100,"price":36}]', 2017, '', '品牌：澜途部件
规格：硼铸铁 湿式 φ105
计量单位：只（现货 2017 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 376, DATE_SUB(NOW(), INTERVAL 18 DAY), NOW(), 0),
(1075, 1, 38, 7, '转向节锻件 40Cr 正火', '澜途部件', '40Cr 正火', 168, '[{"qty":100,"price":168},{"qty":1000,"price":161.28}]', 5014, '', '品牌：澜途部件
规格：40Cr 正火
计量单位：件（现货 5014 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4286, DATE_SUB(NOW(), INTERVAL 25 DAY), NOW(), 0),
(1076, 2, 38, 7, '钢板弹簧总成 前簧 10 片 60Si2Mn', '骏驰汽配', '前簧 10 片 60Si2Mn', 285, '[{"qty":100,"price":285}]', 2058, '', '品牌：骏驰汽配
规格：前簧 10 片 60Si2Mn
计量单位：件（现货 2058 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 407, DATE_SUB(NOW(), INTERVAL 32 DAY), NOW(), 0),
(1077, 1, 39, 7, '冷轧冲压车门内板 DC04 电泳', '骏驰汽配', 'DC04 电泳', 216, '[{"qty":100,"price":216},{"qty":1000,"price":207.36}]', 5067, '', '品牌：骏驰汽配
规格：DC04 电泳
计量单位：件（现货 5067 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4383, DATE_SUB(NOW(), INTERVAL 39 DAY), NOW(), 0),
(1078, 2, 39, 7, '保险杠骨架 PP+GF30 注塑', '澜途部件', 'PP+GF30 注塑', 86, '[{"qty":100,"price":86}]', 2099, '', '品牌：澜途部件
规格：PP+GF30 注塑
计量单位：件（现货 2099 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 438, DATE_SUB(NOW(), INTERVAL 46 DAY), NOW(), 0),
(1079, 1, 40, 7, '永磁同步驱动电机 70kW 乘用车', '澜途部件', '70kW 乘用车', 4850, '[{"qty":5,"price":4850},{"qty":1000,"price":4656}]', 2080, '', '品牌：澜途部件
规格：70kW 乘用车
计量单位：台（现货 2080 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 160, DATE_SUB(NOW(), INTERVAL 53 DAY), NOW(), 0),
(1080, 2, 40, 7, '整车控制器 VCU 商用车', '骏驰汽配', 'VCU 商用车', 2650, '[{"qty":5,"price":2650}]', 680, '', '品牌：骏驰汽配
规格：VCU 商用车
计量单位：台（现货 680 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 61, DATE_SUB(NOW(), INTERVAL 0 DAY), NOW(), 0);
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1081, 1, 41, 7, '驱动电机总成 130kW 扁线油冷', '骏驰汽配', '130kW 扁线油冷', 6850, '[{"qty":5,"price":6850},{"qty":1000,"price":6576}]', 2127, '', '品牌：骏驰汽配
规格：130kW 扁线油冷
计量单位：台（现货 2127 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 249, DATE_SUB(NOW(), INTERVAL 7 DAY), NOW(), 0),
(1082, 2, 41, 7, '驱动电机旋变传感器', '澜途部件', '驱动电机旋变传感器', 86, '[{"qty":100,"price":86}]', 2181, '', '品牌：澜途部件
规格：驱动电机旋变传感器
计量单位：只（现货 2181 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 500, DATE_SUB(NOW(), INTERVAL 14 DAY), NOW(), 0),
(1083, 1, 42, 7, '电机控制器 MCU 180kW SiC 模组', '澜途部件', 'MCU 180kW SiC 模组', 3850, '[{"qty":5,"price":3850},{"qty":1000,"price":3696}]', 2174, '', '品牌：澜途部件
规格：MCU 180kW SiC 模组
计量单位：台（现货 2174 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 338, DATE_SUB(NOW(), INTERVAL 21 DAY), NOW(), 0),
(1084, 2, 42, 7, '车载 OBC 3.3kW 双向', '骏驰汽配', 'OBC 3.3kW 双向', 985, '[{"qty":100,"price":985}]', 2222, '', '品牌：骏驰汽配
规格：OBC 3.3kW 双向
计量单位：台（现货 2222 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 531, DATE_SUB(NOW(), INTERVAL 28 DAY), NOW(), 0),
(1085, 1, 43, 7, '电子水泵 12V 无刷 60W', '骏驰汽配', '12V 无刷 60W', 86, '[{"qty":100,"price":86},{"qty":1000,"price":82.56}]', 5279, '', '品牌：骏驰汽配
规格：12V 无刷 60W
计量单位：只（现货 5279 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4771, DATE_SUB(NOW(), INTERVAL 35 DAY), NOW(), 0),
(1086, 2, 43, 7, '电动空调压缩机 电动涡旋 5kW R134a', '澜途部件', '电动涡旋 5kW R134a', 1260, '[{"qty":5,"price":1260}]', 791, '', '品牌：澜途部件
规格：电动涡旋 5kW R134a
计量单位：台（现货 791 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 34, DATE_SUB(NOW(), INTERVAL 42 DAY), NOW(), 0),
(1087, 1, 44, 7, '全合成机油 5W-40 4L 装', '澜途部件', '5W-40 4L 装', 98, '[{"qty":100,"price":98},{"qty":1000,"price":94.08}]', 5332, '', '品牌：澜途部件
规格：5W-40 4L 装
计量单位：桶（现货 5332 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 668, DATE_SUB(NOW(), INTERVAL 49 DAY), NOW(), 0),
(1088, 2, 44, 7, '乘用车陶瓷刹车片 前轴 FF 级', '骏驰汽配', '前轴 FF 级', 58, '[{"qty":100,"price":58}]', 2304, '', '品牌：骏驰汽配
规格：前轴 FF 级
计量单位：套（现货 2304 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 593, DATE_SUB(NOW(), INTERVAL 56 DAY), NOW(), 0),
(1089, 1, 45, 7, '全钢子午线卡车胎 12R22.5 18PR', '骏驰汽配', '12R22.5 18PR', 985, '[{"qty":100,"price":985},{"qty":1000,"price":945.6}]', 5385, '', '品牌：骏驰汽配
规格：12R22.5 18PR
计量单位：条（现货 5385 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 765, DATE_SUB(NOW(), INTERVAL 3 DAY), NOW(), 0),
(1090, 2, 45, 7, '乘用车半钢胎 205/55R16 91V', '澜途部件', '205/55R16 91V', 226, '[{"qty":100,"price":226}]', 2345, '', '品牌：澜途部件
规格：205/55R16 91V
计量单位：条（现货 2345 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 624, DATE_SUB(NOW(), INTERVAL 10 DAY), NOW(), 0),
(1091, 1, 46, 7, '齿轮油 GL-5 85W-140 18L', '澜途部件', 'GL-5 85W-140 18L', 465, '[{"qty":100,"price":465},{"qty":1000,"price":446.4}]', 5438, '', '品牌：澜途部件
规格：GL-5 85W-140 18L
计量单位：桶（现货 5438 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 862, DATE_SUB(NOW(), INTERVAL 17 DAY), NOW(), 0),
(1092, 2, 46, 7, '刹车油 DOT4 1L 装', '骏驰汽配', 'DOT4 1L 装', 22, '[{"qty":100,"price":22}]', 2386, '', '品牌：骏驰汽配
规格：DOT4 1L 装
计量单位：瓶（现货 2386 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 655, DATE_SUB(NOW(), INTERVAL 24 DAY), NOW(), 0),
(1093, 1, 47, 7, '轮胎拆装机 24 寸大车用', '骏驰汽配', '24 寸大车用', 2850, '[{"qty":5,"price":2850},{"qty":1000,"price":2736}]', 2409, '', '品牌：骏驰汽配
规格：24 寸大车用
计量单位：台（现货 2409 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 783, DATE_SUB(NOW(), INTERVAL 31 DAY), NOW(), 0),
(1094, 2, 47, 7, '汽车故障诊断仪 OBD 全系统', '澜途部件', 'OBD 全系统', 586, '[{"qty":100,"price":586}]', 2427, '', '品牌：澜途部件
规格：OBD 全系统
计量单位：台（现货 2427 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 686, DATE_SUB(NOW(), INTERVAL 38 DAY), NOW(), 0),
(1095, 1, 48, 8, '干式变压器 SCB13-800/10 0.4kV 全铜', '澜电设备', 'SCB13-800/10 0.4kV 全铜', 76500, '[{"qty":2,"price":76500},{"qty":5,"price":73440}]', 47, '', '品牌：澜电设备
规格：SCB13-800/10 0.4kV 全铜
计量单位：台（现货 47 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 68, DATE_SUB(NOW(), INTERVAL 45 DAY), NOW(), 0),
(1096, 2, 48, 8, '箱式变电站 YBW-12/0.4 630kVA', '恒力电气', 'YBW-12/0.4 630kVA', 98500, '[{"qty":2,"price":98500}]', 27, '', '品牌：恒力电气
规格：YBW-12/0.4 630kVA
计量单位：台（现货 27 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 16, DATE_SUB(NOW(), INTERVAL 52 DAY), NOW(), 0),
(1097, 1, 49, 8, '油浸式变压器 S13-400/10 全铜', '恒力电气', 'S13-400/10 全铜', 26500, '[{"qty":2,"price":26500},{"qty":5,"price":25440}]', 31, '', '品牌：恒力电气
规格：S13-400/10 全铜
计量单位：台（现货 31 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 139, DATE_SUB(NOW(), INTERVAL 59 DAY), NOW(), 0),
(1098, 2, 49, 8, '组合式变压器 ZGS11-630/10', '澜电设备', 'ZGS11-630/10', 58500, '[{"qty":2,"price":58500}]', 10, '', '品牌：澜电设备
规格：ZGS11-630/10
计量单位：台（现货 10 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 35, DATE_SUB(NOW(), INTERVAL 6 DAY), NOW(), 0),
(1099, 1, 50, 8, 'KYN28A-12 高压开关柜 中置式', '澜电设备', '高压开关柜 中置式', 38500, '[{"qty":2,"price":38500},{"qty":5,"price":36960}]', 15, '', '品牌：澜电设备
规格：高压开关柜 中置式
计量单位：面（现货 15 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 210, DATE_SUB(NOW(), INTERVAL 13 DAY), NOW(), 0),
(1100, 2, 50, 8, 'GGD3 低压固定式开关柜', '恒力电气', '低压固定式开关柜', 8650, '[{"qty":5,"price":8650}]', 150, '', '品牌：恒力电气
规格：低压固定式开关柜
计量单位：面（现货 150 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 99, DATE_SUB(NOW(), INTERVAL 20 DAY), NOW(), 0);
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1101, 1, 51, 8, '户外防雨配电箱 JP 柜 250kVA', '恒力电气', 'JP 柜 250kVA', 3860, '[{"qty":5,"price":3860},{"qty":1000,"price":3705.6}]', 2597, '', '品牌：恒力电气
规格：JP 柜 250kVA
计量单位：台（现货 2597 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 439, DATE_SUB(NOW(), INTERVAL 27 DAY), NOW(), 0),
(1102, 2, 51, 8, '照明配电箱 PZ30 12 回路', '澜电设备', 'PZ30 12 回路', 86, '[{"qty":100,"price":86}]', 2591, '', '品牌：澜电设备
规格：PZ30 12 回路
计量单位：台（现货 2591 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 24, DATE_SUB(NOW(), INTERVAL 34 DAY), NOW(), 0),
(1103, 1, 52, 8, '电力电缆 YJV 0.6/1kV 4×95', '澜电设备', 'YJV 0.6/1kV 4×95', 142, '[{"qty":100,"price":142},{"qty":1000,"price":136.32}]', 5756, '', '品牌：澜电设备
规格：YJV 0.6/1kV 4×95
计量单位：米（现货 5756 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1444, DATE_SUB(NOW(), INTERVAL 41 DAY), NOW(), 0),
(1104, 2, 52, 8, '铝合金电缆 YJLHV 4×120', '恒力电气', 'YJLHV 4×120', 86, '[{"qty":100,"price":86}]', 2632, '', '品牌：恒力电气
规格：YJLHV 4×120
计量单位：米（现货 2632 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 55, DATE_SUB(NOW(), INTERVAL 48 DAY), NOW(), 0),
(1105, 1, 53, 8, 'YJV22 铠装电缆 8.7/15kV 3×70', '恒力电气', '铠装电缆 8.7/15kV 3×70', 216, '[{"qty":100,"price":216},{"qty":1000,"price":207.36}]', 5809, '', '品牌：恒力电气
规格：铠装电缆 8.7/15kV 3×70
计量单位：米（现货 5809 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1541, DATE_SUB(NOW(), INTERVAL 55 DAY), NOW(), 0),
(1106, 2, 53, 8, 'BV 布电线 2.5mm2 100m/卷', '澜电设备', '布电线 2.5mm2 100m/卷', 128, '[{"qty":100,"price":128}]', 2673, '', '品牌：澜电设备
规格：布电线 2.5mm2 100m/卷
计量单位：卷（现货 2673 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 86, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW(), 0),
(1107, 1, 54, 8, 'KVV 控制电缆 450/750V 14×1.5', '澜电设备', '控制电缆 450/750V 14×1.5', 16.8, '[{"qty":100,"price":16.8},{"qty":1000,"price":16.13}]', 5862, '', '品牌：澜电设备
规格：控制电缆 450/750V 14×1.5
计量单位：米（现货 5862 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1638, DATE_SUB(NOW(), INTERVAL 9 DAY), NOW(), 0),
(1108, 2, 54, 8, 'KVVP 屏蔽控制电缆 10×2.5', '恒力电气', '屏蔽控制电缆 10×2.5', 26.5, '[{"qty":100,"price":26.5}]', 2714, '', '品牌：恒力电气
规格：屏蔽控制电缆 10×2.5
计量单位：米（现货 2714 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 117, DATE_SUB(NOW(), INTERVAL 16 DAY), NOW(), 0),
(1109, 1, 55, 8, '氟塑料高温电缆 AF-250 2×1.5', '恒力电气', 'AF-250 2×1.5', 12.6, '[{"qty":100,"price":12.6},{"qty":1000,"price":12.1}]', 5915, '', '品牌：恒力电气
规格：AF-250 2×1.5
计量单位：米（现货 5915 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1735, DATE_SUB(NOW(), INTERVAL 23 DAY), NOW(), 0),
(1110, 2, 55, 8, '阻燃耐火电缆 WDZN-YJV 4×35', '澜电设备', 'WDZN-YJV 4×35', 98, '[{"qty":100,"price":98}]', 2755, '', '品牌：澜电设备
规格：WDZN-YJV 4×35
计量单位：米（现货 2755 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 148, DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
(1111, 1, 56, 8, '柴油发电机组 100kW', '澜电设备', '100kW', 86500, '[{"qty":2,"price":86500},{"qty":5,"price":83040}]', 9, '', '品牌：澜电设备
规格：100kW
计量单位：台（现货 9 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 156, DATE_SUB(NOW(), INTERVAL 37 DAY), NOW(), 0),
(1112, 2, 56, 8, '工商业储能柜 215kWh 液冷', '恒力电气', '215kWh 液冷', 128000, '[{"qty":2,"price":128000}]', 11, '', '品牌：恒力电气
规格：215kWh 液冷
计量单位：台（现货 11 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 12, DATE_SUB(NOW(), INTERVAL 44 DAY), NOW(), 0),
(1113, 1, 57, 8, '光伏并网逆变器 110kW 组串式', '恒力电气', '110kW 组串式', 16800, '[{"qty":2,"price":16800},{"qty":5,"price":16128}]', 38, '', '品牌：恒力电气
规格：110kW 组串式
计量单位：台（现货 38 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 227, DATE_SUB(NOW(), INTERVAL 51 DAY), NOW(), 0),
(1114, 2, 57, 8, '风力发电机组 5kW 水平轴', '澜电设备', '5kW 水平轴', 58600, '[{"qty":2,"price":58600}]', 24, '', '品牌：澜电设备
规格：5kW 水平轴
计量单位：台（现货 24 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 31, DATE_SUB(NOW(), INTERVAL 58 DAY), NOW(), 0),
(1115, 1, 58, 8, '磷酸铁锂储能电芯 280Ah 3.2V', '澜电设备', '280Ah 3.2V', 452, '[{"qty":100,"price":452},{"qty":1000,"price":433.92}]', 6074, '', '品牌：澜电设备
规格：280Ah 3.2V
计量单位：芯（现货 6074 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2026, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW(), 0),
(1116, 2, 58, 8, '户用储能一体机 5kW/10kWh', '恒力电气', '5kW/10kWh', 12800, '[{"qty":2,"price":12800}]', 7, '', '品牌：恒力电气
规格：5kW/10kWh
计量单位：台（现货 7 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 11, DATE_SUB(NOW(), INTERVAL 12 DAY), NOW(), 0),
(1117, 1, 59, 8, '直流快充桩 120kW 双枪', '恒力电气', '120kW 双枪', 28600, '[{"qty":2,"price":28600},{"qty":5,"price":27456}]', 6, '', '品牌：恒力电气
规格：120kW 双枪
计量单位：台（现货 6 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 129, DATE_SUB(NOW(), INTERVAL 19 DAY), NOW(), 0),
(1118, 2, 59, 8, '交流充电桩 7kW 单枪 计费', '澜电设备', '7kW 单枪 计费', 1260, '[{"qty":5,"price":1260}]', 483, '', '品牌：澜电设备
规格：7kW 单枪 计费
计量单位：台（现货 483 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 18, DATE_SUB(NOW(), INTERVAL 26 DAY), NOW(), 0),
(1119, 1, 60, 9, '东北长粒香大米 一等品 25kg/袋', '澜味食品', '一等品 25kg/袋', 128, '[{"qty":100,"price":128},{"qty":1000,"price":122.88}]', 6180, '', '品牌：澜味食品
规格：一等品 25kg/袋
计量单位：袋（现货 6180 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2220, DATE_SUB(NOW(), INTERVAL 33 DAY), NOW(), 0),
(1120, 2, 60, 9, '一级压榨大豆油 5L×4/箱', '谷源食材', '5L×4/箱', 236, '[{"qty":100,"price":236}]', 2960, '', '品牌：谷源食材
规格：5L×4/箱
计量单位：箱（现货 2960 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 303, DATE_SUB(NOW(), INTERVAL 40 DAY), NOW(), 0);
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1121, 1, 61, 9, '圆粒粳米 二等品 25kg/袋', '谷源食材', '二等品 25kg/袋', 105, '[{"qty":100,"price":105},{"qty":1000,"price":100.8}]', 6233, '', '品牌：谷源食材
规格：二等品 25kg/袋
计量单位：袋（现货 6233 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2317, DATE_SUB(NOW(), INTERVAL 47 DAY), NOW(), 0),
(1122, 2, 61, 9, '红小豆 一级 25kg/编织袋', '澜味食品', '一级 25kg/编织袋', 218, '[{"qty":100,"price":218}]', 3001, '', '品牌：澜味食品
规格：一级 25kg/编织袋
计量单位：袋（现货 3001 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 334, DATE_SUB(NOW(), INTERVAL 54 DAY), NOW(), 0),
(1123, 1, 62, 9, '24 度棕榈油 精炼 200kg/桶', '澜味食品', '度棕榈油 精炼 200kg/桶', 1850, '[{"qty":5,"price":1850},{"qty":1000,"price":1776}]', 314, '', '品牌：澜味食品
规格：度棕榈油 精炼 200kg/桶
计量单位：桶（现货 314 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 718, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW(), 0),
(1124, 2, 62, 9, '菜籽油 四级 散装吨桶', '谷源食材', '四级 散装吨桶', 8650, '[{"qty":5,"price":8650}]', 594, '', '品牌：谷源食材
规格：四级 散装吨桶
计量单位：吨（现货 594 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 87, DATE_SUB(NOW(), INTERVAL 8 DAY), NOW(), 0),
(1125, 1, 63, 9, '玉米淀粉 食用一级 25kg/袋', '谷源食材', '食用一级 25kg/袋', 118, '[{"qty":100,"price":118},{"qty":1000,"price":113.28}]', 6339, '', '品牌：谷源食材
规格：食用一级 25kg/袋
计量单位：袋（现货 6339 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2511, DATE_SUB(NOW(), INTERVAL 15 DAY), NOW(), 0),
(1126, 2, 63, 9, '果葡糖浆 F55 25kg/桶', '澜味食品', 'F55 25kg/桶', 68, '[{"qty":100,"price":68}]', 3083, '', '品牌：澜味食品
规格：F55 25kg/桶
计量单位：桶（现货 3083 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 396, DATE_SUB(NOW(), INTERVAL 22 DAY), NOW(), 0),
(1127, 1, 64, 9, '食品级白砂糖 一级 50kg/袋', '澜味食品', '一级 50kg/袋', 298, '[{"qty":100,"price":298},{"qty":1000,"price":286.08}]', 6392, '', '品牌：澜味食品
规格：一级 50kg/袋
计量单位：袋（现货 6392 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2608, DATE_SUB(NOW(), INTERVAL 29 DAY), NOW(), 0),
(1128, 2, 64, 9, '高麦芽糖浆 75BE 25kg/桶', '谷源食材', '75BE 25kg/桶', 86, '[{"qty":100,"price":86}]', 3124, '', '品牌：谷源食材
规格：75BE 25kg/桶
计量单位：桶（现货 3124 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 427, DATE_SUB(NOW(), INTERVAL 36 DAY), NOW(), 0),
(1129, 1, 65, 9, '味精 99% 纯度 25kg/袋', '谷源食材', '99% 纯度 25kg/袋', 235, '[{"qty":100,"price":235},{"qty":1000,"price":225.6}]', 6445, '', '品牌：谷源食材
规格：99% 纯度 25kg/袋
计量单位：袋（现货 6445 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2705, DATE_SUB(NOW(), INTERVAL 43 DAY), NOW(), 0),
(1130, 2, 65, 9, '酿造酱油 高盐稀态 20L/桶', '澜味食品', '高盐稀态 20L/桶', 96, '[{"qty":100,"price":96}]', 3165, '', '品牌：澜味食品
规格：高盐稀态 20L/桶
计量单位：桶（现货 3165 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 458, DATE_SUB(NOW(), INTERVAL 50 DAY), NOW(), 0),
(1131, 1, 66, 9, '柠檬酸一水 食品级 25kg/袋', '澜味食品', '食品级 25kg/袋', 208, '[{"qty":100,"price":208},{"qty":1000,"price":199.68}]', 6498, '', '品牌：澜味食品
规格：食品级 25kg/袋
计量单位：袋（现货 6498 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2802, DATE_SUB(NOW(), INTERVAL 57 DAY), NOW(), 0),
(1132, 2, 66, 9, '羧甲基纤维素钠 CMC FH9', '谷源食材', 'CMC FH9', 126, '[{"qty":100,"price":126}]', 3206, '', '品牌：谷源食材
规格：CMC FH9
计量单位：袋（现货 3206 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 489, DATE_SUB(NOW(), INTERVAL 4 DAY), NOW(), 0),
(1133, 1, 67, 9, '高活性干酵母 500g×20/箱', '谷源食材', '500g×20/箱', 128, '[{"qty":100,"price":128},{"qty":1000,"price":122.88}]', 6551, '', '品牌：谷源食材
规格：500g×20/箱
计量单位：箱（现货 6551 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2899, DATE_SUB(NOW(), INTERVAL 11 DAY), NOW(), 0),
(1134, 2, 67, 9, '低筋小麦粉 25kg/袋', '澜味食品', '25kg/袋', 112, '[{"qty":100,"price":112}]', 3247, '', '品牌：澜味食品
规格：25kg/袋
计量单位：袋（现货 3247 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 520, DATE_SUB(NOW(), INTERVAL 18 DAY), NOW(), 0),
(1135, 1, 68, 9, '茉莉花茶坯 特级 25kg/箱', '澜味食品', '特级 25kg/箱', 1250, '[{"qty":5,"price":1250},{"qty":1000,"price":1200}]', 596, '', '品牌：澜味食品
规格：特级 25kg/箱
计量单位：箱（现货 596 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 552, DATE_SUB(NOW(), INTERVAL 25 DAY), NOW(), 0),
(1136, 2, 68, 9, '浓缩苹果清汁 70Brix 250kg/桶', '谷源食材', '70Brix 250kg/桶', 1260, '[{"qty":5,"price":1260}]', 816, '', '品牌：谷源食材
规格：70Brix 250kg/桶
计量单位：桶（现货 816 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 33, DATE_SUB(NOW(), INTERVAL 32 DAY), NOW(), 0),
(1137, 1, 69, 9, 'CTC 红茶 BOP 一级 25kg/袋', '谷源食材', '红茶 BOP 一级 25kg/袋', 486, '[{"qty":100,"price":486},{"qty":1000,"price":466.56}]', 6657, '', '品牌：谷源食材
规格：红茶 BOP 一级 25kg/袋
计量单位：袋（现货 6657 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 3093, DATE_SUB(NOW(), INTERVAL 39 DAY), NOW(), 0),
(1138, 2, 69, 9, '奶茶专用植脂末 T50 25kg/袋', '澜味食品', 'T50 25kg/袋', 215, '[{"qty":100,"price":215}]', 3329, '', '品牌：澜味食品
规格：T50 25kg/袋
计量单位：袋（现货 3329 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 582, DATE_SUB(NOW(), INTERVAL 46 DAY), NOW(), 0),
(1139, 1, 70, 9, '浓缩橙汁 65Brix 冷冻 200kg/桶', '澜味食品', '65Brix 冷冻 200kg/桶', 2260, '[{"qty":5,"price":2260},{"qty":1000,"price":2169.6}]', 690, '', '品牌：澜味食品
规格：65Brix 冷冻 200kg/桶
计量单位：桶（现货 690 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 730, DATE_SUB(NOW(), INTERVAL 53 DAY), NOW(), 0),
(1140, 2, 70, 9, '山楂浓缩汁 200kg/桶', '谷源食材', '200kg/桶', 1680, '[{"qty":5,"price":1680}]', 890, '', '品牌：谷源食材
规格：200kg/桶
计量单位：桶（现货 890 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 79, DATE_SUB(NOW(), INTERVAL 0 DAY), NOW(), 0);
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1141, 1, 71, 9, '无水咖啡因 99.5% 25kg/桶', '谷源食材', '99.5% 25kg/桶', 2680, '[{"qty":5,"price":2680},{"qty":1000,"price":2572.8}]', 737, '', '品牌：谷源食材
规格：99.5% 25kg/桶
计量单位：桶（现货 737 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 119, DATE_SUB(NOW(), INTERVAL 7 DAY), NOW(), 0),
(1142, 2, 71, 9, '牛磺酸 食品级 25kg/袋', '澜味食品', '食品级 25kg/袋', 486, '[{"qty":100,"price":486}]', 3411, '', '品牌：澜味食品
规格：食品级 25kg/袋
计量单位：袋（现货 3411 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 644, DATE_SUB(NOW(), INTERVAL 14 DAY), NOW(), 0),
(1143, 1, 72, 10, '对乙酰氨基酚 BP/USP 25kg/桶', '澜康医药', 'BP/USP 25kg/桶', 968, '[{"qty":100,"price":968},{"qty":1000,"price":929.28}]', 6816, '', '品牌：澜康医药
规格：BP/USP 25kg/桶
计量单位：桶（现货 6816 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 3384, DATE_SUB(NOW(), INTERVAL 21 DAY), NOW(), 0),
(1144, 2, 72, 10, '阿莫西林三水酸 USP 25kg/桶', '济安医疗', 'USP 25kg/桶', 1580, '[{"qty":5,"price":1580}]', 964, '', '品牌：济安医疗
规格：USP 25kg/桶
计量单位：桶（现货 964 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 29, DATE_SUB(NOW(), INTERVAL 28 DAY), NOW(), 0),
(1145, 1, 73, 10, '布洛芬 USP 25kg/桶', '济安医疗', 'USP 25kg/桶', 1080, '[{"qty":5,"price":1080},{"qty":1000,"price":1036.8}]', 831, '', '品牌：济安医疗
规格：USP 25kg/桶
计量单位：桶（现货 831 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 297, DATE_SUB(NOW(), INTERVAL 35 DAY), NOW(), 0),
(1146, 2, 73, 10, '盐酸二甲双胍 BP 25kg/桶', '澜康医药', 'BP 25kg/桶', 865, '[{"qty":100,"price":865}]', 3493, '', '品牌：澜康医药
规格：BP 25kg/桶
计量单位：桶（现货 3493 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 706, DATE_SUB(NOW(), INTERVAL 42 DAY), NOW(), 0),
(1147, 1, 74, 10, '黄芪统片 内蒙 1.0-1.2mm', '澜康医药', '内蒙 1.0-1.2mm', 38, '[{"qty":100,"price":38},{"qty":1000,"price":36.48}]', 6922, '', '品牌：澜康医药
规格：内蒙 1.0-1.2mm
计量单位：kg（现货 6922 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 3578, DATE_SUB(NOW(), INTERVAL 49 DAY), NOW(), 0),
(1148, 2, 74, 10, '当归全归 岷县 40 头', '济安医疗', '岷县 40 头', 52, '[{"qty":100,"price":52}]', 3534, '', '品牌：济安医疗
规格：岷县 40 头
计量单位：kg（现货 3534 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 737, DATE_SUB(NOW(), INTERVAL 56 DAY), NOW(), 0),
(1149, 1, 75, 10, '微晶纤维素 PH102 25kg/桶', '济安医疗', 'PH102 25kg/桶', 286, '[{"qty":100,"price":286},{"qty":1000,"price":274.56}]', 6975, '', '品牌：济安医疗
规格：PH102 25kg/桶
计量单位：桶（现货 6975 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 3675, DATE_SUB(NOW(), INTERVAL 3 DAY), NOW(), 0),
(1150, 2, 75, 10, '药用淀粉 药用级 25kg/袋', '澜康医药', '药用级 25kg/袋', 128, '[{"qty":100,"price":128}]', 3575, '', '品牌：澜康医药
规格：药用级 25kg/袋
计量单位：袋（现货 3575 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 768, DATE_SUB(NOW(), INTERVAL 10 DAY), NOW(), 0),
(1151, 1, 76, 10, '多参数监护仪 6 参数 12.1 寸', '澜康医药', '6 参数 12.1 寸', 8650, '[{"qty":5,"price":8650},{"qty":1000,"price":8304}]', 972, '', '品牌：澜康医药
规格：6 参数 12.1 寸
计量单位：台（现货 972 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 564, DATE_SUB(NOW(), INTERVAL 17 DAY), NOW(), 0),
(1152, 2, 76, 10, '全自动生化分析仪 220 速', '济安医疗', '220 速', 128000, '[{"qty":2,"price":128000}]', 31, '', '品牌：济安医疗
规格：220 速
计量单位：台（现货 31 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2, DATE_SUB(NOW(), INTERVAL 24 DAY), NOW(), 0),
(1153, 1, 77, 10, '便携式超声诊断仪 探头双配', '济安医疗', '探头双配', 28600, '[{"qty":2,"price":28600},{"qty":5,"price":27456}]', 33, '', '品牌：济安医疗
规格：探头双配
计量单位：台（现货 33 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 207, DATE_SUB(NOW(), INTERVAL 31 DAY), NOW(), 0),
(1154, 2, 77, 10, '全自动血细胞分析仪 三分类', '澜康医药', '三分类', 38600, '[{"qty":2,"price":38600}]', 14, '', '品牌：澜康医药
规格：三分类
计量单位：台（现货 14 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 21, DATE_SUB(NOW(), INTERVAL 38 DAY), NOW(), 0),
(1155, 1, 78, 10, '中央监护系统 8 床位', '澜康医药', '8 床位', 58600, '[{"qty":2,"price":58600},{"qty":5,"price":56256}]', 17, '', '品牌：澜康医药
规格：8 床位
计量单位：套（现货 17 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 38, DATE_SUB(NOW(), INTERVAL 45 DAY), NOW(), 0),
(1156, 2, 78, 10, '新生儿监护暖箱', '济安医疗', '新生儿监护暖箱', 18600, '[{"qty":2,"price":18600}]', 27, '', '品牌：济安医疗
规格：新生儿监护暖箱
计量单位：台（现货 27 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1, DATE_SUB(NOW(), INTERVAL 52 DAY), NOW(), 0),
(1157, 1, 79, 10, '下肢康复训练器 减重步态', '济安医疗', '减重步态', 28600, '[{"qty":2,"price":28600},{"qty":5,"price":27456}]', 46, '', '品牌：济安医疗
规格：减重步态
计量单位：台（现货 46 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 109, DATE_SUB(NOW(), INTERVAL 59 DAY), NOW(), 0),
(1158, 2, 79, 10, '中频脉冲治疗仪 四通道', '澜康医药', '四通道', 1280, '[{"qty":5,"price":1280}]', 323, '', '品牌：澜康医药
规格：四通道
计量单位：台（现货 323 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 94, DATE_SUB(NOW(), INTERVAL 6 DAY), NOW(), 0),
(1159, 1, 80, 10, '一次性使用无菌注射器 5ml 带针', '澜康医药', '5ml 带针', 0.42, '[{"qty":100,"price":0.42},{"qty":1000,"price":0.4}]', 7240, '', '品牌：澜康医药
规格：5ml 带针
计量单位：支（现货 7240 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4160, DATE_SUB(NOW(), INTERVAL 13 DAY), NOW(), 0),
(1160, 2, 80, 10, '医用外科口罩 灭菌级 独立装', '济安医疗', '灭菌级 独立装', 0.12, '[{"qty":100,"price":0.12}]', 3780, '', '品牌：济安医疗
规格：灭菌级 独立装
计量单位：只（现货 3780 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 137, DATE_SUB(NOW(), INTERVAL 20 DAY), NOW(), 0);
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1161, 1, 81, 10, '一次性输液器 带针 独立包装', '济安医疗', '带针 独立包装', 0.68, '[{"qty":100,"price":0.68},{"qty":1000,"price":0.65}]', 7293, '', '品牌：济安医疗
规格：带针 独立包装
计量单位：套（现货 7293 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4257, DATE_SUB(NOW(), INTERVAL 27 DAY), NOW(), 0),
(1162, 2, 81, 10, '一次性使用无菌注射器 20ml', '澜康医药', '20ml', 0.86, '[{"qty":100,"price":0.86}]', 3821, '', '品牌：澜康医药
规格：20ml
计量单位：支（现货 3821 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 168, DATE_SUB(NOW(), INTERVAL 34 DAY), NOW(), 0),
(1163, 1, 82, 10, '一次性使用手术包 普外', '澜康医药', '普外', 15.8, '[{"qty":100,"price":15.8},{"qty":1000,"price":15.17}]', 7346, '', '品牌：澜康医药
规格：普外
计量单位：包（现货 7346 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4354, DATE_SUB(NOW(), INTERVAL 41 DAY), NOW(), 0),
(1164, 2, 82, 10, '可吸收外科缝线 PGA 2-0 圆针', '济安医疗', 'PGA 2-0 圆针', 12.6, '[{"qty":100,"price":12.6}]', 3862, '', '品牌：济安医疗
规格：PGA 2-0 圆针
计量单位：根（现货 3862 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 199, DATE_SUB(NOW(), INTERVAL 48 DAY), NOW(), 0),
(1165, 1, 83, 10, '医用纱布绷带 8×600cm 灭菌', '济安医疗', '8×600cm 灭菌', 0.86, '[{"qty":100,"price":0.86},{"qty":1000,"price":0.83}]', 7399, '', '品牌：济安医疗
规格：8×600cm 灭菌
计量单位：卷（现货 7399 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4451, DATE_SUB(NOW(), INTERVAL 55 DAY), NOW(), 0),
(1166, 2, 83, 10, '一次性使用引流袋 抗反流', '澜康医药', '抗反流', 1.25, '[{"qty":100,"price":1.25}]', 3903, '', '品牌：澜康医药
规格：抗反流
计量单位：只（现货 3903 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 230, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW(), 0),
(1167, 1, 84, 11, '五层瓦楞纸箱 BC 坑 特硬 40×30×30cm', '澜包印务', 'BC 坑 特硬 40×30×30cm', 2.86, '[{"qty":100,"price":2.86},{"qty":1000,"price":2.75}]', 7452, '', '品牌：澜包印务
规格：BC 坑 特硬 40×30×30cm
计量单位：个（现货 7452 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4548, DATE_SUB(NOW(), INTERVAL 9 DAY), NOW(), 0),
(1168, 2, 84, 11, '白卡彩盒 对裱 印四色', '华印包装', '对裱 印四色', 1.25, '[{"qty":100,"price":1.25}]', 3944, '', '品牌：华印包装
规格：对裱 印四色
计量单位：个（现货 3944 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 261, DATE_SUB(NOW(), INTERVAL 16 DAY), NOW(), 0),
(1169, 1, 85, 11, '三层瓦楞纸箱 普通邮包 3 号', '华印包装', '普通邮包 3 号', 0.68, '[{"qty":100,"price":0.68},{"qty":1000,"price":0.65}]', 7505, '', '品牌：华印包装
规格：普通邮包 3 号
计量单位：个（现货 7505 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4645, DATE_SUB(NOW(), INTERVAL 23 DAY), NOW(), 0),
(1170, 2, 85, 11, '重型七层瓦楞箱 蜂窝加强', '澜包印务', '蜂窝加强', 6.8, '[{"qty":100,"price":6.8}]', 3985, '', '品牌：澜包印务
规格：蜂窝加强
计量单位：个（现货 3985 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 292, DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
(1171, 1, 86, 11, '白卡彩盒 覆哑膜 烫金', '澜包印务', '覆哑膜 烫金', 2.15, '[{"qty":100,"price":2.15},{"qty":1000,"price":2.06}]', 7558, '', '品牌：澜包印务
规格：覆哑膜 烫金
计量单位：个（现货 7558 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4742, DATE_SUB(NOW(), INTERVAL 37 DAY), NOW(), 0),
(1172, 2, 86, 11, '瓦楞彩盒 E 坑 四色印刷', '华印包装', 'E 坑 四色印刷', 1.36, '[{"qty":100,"price":1.36}]', 4026, '', '品牌：华印包装
规格：E 坑 四色印刷
计量单位：个（现货 4026 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 323, DATE_SUB(NOW(), INTERVAL 44 DAY), NOW(), 0),
(1173, 1, 87, 11, '牛皮纸袋 方底阀口 25kg 装', '华印包装', '方底阀口 25kg 装', 1.68, '[{"qty":100,"price":1.68},{"qty":1000,"price":1.61}]', 7611, '', '品牌：华印包装
规格：方底阀口 25kg 装
计量单位：个（现货 7611 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 639, DATE_SUB(NOW(), INTERVAL 51 DAY), NOW(), 0),
(1174, 2, 87, 11, '白卡手提袋 覆膜带绳', '澜包印务', '覆膜带绳', 0.86, '[{"qty":100,"price":0.86}]', 4067, '', '品牌：澜包印务
规格：覆膜带绳
计量单位：个（现货 4067 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 354, DATE_SUB(NOW(), INTERVAL 58 DAY), NOW(), 0),
(1175, 1, 88, 11, 'PE 包装膜 500mm×500m 20μm', '澜包印务', '包装膜 500mm×500m 20μm', 12.6, '[{"qty":100,"price":12.6},{"qty":1000,"price":12.1}]', 7664, '', '品牌：澜包印务
规格：包装膜 500mm×500m 20μm
计量单位：kg（现货 7664 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 736, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW(), 0),
(1176, 2, 88, 11, 'PET 透明瓶 500ml 28 口', '华印包装', '透明瓶 500ml 28 口', 0.42, '[{"qty":100,"price":0.42}]', 4108, '', '品牌：华印包装
规格：透明瓶 500ml 28 口
计量单位：个（现货 4108 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 385, DATE_SUB(NOW(), INTERVAL 12 DAY), NOW(), 0),
(1177, 1, 89, 11, 'PE 缠绕膜 500mm×300m 机用', '华印包装', '缠绕膜 500mm×300m 机用', 11.8, '[{"qty":100,"price":11.8},{"qty":1000,"price":11.33}]', 7717, '', '品牌：华印包装
规格：缠绕膜 500mm×300m 机用
计量单位：卷（现货 7717 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 833, DATE_SUB(NOW(), INTERVAL 19 DAY), NOW(), 0),
(1178, 2, 89, 11, 'POF 热收缩膜 15μm 对折', '澜包印务', '热收缩膜 15μm 对折', 15.6, '[{"qty":100,"price":15.6}]', 4149, '', '品牌：澜包印务
规格：热收缩膜 15μm 对折
计量单位：kg（现货 4149 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 416, DATE_SUB(NOW(), INTERVAL 26 DAY), NOW(), 0),
(1179, 1, 90, 11, 'HDPE 白瓶 100ml 配压旋盖', '澜包印务', '白瓶 100ml 配压旋盖', 0.28, '[{"qty":100,"price":0.28},{"qty":1000,"price":0.27}]', 7770, '', '品牌：澜包印务
规格：白瓶 100ml 配压旋盖
计量单位：个（现货 7770 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 930, DATE_SUB(NOW(), INTERVAL 33 DAY), NOW(), 0),
(1180, 2, 90, 11, 'PET 广口瓶 1L 现货模具', '华印包装', '广口瓶 1L 现货模具', 0.68, '[{"qty":100,"price":0.68}]', 4190, '', '品牌：华印包装
规格：广口瓶 1L 现货模具
计量单位：个（现货 4190 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 447, DATE_SUB(NOW(), INTERVAL 40 DAY), NOW(), 0);
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1181, 1, 91, 11, 'PP 编织袋 50×80cm 内衬膜', '华印包装', '编织袋 50×80cm 内衬膜', 0.68, '[{"qty":100,"price":0.68},{"qty":1000,"price":0.65}]', 7823, '', '品牌：华印包装
规格：编织袋 50×80cm 内衬膜
计量单位：条（现货 7823 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1027, DATE_SUB(NOW(), INTERVAL 47 DAY), NOW(), 0),
(1182, 2, 91, 11, '牛皮纸复合编织袋 25kg 阀口', '澜包印务', '25kg 阀口', 1.15, '[{"qty":100,"price":1.15}]', 4231, '', '品牌：澜包印务
规格：25kg 阀口
计量单位：条（现货 4231 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 478, DATE_SUB(NOW(), INTERVAL 54 DAY), NOW(), 0),
(1183, 1, 92, 11, '水性油墨 白色 20kg/桶', '澜包印务', '白色 20kg/桶', 328, '[{"qty":100,"price":328},{"qty":1000,"price":314.88}]', 7876, '', '品牌：澜包印务
规格：白色 20kg/桶
计量单位：桶（现货 7876 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1124, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW(), 0),
(1184, 2, 92, 11, 'PS 印版 对开 0.27mm', '华印包装', '印版 对开 0.27mm', 28.6, '[{"qty":100,"price":28.6}]', 4272, '', '品牌：华印包装
规格：印版 对开 0.27mm
计量单位：张（现货 4272 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 509, DATE_SUB(NOW(), INTERVAL 8 DAY), NOW(), 0),
(1185, 1, 93, 11, '胶印四色墨 快固亮光 1kg/罐', '华印包装', '快固亮光 1kg/罐', 58, '[{"qty":100,"price":58},{"qty":1000,"price":55.68}]', 7929, '', '品牌：华印包装
规格：快固亮光 1kg/罐
计量单位：罐（现货 7929 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1221, DATE_SUB(NOW(), INTERVAL 15 DAY), NOW(), 0),
(1186, 2, 93, 11, 'UV 油墨 高附着 白色 1kg', '澜包印务', '油墨 高附着 白色 1kg', 98, '[{"qty":100,"price":98}]', 4313, '', '品牌：澜包印务
规格：油墨 高附着 白色 1kg
计量单位：罐（现货 4313 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 540, DATE_SUB(NOW(), INTERVAL 22 DAY), NOW(), 0),
(1187, 1, 94, 11, 'CTP 热敏版 阳图 对开', '澜包印务', '热敏版 阳图 对开', 38.5, '[{"qty":100,"price":38.5},{"qty":1000,"price":36.96}]', 7982, '', '品牌：澜包印务
规格：热敏版 阳图 对开
计量单位：张（现货 7982 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1318, DATE_SUB(NOW(), INTERVAL 29 DAY), NOW(), 0),
(1188, 2, 94, 11, '柔性树脂版 3.94mm 制好', '华印包装', '3.94mm 制好', 86, '[{"qty":100,"price":86}]', 4354, '', '品牌：华印包装
规格：3.94mm 制好
计量单位：张（现货 4354 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 571, DATE_SUB(NOW(), INTERVAL 36 DAY), NOW(), 0),
(1189, 1, 95, 11, 'PET 不干胶标 亚银 防水', '华印包装', '不干胶标 亚银 防水', 0.12, '[{"qty":100,"price":0.12},{"qty":1000,"price":0.12}]', 8035, '', '品牌：华印包装
规格：不干胶标 亚银 防水
计量单位：张（现货 8035 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1415, DATE_SUB(NOW(), INTERVAL 43 DAY), NOW(), 0),
(1190, 2, 95, 11, '铜版纸不干胶 卷装 模切', '澜包印务', '卷装 模切', 0.045, '[{"qty":100,"price":0.045}]', 4395, '', '品牌：澜包印务
规格：卷装 模切
计量单位：张（现货 4395 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 602, DATE_SUB(NOW(), INTERVAL 50 DAY), NOW(), 0),
(1191, 1, 96, 12, '中型仓储货架 200kg/层 五层组', '澜运供应链', '200kg/层 五层组', 186, '[{"qty":100,"price":186},{"qty":1000,"price":178.56}]', 8088, '', '品牌：澜运供应链
规格：200kg/层 五层组
计量单位：组（现货 8088 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1512, DATE_SUB(NOW(), INTERVAL 57 DAY), NOW(), 0),
(1192, 2, 96, 12, '重型横梁式货架 托盘位 2t', '顺捷仓储', '托盘位 2t', 586, '[{"qty":100,"price":586}]', 4436, '', '品牌：顺捷仓储
规格：托盘位 2t
计量单位：位（现货 4436 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 633, DATE_SUB(NOW(), INTERVAL 4 DAY), NOW(), 0),
(1193, 1, 97, 12, '贯通式货架 叉车存取 4 层', '顺捷仓储', '叉车存取 4 层', 486, '[{"qty":100,"price":486},{"qty":1000,"price":466.56}]', 8141, '', '品牌：顺捷仓储
规格：叉车存取 4 层
计量单位：组（现货 8141 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1609, DATE_SUB(NOW(), INTERVAL 11 DAY), NOW(), 0),
(1194, 2, 97, 12, '阁楼式货架 平台结构 定制', '澜运供应链', '平台结构 定制', 386, '[{"qty":100,"price":386}]', 4477, '', '品牌：澜运供应链
规格：平台结构 定制
计量单位：平米（现货 4477 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 664, DATE_SUB(NOW(), INTERVAL 18 DAY), NOW(), 0),
(1195, 1, 98, 12, '滚筒输送线 动力式 800mm 宽', '澜运供应链', '动力式 800mm 宽', 1260, '[{"qty":5,"price":1260},{"qty":1000,"price":1209.6}]', 2006, '', '品牌：澜运供应链
规格：动力式 800mm 宽
计量单位：米（现货 2006 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 422, DATE_SUB(NOW(), INTERVAL 25 DAY), NOW(), 0),
(1196, 2, 98, 12, '皮带输送机 裙边 定长', '顺捷仓储', '裙边 定长', 2860, '[{"qty":5,"price":2860}]', 126, '', '品牌：顺捷仓储
规格：裙边 定长
计量单位：台（现货 126 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 51, DATE_SUB(NOW(), INTERVAL 32 DAY), NOW(), 0),
(1197, 1, 99, 12, '塑料周转箱 EU 600×400×280', '顺捷仓储', 'EU 600×400×280', 28.6, '[{"qty":100,"price":28.6},{"qty":1000,"price":27.46}]', 8247, '', '品牌：顺捷仓储
规格：EU 600×400×280
计量单位：只（现货 8247 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1803, DATE_SUB(NOW(), INTERVAL 39 DAY), NOW(), 0),
(1198, 2, 99, 12, '钢制料箱 折叠式 800×600', '澜运供应链', '折叠式 800×600', 126, '[{"qty":100,"price":126}]', 4559, '', '品牌：澜运供应链
规格：折叠式 800×600
计量单位：只（现货 4559 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 726, DATE_SUB(NOW(), INTERVAL 46 DAY), NOW(), 0),
(1199, 1, 100, 12, 'PP 打包带 机用 9mm', '澜运供应链', '打包带 机用 9mm', 9.8, '[{"qty":100,"price":9.8},{"qty":1000,"price":9.41}]', 8300, '', '品牌：澜运供应链
规格：打包带 机用 9mm
计量单位：kg（现货 8300 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1900, DATE_SUB(NOW(), INTERVAL 53 DAY), NOW(), 0),
(1200, 2, 100, 12, 'PE 缠绕膜 手用 450mm×300m', '顺捷仓储', '缠绕膜 手用 450mm×300m', 10.6, '[{"qty":100,"price":10.6}]', 4600, '', '品牌：顺捷仓储
规格：缠绕膜 手用 450mm×300m
计量单位：卷（现货 4600 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 757, DATE_SUB(NOW(), INTERVAL 0 DAY), NOW(), 0);
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1201, 1, 101, 12, 'PP 自动打包带 12mm 白色', '顺捷仓储', '自动打包带 12mm 白色', 10.8, '[{"qty":100,"price":10.8},{"qty":1000,"price":10.37}]', 8353, '', '品牌：顺捷仓储
规格：自动打包带 12mm 白色
计量单位：kg（现货 8353 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1997, DATE_SUB(NOW(), INTERVAL 7 DAY), NOW(), 0),
(1202, 2, 101, 12, 'PET 塑钢打包带 1608 绿色', '澜运供应链', '塑钢打包带 1608 绿色', 13.6, '[{"qty":100,"price":13.6}]', 4641, '', '品牌：澜运供应链
规格：塑钢打包带 1608 绿色
计量单位：kg（现货 4641 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 788, DATE_SUB(NOW(), INTERVAL 14 DAY), NOW(), 0),
(1203, 1, 102, 12, '手用缠绕膜 500mm 净重 2.5kg', '澜运供应链', '500mm 净重 2.5kg', 11.2, '[{"qty":100,"price":11.2},{"qty":1000,"price":10.75}]', 8406, '', '品牌：澜运供应链
规格：500mm 净重 2.5kg
计量单位：卷（现货 8406 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2094, DATE_SUB(NOW(), INTERVAL 21 DAY), NOW(), 0),
(1204, 2, 102, 12, '机用缠绕膜 23μm 高粘', '顺捷仓储', '23μm 高粘', 12.8, '[{"qty":100,"price":12.8}]', 4682, '', '品牌：顺捷仓储
规格：23μm 高粘
计量单位：kg（现货 4682 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 33, DATE_SUB(NOW(), INTERVAL 28 DAY), NOW(), 0),
(1205, 1, 103, 12, '气泡膜 50cm×100m 双面', '顺捷仓储', '50cm×100m 双面', 28.6, '[{"qty":100,"price":28.6},{"qty":1000,"price":27.46}]', 8459, '', '品牌：顺捷仓储
规格：50cm×100m 双面
计量单位：卷（现货 8459 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2191, DATE_SUB(NOW(), INTERVAL 35 DAY), NOW(), 0),
(1206, 2, 103, 12, '珍珠棉板材 5mm 定制', '澜运供应链', '5mm 定制', 3.2, '[{"qty":100,"price":3.2}]', 4723, '', '品牌：澜运供应链
规格：5mm 定制
计量单位：平米（现货 4723 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 64, DATE_SUB(NOW(), INTERVAL 42 DAY), NOW(), 0),
(1207, 1, 104, 12, '省际干线运输 专线 3 日达', '澜运供应链', '专线 3 日达', 386, '[{"qty":100,"price":386},{"qty":1000,"price":370.56}]', 8512, '', '品牌：澜运供应链
规格：专线 3 日达
计量单位：吨（现货 8512 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2288, DATE_SUB(NOW(), INTERVAL 49 DAY), NOW(), 0),
(1208, 2, 104, 12, '国际海运整柜 20GP 美西线', '顺捷仓储', '20GP 美西线', 12800, '[{"qty":2,"price":12800}]', 5, '', '品牌：顺捷仓储
规格：20GP 美西线
计量单位：柜（现货 5 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 27, DATE_SUB(NOW(), INTERVAL 56 DAY), NOW(), 0),
(1209, 1, 105, 12, '冷链干线运输 0-4°C 整车', '顺捷仓储', '0-4°C 整车', 2860, '[{"qty":5,"price":2860},{"qty":1000,"price":2745.6}]', 2335, '', '品牌：顺捷仓储
规格：0-4°C 整车
计量单位：车次（现货 2335 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 345, DATE_SUB(NOW(), INTERVAL 3 DAY), NOW(), 0),
(1210, 2, 105, 12, '零担快运 省内次日达', '澜运供应链', '省内次日达', 0.68, '[{"qty":100,"price":0.68}]', 4805, '', '品牌：澜运供应链
规格：省内次日达
计量单位：kg（现货 4805 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 126, DATE_SUB(NOW(), INTERVAL 10 DAY), NOW(), 0),
(1211, 1, 106, 12, 'B2C 仓配一体服务 日均 500 单', '澜运供应链', '仓配一体服务 日均 500 单', 3.6, '[{"qty":100,"price":3.6},{"qty":1000,"price":3.46}]', 8618, '', '品牌：澜运供应链
规格：仓配一体服务 日均 500 单
计量单位：单（现货 8618 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2482, DATE_SUB(NOW(), INTERVAL 17 DAY), NOW(), 0),
(1212, 2, 106, 12, 'B2B 分仓代发 华东仓', '顺捷仓储', '分仓代发 华东仓', 2.8, '[{"qty":100,"price":2.8}]', 4846, '', '品牌：顺捷仓储
规格：分仓代发 华东仓
计量单位：单（现货 4846 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 157, DATE_SUB(NOW(), INTERVAL 24 DAY), NOW(), 0),
(1213, 1, 107, 12, '国际空港快线 45kg 起', '顺捷仓储', '45kg 起', 28.6, '[{"qty":100,"price":28.6},{"qty":1000,"price":27.46}]', 8671, '', '品牌：顺捷仓储
规格：45kg 起
计量单位：kg（现货 8671 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2579, DATE_SUB(NOW(), INTERVAL 31 DAY), NOW(), 0),
(1214, 2, 107, 12, '国际海运 40HQ 东南亚线', '澜运供应链', '40HQ 东南亚线', 8600, '[{"qty":5,"price":8600}]', 459, '', '品牌：澜运供应链
规格：40HQ 东南亚线
计量单位：柜（现货 459 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 66, DATE_SUB(NOW(), INTERVAL 38 DAY), NOW(), 0),
(1215, 1, 108, 13, '磷酸铁锂正极材料 LFP-523', '澜能新材', 'LFP-523', 32800, '[{"qty":2,"price":32800},{"qty":5,"price":31488}]', 32, '', '品牌：澜能新材
规格：LFP-523
计量单位：吨（现货 32 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 248, DATE_SUB(NOW(), INTERVAL 45 DAY), NOW(), 0),
(1216, 2, 108, 13, '人造石墨负极材料 石墨化', '光启新能源', '石墨化', 24800, '[{"qty":2,"price":24800}]', 27, '', '品牌：光启新能源
规格：石墨化
计量单位：吨（现货 27 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 25, DATE_SUB(NOW(), INTERVAL 52 DAY), NOW(), 0),
(1217, 1, 109, 13, '三元正极材料 NCM523 单晶', '光启新能源', 'NCM523 单晶', 128000, '[{"qty":2,"price":128000},{"qty":5,"price":122880}]', 16, '', '品牌：光启新能源
规格：NCM523 单晶
计量单位：吨（现货 16 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 79, DATE_SUB(NOW(), INTERVAL 59 DAY), NOW(), 0),
(1218, 2, 109, 13, '锰酸锂正极材料 低压实', '澜能新材', '低压实', 46500, '[{"qty":2,"price":46500}]', 10, '', '品牌：澜能新材
规格：低压实
计量单位：吨（现货 10 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 5, DATE_SUB(NOW(), INTERVAL 6 DAY), NOW(), 0),
(1219, 1, 110, 13, '人造石墨负极 低膨胀 SNC', '澜能新材', '低膨胀 SNC', 22800, '[{"qty":2,"price":22800},{"qty":5,"price":21888}]', 45, '', '品牌：澜能新材
规格：低膨胀 SNC
计量单位：吨（现货 45 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 150, DATE_SUB(NOW(), INTERVAL 13 DAY), NOW(), 0),
(1220, 2, 110, 13, '天然石墨负极 球形', '光启新能源', '球形', 18600, '[{"qty":2,"price":18600}]', 23, '', '品牌：光启新能源
规格：球形
计量单位：吨（现货 23 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 24, DATE_SUB(NOW(), INTERVAL 20 DAY), NOW(), 0);
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1221, 1, 111, 13, '磷酸铁锂型电解液 湿度受控', '光启新能源', '湿度受控', 29800, '[{"qty":2,"price":29800},{"qty":5,"price":28608}]', 29, '', '品牌：光启新能源
规格：湿度受控
计量单位：吨（现货 29 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 221, DATE_SUB(NOW(), INTERVAL 27 DAY), NOW(), 0),
(1222, 2, 111, 13, '三元型电解液 高镍配方', '澜能新材', '高镍配方', 32800, '[{"qty":2,"price":32800}]', 6, '', '品牌：澜能新材
规格：高镍配方
计量单位：吨（现货 6 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4, DATE_SUB(NOW(), INTERVAL 34 DAY), NOW(), 0),
(1223, 1, 112, 13, '多晶硅料 致密料 复投', '澜能新材', '致密料 复投', 48500, '[{"qty":2,"price":48500},{"qty":5,"price":46560}]', 13, '', '品牌：澜能新材
规格：致密料 复投
计量单位：吨（现货 13 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 52, DATE_SUB(NOW(), INTERVAL 41 DAY), NOW(), 0),
(1224, 2, 112, 13, '单晶硅片 M10 182mm 150μm', '光启新能源', 'M10 182mm 150μm', 1.36, '[{"qty":100,"price":1.36}]', 5092, '', '品牌：光启新能源
规格：M10 182mm 150μm
计量单位：片（现货 5092 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 343, DATE_SUB(NOW(), INTERVAL 48 DAY), NOW(), 0),
(1225, 1, 113, 13, '单晶硅片 G12 210mm 140μm', '光启新能源', 'G12 210mm 140μm', 1.85, '[{"qty":100,"price":1.85},{"qty":1000,"price":1.78}]', 8989, '', '品牌：光启新能源
规格：G12 210mm 140μm
计量单位：片（现货 8989 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 3161, DATE_SUB(NOW(), INTERVAL 55 DAY), NOW(), 0),
(1226, 2, 113, 13, '颗粒硅料 FBR 掺镓', '澜能新材', 'FBR 掺镓', 46800, '[{"qty":2,"price":46800}]', 32, '', '品牌：澜能新材
规格：FBR 掺镓
计量单位：吨（现货 32 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 3, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW(), 0),
(1227, 1, 114, 13, 'EVA 胶膜 0.5mm 双层固化', '澜能新材', '胶膜 0.5mm 双层固化', 8.6, '[{"qty":100,"price":8.6},{"qty":1000,"price":8.26}]', 9042, '', '品牌：澜能新材
规格：胶膜 0.5mm 双层固化
计量单位：平米（现货 9042 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 3258, DATE_SUB(NOW(), INTERVAL 9 DAY), NOW(), 0),
(1228, 2, 114, 13, '光伏背板 KPK 复合', '光启新能源', 'KPK 复合', 12.6, '[{"qty":100,"price":12.6}]', 5174, '', '品牌：光启新能源
规格：KPK 复合
计量单位：平米（现货 5174 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 405, DATE_SUB(NOW(), INTERVAL 16 DAY), NOW(), 0),
(1229, 1, 115, 13, '光伏铝边框 6063-T6 阳极氧化', '光启新能源', '6063-T6 阳极氧化', 19.8, '[{"qty":100,"price":19.8},{"qty":1000,"price":19.01}]', 9095, '', '品牌：光启新能源
规格：6063-T6 阳极氧化
计量单位：kg（现货 9095 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 3355, DATE_SUB(NOW(), INTERVAL 23 DAY), NOW(), 0),
(1230, 2, 115, 13, '光伏接线盒 IP68 三分体', '澜能新材', 'IP68 三分体', 8.6, '[{"qty":100,"price":8.6}]', 5215, '', '品牌：澜能新材
规格：IP68 三分体
计量单位：套（现货 5215 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 436, DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
(1231, 1, 116, 13, '储能电池簇 280Ah 风冷 标准柜', '澜能新材', '280Ah 风冷 标准柜', 186000, '[{"qty":2,"price":186000},{"qty":5,"price":178560}]', 39, '', '品牌：澜能新材
规格：280Ah 风冷 标准柜
计量单位：簇（现货 39 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 96, DATE_SUB(NOW(), INTERVAL 37 DAY), NOW(), 0),
(1232, 2, 116, 13, '储能变流器 PCS 100kW 双向', '光启新能源', 'PCS 100kW 双向', 28600, '[{"qty":2,"price":28600}]', 11, '', '品牌：光启新能源
规格：PCS 100kW 双向
计量单位：台（现货 11 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 21, DATE_SUB(NOW(), INTERVAL 44 DAY), NOW(), 0),
(1233, 1, 117, 13, '磷酸铁锂电池模组 48V 100Ah', '光启新能源', '48V 100Ah', 2680, '[{"qty":5,"price":2680},{"qty":1000,"price":2572.8}]', 2899, '', '品牌：光启新能源
规格：48V 100Ah
计量单位：组（现货 2899 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 713, DATE_SUB(NOW(), INTERVAL 51 DAY), NOW(), 0),
(1234, 2, 117, 13, '储能电芯 314Ah 大容量', '澜能新材', '314Ah 大容量', 486, '[{"qty":100,"price":486}]', 5297, '', '品牌：澜能新材
规格：314Ah 大容量
计量单位：芯（现货 5297 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 498, DATE_SUB(NOW(), INTERVAL 58 DAY), NOW(), 0),
(1235, 1, 118, 13, 'PCS 储能变流器 500kW 组串式', '澜能新材', '储能变流器 500kW 组串式', 86500, '[{"qty":2,"price":86500},{"qty":5,"price":83040}]', 7, '', '品牌：澜能新材
规格：储能变流器 500kW 组串式
计量单位：台（现货 7 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 238, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW(), 0),
(1236, 2, 118, 13, 'PCS 储能变流器 50kW 户用', '光启新能源', '储能变流器 50kW 户用', 12600, '[{"qty":2,"price":12600}]', 7, '', '品牌：光启新能源
规格：储能变流器 50kW 户用
计量单位：台（现货 7 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 20, DATE_SUB(NOW(), INTERVAL 12 DAY), NOW(), 0),
(1237, 1, 119, 13, '储能 EMS 系统 千点级监控', '光启新能源', 'EMS 系统 千点级监控', 38600, '[{"qty":2,"price":38600},{"qty":5,"price":37056}]', 36, '', '品牌：光启新能源
规格：EMS 系统 千点级监控
计量单位：套（现货 36 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 69, DATE_SUB(NOW(), INTERVAL 19 DAY), NOW(), 0),
(1238, 2, 119, 13, '能量管理系统 EMS 云平台版', '澜能新材', 'EMS 云平台版', 12800, '[{"qty":2,"price":12800}]', 20, '', '品牌：澜能新材
规格：EMS 云平台版
计量单位：套（现货 20 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 39, DATE_SUB(NOW(), INTERVAL 26 DAY), NOW(), 0),
(1239, 1, 120, 14, '反渗透膜 BW-8040 国产工业级', '澜清水务', 'BW-8040 国产工业级', 868, '[{"qty":100,"price":868},{"qty":1000,"price":833.28}]', 9360, '', '品牌：澜清水务
规格：BW-8040 国产工业级
计量单位：支（现货 9360 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 3840, DATE_SUB(NOW(), INTERVAL 33 DAY), NOW(), 0),
(1240, 2, 120, 14, '一体化污水处理设备 50t/d', '绿净环保', '50t/d', 128000, '[{"qty":2,"price":128000}]', 3, '', '品牌：绿净环保
规格：50t/d
计量单位：套（现货 3 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 19, DATE_SUB(NOW(), INTERVAL 40 DAY), NOW(), 0);
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1241, 1, 121, 14, '反渗透主机 10t/h 双级', '绿净环保', '10t/h 双级', 186000, '[{"qty":2,"price":186000},{"qty":5,"price":178560}]', 49, '', '品牌：绿净环保
规格：10t/h 双级
计量单位：套（现货 49 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 211, DATE_SUB(NOW(), INTERVAL 47 DAY), NOW(), 0),
(1242, 2, 121, 14, '超滤设备 UF 20t/h 内压式', '澜清水务', 'UF 20t/h 内压式', 128000, '[{"qty":2,"price":128000}]', 16, '', '品牌：澜清水务
规格：UF 20t/h 内压式
计量单位：套（现货 16 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 38, DATE_SUB(NOW(), INTERVAL 54 DAY), NOW(), 0),
(1243, 1, 122, 14, '全自动加药装置 双桶双泵', '澜清水务', '双桶双泵', 12800, '[{"qty":2,"price":12800},{"qty":5,"price":12288}]', 33, '', '品牌：澜清水务
规格：双桶双泵
计量单位：套（现货 33 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 42, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW(), 0),
(1244, 2, 122, 14, 'PAM 自动溶药投加系统', '绿净环保', '自动溶药投加系统', 18600, '[{"qty":2,"price":18600}]', 29, '', '品牌：绿净环保
规格：自动溶药投加系统
计量单位：套（现货 29 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 18, DATE_SUB(NOW(), INTERVAL 8 DAY), NOW(), 0),
(1245, 1, 123, 14, '涤纶滤布 750B 压滤机专用', '绿净环保', '750B 压滤机专用', 26, '[{"qty":100,"price":26},{"qty":1000,"price":24.96}]', 9519, '', '品牌：绿净环保
规格：750B 压滤机专用
计量单位：平米（现货 9519 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4131, DATE_SUB(NOW(), INTERVAL 15 DAY), NOW(), 0),
(1246, 2, 123, 14, 'PP 滤布 透气 120μm', '澜清水务', '滤布 透气 120μm', 18, '[{"qty":100,"price":18}]', 5543, '', '品牌：澜清水务
规格：滤布 透气 120μm
计量单位：平米（现货 5543 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 684, DATE_SUB(NOW(), INTERVAL 22 DAY), NOW(), 0),
(1247, 1, 124, 14, '活性炭吸附箱 5000 风量', '澜清水务', '5000 风量', 8600, '[{"qty":5,"price":8600},{"qty":1000,"price":8256}]', 428, '', '品牌：澜清水务
规格：5000 风量
计量单位：台（现货 428 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 636, DATE_SUB(NOW(), INTERVAL 29 DAY), NOW(), 0),
(1248, 2, 124, 14, '布袋除尘器 脉冲 100 袋', '绿净环保', '脉冲 100 袋', 28600, '[{"qty":2,"price":28600}]', 25, '', '品牌：绿净环保
规格：脉冲 100 袋
计量单位：台（现货 25 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 17, DATE_SUB(NOW(), INTERVAL 36 DAY), NOW(), 0),
(1249, 1, 125, 14, 'UV 光解净化器 10000 风量', '绿净环保', '光解净化器 10000 风量', 6860, '[{"qty":5,"price":6860},{"qty":1000,"price":6585.6}]', 475, '', '品牌：绿净环保
规格：光解净化器 10000 风量
计量单位：台（现货 475 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 725, DATE_SUB(NOW(), INTERVAL 43 DAY), NOW(), 0),
(1250, 2, 125, 14, 'RTO 蓄热焚烧炉 5000 风量', '澜清水务', '蓄热焚烧炉 5000 风量', 386000, '[{"qty":2,"price":386000}]', 8, '', '品牌：澜清水务
规格：蓄热焚烧炉 5000 风量
计量单位：套（现货 8 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 36, DATE_SUB(NOW(), INTERVAL 50 DAY), NOW(), 0),
(1251, 1, 126, 14, '餐厨垃圾处理设备 5t/d 好氧', '澜清水务', '5t/d 好氧', 286000, '[{"qty":2,"price":286000},{"qty":5,"price":274560}]', 14, '', '品牌：澜清水务
规格：5t/d 好氧
计量单位：套（现货 14 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 86, DATE_SUB(NOW(), INTERVAL 57 DAY), NOW(), 0),
(1252, 2, 126, 14, '工业固废破碎分选线', '绿净环保', '工业固废破碎分选线', 586000, '[{"qty":2,"price":586000}]', 21, '', '品牌：绿净环保
规格：工业固废破碎分选线
计量单位：条（现货 21 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 16, DATE_SUB(NOW(), INTERVAL 4 DAY), NOW(), 0),
(1253, 1, 127, 14, '隔声屏障 高速公路用 金属复合', '绿净环保', '高速公路用 金属复合', 586, '[{"qty":100,"price":586},{"qty":1000,"price":562.56}]', 9731, '', '品牌：绿净环保
规格：高速公路用 金属复合
计量单位：平米（现货 9731 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4519, DATE_SUB(NOW(), INTERVAL 11 DAY), NOW(), 0),
(1254, 2, 127, 14, '工业隔声罩 拼装式', '澜清水务', '拼装式', 1280, '[{"qty":5,"price":1280}]', 299, '', '品牌：澜清水务
规格：拼装式
计量单位：平米（现货 299 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 46, DATE_SUB(NOW(), INTERVAL 18 DAY), NOW(), 0),
(1255, 1, 128, 14, 'PP 滤芯 10 寸 5μm 熔喷', '澜清水务', '滤芯 10 寸 5μm 熔喷', 3.6, '[{"qty":100,"price":3.6},{"qty":1000,"price":3.46}]', 9784, '', '品牌：澜清水务
规格：滤芯 10 寸 5μm 熔喷
计量单位：支（现货 9784 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4616, DATE_SUB(NOW(), INTERVAL 25 DAY), NOW(), 0),
(1256, 2, 128, 14, '椰壳活性炭 碘值 1000 25kg/袋', '绿净环保', '碘值 1000 25kg/袋', 186, '[{"qty":100,"price":186}]', 5748, '', '品牌：绿净环保
规格：碘值 1000 25kg/袋
计量单位：袋（现货 5748 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 53, DATE_SUB(NOW(), INTERVAL 32 DAY), NOW(), 0),
(1257, 1, 129, 14, '折叠滤芯 40 寸 0.22μm PES', '绿净环保', '40 寸 0.22μm PES', 68, '[{"qty":100,"price":68},{"qty":1000,"price":65.28}]', 9837, '', '品牌：绿净环保
规格：40 寸 0.22μm PES
计量单位：支（现货 9837 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4713, DATE_SUB(NOW(), INTERVAL 39 DAY), NOW(), 0),
(1258, 2, 129, 14, '钛棒滤芯 10 寸 烧结', '澜清水务', '10 寸 烧结', 186, '[{"qty":100,"price":186}]', 5789, '', '品牌：澜清水务
规格：10 寸 烧结
计量单位：支（现货 5789 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 84, DATE_SUB(NOW(), INTERVAL 46 DAY), NOW(), 0),
(1259, 1, 130, 14, '柱状活性炭 煤质 4.0mm', '澜清水务', '煤质 4.0mm', 4680, '[{"qty":5,"price":4680},{"qty":1000,"price":4492.8}]', 710, '', '品牌：澜清水务
规格：煤质 4.0mm
计量单位：吨（现货 710 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 470, DATE_SUB(NOW(), INTERVAL 53 DAY), NOW(), 0),
(1260, 2, 130, 14, '分子筛 13X 球形 1.6-2.5mm', '绿净环保', '13X 球形 1.6-2.5mm', 8600, '[{"qty":5,"price":8600}]', 410, '', '品牌：绿净环保
规格：13X 球形 1.6-2.5mm
计量单位：吨（现货 410 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 19, DATE_SUB(NOW(), INTERVAL 0 DAY), NOW(), 0);
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1261, 1, 131, 14, '聚丙烯酰胺 PAM 阴离子 1200 万', '绿净环保', 'PAM 阴离子 1200 万', 9650, '[{"qty":5,"price":9650},{"qty":1000,"price":9264}]', 757, '', '品牌：绿净环保
规格：PAM 阴离子 1200 万
计量单位：吨（现货 757 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 559, DATE_SUB(NOW(), INTERVAL 7 DAY), NOW(), 0),
(1262, 2, 131, 14, '聚合氯化铝 PAC 30% 饮水级', '澜清水务', 'PAC 30% 饮水级', 1860, '[{"qty":5,"price":1860}]', 447, '', '品牌：澜清水务
规格：PAC 30% 饮水级
计量单位：吨（现货 447 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 42, DATE_SUB(NOW(), INTERVAL 14 DAY), NOW(), 0),
(1263, 1, 132, 15, '尿素 46.4% 小颗粒', '澜丰农科', '46.4% 小颗粒', 1890, '[{"qty":5,"price":1890},{"qty":1000,"price":1814.4}]', 804, '', '品牌：澜丰农科
规格：46.4% 小颗粒
计量单位：吨（现货 804 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 648, DATE_SUB(NOW(), INTERVAL 21 DAY), NOW(), 0),
(1264, 2, 132, 15, '复合肥料 15-15-15 氯基', '沃土农资', '15-15-15 氯基', 2680, '[{"qty":5,"price":2680}]', 484, '', '品牌：沃土农资
规格：15-15-15 氯基
计量单位：吨（现货 484 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 65, DATE_SUB(NOW(), INTERVAL 28 DAY), NOW(), 0),
(1265, 1, 133, 15, '磷酸二铵 18-46-0 颗粒', '沃土农资', '18-46-0 颗粒', 3280, '[{"qty":5,"price":3280},{"qty":1000,"price":3148.8}]', 851, '', '品牌：沃土农资
规格：18-46-0 颗粒
计量单位：吨（现货 851 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 737, DATE_SUB(NOW(), INTERVAL 35 DAY), NOW(), 0),
(1266, 2, 133, 15, '氯化钾 60% 颗粒', '澜丰农科', '60% 颗粒', 2860, '[{"qty":5,"price":2860}]', 521, '', '品牌：澜丰农科
规格：60% 颗粒
计量单位：吨（现货 521 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 88, DATE_SUB(NOW(), INTERVAL 42 DAY), NOW(), 0),
(1267, 1, 134, 15, '草甘膦异丙胺盐水剂 41% 200L/桶', '澜丰农科', '41% 200L/桶', 3860, '[{"qty":5,"price":3860},{"qty":1000,"price":3705.6}]', 898, '', '品牌：澜丰农科
规格：41% 200L/桶
计量单位：桶（现货 898 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 126, DATE_SUB(NOW(), INTERVAL 49 DAY), NOW(), 0),
(1268, 2, 134, 15, '吡虫啉可湿性粉剂 25% 100g×40', '沃土农资', '25% 100g×40', 86, '[{"qty":100,"price":86}]', 5994, '', '品牌：沃土农资
规格：25% 100g×40
计量单位：箱（现货 5994 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 239, DATE_SUB(NOW(), INTERVAL 56 DAY), NOW(), 0),
(1269, 1, 135, 15, '玉米杂交种 春播 4500 粒/袋', '沃土农资', '春播 4500 粒/袋', 38, '[{"qty":100,"price":38},{"qty":1000,"price":36.48}]', 10155, '', '品牌：沃土农资
规格：春播 4500 粒/袋
计量单位：袋（现货 10155 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1095, DATE_SUB(NOW(), INTERVAL 3 DAY), NOW(), 0),
(1270, 2, 135, 15, '西兰花种子 F1 1000 粒/罐', '澜丰农科', 'F1 1000 粒/罐', 128, '[{"qty":100,"price":128}]', 6035, '', '品牌：澜丰农科
规格：F1 1000 粒/罐
计量单位：罐（现货 6035 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 270, DATE_SUB(NOW(), INTERVAL 10 DAY), NOW(), 0),
(1271, 1, 136, 15, '小麦 二等白麦 容重 780', '澜丰农科', '二等白麦 容重 780', 2480, '[{"qty":5,"price":2480},{"qty":1000,"price":2380.8}]', 992, '', '品牌：澜丰农科
规格：二等白麦 容重 780
计量单位：吨（现货 992 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 304, DATE_SUB(NOW(), INTERVAL 17 DAY), NOW(), 0),
(1272, 2, 136, 15, '红富士苹果 80mm 一级 箱装', '沃土农资', '80mm 一级 箱装', 86, '[{"qty":100,"price":86}]', 6076, '', '品牌：沃土农资
规格：80mm 一级 箱装
计量单位：箱（现货 6076 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 301, DATE_SUB(NOW(), INTERVAL 24 DAY), NOW(), 0),
(1273, 1, 137, 15, '玉米 二等 黄玉米 水分 14', '沃土农资', '二等 黄玉米 水分 14', 2260, '[{"qty":5,"price":2260},{"qty":1000,"price":2169.6}]', 1039, '', '品牌：沃土农资
规格：二等 黄玉米 水分 14
计量单位：吨（现货 1039 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 393, DATE_SUB(NOW(), INTERVAL 31 DAY), NOW(), 0),
(1274, 2, 137, 15, '粳稻 中科发 5 号 出米率 68', '澜丰农科', '中科发 5 号 出米率 68', 2860, '[{"qty":5,"price":2860}]', 669, '', '品牌：澜丰农科
规格：中科发 5 号 出米率 68
计量单位：吨（现货 669 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 84, DATE_SUB(NOW(), INTERVAL 38 DAY), NOW(), 0),
(1275, 1, 138, 15, '土豆 荷兰十五 3 两起', '澜丰农科', '荷兰十五 3 两起', 1.86, '[{"qty":100,"price":1.86},{"qty":1000,"price":1.79}]', 10314, '', '品牌：澜丰农科
规格：荷兰十五 3 两起
计量单位：kg（现货 10314 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1386, DATE_SUB(NOW(), INTERVAL 45 DAY), NOW(), 0),
(1276, 2, 138, 15, '大棚黄瓜 密刺 22cm 起', '沃土农资', '密刺 22cm 起', 2.68, '[{"qty":100,"price":2.68}]', 6158, '', '品牌：沃土农资
规格：密刺 22cm 起
计量单位：kg（现货 6158 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 363, DATE_SUB(NOW(), INTERVAL 52 DAY), NOW(), 0),
(1277, 1, 139, 15, '白条鸡 单冻 948g 上下', '沃土农资', '单冻 948g 上下', 8.6, '[{"qty":100,"price":8.6},{"qty":1000,"price":8.26}]', 10367, '', '品牌：沃土农资
规格：单冻 948g 上下
计量单位：kg（现货 10367 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1483, DATE_SUB(NOW(), INTERVAL 59 DAY), NOW(), 0),
(1278, 2, 139, 15, '鸡蛋 粉壳 大码 45 斤/箱', '澜丰农科', '粉壳 大码 45 斤/箱', 216, '[{"qty":100,"price":216}]', 6199, '', '品牌：澜丰农科
规格：粉壳 大码 45 斤/箱
计量单位：箱（现货 6199 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 394, DATE_SUB(NOW(), INTERVAL 6 DAY), NOW(), 0),
(1279, 1, 140, 15, '速冻水饺 B 端 2.5kg×6/箱', '澜丰农科', 'B 端 2.5kg×6/箱', 128, '[{"qty":100,"price":128},{"qty":1000,"price":122.88}]', 10420, '', '品牌：澜丰农科
规格：B 端 2.5kg×6/箱
计量单位：箱（现货 10420 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1580, DATE_SUB(NOW(), INTERVAL 13 DAY), NOW(), 0),
(1280, 2, 140, 15, '泡沫保温箱 3 号 5cm 厚', '沃土农资', '3 号 5cm 厚', 6.8, '[{"qty":100,"price":6.8}]', 6240, '', '品牌：沃土农资
规格：3 号 5cm 厚
计量单位：个（现货 6240 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 425, DATE_SUB(NOW(), INTERVAL 20 DAY), NOW(), 0);
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1281, 1, 141, 15, '调理腌制鸡腿排 1kg×10/箱', '沃土农资', '1kg×10/箱', 128, '[{"qty":100,"price":128},{"qty":1000,"price":122.88}]', 10473, '', '品牌：沃土农资
规格：1kg×10/箱
计量单位：箱（现货 10473 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1677, DATE_SUB(NOW(), INTERVAL 27 DAY), NOW(), 0),
(1282, 2, 141, 15, '免浆鱼片 巴沙 2.5kg/袋', '澜丰农科', '巴沙 2.5kg/袋', 68, '[{"qty":100,"price":68}]', 6281, '', '品牌：澜丰农科
规格：巴沙 2.5kg/袋
计量单位：袋（现货 6281 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 456, DATE_SUB(NOW(), INTERVAL 34 DAY), NOW(), 0),
(1283, 1, 142, 15, 'EPS 保温箱 海鲜 8 号', '澜丰农科', '保温箱 海鲜 8 号', 9.8, '[{"qty":100,"price":9.8},{"qty":1000,"price":9.41}]', 10526, '', '品牌：澜丰农科
规格：保温箱 海鲜 8 号
计量单位：个（现货 10526 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1774, DATE_SUB(NOW(), INTERVAL 41 DAY), NOW(), 0),
(1284, 2, 142, 15, '冰袋 400ml 生物凝胶', '沃土农资', '400ml 生物凝胶', 0.68, '[{"qty":100,"price":0.68}]', 6322, '', '品牌：沃土农资
规格：400ml 生物凝胶
计量单位：个（现货 6322 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 487, DATE_SUB(NOW(), INTERVAL 48 DAY), NOW(), 0),
(1285, 1, 143, 15, '冷链零担 -18°C 城市配送', '沃土农资', '-18°C 城市配送', 0.86, '[{"qty":100,"price":0.86},{"qty":1000,"price":0.83}]', 10579, '', '品牌：沃土农资
规格：-18°C 城市配送
计量单位：kg（现货 10579 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1871, DATE_SUB(NOW(), INTERVAL 55 DAY), NOW(), 0),
(1286, 2, 143, 15, '生鲜冷链整车 9.6m 冷藏车', '澜丰农科', '9.6m 冷藏车', 2860, '[{"qty":5,"price":2860}]', 891, '', '品牌：澜丰农科
规格：9.6m 冷藏车
计量单位：车次（现货 891 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 30, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW(), 0),
(1287, 1, 144, 16, 'E0 多层实木板 18mm 1220×2440', '澜居家装', '多层实木板 18mm 1220×2440', 218, '[{"qty":100,"price":218},{"qty":1000,"price":209.28}]', 10632, '', '品牌：澜居家装
规格：多层实木板 18mm 1220×2440
计量单位：张（现货 10632 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1968, DATE_SUB(NOW(), INTERVAL 9 DAY), NOW(), 0),
(1288, 2, 144, 16, '内墙乳胶漆 净味工程款 20kg', '悦居工坊', '净味工程款 20kg', 86, '[{"qty":100,"price":86}]', 6404, '', '品牌：悦居工坊
规格：净味工程款 20kg
计量单位：桶（现货 6404 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 549, DATE_SUB(NOW(), INTERVAL 16 DAY), NOW(), 0),
(1289, 1, 145, 16, 'OSB 定向刨花板 12mm 欧标', '悦居工坊', '定向刨花板 12mm 欧标', 128, '[{"qty":100,"price":128},{"qty":1000,"price":122.88}]', 10685, '', '品牌：悦居工坊
规格：定向刨花板 12mm 欧标
计量单位：张（现货 10685 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2065, DATE_SUB(NOW(), INTERVAL 23 DAY), NOW(), 0),
(1290, 2, 145, 16, '生态板 马六甲芯 17mm 双面', '澜居家装', '马六甲芯 17mm 双面', 186, '[{"qty":100,"price":186}]', 6445, '', '品牌：澜居家装
规格：马六甲芯 17mm 双面
计量单位：张（现货 6445 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 580, DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
(1291, 1, 146, 16, '外墙乳胶漆 弹性抗碱 20kg', '澜居家装', '弹性抗碱 20kg', 168, '[{"qty":100,"price":168},{"qty":1000,"price":161.28}]', 10738, '', '品牌：澜居家装
规格：弹性抗碱 20kg
计量单位：桶（现货 10738 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2162, DATE_SUB(NOW(), INTERVAL 37 DAY), NOW(), 0),
(1292, 2, 146, 16, '环氧地坪漆 底面套装 30kg', '悦居工坊', '底面套装 30kg', 386, '[{"qty":100,"price":386}]', 6486, '', '品牌：悦居工坊
规格：底面套装 30kg
计量单位：组（现货 6486 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 611, DATE_SUB(NOW(), INTERVAL 44 DAY), NOW(), 0),
(1293, 1, 147, 16, '通体大理石瓷砖 800×800 一级品', '悦居工坊', '800×800 一级品', 32, '[{"qty":100,"price":32},{"qty":1000,"price":30.72}]', 10791, '', '品牌：悦居工坊
规格：800×800 一级品
计量单位：片（现货 10791 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2259, DATE_SUB(NOW(), INTERVAL 51 DAY), NOW(), 0),
(1294, 2, 147, 16, 'SPC 石塑地板 4mm 锁扣', '澜居家装', '石塑地板 4mm 锁扣', 38, '[{"qty":100,"price":38}]', 6527, '', '品牌：澜居家装
规格：石塑地板 4mm 锁扣
计量单位：平米（现货 6527 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 642, DATE_SUB(NOW(), INTERVAL 58 DAY), NOW(), 0),
(1295, 1, 148, 16, '人体工学办公椅 网布 双背', '澜居家装', '网布 双背', 386, '[{"qty":100,"price":386},{"qty":1000,"price":370.56}]', 10844, '', '品牌：澜居家装
规格：网布 双背
计量单位：把（现货 10844 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2356, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW(), 0),
(1296, 2, 148, 16, '酒店实木床 1.5m 白蜡木', '悦居工坊', '1.5m 白蜡木', 1680, '[{"qty":5,"price":1680}]', 176, '', '品牌：悦居工坊
规格：1.5m 白蜡木
计量单位：张（现货 176 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 49, DATE_SUB(NOW(), INTERVAL 12 DAY), NOW(), 0),
(1297, 1, 149, 16, '屏风工位 4 人位 含桌椅', '悦居工坊', '4 人位 含桌椅', 1280, '[{"qty":5,"price":1280},{"qty":1000,"price":1228.8}]', 1603, '', '品牌：悦居工坊
规格：4 人位 含桌椅
计量单位：组（现货 1603 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 761, DATE_SUB(NOW(), INTERVAL 19 DAY), NOW(), 0),
(1298, 2, 149, 16, '钢制文件柜 玻璃开门 2000 高', '澜居家装', '玻璃开门 2000 高', 486, '[{"qty":100,"price":486}]', 6609, '', '品牌：澜居家装
规格：玻璃开门 2000 高
计量单位：个（现货 6609 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 704, DATE_SUB(NOW(), INTERVAL 26 DAY), NOW(), 0),
(1299, 1, 150, 16, '酒店标间成套家具 全屋定制', '澜居家装', '全屋定制', 3860, '[{"qty":5,"price":3860},{"qty":1000,"price":3705.6}]', 1650, '', '品牌：澜居家装
规格：全屋定制
计量单位：间（现货 1650 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 150, DATE_SUB(NOW(), INTERVAL 33 DAY), NOW(), 0),
(1300, 2, 150, 16, '酒店餐桌椅 四人圆桌', '悦居工坊', '四人圆桌', 686, '[{"qty":100,"price":686}]', 6650, '', '品牌：悦居工坊
规格：四人圆桌
计量单位：组（现货 6650 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 735, DATE_SUB(NOW(), INTERVAL 40 DAY), NOW(), 0);
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1301, 1, 151, 16, '塑料收纳箱 60L 加厚', '悦居工坊', '60L 加厚', 28.6, '[{"qty":100,"price":28.6},{"qty":1000,"price":27.46}]', 11003, '', '品牌：悦居工坊
规格：60L 加厚
计量单位：个（现货 11003 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2647, DATE_SUB(NOW(), INTERVAL 47 DAY), NOW(), 0),
(1302, 2, 151, 16, '晾衣架 落地翼型 不锈钢', '澜居家装', '落地翼型 不锈钢', 38.6, '[{"qty":100,"price":38.6}]', 6691, '', '品牌：澜居家装
规格：落地翼型 不锈钢
计量单位：个（现货 6691 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 766, DATE_SUB(NOW(), INTERVAL 54 DAY), NOW(), 0),
(1303, 1, 152, 16, '304 不锈钢合页 4 寸轴承静音', '澜居家装', '不锈钢合页 4 寸轴承静音', 4.8, '[{"qty":100,"price":4.8},{"qty":1000,"price":4.61}]', 11056, '', '品牌：澜居家装
规格：不锈钢合页 4 寸轴承静音
计量单位：个（现货 11056 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2744, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW(), 0),
(1304, 2, 152, 16, '锂电无刷冲击钻 20V 双速', '悦居工坊', '20V 双速', 186, '[{"qty":100,"price":186}]', 6732, '', '品牌：悦居工坊
规格：20V 双速
计量单位：台（现货 6732 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 797, DATE_SUB(NOW(), INTERVAL 8 DAY), NOW(), 0),
(1305, 1, 153, 16, '304 不锈钢门锁执手锁 6068', '悦居工坊', '不锈钢门锁执手锁 6068', 26.8, '[{"qty":100,"price":26.8},{"qty":1000,"price":25.73}]', 11109, '', '品牌：悦居工坊
规格：不锈钢门锁执手锁 6068
计量单位：套（现货 11109 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2841, DATE_SUB(NOW(), INTERVAL 15 DAY), NOW(), 0),
(1306, 2, 153, 16, '内平开下悬五金系统 欧标槽', '澜居家装', '欧标槽', 86, '[{"qty":100,"price":86}]', 6773, '', '品牌：澜居家装
规格：欧标槽
计量单位：套（现货 6773 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 42, DATE_SUB(NOW(), INTERVAL 22 DAY), NOW(), 0),
(1307, 1, 154, 16, '铬钒钢梅花扳手 17mm 亮面', '澜居家装', '17mm 亮面', 12.6, '[{"qty":100,"price":12.6},{"qty":1000,"price":12.1}]', 11162, '', '品牌：澜居家装
规格：17mm 亮面
计量单位：把（现货 11162 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 2938, DATE_SUB(NOW(), INTERVAL 29 DAY), NOW(), 0),
(1308, 2, 154, 16, '组合工具套装 46 件 手提箱', '悦居工坊', '46 件 手提箱', 98, '[{"qty":100,"price":98}]', 6814, '', '品牌：悦居工坊
规格：46 件 手提箱
计量单位：套（现货 6814 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 73, DATE_SUB(NOW(), INTERVAL 36 DAY), NOW(), 0),
(1309, 1, 155, 16, '无刷角磨机 850W 125mm', '悦居工坊', '850W 125mm', 128, '[{"qty":100,"price":128},{"qty":1000,"price":122.88}]', 11215, '', '品牌：悦居工坊
规格：850W 125mm
计量单位：台（现货 11215 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 3035, DATE_SUB(NOW(), INTERVAL 43 DAY), NOW(), 0),
(1310, 2, 155, 16, '锂电电锤 20V 无刷 四坑', '澜居家装', '20V 无刷 四坑', 268, '[{"qty":100,"price":268}]', 6855, '', '品牌：澜居家装
规格：20V 无刷 四坑
计量单位：台（现货 6855 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 104, DATE_SUB(NOW(), INTERVAL 50 DAY), NOW(), 0),
(1311, 1, 156, 1, '三级螺纹钢 HRB400E Φ16mm×9m 定尺', '澜建新材', 'HRB400E Φ16mm×9m 定尺', 3320, '[{"qty":5,"price":3320},{"qty":1000,"price":3187.2}]', 1932, '', '品牌：澜建新材
规格：HRB400E Φ16mm×9m 定尺
计量单位：吨（现货 1932 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 684, DATE_SUB(NOW(), INTERVAL 57 DAY), NOW(), 0),
(1312, 2, 156, 1, '三级螺纹钢 HRB400E Φ20mm×12m 定尺', '华冶工贸', 'HRB400E Φ20mm×12m 定尺', 3380, '[{"qty":5,"price":3380}]', 472, '', '品牌：华冶工贸
规格：HRB400E Φ20mm×12m 定尺
计量单位：吨（现货 472 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 41, DATE_SUB(NOW(), INTERVAL 4 DAY), NOW(), 0),
(1313, 1, 157, 1, '热轧光圆钢筋 HPB300 Φ8mm×9m 定尺', '华冶工贸', 'HPB300 Φ8mm×9m 定尺', 3520, '[{"qty":5,"price":3520},{"qty":1000,"price":3379.2}]', 1979, '', '品牌：华冶工贸
规格：HPB300 Φ8mm×9m 定尺
计量单位：吨（现货 1979 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 773, DATE_SUB(NOW(), INTERVAL 11 DAY), NOW(), 0),
(1314, 2, 157, 1, '热轧带肋钢筋 HRB500E Φ18mm×9m 定尺', '澜建新材', 'HRB500E Φ18mm×9m 定尺', 3650, '[{"qty":5,"price":3650}]', 509, '', '品牌：澜建新材
规格：HRB500E Φ18mm×9m 定尺
计量单位：吨（现货 509 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 64, DATE_SUB(NOW(), INTERVAL 18 DAY), NOW(), 0),
(1315, 1, 158, 1, '低碳钢热轧圆盘条 Q195 Φ6.5mm', '澜建新材', 'Q195 Φ6.5mm', 3480, '[{"qty":5,"price":3480},{"qty":1000,"price":3340.8}]', 2026, '', '品牌：澜建新材
规格：Q195 Φ6.5mm
计量单位：吨（现货 2026 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 162, DATE_SUB(NOW(), INTERVAL 25 DAY), NOW(), 0),
(1316, 2, 158, 1, '冷拉盘圆 Q235B Φ8mm', '华冶工贸', 'Q235B Φ8mm', 3690, '[{"qty":5,"price":3690}]', 546, '', '品牌：华冶工贸
规格：Q235B Φ8mm
计量单位：吨（现货 546 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 87, DATE_SUB(NOW(), INTERVAL 32 DAY), NOW(), 0),
(1317, 1, 159, 1, '高速线材 SWRH82B Φ10mm', '华冶工贸', 'SWRH82B Φ10mm', 3980, '[{"qty":5,"price":3980},{"qty":1000,"price":3820.8}]', 2073, '', '品牌：华冶工贸
规格：SWRH82B Φ10mm
计量单位：吨（现货 2073 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 251, DATE_SUB(NOW(), INTERVAL 39 DAY), NOW(), 0),
(1318, 2, 159, 1, '高速线材 Q235B Φ8mm', '澜建新材', 'Q235B Φ8mm', 3460, '[{"qty":5,"price":3460}]', 583, '', '品牌：澜建新材
规格：Q235B Φ8mm
计量单位：吨（现货 583 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 14, DATE_SUB(NOW(), INTERVAL 46 DAY), NOW(), 0),
(1319, 1, 160, 1, '热轧工字钢 Q235B 20#a 9m 定尺', '澜建新材', 'Q235B 20#a 9m 定尺', 3780, '[{"qty":5,"price":3780},{"qty":1000,"price":3628.8}]', 2120, '', '品牌：澜建新材
规格：Q235B 20#a 9m 定尺
计量单位：吨（现货 2120 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 340, DATE_SUB(NOW(), INTERVAL 53 DAY), NOW(), 0),
(1320, 2, 160, 1, '热轧工字钢 Q235B 10# 12m 定尺', '华冶工贸', 'Q235B 10# 12m 定尺', 3860, '[{"qty":5,"price":3860}]', 620, '', '品牌：华冶工贸
规格：Q235B 10# 12m 定尺
计量单位：吨（现货 620 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 37, DATE_SUB(NOW(), INTERVAL 0 DAY), NOW(), 0);
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1321, 1, 161, 1, '热轧等边角钢 Q235B 50×50×5 6m 定尺', '华冶工贸', 'Q235B 50×50×5 6m 定尺', 3720, '[{"qty":5,"price":3720},{"qty":1000,"price":3571.2}]', 2167, '', '品牌：华冶工贸
规格：Q235B 50×50×5 6m 定尺
计量单位：吨（现货 2167 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 429, DATE_SUB(NOW(), INTERVAL 7 DAY), NOW(), 0),
(1322, 2, 161, 1, '热轧不等边角钢 Q235B 80×50×6 9m 定尺', '澜建新材', 'Q235B 80×50×6 9m 定尺', 3810, '[{"qty":5,"price":3810}]', 657, '', '品牌：澜建新材
规格：Q235B 80×50×6 9m 定尺
计量单位：吨（现货 657 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 60, DATE_SUB(NOW(), INTERVAL 14 DAY), NOW(), 0),
(1323, 1, 162, 1, '热轧槽钢 Q235B 10# 9m 定尺', '澜建新材', 'Q235B 10# 9m 定尺', 3750, '[{"qty":5,"price":3750},{"qty":1000,"price":3600}]', 2214, '', '品牌：澜建新材
规格：Q235B 10# 9m 定尺
计量单位：吨（现货 2214 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 518, DATE_SUB(NOW(), INTERVAL 21 DAY), NOW(), 0),
(1324, 2, 162, 1, '热轧槽钢 Q345B 16# 12m 定尺', '华冶工贸', 'Q345B 16# 12m 定尺', 3960, '[{"qty":5,"price":3960}]', 694, '', '品牌：华冶工贸
规格：Q345B 16# 12m 定尺
计量单位：吨（现货 694 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 83, DATE_SUB(NOW(), INTERVAL 28 DAY), NOW(), 0),
(1325, 1, 163, 2, '全棉平纹坯布 21S×21S 108×58', '恒溢纺织', '21S×21S 108×58', 9.6, '[{"qty":100,"price":9.6},{"qty":1000,"price":9.22}]', 11639, '', '品牌：恒溢纺织
规格：21S×21S 108×58
计量单位：米（现货 11639 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 3811, DATE_SUB(NOW(), INTERVAL 35 DAY), NOW(), 0),
(1326, 2, 163, 2, '全棉斜纹布 40S×40S 133×72', '锦纺新材', '40S×40S 133×72', 11.5, '[{"qty":100,"price":11.5}]', 7183, '', '品牌：锦纺新材
规格：40S×40S 133×72
计量单位：米（现货 7183 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 352, DATE_SUB(NOW(), INTERVAL 42 DAY), NOW(), 0),
(1327, 1, 164, 2, '涤棉混纺坯布 CVC 60/40 32S×32S', '锦纺新材', 'CVC 60/40 32S×32S', 7.9, '[{"qty":100,"price":7.9},{"qty":1000,"price":7.58}]', 11692, '', '品牌：锦纺新材
规格：CVC 60/40 32S×32S
计量单位：米（现货 11692 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 3908, DATE_SUB(NOW(), INTERVAL 49 DAY), NOW(), 0),
(1328, 2, 164, 2, '棉锦混纺面料 棉80锦20 21S', '恒溢纺织', '棉80锦20 21S', 16.8, '[{"qty":100,"price":16.8}]', 7224, '', '品牌：恒溢纺织
规格：棉80锦20 21S
计量单位：米（现货 7224 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 383, DATE_SUB(NOW(), INTERVAL 56 DAY), NOW(), 0),
(1329, 1, 165, 2, '涤塔夫 190T 幅宽 150cm', '恒溢纺织', '190T 幅宽 150cm', 3.6, '[{"qty":100,"price":3.6},{"qty":1000,"price":3.46}]', 11745, '', '品牌：恒溢纺织
规格：190T 幅宽 150cm
计量单位：米（现货 11745 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4005, DATE_SUB(NOW(), INTERVAL 3 DAY), NOW(), 0),
(1330, 2, 165, 2, '春亚纺 300D PVC 涂层', '锦纺新材', '300D PVC 涂层', 6.5, '[{"qty":100,"price":6.5}]', 7265, '', '品牌：锦纺新材
规格：300D PVC 涂层
计量单位：米（现货 7265 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 414, DATE_SUB(NOW(), INTERVAL 10 DAY), NOW(), 0),
(1331, 1, 166, 2, '尼丝纺 70D 半光', '锦纺新材', '70D 半光', 5.8, '[{"qty":100,"price":5.8},{"qty":1000,"price":5.57}]', 11798, '', '品牌：锦纺新材
规格：70D 半光
计量单位：米（现货 11798 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4102, DATE_SUB(NOW(), INTERVAL 17 DAY), NOW(), 0),
(1332, 2, 166, 2, '锦纶牛津布 420D DWR 防泼水', '恒溢纺织', '420D DWR 防泼水', 11.2, '[{"qty":100,"price":11.2}]', 7306, '', '品牌：恒溢纺织
规格：420D DWR 防泼水
计量单位：米（现货 7306 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 445, DATE_SUB(NOW(), INTERVAL 24 DAY), NOW(), 0),
(1333, 1, 167, 2, 'PVC 涂层牛津布 600D 防水', '恒溢纺织', '涂层牛津布 600D 防水', 9.8, '[{"qty":100,"price":9.8},{"qty":1000,"price":9.41}]', 11851, '', '品牌：恒溢纺织
规格：涂层牛津布 600D 防水
计量单位：米（现货 11851 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4199, DATE_SUB(NOW(), INTERVAL 31 DAY), NOW(), 0),
(1334, 2, 167, 2, 'TPU 复合面料 210D 耐低温', '锦纺新材', '复合面料 210D 耐低温', 13.5, '[{"qty":100,"price":13.5}]', 7347, '', '品牌：锦纺新材
规格：复合面料 210D 耐低温
计量单位：米（现货 7347 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 476, DATE_SUB(NOW(), INTERVAL 38 DAY), NOW(), 0),
(1335, 1, 168, 2, '阻燃牛津布 600D 涂层 氧指数 28', '锦纺新材', '600D 涂层 氧指数 28', 15.8, '[{"qty":100,"price":15.8},{"qty":1000,"price":15.17}]', 11904, '', '品牌：锦纺新材
规格：600D 涂层 氧指数 28
计量单位：米（现货 11904 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4296, DATE_SUB(NOW(), INTERVAL 45 DAY), NOW(), 0),
(1336, 2, 168, 2, '全棉阻燃布 CVC 240gsm EN11612', '恒溢纺织', 'CVC 240gsm EN11612', 21.5, '[{"qty":100,"price":21.5}]', 7388, '', '品牌：恒溢纺织
规格：CVC 240gsm EN11612
计量单位：米（现货 7388 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 507, DATE_SUB(NOW(), INTERVAL 52 DAY), NOW(), 0),
(1337, 1, 169, 3, '轻质碳酸钙 98% 25kg/袋', '汇润化工', '98% 25kg/袋', 880, '[{"qty":100,"price":880},{"qty":1000,"price":844.8}]', 11957, '', '品牌：汇润化工
规格：98% 25kg/袋
计量单位：吨（现货 11957 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 4393, DATE_SUB(NOW(), INTERVAL 59 DAY), NOW(), 0),
(1338, 2, 169, 3, '重质碳酸钙 400 目 25kg/袋', '澜化科技', '400 目 25kg/袋', 620, '[{"qty":100,"price":620}]', 7429, '', '品牌：澜化科技
规格：400 目 25kg/袋
计量单位：吨（现货 7429 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 538, DATE_SUB(NOW(), INTERVAL 6 DAY), NOW(), 0),
(1339, 1, 170, 3, '氢氧化钾 90% 片状', '澜化科技', '90% 片状', 7200, '[{"qty":5,"price":7200},{"qty":1000,"price":6912}]', 2590, '', '品牌：澜化科技
规格：90% 片状
计量单位：吨（现货 2590 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 530, DATE_SUB(NOW(), INTERVAL 13 DAY), NOW(), 0),
(1340, 2, 170, 3, '碳酸氢钠 工业级 99% 25kg/袋', '汇润化工', '工业级 99% 25kg/袋', 1650, '[{"qty":5,"price":1650}]', 990, '', '品牌：汇润化工
规格：工业级 99% 25kg/袋
计量单位：吨（现货 990 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 75, DATE_SUB(NOW(), INTERVAL 20 DAY), NOW(), 0);
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1341, 1, 171, 3, '工业甲醇 99.9% 净水', '汇润化工', '99.9% 净水', 2380, '[{"qty":5,"price":2380},{"qty":1000,"price":2284.8}]', 2637, '', '品牌：汇润化工
规格：99.9% 净水
计量单位：吨（现货 2637 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 619, DATE_SUB(NOW(), INTERVAL 27 DAY), NOW(), 0),
(1342, 2, 171, 3, '工业苯 99.5% 石油级', '澜化科技', '99.5% 石油级', 6850, '[{"qty":5,"price":6850}]', 127, '', '品牌：澜化科技
规格：99.5% 石油级
计量单位：吨（现货 127 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 98, DATE_SUB(NOW(), INTERVAL 34 DAY), NOW(), 0),
(1343, 1, 172, 3, '低凝抗磨液压油 L-HS46 200L/桶', '澜化科技', 'L-HS46 200L/桶', 3980, '[{"qty":5,"price":3980},{"qty":1000,"price":3820.8}]', 2684, '', '品牌：澜化科技
规格：L-HS46 200L/桶
计量单位：桶（现货 2684 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 708, DATE_SUB(NOW(), INTERVAL 41 DAY), NOW(), 0),
(1344, 2, 172, 3, '高压抗磨液压油 L-HM68 18L/桶', '汇润化工', 'L-HM68 18L/桶', 392, '[{"qty":100,"price":392}]', 7552, '', '品牌：汇润化工
规格：L-HM68 18L/桶
计量单位：桶（现货 7552 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 631, DATE_SUB(NOW(), INTERVAL 48 DAY), NOW(), 0),
(1345, 1, 173, 3, '中负荷齿轮油 L-CKC150 200L/桶', '汇润化工', 'L-CKC150 200L/桶', 4280, '[{"qty":5,"price":4280},{"qty":1000,"price":4108.8}]', 2731, '', '品牌：汇润化工
规格：L-CKC150 200L/桶
计量单位：桶（现货 2731 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 797, DATE_SUB(NOW(), INTERVAL 55 DAY), NOW(), 0),
(1346, 2, 173, 3, '合成齿轮油 PAO220 18L/桶', '澜化科技', 'PAO220 18L/桶', 980, '[{"qty":100,"price":980}]', 7593, '', '品牌：澜化科技
规格：PAO220 18L/桶
计量单位：桶（现货 7593 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 662, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW(), 0),
(1347, 1, 174, 3, '半合成切削液 MAC-500 200L/桶', '澜化科技', 'MAC-500 200L/桶', 3850, '[{"qty":5,"price":3850},{"qty":1000,"price":3696}]', 2778, '', '品牌：澜化科技
规格：MAC-500 200L/桶
计量单位：桶（现货 2778 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 186, DATE_SUB(NOW(), INTERVAL 9 DAY), NOW(), 0),
(1348, 2, 174, 3, '全合成磨削液 25kg/桶', '汇润化工', '25kg/桶', 620, '[{"qty":100,"price":620}]', 7634, '', '品牌：汇润化工
规格：25kg/桶
计量单位：桶（现货 7634 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 693, DATE_SUB(NOW(), INTERVAL 16 DAY), NOW(), 0),
(1349, 1, 175, 4, '贴片厚膜电阻 0603 1KΩ ±1%', '启明微电', '0603 1KΩ ±1%', 0.008, '[{"qty":100,"price":0.008},{"qty":1000,"price":0.01}]', 12275, '', '品牌：启明微电
规格：0603 1KΩ ±1%
计量单位：颗（现货 12275 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 775, DATE_SUB(NOW(), INTERVAL 23 DAY), NOW(), 0),
(1350, 2, 175, 4, '贴片精密电阻 1206 0.1% 25ppm', '澜芯电子', '1206 0.1% 25ppm', 0.12, '[{"qty":100,"price":0.12}]', 7675, '', '品牌：澜芯电子
规格：1206 0.1% 25ppm
计量单位：颗（现货 7675 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 724, DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
(1351, 1, 176, 4, '直插铝电解电容 470uF/16V 8×10', '澜芯电子', '470uF/16V 8×10', 0.16, '[{"qty":100,"price":0.16},{"qty":1000,"price":0.15}]', 12328, '', '品牌：澜芯电子
规格：470uF/16V 8×10
计量单位：颗（现货 12328 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 872, DATE_SUB(NOW(), INTERVAL 37 DAY), NOW(), 0),
(1352, 2, 176, 4, '贴片铝电解电容 100uF/25V 6.3×5.4', '启明微电', '100uF/25V 6.3×5.4', 0.42, '[{"qty":100,"price":0.42}]', 7716, '', '品牌：启明微电
规格：100uF/25V 6.3×5.4
计量单位：颗（现货 7716 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 755, DATE_SUB(NOW(), INTERVAL 44 DAY), NOW(), 0),
(1353, 1, 177, 4, 'SHT30 温湿度传感器 I2C', '启明微电', '温湿度传感器 I2C', 4.8, '[{"qty":100,"price":4.8},{"qty":1000,"price":4.61}]', 12381, '', '品牌：启明微电
规格：温湿度传感器 I2C
计量单位：颗（现货 12381 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 969, DATE_SUB(NOW(), INTERVAL 51 DAY), NOW(), 0),
(1354, 2, 177, 4, 'MEMS 压力传感器 0-1.6MPa', '澜芯电子', '压力传感器 0-1.6MPa', 18.5, '[{"qty":100,"price":18.5}]', 7757, '', '品牌：澜芯电子
规格：压力传感器 0-1.6MPa
计量单位：颗（现货 7757 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 786, DATE_SUB(NOW(), INTERVAL 58 DAY), NOW(), 0),
(1355, 1, 178, 4, '板端连接器 1.25mm 卧贴 8P', '澜芯电子', '1.25mm 卧贴 8P', 0.55, '[{"qty":100,"price":0.55},{"qty":1000,"price":0.53}]', 12434, '', '品牌：澜芯电子
规格：1.25mm 卧贴 8P
计量单位：个（现货 12434 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1066, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW(), 0),
(1356, 2, 178, 4, '板端连接器 2.0mm 双排 2×10P 镀金', '启明微电', '2.0mm 双排 2×10P 镀金', 1.65, '[{"qty":100,"price":1.65}]', 7798, '', '品牌：启明微电
规格：2.0mm 双排 2×10P 镀金
计量单位：个（现货 7798 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 31, DATE_SUB(NOW(), INTERVAL 12 DAY), NOW(), 0),
(1357, 1, 179, 4, '线对板连接器 1.0mm 12P 带锁', '启明微电', '1.0mm 12P 带锁', 0.85, '[{"qty":100,"price":0.85},{"qty":1000,"price":0.82}]', 12487, '', '品牌：启明微电
规格：1.0mm 12P 带锁
计量单位：个（现货 12487 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1163, DATE_SUB(NOW(), INTERVAL 19 DAY), NOW(), 0),
(1358, 2, 179, 4, '线对板连接器 PH2.0 6P 卧式', '澜芯电子', 'PH2.0 6P 卧式', 0.35, '[{"qty":100,"price":0.35}]', 7839, '', '品牌：澜芯电子
规格：PH2.0 6P 卧式
计量单位：个（现货 7839 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 62, DATE_SUB(NOW(), INTERVAL 26 DAY), NOW(), 0),
(1359, 1, 180, 4, '汽车线束总成 低压 12V 48 回路', '澜芯电子', '低压 12V 48 回路', 86, '[{"qty":100,"price":86},{"qty":1000,"price":82.56}]', 12540, '', '品牌：澜芯电子
规格：低压 12V 48 回路
计量单位：套（现货 12540 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1260, DATE_SUB(NOW(), INTERVAL 33 DAY), NOW(), 0),
(1360, 2, 180, 4, 'JST-XH 端子线 2P-10P 300mm', '启明微电', '端子线 2P-10P 300mm', 0.65, '[{"qty":100,"price":0.65}]', 7880, '', '品牌：启明微电
规格：端子线 2P-10P 300mm
计量单位：条（现货 7880 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 93, DATE_SUB(NOW(), INTERVAL 40 DAY), NOW(), 0);
INSERT IGNORE INTO t_product (prod_id, mer_id, cat_id, ind_id, title, brand, spec, price, tier_price_json, stock, cover_url, detail, status, review_status, sales, create_time, update_time, deleted) VALUES
(1361, 1, 181, 4, '7 寸 LCD 模组 1024×600 RGB 接口', '启明微电', '寸 LCD 模组 1024×600 RGB 接口', 215, '[{"qty":100,"price":215},{"qty":1000,"price":206.4}]', 12593, '', '品牌：启明微电
规格：寸 LCD 模组 1024×600 RGB 接口
计量单位：片（现货 12593 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1357, DATE_SUB(NOW(), INTERVAL 47 DAY), NOW(), 0),
(1362, 2, 181, 4, '2.8 寸 TFT LCD 320×240 SPI', '澜芯电子', '寸 TFT LCD 320×240 SPI', 38, '[{"qty":100,"price":38}]', 7921, '', '品牌：澜芯电子
规格：寸 TFT LCD 320×240 SPI
计量单位：片（现货 7921 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 124, DATE_SUB(NOW(), INTERVAL 54 DAY), NOW(), 0),
(1363, 1, 182, 4, '0.96 寸 OLED 模组 128×64 I2C 白光', '澜芯电子', '寸 OLED 模组 128×64 I2C 白光', 7.8, '[{"qty":100,"price":7.8},{"qty":1000,"price":7.49}]', 12646, '', '品牌：澜芯电子
规格：寸 OLED 模组 128×64 I2C 白光
计量单位：片（现货 12646 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1454, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW(), 0),
(1364, 2, 182, 4, '1.3 寸 OLED 模组 SH1106 SPI', '启明微电', '寸 OLED 模组 SH1106 SPI', 12.5, '[{"qty":100,"price":12.5}]', 7962, '', '品牌：启明微电
规格：寸 OLED 模组 SH1106 SPI
计量单位：片（现货 7962 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 155, DATE_SUB(NOW(), INTERVAL 8 DAY), NOW(), 0),
(1365, 1, 183, 4, '10.1 寸电容触摸显示模组 USB-HDMI', '启明微电', '寸电容触摸显示模组 USB-HDMI', 285, '[{"qty":100,"price":285},{"qty":1000,"price":273.6}]', 12699, '', '品牌：启明微电
规格：寸电容触摸显示模组 USB-HDMI
计量单位：套（现货 12699 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 1551, DATE_SUB(NOW(), INTERVAL 15 DAY), NOW(), 0),
(1366, 2, 183, 4, '3.5 寸串口显示模组 UART TTL', '澜芯电子', '寸串口显示模组 UART TTL', 88, '[{"qty":100,"price":88}]', 8003, '', '品牌：澜芯电子
规格：寸串口显示模组 UART TTL
计量单位：套（现货 8003 起）
源头厂商直供，出厂全检并随货附检测报告。
支持对公转账与账期结算，可按需开票。', 1, 1, 186, DATE_SUB(NOW(), INTERVAL 22 DAY), NOW(), 0);

-- ==================== 商品五维因子自主分析快照（自动分析链，每小时纳入新发布商品） ====================
CREATE TABLE IF NOT EXISTS t_product_factor_auto (
  prod_id BIGINT PRIMARY KEY COMMENT '商品ID',
  payload LONGTEXT NOT NULL COMMENT '快照 JSON：factors 五维/systematicAnalysis/contentSafety/webInfo',
  web_info_count INT NOT NULL DEFAULT 0 COMMENT '网络检索并过滤后采纳的信息条数',
  generated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='商品五维因子自主分析快照（含网络检索增强与有害过滤）';

-- ==================== 技术服务模块（技术交流 / 供需对接 / 成果鉴定 / 专利托管 / 交易陪跑） ====================
-- 说明：平台定位为技术服务信息发布与协作工具平台，不从事专利代理、资产评估、
-- 交易场所运营或支付结算；法定手续由具备相应资质的机构办理。
CREATE TABLE IF NOT EXISTS t_tech_expert (
  expert_id      BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '专家/经纪人ID',
  user_id        BIGINT COMMENT '关联平台用户ID',
  expert_type    VARCHAR(16) NOT NULL DEFAULT 'EXPERT' COMMENT 'EXPERT专家/BROKER技术经纪人',
  name           VARCHAR(64) NOT NULL COMMENT '姓名',
  avatar_url     VARCHAR(255) COMMENT '头像URL',
  title          VARCHAR(128) COMMENT '职称/头衔',
  organization   VARCHAR(128) COMMENT '所属机构',
  industries     VARCHAR(255) COMMENT '擅长行业（逗号分隔）',
  domains        VARCHAR(255) COMMENT '技术领域（逗号分隔）',
  bio            VARCHAR(1000) COMMENT '个人简介',
  credentials    VARCHAR(500) COMMENT '资质与履历',
  verified       TINYINT NOT NULL DEFAULT 0 COMMENT '是否平台核验 0否1是',
  invite_status  VARCHAR(16) NOT NULL DEFAULT 'INVITED' COMMENT 'INVITED已邀约/ACTIVE已入驻/SUSPENDED已暂停',
  rating         DECIMAL(3,2) DEFAULT 4.80 COMMENT '服务评分',
  service_count  INT DEFAULT 0 COMMENT '服务次数',
  consult_fee    DECIMAL(10,2) DEFAULT 0 COMMENT '咨询参考价（元/次）',
  response_hours INT DEFAULT 24 COMMENT '平均响应时长（小时）',
  sort           INT DEFAULT 0 COMMENT '排序权重',
  status         TINYINT NOT NULL DEFAULT 1 COMMENT '1上架0下架',
  create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_tech_expert_type (expert_type, status),
  KEY idx_tech_expert_verified (verified, sort)
) ENGINE=InnoDB COMMENT='技术服务专家与技术经纪人资源池';

CREATE TABLE IF NOT EXISTS t_tech_organization (
  org_id             BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '机构ID',
  name               VARCHAR(128) NOT NULL COMMENT '机构名称',
  org_type           VARCHAR(24) NOT NULL COMMENT 'EXCHANGE交易场所/EVALUATION评价机构/AGENCY知识产权服务机构/SAAS工具服务商/UNIVERSITY高校院所/OTHER',
  license_no         VARCHAR(128) COMMENT '执业许可证号/备案号（演示脱敏）',
  qualifications     VARCHAR(500) COMMENT '资质说明',
  logo_text          VARCHAR(16) COMMENT '机构简称/标识文字',
  region             VARCHAR(64) COMMENT '所在地区',
  intro              VARCHAR(1000) COMMENT '机构简介',
  website            VARCHAR(255) COMMENT '官网',
  verified           TINYINT NOT NULL DEFAULT 0 COMMENT '是否完成资质核验',
  rating             DECIMAL(3,2) NOT NULL DEFAULT 0 COMMENT '履约评价综合分（1-5）',
  partnership_status VARCHAR(16) NOT NULL DEFAULT 'POTENTIAL' COMMENT 'POTENTIAL拟合作/PARTNER已签约/REFERENCE公开参考',
  sort               INT DEFAULT 0,
  status             TINYINT NOT NULL DEFAULT 1,
  create_time        DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_tech_org_type (org_type, status)
) ENGINE=InnoDB COMMENT='技术服务生态机构（合作以书面协议为准）';

CREATE TABLE IF NOT EXISTS t_tech_service_product (
  service_id      BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '服务产品ID',
  provider_type   VARCHAR(16) NOT NULL DEFAULT 'PLATFORM' COMMENT 'PLATFORM/EXPERT/ORGANIZATION',
  provider_id     BIGINT COMMENT '提供方ID',
  category        VARCHAR(24) NOT NULL COMMENT 'CONSULT咨询/MATCHING对接/APPRAISAL鉴定/PATENT_HOSTING专利托管/AGENCY_HOSTING机构托管/SAAS工具托管/TRANSACTION交易陪跑',
  title           VARCHAR(128) NOT NULL,
  subtitle        VARCHAR(255),
  description     VARCHAR(1000),
  deliverables    VARCHAR(500) COMMENT '交付物',
  price_mode      VARCHAR(16) NOT NULL DEFAULT 'QUOTE' COMMENT 'FIXED/COMMISSION/SUBSCRIPTION/QUOTE',
  price           DECIMAL(12,2) DEFAULT 0,
  commission_rate DECIMAL(5,2) DEFAULT 0 COMMENT '成交分成比例（%）',
  duration_days   INT DEFAULT 0,
  tags            VARCHAR(255),
  icon            VARCHAR(32) COMMENT 'Lucide图标名',
  highlight       VARCHAR(64) COMMENT '卖点短标签',
  sort            INT DEFAULT 0,
  status          TINYINT NOT NULL DEFAULT 1,
  create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_tech_service_category (category, status)
) ENGINE=InnoDB COMMENT='技术服务产品目录';

CREATE TABLE IF NOT EXISTS t_tech_demand (
  demand_id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  demand_no        VARCHAR(32) NOT NULL COMMENT '需求编号',
  user_id          BIGINT NOT NULL COMMENT '发布用户',
  title            VARCHAR(128) NOT NULL,
  industry         VARCHAR(64) NOT NULL,
  tech_field       VARCHAR(64) NOT NULL,
  description      VARCHAR(2000) NOT NULL,
  pain_points      VARCHAR(1000),
  expected_outcome VARCHAR(1000),
  budget_min       DECIMAL(12,2),
  budget_max       DECIMAL(12,2),
  deadline         DATE,
  anonymous        TINYINT NOT NULL DEFAULT 0 COMMENT '是否匿名展示',
  status           VARCHAR(16) NOT NULL DEFAULT 'MATCHING' COMMENT 'MATCHING匹配中/IN_PROGRESS对接中/CLOSED已关闭',
  view_count       INT NOT NULL DEFAULT 0,
  match_count      INT NOT NULL DEFAULT 0,
  create_time      DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted          TINYINT DEFAULT 0,
  UNIQUE KEY uk_tech_demand_no (demand_no),
  KEY idx_tech_demand_status (status, industry),
  KEY idx_tech_demand_user (user_id)
) ENGINE=InnoDB COMMENT='企业技术需求';

CREATE TABLE IF NOT EXISTS t_tech_achievement (
  achievement_id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  owner_user_id         BIGINT NOT NULL COMMENT '成果/专利权属用户',
  title                 VARCHAR(160) NOT NULL,
  achievement_type      VARCHAR(16) NOT NULL DEFAULT 'PATENT' COMMENT 'PATENT专利/TECHNOLOGY技术成果',
  patent_no             VARCHAR(64) COMMENT '专利号（演示脱敏）',
  patent_type           VARCHAR(16) COMMENT 'INVENTION发明/UTILITY实用新型/DESIGN外观',
  legal_status          VARCHAR(16) COMMENT '有效/审中/失效',
  owners                VARCHAR(255) COMMENT '权属人（演示脱敏）',
  industry              VARCHAR(64),
  tech_field            VARCHAR(64),
  maturity_level        INT DEFAULT 5 COMMENT 'TRL成熟度1-9',
  summary               VARCHAR(1500),
  highlights            VARCHAR(1000),
  application_scenarios VARCHAR(1000),
  licensing_mode        VARCHAR(16) DEFAULT 'LICENSE' COMMENT 'ASSIGN转让/LICENSE许可/OPEN_LICENSE开放许可',
  expected_price        DECIMAL(12,2) DEFAULT 0,
  price_mode            VARCHAR(16) DEFAULT 'QUOTE' COMMENT 'FIXED/QUOTE/COMMISSION',
  dormant               TINYINT NOT NULL DEFAULT 0 COMMENT '是否沉睡专利',
  verified              TINYINT NOT NULL DEFAULT 0 COMMENT '权属材料是否核验',
  status                VARCHAR(16) NOT NULL DEFAULT 'LISTED' COMMENT 'LISTED已上架/MATCHING匹配中/IN_DEAL交易中/COMPLETED已完成',
  view_count            INT NOT NULL DEFAULT 0,
  create_time           DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time           DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted               TINYINT DEFAULT 0,
  KEY idx_tech_ach_status (status, industry),
  KEY idx_tech_ach_dormant (dormant, status),
  KEY idx_tech_ach_owner (owner_user_id)
) ENGINE=InnoDB COMMENT='技术成果与专利供给库';

CREATE TABLE IF NOT EXISTS t_tech_project (
  project_id      BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_no      VARCHAR(32) NOT NULL,
  user_id         BIGINT NOT NULL COMMENT '委托用户',
  project_type    VARCHAR(16) NOT NULL COMMENT 'CONSULT/MATCH/APPRAISAL/HOSTING/TRANSACTION',
  title           VARCHAR(160) NOT NULL,
  stage           VARCHAR(24) NOT NULL DEFAULT 'INTAKE' COMMENT 'INTAKE需求受理/DIAGNOSIS诊断/MATCHING匹配/DUE_DILIGENCE尽调/CONTRACT签约/EXECUTION履约/ACCEPTANCE验收/CLOSED关闭',
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/PAUSED/COMPLETED/CANCELLED',
  progress        INT NOT NULL DEFAULT 0,
  owner_expert_id BIGINT COMMENT '陪跑专家',
  broker_id       BIGINT COMMENT '技术经纪人',
  amount          DECIMAL(12,2) DEFAULT 0,
  commission_rate DECIMAL(5,2) DEFAULT 0,
  source_type     VARCHAR(24) COMMENT 'DEMAND/ACHIEVEMENT/APPRAISAL/HOSTING/CONSULTATION/DEAL',
  source_id       BIGINT,
  next_action     VARCHAR(255) COMMENT '下一步动作提示',
  create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tech_project_no (project_no),
  KEY idx_tech_project_user (user_id, status),
  KEY idx_tech_project_stage (stage)
) ENGINE=InnoDB COMMENT='技术服务全流程陪跑项目';

CREATE TABLE IF NOT EXISTS t_tech_milestone (
  milestone_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id   BIGINT NOT NULL,
  seq          INT NOT NULL DEFAULT 1,
  name         VARCHAR(128) NOT NULL,
  description  VARCHAR(500),
  status       VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/IN_PROGRESS/DONE',
  due_date     DATE,
  completed_at DATETIME,
  deliverable  VARCHAR(255),
  note         VARCHAR(500),
  create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_tech_milestone_project (project_id, seq)
) ENGINE=InnoDB COMMENT='陪跑项目里程碑';

CREATE TABLE IF NOT EXISTS t_tech_appraisal (
  appraisal_id    BIGINT PRIMARY KEY AUTO_INCREMENT,
  appraisal_no    VARCHAR(32) NOT NULL,
  user_id         BIGINT NOT NULL,
  achievement_id  BIGINT,
  project_id      BIGINT,
  institution_id  BIGINT COMMENT '评价机构ID',
  standard_code   VARCHAR(64) DEFAULT 'GB/T 44731-2024' COMMENT '评价标准依据',
  tech_score      INT,
  economic_score  INT,
  legal_score     INT,
  maturity_score  INT,
  conclusion_level VARCHAR(32) COMMENT '机构出具结论等级（演示）',
  report_no       VARCHAR(64) COMMENT '报告编号（演示）',
  status          VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED' COMMENT 'SUBMITTED/MATERIAL_REVIEW/EXPERT_REVIEW/REPORT_ISSUED/REJECTED（与 TechAppraisalService.APPRAISAL_STATUSES 同一口径）',
  disclaimer      VARCHAR(1000) COMMENT '免责声明',
  create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tech_appraisal_no (appraisal_no),
  KEY idx_tech_appraisal_user (user_id, status)
) ENGINE=InnoDB COMMENT='技术成果鉴定/评价申请（由合作机构出具结论）';

CREATE TABLE IF NOT EXISTS t_tech_hosting (
  hosting_id      BIGINT PRIMARY KEY AUTO_INCREMENT,
  hosting_no      VARCHAR(32) NOT NULL,
  user_id         BIGINT NOT NULL,
  project_id      BIGINT COMMENT '陪跑项目ID',
  hosting_type    VARCHAR(16) NOT NULL COMMENT 'PATENT专利托管/AGENCY机构托管/SAAS工具托管',
  target_id       BIGINT COMMENT '成果/服务产品ID',
  target_name     VARCHAR(255),
  provider_org_id BIGINT COMMENT '服务机构ID',
  agreement_no    VARCHAR(32),
  scope           VARCHAR(1000) COMMENT '托管范围',
  start_date      DATE,
  end_date        DATE,
  fee_mode        VARCHAR(16) DEFAULT 'ANNUAL' COMMENT 'ANNUAL年费/COMMISSION分成/SUBSCRIPTION订阅',
  fee_amount      DECIMAL(12,2) DEFAULT 0,
  commission_rate DECIMAL(5,2) DEFAULT 0,
  status          VARCHAR(16) NOT NULL DEFAULT 'APPLIED' COMMENT 'APPLIED/ACTIVE/SUSPENDED/COMPLETED/CANCELLED（与 TechHostingService.HOSTING_STATUSES 同一口径）',
  activation_plan VARCHAR(1500) COMMENT '盘活/运营计划',
  last_service_at DATETIME,
  create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tech_hosting_no (hosting_no),
  KEY idx_tech_hosting_user (user_id, hosting_type, status)
) ENGINE=InnoDB COMMENT='专利/机构/SaaS托管申请';

CREATE TABLE IF NOT EXISTS t_tech_deal (
  deal_id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  deal_no          VARCHAR(32) NOT NULL,
  project_id       BIGINT,
  achievement_id   BIGINT NOT NULL,
  buyer_user_id    BIGINT NOT NULL,
  seller_user_id   BIGINT NOT NULL,
  deal_type        VARCHAR(16) NOT NULL COMMENT 'ASSIGN转让/LICENSE许可/OPEN_LICENSE开放许可/SERVICE服务',
  amount           DECIMAL(12,2) NOT NULL DEFAULT 0,
  commission_rate  DECIMAL(5,2) NOT NULL DEFAULT 15.00,
  commission_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
  payment_mode     VARCHAR(24) DEFAULT 'ESCROW' COMMENT 'ESCROW监管/INSTALLMENT分期/PAY_AFTER_USE先使用后付费',
  status           VARCHAR(16) NOT NULL DEFAULT 'INTENT' COMMENT 'INTENT意向/REVIEW审核/SIGNED签约/ESCROWED监管中/DELIVERING交割中/COMPLETED完成/CANCELLED取消',
  contract_no      VARCHAR(64),
  evidence_hash    VARCHAR(128) COMMENT '交易材料存证哈希（演示）',
  escrow_ref       VARCHAR(64) COMMENT '持牌机构资金监管流水号（演示）',
  create_time      DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tech_deal_no (deal_no),
  KEY idx_tech_deal_buyer (buyer_user_id, status),
  KEY idx_tech_deal_seller (seller_user_id, status)
) ENGINE=InnoDB COMMENT='技术交易意向与流程记录';

CREATE TABLE IF NOT EXISTS t_tech_consultation (
  consult_id   BIGINT PRIMARY KEY AUTO_INCREMENT,
  expert_id    BIGINT NOT NULL,
  user_id      BIGINT NOT NULL,
  project_id   BIGINT,
  topic        VARCHAR(160) NOT NULL,
  description  VARCHAR(1000),
  mode         VARCHAR(16) DEFAULT 'VIDEO' COMMENT 'VIDEO/PHONE/OFFLINE',
  scheduled_at DATETIME,
  duration_min INT DEFAULT 60,
  amount       DECIMAL(10,2) DEFAULT 0,
  status       VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CONFIRMED/COMPLETED/CANCELLED',
  create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_tech_consult_user (user_id, status),
  KEY idx_tech_consult_expert (expert_id, status)
) ENGINE=InnoDB COMMENT='专家技术咨询预约';

CREATE TABLE IF NOT EXISTS t_tech_post (
  post_id       BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id       BIGINT COMMENT '作者用户ID',
  post_type     VARCHAR(16) NOT NULL DEFAULT 'ARTICLE' COMMENT 'ARTICLE专栏/QUESTION问答/DISCUSSION讨论',
  title         VARCHAR(160) NOT NULL,
  content       VARCHAR(4000) NOT NULL,
  tags          VARCHAR(255),
  industry      VARCHAR(64),
  author_name   VARCHAR(64),
  author_title  VARCHAR(64),
  view_count    INT NOT NULL DEFAULT 0,
  like_count    INT NOT NULL DEFAULT 0,
  comment_count INT NOT NULL DEFAULT 0,
  status        TINYINT NOT NULL DEFAULT 1,
  create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_tech_post_type (post_type, status),
  KEY idx_tech_post_user (user_id)
) ENGINE=InnoDB COMMENT='技术交流社区内容';

CREATE TABLE IF NOT EXISTS t_tech_policy (
  policy_id    BIGINT PRIMARY KEY AUTO_INCREMENT,
  title        VARCHAR(200) NOT NULL,
  issuer       VARCHAR(128) NOT NULL,
  doc_no       VARCHAR(64),
  region       VARCHAR(32) DEFAULT '全国',
  category     VARCHAR(32) DEFAULT '政策',
  publish_date DATE,
  summary      VARCHAR(1000),
  url          VARCHAR(500),
  highlight    VARCHAR(64),
  sort         INT DEFAULT 0,
  status       TINYINT NOT NULL DEFAULT 1,
  create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_tech_policy_region (region, category, status)
) ENGINE=InnoDB COMMENT='技术转移与专利转化政策库';

-- 快照分析失败隔离表（连续失败 3 次停止自动重试，管理端 rebuild 清零恢复）
CREATE TABLE IF NOT EXISTS t_product_factor_auto_fail (
  prod_id BIGINT PRIMARY KEY COMMENT '商品ID',
  fail_count INT NOT NULL DEFAULT 0 COMMENT '连续失败次数',
  last_fail_reason VARCHAR(500) DEFAULT NULL COMMENT '最近一次失败原因',
  last_fail_at DATETIME DEFAULT NULL COMMENT '最近一次失败时间'
) ENGINE=InnoDB COMMENT='五维因子自动分析失败隔离（防无限重试）';

-- 业务材料附件：商户资质（营业执照/完税/特殊许可）与技术服务材料（专家档案/机构资质/评价与交易文件）共用。
-- 库内只存 object key，永不存 URL：桶地址会变，key 才是稳定标识。与 AttachmentSchemaInitializer 的 DDL 必须一致。
CREATE TABLE IF NOT EXISTS t_attachment (
  attachment_id BIGINT NOT NULL AUTO_INCREMENT,
  attach_no VARCHAR(40) NOT NULL,
  biz_type VARCHAR(32) NOT NULL,
  biz_id BIGINT NULL,
  owner_user_id BIGINT NOT NULL,
  file_name VARCHAR(200) NOT NULL,
  object_key VARCHAR(255) NOT NULL,
  content_type VARCHAR(64) NOT NULL,
  file_size BIGINT NOT NULL DEFAULT 0,
  sha256 CHAR(64) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
  thumb_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待处理 / READY 已生成 / SKIPPED 非图片 / FAILED 待重试',
  thumb_key VARCHAR(255) NULL,
  thumb_attempts INT NOT NULL DEFAULT 0,
  width INT NULL,
  height INT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (attachment_id),
  UNIQUE KEY ux_attachment_no (attach_no),
  UNIQUE KEY ux_attachment_object_key (object_key),
  KEY idx_attachment_owner_status (owner_user_id, status),
  KEY idx_attachment_biz (biz_type, biz_id, status),
  KEY idx_attachment_status_create (status, create_time),
  KEY idx_attachment_thumb_scan (thumb_status, attachment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务材料附件（商户资质/技术服务材料），库内只存 object key';

-- 通知消费去重：MQ 是 at-least-once，重复投递靠这张表的唯一键折叠，避免用户收到两封一样的站内信。
-- 与 NotifySchemaInitializer 的 DDL 必须一致。
CREATE TABLE IF NOT EXISTS t_notify_dedupe (
  id BIGINT NOT NULL AUTO_INCREMENT,
  message_key VARCHAR(64) NOT NULL,
  consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY ux_notify_message_key (message_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知消费去重（at-least-once 投递下防重复发信）';

-- 通知投递重试箱：提交后 convertAndSend 失败时暂存，worker 按指数退避重投，超限转死态。
-- 与 NotifySchemaInitializer 的 DDL 必须一致。
CREATE TABLE IF NOT EXISTS t_notify_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT,
  message_key VARCHAR(64) NOT NULL,
  payload TEXT NOT NULL,
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0待投 1已投 2死态',
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY ux_notify_outbox_key (message_key),
  KEY idx_notify_outbox_due (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知投递重试箱（提交后投递失败时暂存，worker 退避重投）';
