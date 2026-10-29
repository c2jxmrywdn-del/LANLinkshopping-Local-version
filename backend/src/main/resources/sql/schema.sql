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
  sales          INT DEFAULT 0 COMMENT '销量',
  create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted        TINYINT DEFAULT 0
) ENGINE=InnoDB COMMENT='商品';

-- 幂等升级：历史库 t_product 补齐审核状态两列（列不存在时才 ADD）
SET @pd1 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_product ADD COLUMN review_status TINYINT DEFAULT 0 COMMENT ''审核状态 0待审核 1通过 2驳回'' AFTER status', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_product' AND COLUMN_NAME='review_status');
PREPARE pd_stmt1 FROM @pd1; EXECUTE pd_stmt1; DEALLOCATE PREPARE pd_stmt1;
SET @pd2 = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_product ADD COLUMN reject_reason VARCHAR(255) COMMENT ''驳回原因'' AFTER review_status', 'SELECT 1')
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='lanlink_shopping' AND TABLE_NAME='t_product' AND COLUMN_NAME='reject_reason');
PREPARE pd_stmt2 FROM @pd2; EXECUTE pd_stmt2; DEALLOCATE PREPARE pd_stmt2;
-- 存量数据补齐：历史已上架商品视为审核通过（新发布的待审核商品 status=0 不受影响）
UPDATE t_product SET review_status = 1 WHERE status = 1 AND review_status = 0;

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
  refund_status VARCHAR(16) DEFAULT 'none' COMMENT '退款状态 none/applied/rejected/processing/partial/success',
  refund_id    VARCHAR(64) COMMENT '渠道退款单号',
  refund_amount DECIMAL(14,2) COMMENT '已退金额',
  refund_time  DATETIME COMMENT '退款完成时间',
  update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
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
  create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_campaign_type_status (type, status)
) ENGINE=InnoDB COMMENT='活动(统一营销：促销/领券/激励)';

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
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='会员积分流水';

-- 幂等种子：会员等级（六档；重复执行时按主键更新，确保政策调整能生效）
-- 门槛为成长值累计（企业级 B2B 量级）：10万 / 50万 / 100万 / 500万 / 1000万 / 2000万；
-- 升级需「有效订阅 + 成长值达标」双条件，折扣按 0 / 1% / 3% / 5% / 7% / 10% 递增，最高为黑钻会员。
INSERT INTO t_member_level (level_id, level_name, min_growth, discount_rate, description, sort) VALUES
 (1,'普通会员', 100000,  1.000, '基础会员权益', 1),
 (2,'白银会员', 500000,  0.990, '99折 起',      2),
 (3,'黄金会员', 1000000, 0.970, '97折 特权',    3),
 (4,'铂金会员', 5000000, 0.950, '95折 尊享',    4),
 (5,'钻石会员', 10000000,0.930, '93折 尊享',    5),
 (6,'黑钻会员', 20000000,0.900, '9折 至臻',     6)
ON DUPLICATE KEY UPDATE
 level_name=VALUES(level_name), min_growth=VALUES(min_growth),
 discount_rate=VALUES(discount_rate), description=VALUES(description), sort=VALUES(sort);

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
SET @paycol = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN refund_status VARCHAR(16) DEFAULT ''none'' COMMENT ''退款状态 none/applied/rejected/processing/partial/success''', 'SELECT 1')
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
