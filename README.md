<div align="center">

# LANLinkshopping

### 聚合型一体多元化解决方案 · B2B 电商全栈平台

**Spring Boot 3.5.3 + Vue 3 + Vite 6 + Redis Stack + 本地双大模型编排**

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.3-brightgreen)
![Vue 3](https://img.shields.io/badge/Vue-3-42b883)
![Vite](https://img.shields.io/badge/Vite-6.4.5-646cff)
![MySQL](https://img.shields.io/badge/MySQL-8.0-blue)
![Redis](https://img.shields.io/badge/Redis-Stack%20HNSW-dc382d)
![License](https://img.shields.io/badge/License-GNU%20GPL%20v3-blue)

[功能特性](#-功能特性) · [快速开始](#-快速开始) · [架构与亮点](#-架构与系统亮点) · [使用说明](#-使用说明) · [接口速览](#-核心接口速览) · [联系与声明](#-联系方式与原创声明) · [许可证](#-许可证)

</div>

---

## 📖 项目简介

**LANLinkshopping** 是一个专为现代化供应链与企业采购场景设计的**聚合型、多行业、B2B 一体化电商全栈中台平台**。项目深度覆盖 **“统一身份鉴权 · 商户自主入驻与资质审核 · 24 大行业商品商城 · 智能购物车 · 下单支付与对账履约”** 五大基础交易闭环，并在此之上横向拓展了 **商品直播带货、Apple UI 交互体验、纯矢量图标系统、本地双大模型编排与向量知识库、履约物流结算、价格情报雷达、营销活动中台** 等全套现代化增值能力。

项目交付包含：
- **Web 业务主线**：Spring Boot 3.5.3 + MyBatis-Plus + Vue 3 (Vite 6 + Pinia + Ant Design Vue + Lucide 矢量图标)
- **直播带货系统**：支持商户在线开播、商品货架边播边买、毫秒级实时互动弹幕与点赞人气流
- **Apple UI 体验规范**：基于APPLE风格打造的高斯毛玻璃、拟态物理流体悬浮 Dock、iPhone 灵动岛通知胶囊与 3D 景深卡片轮播，全端（含手机浏览器 H5）自适应
- **本地 AI 中枢沙盒**：基于 Docker 托管 llama.cpp 实现 Qwen2.5-7B（文本）与 Qwen2.5-VL（多模态视觉）双模型 Router 热切换调度，集成硬件显存感知调度器与 Redis Stack HNSW 向量知识库（bge-m3 1024 维密集嵌入）
- **微服务演进骨架**（`cloud/`）：Spring Cloud + Nacos + Spring Cloud Gateway + OpenFeign 8 大微服务模块预研骨架
- **供应链与安全防护**：TOTP 双因素安全验证、Google / Apple 社交登录集成、BCrypt 密码散列、AES-256-GCM 敏感字段加密、CodeQL 与 Dependabot 自动化审计

> 本项目为**教学 / 毕业设计演示项目**，默认运行在本地开发模式，请勿直接用于真实交易或对外提供生产级服务。

---

## ✨ 功能特性

### 1. 核心业务矩阵（五大基础模块）

| 模块 | 能力与特性 |
|------|-----------|
| 🔐 **登录鉴权** | 手机号注册/登录、RBAC 五级权限矩阵（`GUEST` / `BUYER` / `VIP` / `MERCHANT` / `ADMIN`）、TOTP 双因素验证（2FA）、CSRF 双提交防护、密码连续失败防爆破锁定、Google & Apple 社交登录流程规范 |
| 🏪 **商户入驻** | 企业入驻申请（统一社会信用代码、企业规模、经营执照）、多维智能审核（注册资本 + 纳税评级自动过审模型）、商户专属后台（`MerchantCenterLayout`：店铺设置、商品管理、活动报名、直播控制台） |
| 🛍️ **多元商城** | 24 大行业多级分类索引导航、阶梯批发价配置（大宗采购门槛）、多规格 SKU 展示、商品审核状态机、价格情报与日 K 线走势分析 |
| 🛒 **购物车** | 原子级规格库存校验、大宗批发批量修改数量、一键全选/按店铺勾选结算、企业收货地址中枢联动 |
| 📦 **下单支付** | 事务原子扣减库存、订单全明细快照持久化、支付宝 / 微信 / 余额沙箱 Mock 支付、企业积分抵扣（1000 积分 = 1 元）、企业白条账期授信延期结账 |

---

### 2. 核心增值亮点

#### 🎥 商品直播带货系统 (Live Commerce)
- **企业级直播广场**：汇聚各行业在播商户，支持观看热度、开播状态实时检索。
- **沉浸式直播间**：定制化视频播放流媒体交互、毫秒级实时滚动互动弹幕与点赞动效流。
- **边播边卖互动货架**：主播端一键推送正在讲解的爆款商品，买家端弹窗即时查看规格并快捷下单。
- **商户与监管治理**：商户后台自主配置开播信息与关联带货商品，平台运营管理端实时巡查监管。

#### 🍎 Apple UI 交互规范与 21st.dev 纯矢量图标系统
- **纯矢量图标治理**：全面摒弃原生 Emoji 与字符替代方案，深度集成 21st.dev 风格的 Lucide SVG 矢量图标库，封装 `AppleIcon.vue` 实现全站图标集中映射与规范化渲染。
- **macOS 拟态悬浮 Dock**（`AppleDock.vue`）：流体物理弹性缩放动画、平滑贝塞尔曲线微交互。
- **iPhone 灵动岛交互**（`AppleDynamicIsland.vue`）：悬浮消息通知胶囊、状态变换弹性伸缩动效。
- **3D 景深卡片轮播**（`AppleCardsCarousel.vue`）：视差平移动态光影、手势滑动支持。
- **高斯毛玻璃拟态质感**（`AppleGlassCard.vue`）：背景多重模糊过滤、边缘渐变微光。
- **移动端与全端响应式**：优化低饱和度色彩配比，自适应手机浏览器与宽屏显示，保障高对比度可读性与护眼视觉体验。

#### 🤖 本地 AI 向量知识库与双模型编排中枢
- **本地向量知识库**：基于 Redis Stack HNSW 向量索引（`aikb-idx`）+ 本地 **bge-m3** 多语言嵌入模型（1024 维），结合「向量相似度 0.6 + 中文分词匹配 0.4」实现高精度 Hybrid 混合召回。
- **llama.cpp 双模型 Router 架构**：Docker 容器化管理 `llama-cpp`，支持按业务意图无缝热切换 **Qwen2.5-7B（大语言模型）** 与 **Qwen2.5-VL（视觉多模态模型）**。
- **硬件感知调度器**（`hardware_scheduler.py`）：实时感知 GPU 显存与温度；优先 GPU 全量加载，显存轻微不足时自动进行多层 CPU 卸载辅助，显存告急时提供明确多模态降级保护；支持 `GET /v1/hardware` 查询实时调度依据。
- **智能客服主备链路架构**：本地知识库问答优先，外接大模型智能兜底润色；内置敏感 PII 数据脱敏拦截与出站安全策略守卫；运营端可一键监控备用链路状态与拦截计数。

#### 🚚 履约物流与对账结算中枢
- **运单生命周期跟踪**：覆盖“待发货 → 已揽收 → 运输中 → 派件中 → 已签收 / 异常 / 退回 / 丢失”全状态机流转。
- **物流轨迹同步推进**：支持批量轨迹节点录入，签收节点自动解析并联动推进商城订单进入“已完成”状态。
- **服务商对账单批量生成**：支持按物流服务商与账期区间聚合已完结运单，支持扣减金额与附加费用核算，内置同周期幂等防重复生成校验。
- **售后索赔闭环**：支持物流异常与破损快速发起售后工单与金额赔付流转。

#### 📈 价格情报与行情监控台
- **五维价格因子科学建模**：综合市场情绪、上游供应链、基础原材料、国际化、政策环境五大维度，结合商品相关资讯加权计算，输出差异化行情风险评分。
- **行业基准与日 K 线**：直观展示商品与大盘价格走势对比。
- **外部资讯溯源直达**：自动化 RSS 抓取清洗（过滤娱乐/低质推广），每日资讯一键直达原始新闻源头站点。

#### 💼 营销活动与会员资产
- **营销中台**：活动中心、限时立减、满减优惠券领取与核销、大促商品审核。
- **会员资产**：积分成长体系（签到、消费奖励）、专属等级勋章、会员权益兑换。
- **招贤纳士与企业合作**（`JoinUs`）：前台提交求职意向与战略合作意向，管理端一键审核流转。

---

## 🗂️ 项目结构

```
LANLinkshopping/
├── backend/                  # Spring Boot 3 后端核心应用
│   ├── src/main/java/com/lanlink/shopping/
│   │   ├── controller/       # 接口控制器（认证、商品、订单、直播、物流、商户、客服、管理）
│   │   ├── service/          # 核心业务服务层与接口实现
│   │   ├── mapper/           # MyBatis-Plus 数据访问层
│   │   ├── entity/           # 数据库实体模型（User, LiveRoom, Product, Order, Waybill...）
│   │   ├── support/          # 智能客服意图分析、备用链路兜底与策略防护
│   │   ├── payment/          # 支付沙箱抽象层（微信、支付宝、余额）
│   │   └── config/           # 安全配置、拦截器、跨域与 MyBatis 配置
│   └── src/main/resources/
│       ├── application.yml   # 核心配置文件（默认 dev profile）
│       ├── application-dev.yml / application-prod.yml
│       ├── knowledge-base/   # 预置本地知识库 JSON 数据源
│       └── sql/              # schema.sql 数据库幂等建表与测试数据
├── frontend/                 # Vue 3 现代化前端应用
│   ├── src/
│   │   ├── api/              # Axios 接口模块（认证、商城、直播、物流、客服、管理后台等）
│   │   ├── components/       # 公共业务组件
│   │   │   └── ui/           # 21st.dev 风格 Apple UI 核心组件库
│   │   │       ├── AppleIcon.vue           # 集中式 Lucide 纯矢量 SVG 图标解析器
│   │   │       ├── AppleDock.vue           # macOS 风格流体悬浮导航栏
│   │   │       ├── AppleDynamicIsland.vue  # iPhone 灵动岛交互胶囊组件
│   │   │       ├── AppleCardsCarousel.vue  # 3D 视差卡片轮播组件
│   │   │       └── AppleGlassCard.vue      # 高级毛玻璃材质卡片
│   │   ├── views/            # 核心业务页面
│   │   │   ├── live/         # 直播间（LiveRoomView.vue）
│   │   │   ├── admin/        # 管理后台（仪表盘、物流中枢、AI 网关、活动审核、风控）
│   │   │   ├── center/       # 商户中心（店铺管理、商品发布、订单履约、商户直播）
│   │   │   └── ...           # 首页、商城、购物车、收银台、个人中心、活动中心等
│   │   ├── router/           # Vue Router 路由配置
│   │   ├── store/            # Pinia 状态管理
│   │   └── utils/            # 通用工具库与埋点上报
│   ├── package.json          # 前端依赖配置（Lucide Vue Next, Ant Design Vue, Pinia 等）
│   └── vite.config.js        # Vite 构建与开发服务器代理配置
├── ai-gateway/               # 本地 AI 网关沙盒（FastAPI + Ollama / llama.cpp + RAG）
│   ├── main.py               # OpenAI 兼容 API 路由层
│   ├── hardware_scheduler.py # GPU/CPU 硬件感知调度中枢
│   └── docker-compose.yml    # AI 容器集群编排
├── cloud/                    # Spring Cloud 微服务演进骨架（8 大模块 + Nacos）
├── docker-compose.yml        # Redis Stack HNSW 向量库 + llama.cpp 基础设施编排
├── docker-compose.local.yml  # 本地配套服务（Mailpit / 沙盒测试）
├── start-local-env.ps1       # Windows PowerShell 一键启动全栈脚本
├── 启动本地环境.bat            # Windows 批处理一键运行入口
├── LICENSE                   # GNU General Public License v3.0 开源协议
├── SECURITY.md               # 安全漏洞披露与响应规范
└── README.md                 # 项目使用与架构说明文档
```

---

## 🚀 快速开始

### 环境依赖

| 组件 | 版本建议 | 说明 |
|------|----------|------|
| **JDK** | 17+ | 推荐 Eclipse Temurin 或 Oracle JDK 17 |
| **Maven** | 3.8+ | 包含项目根目录 Maven Wrapper |
| **Node.js** | 18+ / 20+ | 前端构建与运行环境 |
| **MySQL** | 8.0+ | 核心关系型数据库 |
| **Redis** | 7.0+ (Redis Stack) | 向量索引检索（推荐 Docker 启动） |
| **Docker** | 24+ | 可选，用于运行 AI 沙盒与 Redis 向量库 |

---

### 1. 检出仓库代码

```bash
git clone https://github.com/c2jxmrywdn-del/LANLinkshopping-Local-version.git
cd LANLinkshopping-Local-version
```

### 2. 配置本地环境变量

复制模板并按需填写：

```bash
# Windows
copy .env.example .env

# macOS / Linux
cp .env.example .env
```

`.env` 基础数据库配置示例：
```env
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/lanlink_shopping?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true
SPRING_DATASOURCE_USERNAME=root
SPRING_DATASOURCE_PASSWORD=your_mysql_password
```

---

### 3. 一键启动（推荐方案）

在 Windows 环境下，直接双击运行根目录的 **`启动本地环境.bat`**，或在终端执行：

```powershell
powershell -ExecutionPolicy Bypass -File start-local-env.ps1
```

> **启动脚本自动化流程**：读取 `.env` 环境变量 → 检查并拉起 Docker 基础设施 → 启动 Spring Boot 后端（8080）→ 启动 Vite 前端（5173）→ 验证健康状态。

---

### 4. 手动分步启动

#### 步骤一：数据库初始化
后端在 `dev` 环境下已配置启动自动幂等执行 `schema.sql`，如需手动预导入：
```bash
mysql -uroot -p --default-character-set=utf8mb4 < backend/src/main/resources/sql/schema.sql
```

#### 步骤二：启动后端服务 (Port: 8080)
```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

#### 步骤三：启动前端应用 (Port: 5173)
```bash
cd frontend
npm install   # 或 npm ci
npm run dev
```

启动完成后，在浏览器访问 **[http://localhost:5173](http://localhost:5173)**。局域网移动端设备可访问 `http://<本机局域网IP>:5173` 测试手机端自适应体验。

---

## 🧭 使用说明

### 演示账号与权限矩阵（初始密码统一为 `123456`）

| 手机号码 | 平台角色 | 权限范围与演示场景 |
|----------|----------|--------------------|
| `13800000000` | **平台运营 (ADMIN)** | 商户资质入驻终审、商品上架审核、物流履约与对账中枢、AI 网关调度、动态系统配置维护 |
| `13700000002` | **商户卖家 (MERCHANT)** | 商户后台管理、自有商品发布、活动申报参与、开播带货控制台、订单出库发货 |
| `13900000001` | **采购买家 (BUYER)** | 浏览 24 行业商城、进入商品直播间与边播边买、购物车阶梯结算、沙箱支付与账期白条体验 |

---

### 📡 核心接口速览（统一前缀 `/api`）

| 业务领域 | 核心 Endpoint 示例 | 功能说明 |
|----------|-------------------|----------|
| **认证与社交登录** | `POST /auth/login`<br>`POST /auth/register`<br>`GET /auth/me`<br>`POST /auth/social/login` | 用户鉴权、Token 状态获取与多端社交登录流 |
| **商品直播带货** | `GET /live/rooms`<br>`GET /live/room/{id}`<br>`GET /live/room/{id}/products`<br>`POST /live/room/{id}/comment`<br>`POST /live/room/{id}/like` | 直播广场大厅、房间流状态、边播边买货架拉取、实时弹幕发送与点赞热度递增 |
| **多元商城中心** | `GET /product/page`<br>`GET /product/detail/{id}`<br>`POST /product/publish`<br>`GET /category/list` | 商品多条件分页、价格走势、商户自主发布商品、多级分类 |
| **交易与订单闭环** | `POST /cart/add`<br>`GET /cart/list`<br>`POST /order/checkout`<br>`POST /order/pay/{orderNo}` | 购物车协同、事务原子结算、微信/支付宝沙箱收银支付 |
| **智能客服与备用链路** | `POST /support/ask`<br>`POST /support/fallback/status`<br>`POST /support/intent/analyze` | 混合向量知识检索客服问答、备用链路状态探测与意图分析 |
| **物流履约与对账** | `GET /admin/logistics/waybills`<br>`POST /admin/logistics/traces/sync`<br>`POST /admin/logistics/settlement/generate` | 运单追踪、物流轨迹推进（签收联动订单闭环）、服务商账期对账单生成 |
| **系统运行时治理** | `GET /admin/config/list`<br>`POST /admin/config/set`<br>`POST /admin/config/refresh` | 全站动态配置管理、一键全站维护模式切换、缓存热重载 |

---

## 🧪 自动化测试与质量保障

项目配备了严格的分层测试机制：

```bash
# 1. 后端工程单元测试与集成测试（JUnit 5 + Spring Boot Test）
cd backend
mvn test

# 2. 前端单元测试与组件检查（Vitest）
cd frontend
npm test

# 3. 前端生产打包构建验证
cd frontend
npm run build
```

---

## 🔒 安全体系设计

1. **凭据安全与哈希防护**：用户密码采用强盐散列算法（`BCrypt`）加密，支持 Google Authenticator TOTP 两步认证。
2. **敏感隐私脱敏**：企业核心敏感资产与账号字段通过 `AES-256-GCM` 算法加密落库，客服输入输出全链路实时扫描并脱敏敏感 PII 数据。
3. **接口安全与风控**：配置高防 CSRF 双提交 Cookie 校验，结合 IP / 账号维度的阶梯限流防刷拦截策略。
4. **供应链与代码静态分析**：持续配置 GitHub Actions CodeQL 多语言静态代码安全扫描与 Dependabot 依赖漏洞监控体系。

---

## 📬 联系方式与原创声明

### 👨‍💻 项目作者与联系方式

本项目由 **Jason Ouyang（欧阳）** 独立构思、设计与开发完成：

- **作者身份**：项目发起人 / 独立研发者（GitHub: [@c2jxmrywdn-del](https://github.com/c2jxmrywdn-del)）
- **主要邮箱**：[3514485358@qq.com](mailto:3514485358@qq.com)（日常项目交流、学术探讨与技术沟通）
- **商务/海外邮箱**：[ouyangjason001@gmail.com](mailto:ouyangjason001@gmail.com)（合作咨询与对外联络）
- **X / Twitter**：[@Orion_Yves_Jude](https://x.com/Orion_Yves_Jude)
- **Facebook**：[Jason Ouyang](https://www.facebook.com/profile.php?id=61590596057471)
- **即时通讯**：WhatsApp · [OuyangJason](https://wa.me/OuyangJason?s=t) / 微信：`O19111295446`
- **开源主页**：[https://github.com/c2jxmrywdn-del/LANLinkshopping-Local-version](https://github.com/c2jxmrywdn-del/LANLinkshopping-Local-version)

---

### ⚠️ 原创性与法律权益严正声明（严禁商业化、冒用与盗用）

> [!CAUTION]
> **知识产权与维权警告**
> 
> 1. **原创毕业设计成果归属**：本项目（包含但不限于平台架构设计、技术选型方案、功能业务闭环、前端与移动端 UI/UX 视觉体系、Apple UI 组件库、后端微服务与 AI 沙盒代码实现、接口定义、数据库结构及相关说明文档）均为作者本人拥有独立知识产权与著作权的原创毕业设计成果。
> 2. **严禁商业化与营利性使用**：本项目仅限用于**个人技术学习、学术交流与毕业设计演示**用途。**未经作者本人明确的书面授权许可，任何组织、机构、团队或个人严禁将本项目源码、架构或衍生作品用于任何商业营利活动、付费课程、外包交付、商业 SaaS 售卖或商业产品包装推广**。
> 3. **严禁冒用、抄袭与剽窃盗用**：严禁任何人对本项目进行署名篡改、冒用作者身份、去除原作者版权信息、伪造成自身独立原创成果（包括但不限于将本项目用于各类高校毕业设计答辩、课程大作业提报、技术竞赛作品评奖等欺瞒行为）。
> 4. **维权与法律追责**：对于任何未经许可的盗用、剽窃、商业倒卖或冒用侵权行为，作者已对项目完整研发历程、代码提交历史及相关成果进行全网公证固证，并**保留采取发送律师函、向所在高校/工作单位/托管平台进行侵权举证下架，乃至依法向人民法院提起知识产权侵权诉讼追究法律赔偿责任等一切合法维权措施**。请共同尊重原创知识成果！

---

## 📄 许可证说明

本项目依据 **GNU General Public License v3.0（GNU GPL-3.0）** 开源协议授权发布。

```text
LANLinkshopping — 聚合型一体多元化解决方案 B2B 电商平台
Copyright (C) 2026 LANLinkshopping the sole editor

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```

- 完整许可证协议文本详见根目录 [LICENSE](./LICENSE) 文件。
- **任何包含本项目源代码的衍生作品必须以相同的 GPL-3.0 协议开源**（强传染性 Copyleft 保障）。

---

<div align="center">

**感谢所有为 LANLinkshopping 提供支持的开源项目与生态技术社区！**

Made with ❤️ for Modern B2B E-Commerce & Open Innovation · 12/9/2026

</div>
