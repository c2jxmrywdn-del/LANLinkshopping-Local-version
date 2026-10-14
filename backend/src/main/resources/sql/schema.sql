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
  refund_status VARCHAR(16) DEFAULT 'none' COMMENT '退款状态 none/processing/success',
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

-- ==================== 营销中台：活动系统 ====================
CREATE TABLE IF NOT EXISTS t_activity (
  activity_id  BIGINT PRIMARY KEY AUTO_INCREMENT,
  title        VARCHAR(100) NOT NULL COMMENT '活动名称',
  type         VARCHAR(16) COMMENT '活动类型 register注册有礼 purchase消费有礼',
  description  VARCHAR(500) COMMENT '活动说明',
  start_time   DATETIME COMMENT '开始时间',
  end_time     DATETIME COMMENT '结束时间',
  status       TINYINT DEFAULT 0 COMMENT '0未开始 1进行中 2已结束',
  create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='活动';

CREATE TABLE IF NOT EXISTS t_activity_participant (
  participant_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  activity_id    BIGINT NOT NULL COMMENT '活动ID',
  user_id        BIGINT NOT NULL COMMENT '参与用户',
  bonus_points   INT DEFAULT 0 COMMENT '参与奖励积分',
  join_time      DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_act_user (activity_id, user_id)
) ENGINE=InnoDB COMMENT='活动参与记录';

-- ==================== 营销中台：促销系统 ====================
CREATE TABLE IF NOT EXISTS t_promotion (
  promo_id       BIGINT PRIMARY KEY AUTO_INCREMENT,
  title          VARCHAR(100) NOT NULL COMMENT '促销名称',
  type           VARCHAR(16) NOT NULL COMMENT '促销类型 full_reduce满减 discount折扣',
  threshold      DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '满减门槛金额/折扣门槛',
  benefit_amount DECIMAL(12,2) COMMENT '满减金额(满减时生效)',
  discount_rate  DECIMAL(4,3) COMMENT '折扣率(折扣时生效, 0.95=95折)',
  scope          VARCHAR(16) DEFAULT 'all' COMMENT '适用范围 all全场 ind行业',
  ind_id         BIGINT COMMENT '限定行业(scope=ind时)',
  start_time     DATETIME,
  end_time       DATETIME,
  status         TINYINT DEFAULT 1 COMMENT '0停用 1启用',
  create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='促销';

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

-- 幂等种子：会员等级（存在则跳过）
INSERT IGNORE INTO t_member_level (level_id, level_name, min_growth, discount_rate, description, sort) VALUES
 (1,'普通会员', 0,     1.000, '基础会员权益', 1),
 (2,'白银会员', 1000,  0.990, '98折 起',      2),
 (3,'黄金会员', 5000,  0.970, '97折 特权',    3),
 (4,'钻石会员', 20000, 0.950, '95折 尊享',    4);

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
SET @paycol = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN refund_status VARCHAR(16) DEFAULT ''none'' COMMENT ''退款状态 none/processing/success''', 'SELECT 1')
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
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_credit_repayment_bill (bill_id),
  KEY idx_credit_repayment_user (user_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业账期还款记录';

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
