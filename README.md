<div align="center">

# LANLinkshopping

### 聚合型一体多元化解决方案 · B2B 电商平台

**Spring Boot 3 + Vue 3 全栈电商毕业设计项目**

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.3-brightgreen)
![Vue 3](https://img.shields.io/badge/Vue-3-42b883)
![MySQL](https://img.shields.io/badge/MySQL-8-blue)
![License](https://img.shields.io/badge/License-GNU%20GPL%20v3-blue)

[功能特性](#-功能特性) · [快速开始](#-快速开始) · [使用说明](#-使用说明) · [主编者指南](#-主编者指南) · [许可证](#-许可证)

</div>

---

## 📖 项目简介

**LANLinkshopping** 是一个聚合型、多行业、B2B 一体化的电商平台，覆盖 **登录鉴权（RBAC）· 商户入驻与筛选 · 商品商城 · 购物车 · 下单支付** 五大核心业务闭环，并内置"链式业务拓展"设计，可按行业低成本扩展。

项目同时交付：

- **Web 网站端**（Spring Boot + Vue 3 全栈 MVP，本仓库主线）
- **微服务骨架**（`cloud/`：Spring Cloud + Nacos + Gateway + OpenFeign，8 个模块）
- **AI 网关沙盒**（`ai-gateway/`：OpenAI 兼容接口 + 本地大模型 Ollama + 知识库 RAG）
- **智能客服**（本地知识库问答 + 哨兵式备用链路 + 策略守卫）

> 本项目为**教学 / 毕业设计演示项目**，默认运行在本地开发模式，请勿直接用于真实交易或对外提供服务。

## ✨ 功能特性

### 核心业务（五大模块）

| 模块 | 能力 |
|------|------|
| 🔐 登录鉴权 | 注册/登录、RBAC 五级身份（GUEST/BUYER/VIP/MERCHANT/ADMIN）、TOTP 两步验证、CSRF 双提交、登录限流 |
| 🏪 商户入驻 | 入驻申请、资质审核、智能筛选（企业类型 + 注册资本 + 纳税记录自动过审）、后台审核工作台 |
| 🛍️ 商品商城 | 24 行业分类导航、商品检索/详情/发布、阶梯价、商品审核流 |
| 🛒 购物车 | 加购、改量、勾选结算、库存校验 |
| 📦 下单支付 | 结算下单（事务扣库存 + 订单明细快照）、微信/支付宝（mock 沙箱）、积分抵扣（1000 积分 = 1 元）、订单全生命周期 |

### 增值能力

- **智能客服**：本地知识库匹配问答、寒暄/自我介绍意图路由、统一对话礼仪（标准问候语）、会话落库、PII 自动脱敏
- **客服备用链路**：主服务异常时自动接管，DeepSeek/Mimosa 仅限寒暄润色、知识问答强制走本地知识库、反代/绕过策略拦截、恢复后自动切回
- **AI 网关（Docker 沙盒）**：OpenAI 兼容 `/v1/chat/completions`、本地 qwen2.5 推理、TF-IDF 向量检索 RAG、电商场景 Prompt 模板
- **账号中心**：AES-256-GCM 敏感字段加密、钱包与账期、会员等级、审计日志
- **前端体验**：全站动效体系（滚动进场/骨架屏/微交互）、i18n 中英切换、响应式布局、性能治理（帧率自适应 + 软硬解码切换 + 熔断降级）

## 🗂️ 项目结构

```
LANLinkshopping/
├── backend/                  # Spring Boot 3 后端（主应用）
│   ├── src/main/java/…       # controller / service / mapper / entity / support / payment
│   └── src/main/resources/
│       ├── application.yml   # 主配置（默认 dev profile）
│       ├── application-dev.yml / application-prod.yml
│       ├── knowledge-base/   # 客服知识库（customer-service-zh-CN.json）
│       └── sql/              # schema.sql（幂等建表）+ account.sql
├── frontend/                 # Vue 3 + Vite + Ant Design Vue + Pinia
│   ├── src/{api,router,store,views,components,utils,styles}
│   └── package-lock.json     # 依赖锁定文件（必须提交）
├── ai-gateway/               # AI 网关沙盒（FastAPI + Ollama + RAG，Docker 编排）
├── cloud/                    # Spring Cloud 微服务骨架（8 模块 + Nacos）
├── support-learning-sandbox/ # 客服学习沙盒（Python 评估服务）
├── docs/                     # 项目文档（部署说明 / 接口文档 / 测试报告）
├── docker-compose.local.yml  # 本地基础设施（Mailpit / 客服沙盒）
├── start-local-env.ps1       # 一键启动脚本（读取 .env）
├── 启动本地环境.bat            # 一键启动入口（纯 ASCII 薄壳）
├── LICENSE                   # GNU General Public License v3.0
├── SECURITY.md               # 安全策略与漏洞披露流程
└── README.md
```

## 🚀 快速开始

### 环境要求

| 组件 | 版本要求 |
|------|----------|
| JDK | 17+ |
| Maven | 3.8+（或使用 `mvnw`） |
| Node.js | 18+ |
| MySQL | 8.0+ |
| Docker | 24+（可选，本地基础设施 / AI 网关沙盒） |

### 1. 克隆仓库

```bash
git clone https://github.com/<your-username>/LANLinkshopping.git
cd LANLinkshopping
```

### 2. 配置本地环境变量

```bash
# 复制模板并填写（.env 已被 .gitignore 排除，绝不入库）
copy .env.example .env       # Windows
cp .env.example .env         # macOS / Linux
```

`.env` 至少需要配置：

```env
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/lanlink_shopping?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true
SPRING_DATASOURCE_USERNAME=root
SPRING_DATASOURCE_PASSWORD=<你的 MySQL root 密码>
```

> 完整可选项（邮件 / 支付沙箱 / 客服沙盒令牌 / 备用链路 API Key）见 [.env.example](./.env.example)。

### 3. 一键启动（推荐）

```powershell
# Windows：双击 启动本地环境.bat，或执行
powershell -ExecutionPolicy Bypass -File start-local-env.ps1
```

脚本自动完成：读取 `.env` → 启动 Docker 基础设施 → 启动后端（8080）→ 启动前端（5173）→ 健康检查。

### 4. 手动启动（分步）

```bash
# 数据库初始化（后端 dev profile 启动时自动执行 schema.sql，幂等安全）
# 如需手动导入：
mysql -uroot -p --default-character-set=utf8mb4 < backend/src/main/resources/sql/schema.sql

# 后端（端口 8080，接口前缀 /api）
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 前端（端口 5173，已配置 /api 代理到 8080）
cd frontend
npm ci
npm run dev
```

浏览器访问 **http://localhost:5173**（局域网访问使用 `http://<本机IP>:5173`）。

## 🧭 使用说明

### 演示账号（dev profile 自动初始化，密码均为 `123456`）

| 手机号 | 角色 | 权限说明 |
|--------|------|----------|
| 13800000000 | 平台运营 admin | 商户审核、退款、运营后台 |
| 13900000001 | 采购方 buyer | 浏览、加购、下单、支付 |
| 13700000002 | 商户 merchant | 商品发布、店铺管理 |

### 核心接口速览（前缀 `/api`）

| 分组 | 接口 |
|------|------|
| 认证 | `POST /auth/register` `POST /auth/login` `POST /auth/logout` `GET /auth/me` |
| 首页 | `GET /home/industries` `GET /home/hot` `GET /category/list` |
| 商城 | `GET /product/page` `GET /product/detail/{id}` `POST /product/publish` |
| 商户 | `POST /merchant/apply` `GET /merchant/my` `POST /merchant/admin/review/{id}` |
| 购物车 | `POST /cart/add` `GET /cart/list` `DELETE /cart/{id}` |
| 订单 | `POST /order/checkout` `GET /order/my` `POST /order/pay/{no}` |
| 客服 | `POST /support/ask`（知识库问答 / 会话记录） |

接口出入参与鉴权细节见 [docs/账号中心接口文档.md](./docs/账号中心接口文档.md)。

### 运行测试

```bash
# 后端测试（JUnit，约 190+ 用例）
cd backend && mvn test

# 前端测试（Vitest）
cd frontend && npm test

# AI 网关测试（22 用例，构建镜像时自动执行）
cd ai-gateway && python -m unittest discover -s tests
```

## 🤝 贡献指南

欢迎通过 Issue 与 Pull Request 参与贡献！

1. **Fork** 本仓库并创建特性分支：`git checkout -b feature/your-feature`
2. **遵循现有代码风格**：后端 Java 使用 Spring Boot 分层规范，前端 Vue 3 组合式 API
3. **提交前自检**：
   - 后端 `mvn test`、前端 `npm test` 全部通过
   - 不提交任何密钥、证书、`.env`、数据库口令（`.gitignore` 已覆盖常见敏感文件）
   - 新增配置项请同步更新 `.env.example` 与文档
4. **提交信息**：使用清晰的 Conventional 风格（如 `feat: xxx` / `fix: xxx` / `docs: xxx`）
5. 发起 Pull Request，描述改动动机、方案与测试结果

行为准则：请以尊重、建设性的态度参与讨论。

## 🔒 安全

- 本项目是**演示项目**，请勿用于真实交易或生产环境。
- 发现安全漏洞请按 [SECURITY.md](./SECURITY.md) 私密披露，**不要在公开 Issue 中披露**。
- 疑似泄露的密钥（数据库口令、支付私钥、API Key）请立即轮换并联系我们。
- 安全设计要点：BCrypt 口令哈希、TOTP 两步验证、AES-256-GCM 敏感字段加密、CSRF 防护、RBAC 权限矩阵、请求限流、审计日志、支付回调签名校验。

## 📦 依赖锁定与供应链安全

- 前端：[frontend/package-lock.json](./frontend/package-lock.json) 已提交，安装请使用 `npm ci` 保证版本一致
- 后端：[backend/pom.xml](./backend/pom.xml) 固定依赖版本；Maven Wrapper 锁定构建工具（`.mvn/wrapper/maven-wrapper.properties`）
- 依赖漏洞由 [`.github/dependabot.yml`](./.github/dependabot.yml) 自动跟踪，CI 含 CodeQL 静态扫描（[`.github/workflows/codeql.yml`](./.github/workflows/codeql.yml)）

## 📄 许可证

本项目采用 **GNU General Public License v3.0（GNU GPL-3.0）** 开源许可证发布。

```
LANLinkshopping — 聚合型一体多元化解决方案 B2B 电商平台
Copyright (C) 2026  LANLinkshopping the sole editor

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```

- 完整许可证全文见根目录 [LICENSE](./LICENSE) 文件（GNU GPL v3.0 官方原文）
- 你可以自由地使用本软件，但**衍生作品必须以相同 GPL-3.0 条款开源**（copyleft）
- 本软件不提供任何明示或默示的担保，详见许可证第 15-17 节

## 🙏 致谢下午 02:23 2026/10/23

- [Spring Boot](https://spring.io/projects/spring-boot) · [Vue.js](https://vuejs.org/) · [Ant Design Vue](https://antdv.com/) · [MyBatis-Plus](https://baomidou.com/)

---

<div align="center">

**如果本项目对你有帮助，请给一个 ⭐ Star 支持一下！**

Made with ❤️ for Graduation Project 2026

</div>
