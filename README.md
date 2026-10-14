# LANLinkshopping · 聚合型一体多元化解决方案电商平台

> 软件技术 2404 · Spring Boot 毕业设计（Web 网站端 · 全栈核心 MVP）
> 技术栈：Spring Boot 3.5.3 + MyBatis-Plus 3.5.9 + MySQL 8 + Vue 3 + Vite + Ant Design Vue 4 + Pinia

本项目依据《LANLinkshopping 项目方案》与《Web 网站搭建方案》落地，实现五大核心业务模块：
**登录鉴权(RBAC) · 商户入驻与筛选 · 商品商城 · 购物车 · 下单支付**，并内置"链式业务拓展"设计。

---

## 一、目录结构

```
LANLinkshopping/
├── backend/                 # Spring Boot 后端
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/lanlink/shopping/
│       │   ├── common/      R、BusinessException、GlobalExceptionHandler
│       │   ├── config/      MybatisPlusConfig、WebConfig、AuthInterceptor、UserContext、DataInitializer
│       │   ├── controller/  Auth、Home、Product、Merchant、Cart、Order
│       │   ├── service/     同名业务服务（Order 含事务扣库存）
│       │   ├── mapper/      11 个 BaseMapper
│       │   ├── entity/      11 张表实体
│       │   ├── dto/ vo/     出入参对象
│       │   └── LanlinkShoppingApplication.java
│       └── resources/
│           ├── application.yml / application-dev.yml
│           └── sql/schema.sql        # 建库 + 建表 + 字典数据（幂等，可重复执行）
├── frontend/                # Vue3 + Ant Design Vue 前端
│   └── src/{api,router,store,layouts,views}
└── README.md
```

## 二、启动步骤

### 1. 初始化数据库

**前提**：本机 MySQL 8 已启动。开发环境通过 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME` 和 `SPRING_DATASOURCE_PASSWORD` 环境变量配置连接；数据库密码不再保存在仓库文件中。运行一键脚本前需设置 `SPRING_DATASOURCE_PASSWORD`。

脚本按下面 4 步引导式执行，**全文没有 `DROP DATABASE` / `DROP TABLE` / `TRUNCATE` / `DELETE`，可以重复执行且不丢数据**：

| 步骤 | 内容 | 谁来做 |
|------|------|--------|
| 1 | 创建数据库 `lanlink_shopping`（`utf8mb4` + `utf8mb4_general_ci`） | `schema.sql` 第 1 段 / 数据源 URL |
| 2 | 建 11 张表 `t_role`、`t_enterprise`、`t_user`、`t_merchant`、`t_qualification`、`t_industry`、`t_category`、`t_product`、`t_cart`、`t_order`、`t_order_item` | `schema.sql` 第 2 段 |
| 3 | 插基础字典：角色 3 条、行业 4 条、分类 11 条（显式主键 + `INSERT IGNORE`） | `schema.sql` 第 3 段 |
| 4 | 示例用户 / 企业 / 商户 / 商品（密码 BCrypt） | 后端 `DataInitializer`，仅当 `t_user` 为空时写入 |

#### 方式 A：自动执行（仅开发 Profile）

`application-dev.yml` 已配置好，**不需要手动建表**，直接启动后端即可：

```yaml
spring:
  sql:
    init:
      mode: always                                  # 每次启动都执行脚本（脚本幂等，所以安全）
      encoding: UTF-8                               # 防止中文注释/中文数据乱码
      schema-locations: classpath:sql/schema.sql    # 脚本在 sql/ 子目录，必须显式指定
```

关于**谁真正负责建库**：自动路径下是数据源 URL 里的 <createDatabaseIfNotExist=true>——JDBC 驱动必须先连上（或先建好）目标库，连接建立成功后才轮到 `spring.sql.init` 跑脚本，所以脚本内的 `CREATE DATABASE IF NOT EXISTS` 在这条路径上是幂等的双保险，执行时直接跳过。手动导入时反过来的：由脚本自己建库，无需提前手建。

需要**手动先建库**的只有两种情况：① URL 去掉了 <createDatabaseIfNotExist=true>；② 执行账号没有 `CREATE DATABASE` 权限（由 DBA 先建库）。另外，若你改了 URL 里的库名，请同步修改脚本中 `CREATE DATABASE` 与 `USE` 两行（或删掉这两行完全交给 URL）。

#### 方式 B：手动导入

PowerShell（**注意 PowerShell 不支持 `<` 输入重定向**，会报 The ‘<’ operator is reserved for future use）：

```powershell
# 路径含中文与空格，必须加引号；先切到项目根目录
Set-Location -LiteralPath 'D:\桌面\创新创业\Spring boot毕业设计\LANLinkshopping'

# 写法一：借 cmd 完成重定向（最稳）
cmd /c 'mysql -uroot -p"<你的MySQL密码>" --default-character-set=utf8mb4 < "backend\src\main\resources\sql\schema.sql"'

# 写法二：管道（先把输出编码设为 UTF-8，否则中文会乱码）
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8
Get-Content -Raw -Encoding UTF8 'backend\src\main\resources\sql\schema.sql' |
  mysql -uroot -p"<你的MySQL密码>" --default-character-set=utf8mb4

# 写法三：用 mysql 客户端自身的 source 命令（路径用正斜杠更稳）
mysql -uroot -p"<你的MySQL密码>" --default-character-set=utf8mb4 -e "source backend/src/main/resources/sql/schema.sql"
```

如果 `mysql` 不在 PATH（常见于只装了 MySQL Server 没装 Tools），用完整路径或临时加入 PATH：

```powershell
& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' -uroot -p --default-character-set=utf8mb4 -e 'SELECT VERSION()'
$env:Path += ';C:\Program Files\MySQL\MySQL Server 8.0\bin'   # 当次会话生效，然后就能直接敲 mysql
```

CMD（支持 `<` 重定向）：

```cmd
chcp 65001
cd /d "D:\桌面\创新创业\Spring boot毕业设计\LANLinkshopping"
mysql -uroot -p"<你的MySQL密码>" --default-character-set=utf8mb4 < "backend\src\main\resources\sql\schema.sql"
```

图形化（Navicat / DataGrip / IDEA 的 Database 工具窗）：新建连接指向 `localhost:3306`（用户 `root`）→ 打开查询控制台 → 载入 `backend/src/main/resources/sql/schema.sql` → 执行整个文件。因为脚本第 1 段自带建库与 `USE`，执行前**不需要**先选中某个 schema。注意在 IDEA 中编辑此文件时保持右下角编码为 UTF-8（不保 BOM）。

乱码避免清单：① 文件以 UTF-8 无 BOM 保存；② 库/表字符集 `utf8mb4`（脚本已写）；③ 客户端带 `--default-character-set=utf8mb4`；④ Spring 侧由 `spring.sql.init.encoding: UTF-8` 保证。导入后若中文变问号或乱码，优先查 ① 和 ③。

#### 执行后自检

```sql
SELECT COUNT(*) AS tables_created FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'lanlink_shopping';  -- 预期 11
SELECT COUNT(*) AS roles       FROM t_role;                                     -- 预期 3
SELECT COUNT(*) AS industries  FROM t_industry;                                 -- 预期 4
SELECT COUNT(*) AS categories  FROM t_category;                                 -- 预期 11
SELECT COUNT(*) AS users       FROM t_user WHERE deleted = 0;                   -- 启动后端后预期 3
SELECT title FROM t_product LIMIT 3;                                            -- 验证中文未乱码
```

> ⚠️ 历史版本脚本里的 `DROP TABLE IF EXISTS` 已全部移除。在 `mode: always` 下，一旦脚本含 `DROP TABLE`，每次重启后端都会重建表、清空已录入的数据。
> 如需彻底重置演示数据：`DROP DATABASE lanlink_shopping;` 后重启后端，库表与示例数据会重新自动创建。

### 2. 启动后端（端口 8080，接口前缀 /api）
```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# 或运行已打好的 jar：
java -jar target/lanlink-shopping.jar --spring.profiles.active=dev
```
开发 Profile 下 `spring.sql.init` 自动执行脚本，`DataInitializer` 根据开关写入演示数据；生产 Profile 默认禁用 SQL 自动初始化和演示数据注入。

### 3. 启动前端（端口 5173，已配 /api 代理到 8080）
```bash
cd frontend
npm install
npm run dev
```
浏览器打开 http://localhost:5173

## 三、演示账号（仅开发 Profile 提供，密码均为 123456）

| 手机号 | 身份 | 说明 |
|--------|------|------|
| 13800000000 | 平台运营 admin | 可访问商户审核接口 |
| 13900000001 | 采购方 buyer | 浏览、加购、下单、支付 |
| 13700000002 | 商户 merchant | 已通过审核，可上架商品 |

## 四、核心接口一览（前缀 /api）

- 认证：`POST /auth/register`、`POST /auth/login`、`POST /auth/logout`、`GET /auth/me`
- 首页：`GET /home/industries`、`GET /home/hot`、`GET /category/list`
- 商城：`GET /product/page`、`GET /product/detail/{id}`、`POST /product/publish`（商户）
- 商户：`POST /merchant/apply`、`GET /merchant/my`、`GET /merchant/admin/list`、`POST /merchant/admin/review/{id}`
- 购物车：`POST /cart/add`、`GET /cart/list`、`POST /cart/quantity/{id}`、`DELETE /cart/{id}`
- 订单：`POST /order/checkout`、`GET /order/my`、`GET /order/detail/{no}`、`POST /order/pay/{no}`、`POST /order/cancel/{no}`

## 五、链式业务拓展（设计亮点）

平台不是十个孤立模块，而是把相似业务逻辑抽象为可复用的"链"：

- **审核链**：商户入驻 / 资质 / 金融授信 / 定制报价 共用 `AuditService` 式流程；
- **订单链**：商城 / 购物车 / 礼品定制 共用 `OrderService`，以 `orderType` 区分；
- **资金链**：钱包 / 账期 / 结算 共用余额与流水逻辑；
- **行业适配链**：新增行业只需在 `t_industry` / `t_category` 配置数据，前端专区与商户资质要求自动生效，**零代码接入**。

商户筛选机制（对应思维导图硬指标）在 `MerchantService.applyScreening()` 中实现：
公司类型，或个体工商户且注册资本 > 10 万元，且有稳定纳税记录 → 自动通过，否则驳回并给出原因。

## 六、已验证

- 后端 `mvn package` 编译打包通过，产出 `lanlink-shopping.jar`；
- 前端 `npm run build` 构建通过，产出 `dist/`；
- 下单主流程含事务：校验库存 → 生成订单+明细（冗余快照）→ 扣库存加销量 → 清购物车。

## 七、后续可扩展

供应链金融（账期/尾款）、礼品定制、消息中心、评价信用、运营数据看板等增值模块，
可基于上述"链式"复用点增量开发，无需改动核心交易链路。


## 生产部署配置

默认 Profile 为 `prod`。部署到 Railway 等环境时，应配置 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`，并按需配置 `MAIL_HOST`、`MAIL_PORT`、`MAIL_USERNAME`、`MAIL_PASSWORD`。生产 Profile 显式关闭 SQL 自动初始化、模拟支付、验证码回显和演示数据注入。不要把数据库口令、邮件授权码或支付私钥提交到 GitHub。本地开发时请显式指定 `--spring.profiles.active=dev`。
