<div align="center">

# LANLinkshopping

### 聚合型一体多元化解决方案 · 现代化工业级 B2B 电商全栈中台平台

**Spring Boot 3.5.3 + Vue 3 (Vite 6) + Redis Stack HNSW + MinIO S3 + RabbitMQ 4.0 + 本地双大模型编排 + Dify 工作流 + Apple UI 交互规范**

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.3-brightgreen)
![Vue 3](https://img.shields.io/badge/Vue-3-42b883)
![Vite](https://img.shields.io/badge/Vite-6.4.5-646cff)
![MySQL](https://img.shields.io/badge/MySQL-8.0-blue)
![Redis](https://img.shields.io/badge/Redis-Stack%20HNSW-dc382d)
![MinIO](https://img.shields.io/badge/MinIO-S3%20Storage-c72c48)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-4.0%20Outbox-ff6600)
![Ubuntu](https://img.shields.io/badge/Ubuntu-22.04%20%7C%2024.04%20LTS-E95420?logo=ubuntu&logoColor=white)
![CUDA](https://img.shields.io/badge/CUDA-12.4%20%7C%2012.8%20%7C%2013.0-76B900?logo=nvidia&logoColor=white)
![License](https://img.shields.io/badge/License-GNU%20GPL%20v3-blue)
[![Twitter / X](https://img.shields.io/badge/X-@Orion__Yves__Jude-000000?logo=x&logoColor=white)](https://x.com/Orion_Yves_Jude)
[![Facebook](https://img.shields.io/badge/Facebook-Jason_Ouyang-1877F2?logo=facebook&logoColor=white)](https://www.facebook.com/profile.php?id=61590596057471)

[功能特性](#-核心业务与高阶微中台能力矩阵) · [系统架构](#-系统架构与技术全景) · [架构理念](#2--核心架构理念与设计哲学-architectural-philosophy--design-doctrine) · [交互式架构图](./architecture-overview.html) · [开发文档](#-开发者工程手册与调试指南) · [快速开始](#-快速开始与环境部署) · [核心接口](#-核心-api-接口速览统一前缀-api) · [Twitter (X)](https://x.com/Orion_Yves_Jude) · [Facebook](https://www.facebook.com/profile.php?id=61590596057471) · [联系与声明](#-联系方式与原创声明) · [许可证](#-许可证说明)

**[English](./README.en.md)** · 简体中文

</div>

---

## 📖 项目简介与系统定位

**LANLinkshopping** 是一个专为现代化工业供应链、大宗生产资料集采、企业协作与科技成果转化设计的**聚合型、多行业、B2B 一体化全栈电商中台平台**：深度覆盖「统一身份鉴权 · 商户自主入驻与资质审核 · 24 大行业商品商城 · 智能协同推荐 · 购物车 · 下单支付与对账履约」基础交易闭环，并演进出工业品技术服务交易中心、物流 GIS 干线调度、企业订阅采购、企业信用白条、售后多媒体证据链、大促主会场与领券中心、AI 商品标题润色、直播带货、Apple 拟态交互体系、本地双大模型编排（Qwen2.5-7B + Qwen2.5-VL）与 MinIO / RabbitMQ 事务性中间件等工业生产级中台能力。

> [!NOTE]
> 本项目为**教学 / 毕业设计演示项目**，默认运行在本地开发模式，请勿直接用于真实生产资金流交易或未经安全加固对外提供公共服务。

---

## 🏛️ 系统架构与技术全景

### 1. 现代化工业全栈架构拓扑图 (Architecture Topology)

> 🌐 **交互式全景架构图（Archify 可视化呈现）**：  
> 本系统架构已通过项目内置的 `archify` 工业架构制图引擎完成自动化构建与验证（通过 `validate`、`deliver`、`check` 以及实际无头浏览器 `browser-check` 100% 门禁校验）。支持**深浅色主题自由切换、物理实时动效流动、高亮路径追溯与 24 个核心节点/7 大安全边界探索**。可在浏览器中直接打开预览：[`architecture-overview.html`](file:///D:/Desktop/LANLinkShopping/Shopping/architecture-overview.html)（本地源码定义见 [`.archify/architecture-overview/candidate.json`](file:///D:/Desktop/LANLinkShopping/Shopping/.archify/architecture-overview/candidate.json)）。

```mermaid
flowchart TD
    subgraph Client["1. 表现层 · 现代化工业人机交互 (Presentation Tier)"]
        WebDesktop["桌面端 Web 生产力中台 (Vue 3 + Vite 6 + AntD 4.x)"]
        WebMobile["移动端 H5 敏捷商城 (自适应流体布局)"]
        AppleUI["Apple HIG 物理流体拟态体系 (Dock / 灵动岛 / FluidTabs / GlowCard / 3D轮播)"]
    end

    subgraph Ingress["2. 接入层 · 零信任安全防护网 (Security Ingress)"]
        NginxProxy["Nginx 高性能反向代理 (动静分离 / 静态资源加速)"]
        SecurityGateway["Spring Boot 安全网关 (GATEWAY_STRICT_ROLE / CSRF双提交 / TOTP 2FA / 令牌桶限流)"]
    end

    subgraph BizHub["3. 核心业务与高阶微中台层 (Business Mid-Platform)"]
        TradeCore["实体交易核心主线 (鉴权 / 商户入驻审核-实缴≥10万 / 24行业商品 / 购物车 / 下单支付 / 营销大促 / 直播)"]
        IndustryHub["工业赋能高阶中台 (技术合同里程碑 / 成果TRL鉴定 / 专家咨询 / 中试预约 / 物流GIS轨迹调度 / 周期订阅)"]
        FinanceLedger["金融合规与出金中枢 (银行存管流水 / 无二清对公提现台账 / 订单评价聚合 / 商户退店六道门禁)"]
    end

    subgraph Middleware["4. 异步管道与中间件层 (Middleware & Async Pipeline)"]
        OutboxWorker["Transactional Outbox 投递工作器 (本地事务消息表 / 强一致保证)"]
        RabbitMQCluster["RabbitMQ 4.0 消息总线 (Topic交换机 / 消费端唯一键去重 / q.notify.dlq 死信兜底)"]
        MinIOS3["MinIO S3 对象存储 (私有桶凭据隔离 / 后端临时签名防盗链中转)"]
        MailpitSandbox["Mailpit 邮件沙箱 (本地验证码拦截与通知调试)"]
    end

    subgraph AIHub["5. AI 与多智能体枢纽 (AI & Multi-Agent Hub)"]
        FastApiGW["FastAPI AI 网关 (8090端口 / OpenAI标准API / Tool Calling / 流式语音边界探测)"]
        LlamaRouter["llama.cpp 双模型 Router (Qwen2.5-7B 文本 + Qwen2.5-VL 视觉热切换 / 显存感知调度器)"]
        RagEngine["LightRAG / Redis Stack HNSW (BGE-M3 1024维密集嵌入 / 知识库CDC重建)"]
        SQLBotEngine["SQLBot 智能问数中枢 (Text-to-SQL 容器内网8000隔离 / 只读数据沙箱)"]
        DifyOrchestrator["Dify 多智能体工作流 (采购意图理解 / 寻源比价 / 售后协同)"]
        McpBridge["标准 MCP 工具桥 (lanlink_kb_search / lanlink_ask_data)"]
    end

    subgraph DataStore["6. 数据持久化与缓存中枢 (Data Persistence Tier)"]
        MySQLPrimary["MySQL 8.0 核心主库 (InnoDB 行级排他锁 / 事务 ACID 保证)"]
        RedisCluster["Redis Stack 7.4 (HNSW 向量索引 / 两级语义缓存 / 分布式限流计数)"]
    end

    subgraph SRE["7. 全链路可观测性与系统韧性 (Observability & Chaos Engineering)"]
        PrometheusObs["Prometheus (指标周期抓取 / SLA规则评估)"]
        AlertManagerObs["Alertmanager (钉钉/飞书 Webhook 实时告警)"]
        GrafanaDashboard["Grafana 综合监控大屏 (QPS / P99时延 / GPU显存及温度)"]
        ChaosWatchdog["守护看门狗与混沌演练 (自愈探针 / 故障注入测试)"]
    end

    %% 核心链路拓扑
    Client -->|"HTTPS / WSS"| Ingress
    Ingress -->|"RESTful / 鉴权拦截"| BizHub
    Ingress -.->|"WebSocket 流式语音 / SSE"| AIHub
    
    BizHub -->|"Transactional Outbox 原子写入"| Middleware
    Middleware -->|"AMQP 异步广播 / 死信重试"| BizHub
    BizHub -->|"私有合同与售后多媒体存证"| MinIOS3
    
    BizHub -->|"SQL 强一致读写 (排他行锁)"| MySQLPrimary
    BizHub -->|"高频热点缓存 / 分布式限流"| RedisCluster
    
    BizHub <-->|"MCP RPC / 知识库问答"| AIHub
    AIHub -->|"两级语义缓存 (SHA-256 + 向量相似度)"| RedisCluster
    SQLBotEngine -->|"Text-to-SQL 只读安全查询 (sqlbot_ro)"| MySQLPrimary
    
    BizHub -.->|"Metrics 暴露"| SRE
    AIHub -.->|"推理吞吐与显存监控"| SRE
```

---

### 2. 💡 核心架构理念与设计哲学 (Architectural Philosophy & Design Doctrine)

系统在演进过程中秉承现代软件工程最高标准，针对工业互联网与企业级交易的严苛要求，沉淀了七大不可妥协的架构设计哲学：

#### 理念一：聚合型工业一体化微中台（Aggregated Unified Industrial Mid-Platform）
- **工业痛点破局**：传统工业品交易与产业生态中，“实体大宗生产资料采买”与“技术服务/科研成果转化”长期严重割裂。大宗精密设备买卖后，后续安装调试、专家诊断、中试验证与周期性维保缺乏履约闭环；高校科研院所成果因缺乏 TRL 鉴定与中试基准深陷“死亡之谷”；大宗技术合同缺乏阶段验收约束导致“交付即扯皮”。
- **架构闭环设计**：
  - **一体多元双核驱动**：商城大宗采购（24 大实体行业、阶梯批发价、价格日 K 线）与工业技术服务（[`module/techservice/`](file:///d:/Desktop/LANLinkShopping/Shopping/backend/src/main/java/com/lanlink/shopping/module/techservice/)）深度聚合；
  - **数字化合同与资金存管联动**：构建全生命周期电子合同状态机（[`TechContractService`](file:///d:/Desktop/LANLinkShopping/Shopping/backend/src/main/java/com/lanlink/shopping/module/techservice/service/TechContractService.java)），将工程周期按 30%-40%-30% 划分为独立里程碑，买方资金存管至第三方托管流水（[`TechEscrowLedger`](file:///d:/Desktop/LANLinkShopping/Shopping/backend/src/main/java/com/lanlink/shopping/module/techservice/entity/TechEscrowLedger.java)），阶段验收单（`TechAcceptance`）签章生效后触发逐期解冻放款；
  - **产学研生态闭环**：专家付费在线问诊（`TechExpertService`）、共享中试基地工位预约（`TechPilotService`）、科技成果 TRL 1~9 级就绪度鉴定报告（`TechAppraisalService`）与设备全生命周期维保档案（`TechHostingService`）反哺商城备件消耗。
- **工程权衡（Trade-offs）**：采用 DDD 领域驱动设计的**高内聚模块化单体（Modular Monolith）**，坚决避免过早微服务化带来的网络 RPC 时延、分布式事务损耗与多容器显存内耗；各域在 `module.*` 内自闭环，仅通过强契约接口与事件总线通信，保留平滑拆分微服务的架构弹性。

#### 理念二：异步解耦与事务外发强最终一致性（Transactional Outbox & Event-Driven Decoupling）
- **工业痛点破局**：商品数据变更同步 AI 向量库、订单支付触发下游通知或履约等场景中，若在本地数据库事务中直接发起外部 RPC 或 MQ 投递，极易因模型推理慢、网络抖动或显存锁死导致数据库连接池雪崩，甚至引发分布式双写不一致。
- **架构实现**：
  - **Transactional Outbox 模式**：业务实体变更与本地消息表（如 `t_ai_kb_outbox`、`t_order_outbox`）在同一个本地数据库事务中原子提交，杜绝半事务悬挂；
  - **数据库级原子合并防抖**：采用 `INSERT ... ON DUPLICATE KEY UPDATE` 配合 `pending_key` 唯一键，商户对同一商品的连续高频修改原子收敛为单条待同步任务；
  - **租约锁与指数退避**：守护工作线程（`KbOutboxWorker`）按批次（Batch Size=5）获取分布式租约排他锁，外部 AI 推理失败执行带 Jitter 的指数退避重试（上限 8 次），最终失败入死信队列（`q.notify.dlq`）触发人工介入。
- **工程权衡**：以秒级最终一致性换取核心交易链路的绝对零阻塞与系统吞吐量最大化。

#### 理念三：端云协同与消费级算力极限适配哲学（Hardware-Aware Adaptive Edge-Cloud AI）
- **工业痛点破局**：在边缘工业网关或私有化交付中，算力常受限于单张消费级显卡（如 RTX 4070 Laptop 8GB 显存 / 24GB 内存）。盲目常驻多个 7B 大模型必导致 CUDA OOM；全量上云又受制于企业核心涉密数据外泄与高昂 Token 费用；频繁切换模型会引发剧烈的显存抖动（Model Thrashing）。
- **架构实现**：
  - **硬件感知自适应调度（`HardwareScheduler`）**：实时探测 GPU 显存与负载（2s 缓存防系统调用抖动），以 6650MB (Text)、7300MB (VL)、5550MB (CPU Assist) 与 250MB 安全冗余建立物理基线；
  - **llama.cpp 双模型 Router 动态热切换**：确保显存中任意时刻仅驻留单一模型，在 Qwen2.5-7B（工业文本/客服）与 Qwen2.5-VL（工业图纸/质检多模态）间平滑切换；
  - **CPU 卸载与滞回阈值防颠簸**：显存紧张时平滑降级至 `qwen2.5-7b-cpu-assist`（3/29 层卸载至 CPU，速度保持在纯 GPU 的 85% 以上），并设置 120s 冷却期与 500MB 滞回缓冲（Hysteresis）；
  - **两级语义向量缓存（`SemanticCache`）**：Tier 1 SHA-256 精确哈希（<0.5ms）+ Tier 2 RediSearch HNSW 向量近似匹配（Cosine 相似度 $\ge 0.96$），拦截重复性工业咨询；
  - **RAG 服务极致轻量化**：剥离重度依赖，镜像骤降至 334MB，重度文档解析下沉至 Java 宿主层。
- **工程权衡**：通过算法调度与内存排布优化，在 8GB 消费级显卡上榨取出媲美数据中心集群级的大模型多模态问答与语义检索能力。

#### 理念四：零信任金融合规边界与“防二清”账面治理（Zero-Trust Financial Boundary & Anti-Second-Clearing）
- **工业痛点破局**：无支付牌照的平台私设资金池极易触碰“非法大商户二清”刑事合规红线；而在并发提现与大促退款中，MySQL 默认快照读极易导致“同一笔资金被重复提现或退款”的毁灭性资损。
- **架构实现**：
  - **严守防二清红线**：平台绝不沉淀资金池。大宗技术服务款项全链路登记银行第三方存管/托管流水（[`TechEscrowLedger`](file:///d:/Desktop/LANLinkShopping/Shopping/backend/src/main/java/com/lanlink/shopping/module/techservice/entity/TechEscrowLedger.java)）；商户资金提现采用“线下对公银行转账 + 平台仅记账面台账”口径，杜绝资金代收代付；
  - **行级排他锁与当前读**：资金核心操作强置 `SELECT ... FOR UPDATE` 行级悲观排他锁（`lockMerchant()`），彻底击穿 MySQL REPEATABLE READ 快照读盲区，强制执行最新当前读，并发提现必定严格串行化，断言“一成功一拒绝”；
  - **单向追加式财务流水（Append-Only Ledger）**：`t_finance_ledger` 冻结与解冻全部采用单向递增追加写入，落库严格校验前置与后置余额（`balance_after`），与业务事务同生共死；
  - **金融级 JWE 信封加密**：银行账号仅落前 4 后 4 掩码，明文全密文落库采用 RSA-2048 + AES-256-GCM JWE 强加密。
- **工程权衡**：在资金敏感链路上容忍毫秒级行锁排队，换取零资金损失与银行级合规审计保证。

#### 理念五：时空 GIS 拓扑与工业级供应链透明度（Spatio-Temporal Logistics GIS & Supply Chain Observability）
- **工业痛点破局**：大宗工业物资运输动辄跨省长途、重型件、特种车。国内 WGS84、GCJ02 与 BD09 坐标系碎片化导致轨迹漂移 100~700 米，在途运输陷入“信息黑盒”，纸质回单结算周期漫长。
- **架构实现**：
  - **无偏坐标双向转换引擎（[`coordTransform.js`](file:///d:/Desktop/LANLinkShopping/Shopping/frontend/src/utils/coordTransform.js)）**：基于克拉索夫斯基椭球体非线性回归算法，实现高精双向互转；严格秉承“库内洁净不污染，地图消费端动态投影”原则；
  - **全国干线拓扑推演**：利用大圆距离（Haversine Formula）结合全国公路实际绕行修正因子，智能测算干线物理里程并推演中转节点时序；
  - **分级异常看门狗（`TrackingExceptionService`）**：在途节点停留 >24h 触发黄灯催件预警，>48h 标红并自动向运营客服派发加急工单；签收 24h 未复核电子回单（e-POD）自动阻断运费打款；
  - **账期对账单自动化**：签收复核完毕后自动汇总服务商运费、理赔金与燃油附加费，形成透明对账闭环。
- **工程权衡**：空间几何变换下沉至前端浏览器计算，无需后端额外引入沉重的 PostGIS 空间数据库插件。

#### 理念六：严格最小特权模型与全链路深度纵深防御（Defense-in-Depth & Strict Role Governance）
- **工业痛点破局**：智能体与 API 网关交互中，恶意请求可能通过篡改 Header 冒充内部角色调用高危写工具；Text-to-SQL 智能问数可能被注入恶意提权命令；违规商户可能在被追偿前突击下架逃逸。
- **架构实现**：
  - **严格网关角色模式（`GATEWAY_STRICT_ROLE=1`）**：网关服务端维护权威 `KEY_ROLE_MAP`，调用方必须携带专属已绑定角色密钥（`service` / `buyer` / `support` / `finance` / `fulfillment`），未绑定密钥严禁采纳自报角色，默认 Fail-Closed 仅开放只读工具；
  - **OpenAPI 模式物理剥离**：未授权访问 `/v1/ai/openapi.json` 时，网关在序列化层物理剔除三大高危写工具（建草稿、财务过审、审批签发），攻击者无法探测内部写契约；
  - **SQLBot 物理隔离安全沙箱**：容器编排（[`docker-compose.sqlbot.yml`](file:///d:/Desktop/LANLinkShopping/Shopping/docker-compose.sqlbot.yml)）坚决不向宿主机映射 8000/8001 端口，限死在 `shopping_default` 隔离网桥；数据库配置只读受限账号 `sqlbot_ro`（仅授予 SELECT 权限）；调用显式注入 `extra_body = {"use_kb": false}` 杜绝 RAG 提示词二次污染；
  - **商户退店六道硬门禁（[`module/merchantexit/`](file:///d:/Desktop/LANLinkShopping/Shopping/backend/src/main/java/com/lanlink/shopping/module/merchantexit/)）**：深度对齐《电子商务法》第十六条，构建 30 天法定公示期软关闭流程，实施在途订单、未决售后、未结清款项、在售商品、欠款保证金、完成期时间窗六重硬卡点，彻底封堵恶意商户逃逸漏洞。
- **工程权衡**：多层防线物理隔离与权限锁死，构筑零信任纵深防御堡垒。

#### 理念七：工业级人体工学与 Apple 拟态交互美学（Industrial Ergonomics & Apple HIG Fluid Aesthetics）
- **工业痛点破局**：传统工业管理软件常陷入冰冷死板、表格压抑、缺乏交互引导的“地狱体验”；跨平台滥用系统 Emoji 造成严重的字体渲染差异与乱码；传统语音客服必须全文生成后才发声，首字延迟高且无法打断。
- **架构实现**：
  - **Apple HIG 拟态流体交互体系（[`components/ui/`](file:///d:/Desktop/LANLinkShopping/Shopping/frontend/src/components/ui/)）**：自研 macOS 拟态物理流体 Dock（弹簧物理缩放）、iPhone 灵动岛交互胶囊（业务状态弹性平滑变形展收）、带惯性阻尼的 FluidAnimatedTabs、光标跟随的 GlowBorderCard 流光边框与 RadarPulseBadge 呼吸徽章；
  - **全站纯矢量 Lucide SVG 资产治理**：彻底驱逐非标 Emoji 符号，100% 收敛至纯矢量 `lucide-vue-next` (v0.577.0) 图标库，由全局 CSS 语义设计令牌统一渲染，跨系统视觉零歧义；
  - **全双工流式语音交互闭环**：基于 WebSocket（`/api/support/stream-voice`）构建流式语音通信；首创智能切句算法（`SentenceBoundaryDetector`，首句 6/10 字符极速合成，防饥饿 30 字符截断，智能避开 URL/版本号虚假标点），支持 4ms 极速打断（Barge-in），实现行云流水般的工业人机智能交互。
- **工程权衡**：依托现代 GPU CSS3 硬件渲染与矢量图形学，将消费电子级旗舰交互体验注入严肃严谨的工业生产力场景。

---

### 3. 全技术栈清单速览

| 维度 | 技术选型 | 版本/规范 | 架构作用与定位 |
|------|---------|-----------|----------------|
| **后端主框架** | Spring Boot | 3.5.3 (Java 17 LTS) | 核心微服务与单体应用容器底座，高性能异步通信 |
| | MyBatis-Plus | 3.5.10.1+ | 实体持久化、分页防 SQL 注入与动态条件封装 |
| | Spring Security | 6.x | RBAC 五级鉴权、BCrypt 强密码加盐、TOTP 双因素安全认证 |
| | Spring Cloud | 2025.1.x (演进骨架) | 预研微服务骨架（Nacos 注册中心、Gateway、OpenFeign） |
| **前端体系** | Vue.js | 3.5.x (组合式 API) | 现代化响应式框架，模块化单文件组件 (SFC) |
| | 构建工具 | Vite 6.4.5 | 极速 ESM 开发冷启动、HMR 热更新、Rollup 生产分包优化 |
| | UI 组件库 | Ant Design Vue 4.x | 企业级中后台高质感 UI 交互控件库 |
| | 矢量图标 | Lucide Vue Next | 全站纯矢量 SVG 统一映射治理，无任何 Emoji 字符滥用 |
| | 状态与路由 | Pinia + Vue Router 4.x | 全局响应式状态共享、路由守卫与平滑滚动记忆 |
| **消息与存储** | 对象存储 | MinIO (S3 兼容) + AWS SDK v2 2.57.8 | 商户资质与技术服务材料加密存储；私有桶后端安全代理出站 |
| | 消息队列 | RabbitMQ 4.0 (`rabbitmq:4-management`) | 事务外发模式（Transactional Outbox）、唯一键去重、死信兜底 |
| | 数据库 | MySQL 8.0+ (InnoDB) | 行级排他锁、多表事务 ACID 保证、索引与外键约束优化 |
| | 缓存与向量库 | Redis Stack 7.4 (RediSearch) | 整合 HNSW 向量索引、分布式缓存、分布式限流原子计数 |
| **AI / 大模型** | 推理底座 | llama.cpp (Docker + cuBLAS) | 本地默认适配 Qwen2.5-7B 与 Qwen2.5-VL（受限于 RTX 4070 Laptop 8G 显存；硬件宽裕者可无缝扩展至 14B/32B/72B） |
| | 系统环境 | Ubuntu 22.04 / 24.04 LTS | 原生 Linux 或 Windows WSL2 容器与微调运行环境 |
| | GPU 加速 | CUDA 12.4 / 12.8 / 13.0 | cuBLAS 与 FlashAttention-2 高性能并行计算 |
| | AI 网关 | FastAPI + Uvicorn | 兼容 OpenAI 标准 API、多工具调用 (Tool Calling)、流式输出 |
| | 向量嵌入 | BAAI/bge-m3 | 1024 维多语言高维密集向量嵌入模型 |
| | 多智能体编排 | Dify DSL + MCP | 编排多 Agent 协作工作流；标准 MCP 协议只读向量检索 (`lanlink_kb_search`) |
| | 模型微调强化 | Unsloth + TRL + PyTorch | 工业意图分类 LoRA SFT 微调、AGL/GRPO 强化学习闭环与量化导出 |
| **运维与观测** | 指标与看板 | Prometheus + Grafana | 核心吞吐率 (QPS/TPS)、P99 延迟、GPU 显存占用与显卡温度监控 |
| | 韧性与自愈 | Chaos / Watchdog | 混沌故障断网宕机演练、守护进程健康探活与故障自愈 |

---

## ✨ 核心业务与高阶微中台能力矩阵

### 1. 基础交易全闭环（五大核心模块）

| 模块 | 核心能力 |
|:---|:---|
| 🔐 **登录鉴权中心** | 手机号注册/登录、RBAC 五级权限矩阵（GUEST / BUYER / VIP / MERCHANT / ADMIN）、TOTP 2FA、CSRF 双提交防护与密码错误阶梯锁定。 |
| 🏪 **商户入驻中台** | 企业入驻在线申请与营业执照 MinIO 存证、实缴资本 ≥ 10 万元硬性门槛、智能合规初筛与独立商户管理后台。 |
| 🛍️ **多元商品商城** | 24 大实体行业多级分类索引、阶梯批发价与大宗起订量、多规格 SKU 体系与价格走势日 K 线行情。 |
| 🛒 **智能购物车** | 原子级规格库存校验、大宗批发批量修改数量、一键全选/按店铺勾选结算与运费预估。 |
| 📦 **下单支付履约** | 分布式原子扣减库存、订单全明细快照、支付宝/微信/余额沙箱支付、积分抵扣与白条账期结账，核心入口 [`OrderController.java`](file:///d:/Desktop/LANLinkShopping/Shopping/backend/src/main/java/com/lanlink/shopping/controller/OrderController.java)。 |

### 2. 工业品技术服务与科技成果转化中台

面向工业企业与技术服务商的技术交易与成果转化中台：技术电子合同阶段里程碑验收并绑定第三方资金托管放款、「卡脖子」需求揭榜挂帅公开竞标、专家在线诊断与付费问答、中试基地/共享实验室预约、科技成果 TRL 鉴定评估与设备全生命周期托管维保，模块全景见 [`module/techservice/`](file:///d:/Desktop/LANLinkShopping/Shopping/backend/src/main/java/com/lanlink/shopping/module/techservice)。

### 3. 物流 GIS 轨迹与干线中枢

中国干线物流轨迹与调度中枢：WGS84 / GCJ02 / BD09 高精坐标双向换算引擎（[`coordTransform.js`](file:///d:/Desktop/LANLinkShopping/Shopping/frontend/src/utils/coordTransform.js)）、大圆距离 + 绕行修正因子的干线拓扑路由与在途节点时序推演、运单单节点停留超 24 小时黄灯 / 超 48 小时红灯预警与一键催件改派，以及按服务商账期的对账结算单自动化。

### 4. 营销大促主会场与领券中心

大促主会场集纳各行业爆品、限时满减会场与主推首发专区；高并发领券中心支持阶梯满减、新人专享与限时折扣券一键批量领取，基于 Redis 原子计数防刷（[`AdCampaignController.java`](file:///d:/Desktop/LANLinkShopping/Shopping/backend/src/main/java/com/lanlink/shopping/controller/AdCampaignController.java)）；下单结算实时匹配最优券包组合，订单支付后自动核销。

### 5. 企业周期性订阅采购与任务中心

面向企业高频易耗品（劳保、润滑油脂、传动件等）的周期订阅采购：按周/按月自动生成采购订单草稿、提醒采购专员一键确认付款，避免生产断料；会员任务中心以 `BUYER → VIP` 阶梯成长体系，完成首单、大宗采买与评价互动解锁积分返点与白条提额权益。

### 6. 企业信用白条精准测算器与风控引擎

基于企业注册资本、开票历史、履约评级与采购频次构建多因子评分矩阵，输出最高 500 万元的动态信用额度建议（[`creditCalculator.js`](file:///d:/Desktop/LANLinkShopping/Shopping/frontend/src/utils/creditCalculator.js)）；支持 T+30 / T+60 / T+90 账期免息，逾期按日万分之五阶梯计息，收银台自动拦截未结清逾期账户。

### 7. 售后全流程协商与多媒体证据链存证

仅退款、退货退款、破损补寄与上门返修全轨流转，买家/卖家/平台三方仲裁举证；设备损毁照片、检测报告与维修视频直连 MinIO S3 私有加密桶（[`refundEvidence.js`](file:///d:/Desktop/LANLinkShopping/Shopping/frontend/src/utils/refundEvidence.js)），前端经后端防盗链中转渲染，确保商业举证材料防篡改、安全保密。

### 8. AI 智能商品标题润色与卖点高光

依托本地 Qwen2.5 工业大模型（[`AiTitlePolish.vue`](file:///d:/Desktop/LANLinkShopping/Shopping/frontend/src/components/merchant/AiTitlePolish.vue)），自动提取「品牌 + 核心型号 + 工业规格 + 执行标准 + 适用场景」、过滤夸大极限词，重组为符合 B2B SEO 检索权重的标准化标题，并一键提炼核心卖点高光标签。

### 9. Apple UI 拟态流体交互规范与新一代组件库

秉承 Apple Human Interface Guidelines 的纯矢量拟态交互体系（[`frontend/src/components/ui/`](file:///d:/Desktop/LANLinkShopping/Shopping/frontend/src/components/ui/)）：macOS 物理流体 Dock、iPhone 灵动岛胶囊、带惯性滑块的 FluidAnimatedTabs、跟随光标的 GlowBorderCard 流光边框、RadarPulseBadge 雷达脉冲徽章、SmoothMobileDrawer 触摸抽屉与 3D 景深卡片轮播；图标经 AppleIcon 统一映射 Lucide SVG，杜绝 Emoji 滥用。

### 10. 本地双大模型编排与 MCP 向量中枢

Docker 托管 llama.cpp 双模型 Router：按请求意图热切换 **Qwen2.5-7B（文本）** 与 **Qwen2.5-VL（视觉多模态）**；硬件显存感知调度器（[`hardware_scheduler.py`](file:///d:/Desktop/LANLinkShopping/Shopping/ai-gateway/app/hardware_scheduler.py)）实时感知 GPU 显存与温度，支持全显存加载、CPU 卸载与降级保护；网关侧暴露标准 MCP 只读工具 `lanlink_kb_search`，外部智能体经 `/mcp` 检索共享向量索引 `aikb-idx`。

> [!NOTE]
> **💻 操作系统与硬件适配**：推荐 Ubuntu 22.04 / 24.04 LTS（原生 Linux 或 Windows 11 WSL2）+ CUDA 12.4 / 12.8 / 13.0（NVIDIA 驱动 ≥ 550.x）。作者基准环境为 RTX 4070 Laptop 8G，默认适配并深度支持 Qwen2.5-7B 与 Qwen2.5-VL 双模型；硬件宽裕者（RTX 3090/4090 24G、A100/H100 等）仅需在 `.env` 与 `docker-compose.yml` 调整模型挂载即可升级至 14B/32B/72B 或 FP16/BF16，业务代码零改动。

### 11. 双向合规条款体系与违规惩戒治理中枢 (Legal Compliance & Penalty System)

依托平台法务条款中心（[`LegalCenter.vue`](file:///d:/Desktop/LANLinkShopping/Shopping/frontend/src/views/LegalCenter.vue)）构建买卖双向合规闭环：商户端实缴资本 ≥ 10 万元全链路强校验、正品保真与 48 小时发货履约承诺、年度 12 分动态记分与阶梯惩戒（降权 / 下架 / 提现限制 / 清退关店）；买家端严禁刷单与恶意差评勒索、白条逾期罚息与分级处罚直至供应链失信黑名单；全条款中英双语留痕，入驻与交易全程主动勾选授权。

### 12. 商户退店与闭店全生命周期治理中枢 (Merchant Exit & Closure Governance)

模块全景见 [`module/merchantexit/`](file:///d:/Desktop/LANLinkShopping/Shopping/backend/src/main/java/com/lanlink/shopping/module/merchantexit)。对齐《电子商务法》第十六条法定公示要求，构建「申请 → 运营审核 → 30 天法定公示（可撤回）→ 冻结清算 → 正式关闭」五态不可逆软关闭闭环：六项退店门禁（在途订单 / 进行中售后 / 结算 / 商品下架 / 保证金 / 时间闸门）逐项自查、退店期间商品与报价写操作冻结堵洞、append-only 保证金账面台账（仅账面登记不触发真实打款，严守二清合规口径）与商户/运营双端双语审计轨迹。

### 13. 并发正确性与架构治理（2026-11 加固）

针对「单实例跑通、多实例/高并发出错」问题系统性加固，每项均配可证伪回归测试：广告支付与退款看门狗改条件更新 / CAS 状态机防双扣与部分退款放大、购物车加购 `INSERT ... ON DUPLICATE KEY UPDATE` 原子累加（真机 5 并发 ×100 精确 500）、物流运单落库 CAS 防已签收回退、定时任务 `DbJobLock`（MySQL 命名锁）跨实例互斥；前端 401 同步清理登录态消除踢登循环，nginx upstream 变量化消除下游容器启动顺序依赖。

### 14. 商户资金出金与提现闭环（merchant-finance，2026-11）

模块全景见 [`module/finance/`](file:///d:/Desktop/LANLinkShopping/Shopping/backend/src/main/java/com/lanlink/shopping/module/finance)。提现单全状态机（待审 / 已打款 / 驳回 / 已撤回）全部条件更新防并发抢占；资金路径以商户行排他锁建立当前读次序，append-only 财务台账逐行余额快照，真实双连接并发回归断言恰一成功一拒绝；可提余额 = 已结算净额 − 累计已打款 − 在途冻结，银行账号仅落前 4 后 4 掩码，敏感字段 RSA-2048 + A256GCM JWE 加密落库；打款为线下对公登记，平台不发起出金调用（二清口径）。

### 15. 订单评价闭环（独立订单维度评价模型，2026-11）

独立 `t_order_review` 表按订单商品拆行、唯一键防重复评（重复提交返回业务码 409）；评价落库与商品评分聚合（均分 / 好评率 / 星级分布）同事务提交，公开端点暴露评分摘要（[`OrderReviewService.java`](file:///d:/Desktop/LANLinkShopping/Shopping/backend/src/main/java/com/lanlink/shopping/module/review/service/OrderReviewService.java)）；买家「去评价」与商品详情评价区双端 UI，检索排序链路与评分聚合解耦。

### 16. SEO 基线与按路由 TDK 注入（CSR，2026-11）

`frontend/public/` 提供 robots.txt（私域路由 Disallow）与 sitemap.xml（仅公开路由）并以单测校验路由一致性；34 条公开路由携带 `meta.seo`，经路由钩子注入 title / description / keywords / og，商品详情回写真实商品名、私域路由回落全局兜底；告警经 Alertmanager 邮件 + IM webhook（钉钉/飞书适配器）双通道并行送达，真实地址只存根 `.env` 不进仓库。

### 17. 市场化批次演进总览（对标淘宝/1688/京东，2026-11）

对标主流电商平台的市场化批次演进：埋点 Amplitude 双门控与六事件漏斗、CSRF 拦截扩面与页脚公示区、Flyway 迁移底座（V2026_10 / V2026_11 存量迁移纳管）、nginx gzip 与 assets immutable 缓存、阶梯批发价写侧契约与买家 MOQ 展示、增值税发票需求与地址簿多地址、客服转人工与最小错误上报等 11 个批次，其中评价（第 15 节）、出金（第 14 节）、SEO（第 16 节）已单列成节。

### 18. SQLBot 智能问数集成（LLM + RAG Text-to-SQL，2026-11）

集成 [dataease/SQLBot](https://github.com/dataease/SQLBot) 作为平台智能问数引擎：自然语言直接生成 SQL 并执行（LLM + RAG），容器编排见 [`docker-compose.sqlbot.yml`](file:///d:/Desktop/LANLinkShopping/Shopping/docker-compose.sqlbot.yml)。UI `8000` / 自带 MCP `8001` **默认不映射宿主端口**，仅 `shopping_default` 内网可达；网关用服务名 `http://sqlbot:8000` 调用，排障时 `docker exec` 进容器访问 `127.0.0.1:8000/8001`。LLM 接线指向本地 AI 网关并关闭网关侧 RAG 模板改写，防止污染 Text-to-SQL 提示词；SQLBot 使用根 `.env` 的 `SQLBOT_GATEWAY_KEY`（独立、绑定 `support` 只读角色的网关 key），不得复用 `AI_GATEWAY_API_KEY`——SQLBot 管理端可明文读回 `ai_model.api_key`。数据源使用专用只读账号 `sqlbot_ro`（仅 `SELECT lanlink_shopping.*`，密码为 `SQLBOT_DB_PASSWORD`）；网关注册 `lanlink_ask_data` MCP 工具，外部大模型经 `/v1/chat/completions` 即可对话式查询平台业务数据；语义缓存对 sql_gen 类请求关闭写入。管理员密码只存根 `.env` 的 `SQLBOT_PASSWORD`，首次部署后必须通过 `POST /api/v1/login/change-pwd` 改掉镜像默认密码（写入 SQLBot 的 PostgreSQL，重启不丢）。

SQLBot 的模型/数据源配置存在它自己的 PostgreSQL 里（bind mount），Docker Desktop 异常重启后可能整体丢失，表现为登录成功但问数 500 `The system default model has not been set`。恢复用 `python scripts/sqlbot_restore.py`；该脚本检测到宿主 `8000` 不可达时会自动 `docker cp` 自己进 `sqlbot` 容器执行，不要求重新开放宿主端口。

### 19. 网关严格角色模式与密钥绑定（2026-11）

`GATEWAY_STRICT_ROLE=1`：未绑定角色的 key 不再采纳任何客户端自报角色（`X-Agent-Role` / `?role=` / JSON-RPC `params.role`），只能拿到只读工具。这是「拿到网关 key 就能自称 buyer 调写工具」的收口点。

开启前提是每个调用方都有**自己的、已绑定角色的 key**，不能继续用「一把主 key + 切请求头」：

| key | 绑定角色 | 用途 |
|:---|:---|:---|
| `AI_GATEWAY_API_KEY` | `service` | 后端内部 AI 调用（客服 MCP 编排、知识库、媒体感知、TTS） |
| `GATEWAY_BUYER_KEY` | `buyer` | 买家侧集成（含建采购草稿） |
| `GATEWAY_SUPPORT_KEY` / `SQLBOT_GATEWAY_KEY` | `support` | 客服只读 / SQLBot 只读问数 |
| `GATEWAY_FINANCE_KEY` | `finance` | 财务审核与审批签发 |
| `GATEWAY_FULFILLMENT_KEY` | `fulfillment` | 物流追踪与 SLA 监控 |

`service` 是本次新增的内部服务角色：只读工具 + `web_search` / `fetch_page`，**不含** 3 个写工具（建草稿 / 过审 / 审批签发）。用 `support` 代替会让后端知识库编排静默丢失外网检索能力。

上线记录与验证矩阵见 [`docs/architecture-audit/_parts/strict-role-rollout.md`](file:///d:/Desktop/LANLinkShopping/Shopping/docs/architecture-audit/_parts/strict-role-rollout.md)。

---

## 🛠️ 开发者工程手册与调试指南

### 本地网络拓扑与端口监听矩阵

| 端口 | 服务名称 | 绑定范围 | 核心用途 |
|:---:|:---|:---:|:---|
| **8080** | Spring Boot 后端 | `127.0.0.1` | 业务中台核心 RESTful API（全局 Context-Path `/api`） |
| **5173** | 前端静态加速 / 局域网验收 | `0.0.0.0` | 移动端真机访问 `http://<本机IP>:5173` |
| **5174** | 前端开发调试服务器 | `127.0.0.1` | Vite 热重载，`/api` 代理转发至 8080 |
| **3306** | MySQL 8.0 | `127.0.0.1` | 业务持久化数据库（utf8mb4） |
| **6379** | Redis Stack 7.4 | `127.0.0.1` | HNSW 向量索引 `aikb-idx`、缓存与限流计数 |
| **9000** | MinIO S3 API | `127.0.0.1` | S3 兼容对象存储读写 |
| **9001** | MinIO Web 控制台 | `127.0.0.1` | 存储桶与访问策略管理 |
| **5672** | RabbitMQ AMQP | `127.0.0.1` | 生产者与消费者双向通道 |
| **15672** | RabbitMQ 管理后台 | `127.0.0.1` | 队列堆积、消费速率与死信队列监控 |
| **18123** | llama.cpp 推理底座 | `127.0.0.1` | GGUF 双模型推理（容器内 8123） |
| **1025** | Mailpit SMTP | `127.0.0.1` | 本地测试邮件投递 |
| **8081** | Mailpit Web 邮箱 | `127.0.0.1` | 本地邮件收件箱（验证码拦截） |
| **8000** | SQLBot 智能问数 UI + API | 仅容器网络 | 不映射宿主端口；网关走 `sqlbot:8000`（见能力矩阵第 18 节） |
| **8001** | SQLBot MCP 服务 | 仅容器网络 | 不映射宿主端口；排障用 `docker exec` |
| **9093** | Alertmanager Web UI | `127.0.0.1` | 告警管理控制台（静默 / 通知状态） |
| **3000** | 批量导入 Workflow | `127.0.0.1` | 大宗商品与采购单批量导入 |

> [!IMPORTANT]
> 除用于局域网多设备真机验收的前端端口绑定所有网卡外，数据库、缓存、对象存储、消息队列与 AI 服务**严格仅绑定 `127.0.0.1` 本地回环地址**，防止局域网端口探测与越权穿透。

### 环境变量与凭据配置（`.env`）

根目录 `.env.example` 提供全部必填项与配置注释（MySQL / Redis / llama.cpp / MinIO / RabbitMQ 五组连接凭据与 `LANLINK_MQ_*_FLOW` 双链路开关），遵循 12-Factor 规范；`docker-compose.yml` 对存储与消息队列凭据启用 `${VAR:?...}` 强约束校验，缺失即报错拦截。切勿将含真实密码的 `.env` 提交至版本管理。

---

## 🚀 快速开始与环境部署

### 前置依赖

JDK 17 LTS、Maven 3.8+（项目自带 `./mvnw`）、Node.js 18+ / 20+ LTS、Docker & Compose v2、MySQL 8.0+、Python 3.10+；推荐 Ubuntu 22.04 / 24.04 LTS 或 Windows 11 WSL2，GPU 需 NVIDIA 驱动 ≥ 550.x + CUDA 12.4 / 12.8 / 13.0（`nvidia-smi` 自检）。

### 本地一键启动（强烈推荐）

Windows 下双击运行根目录 **`启动本地环境.bat`**，或在 PowerShell 终端执行：

```powershell
powershell -ExecutionPolicy Bypass -File start-local-env.ps1
```

脚本自动完成 `.env` 完整性校验、拉起 Redis Stack / MinIO / RabbitMQ / Mailpit / llama.cpp 容器、执行 `schema.sql` 幂等建表、启动后端（8080）与前端（5174 / 5173）并发起全链路健康探针。

### 手动分步启动

```bash
docker compose up -d                                              # 拉起中间件容器
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev  # 后端 8080（dev 幂等建表）
cd frontend && npm install && npm run dev                         # 前端 5174
```

启动成功后浏览器访问 **`http://localhost:5174`**（或 `http://localhost:5173`）；同一局域网移动端可访问 `http://<本机局域网IP>:5173` 体验自适应界面。

---

## 🗂️ 仓库目录全景导览

顶层结构一览（各能力细节以能力矩阵中的代码入口为准）：`backend/`（Spring Boot 3.5.3 后端，`module/` 下含技术服务、营销、退店治理、财务出金、评价、检索等 20+ 高阶中台包）、`frontend/`（Vue 3 + Vite 6，`components/ui/` 为 Apple 拟态组件库）、`ai-gateway/`（FastAPI AI 网关与硬件调度器）、`training/`（Unsloth / TRL 微调与 GGUF 量化导出）、`workflow/`（Dify 多智能体 DSL）、`monitoring/`（Prometheus 规则与 Grafana 看板）、`scripts/`（混沌演练、压测、看门狗）、`docker-compose*.yml`、`start-local-env.ps1` 与 `启动本地环境.bat`。

---

## 🧭 业务使用说明与演示账号

平台内置演示账号矩阵（初始密码统一为 `123456`）：

| 手机号码 | 平台角色 | 演示业务场景 |
|:---:|:---:|:---|
| **`13800000000`** | **平台运营 (ADMIN)** | 商户资质审核终审、物流 GIS 异常调度大屏、全栈指标监控看板、AI 网关调度配置 |
| **`13700000002`** | **商户卖家 (MERCHANT)** | 商户中心工作台、发品与 AI 标题润色、开播推流、出库发货与财务对账 |
| **`13900000001`** | **采购买家 (BUYER)** | 24 行业商城与推荐、直播边播边买、领券结算、白条申请、售后举证 |

---

## 📡 核心 API 接口速览（统一前缀 `/api`）

| 业务领域 | 核心 Endpoint | 业务功能 |
|:---|:---|:---|
| **统一鉴权** | `/auth/login` · `/auth/register` · `/auth/me` | 手机号鉴权、注册与当前身份权限获取 |
| **工业技术服务** | `/tech/contracts` · `/tech/experts` · `/tech/demands` · `/tech/pilot/bookings` | 技术合同与里程碑验收、专家咨询、需求揭榜、中试预约 |
| **物流 GIS 调度** | `/admin/logistics/waybills` · `/exceptions` · `/traces/sync` | 运单跟踪、阻滞异常查询、轨迹批量推进同步 |
| **大促与领券** | `/campaign/list` · `/campaign/coupons/claim` · `/pricing/quote` | 活动清单、批量领券、结算最优优惠试算 |
| **企业信用白条** | `/credit/account/info` · `/credit/apply` · `/credit/bills` | 额度查询、授信测算申请、账单查询 |
| **AI 智能中枢** | `/support/ask` · `/v1/hardware` · `/mcp` | 知识库问答、硬件调度状态、MCP 工具调用 |
| **商品与交易** | `/product/page` · `/cart/add` · `/order/checkout` · `/order/pay/{orderNo}` | 分页检索、购物车、下单结算、沙箱支付 |
| **商户退店治理** | `/merchant/exit/status` · `/merchant/exit/request` · `/admin/merchant-exit/list` · `/admin/merchant-exit/{merId}/close` | 门禁自查、申请退店、审核列表、公示期满关闭 |

---

## 🧪 自动化测试与质量保障体系

当前实测基线（2026-11-15）：

| 套件 | 命令 | 实测结果 |
|:---|:---|:---|
| 后端单元 + 集成测试 | `cd backend && mvn test` | **2046 通过 / 0 失败 / 1 跳过** |
| 前端单元测试 | `cd frontend && npm test` | **630 通过 / 0 失败（71 个测试文件）** |
| 前端生产构建 | `cd frontend && npm run build` | 通过（206 个 JS chunk，5.5 MB raw / 1.87 MB gzip） |

接口延迟实测（本机 Docker，n=10）：`GET /api/product/page` 中位 **25 ms**、`GET /api/product/detail/{id}` 中位 **8 ms**，均低于 200 ms 目标。尚未验证：1000 QPS 并发压测、首屏 LCP < 2s（需压测工具与浏览器自动化，不作达标声明）。

---

## 🔒 全方位安全防护体系

密码 BCrypt 强盐散列 + Google Authenticator TOTP 2FA；统一社会信用代码、银行账号等敏感资产 AES-256-GCM 加密落库；客服与聊天链路实时 PII 脱敏；CSRF 双提交校验、IP / 用户阶梯滑动窗口限流、连续密码错误锁定与实时风控审计；全站商品素材为开源商业友好授权，归档于 [`CREDITS.json`](file:///d:/Desktop/LANLinkShopping/Shopping/frontend/public/products/CREDITS.json)。

---

## 📬 联系方式与原创声明

本项目由 **Jason Ouyang（欧阳）** 独立构思、架构设计与编码开发：GitHub [@c2jxmrywdn-del](https://github.com/c2jxmrywdn-del) · 邮箱 [3514485358@qq.com](mailto:3514485358@qq.com) · X [@Orion_Yves_Jude](https://x.com/Orion_Yves_Jude) · Facebook [Jason Ouyang](https://www.facebook.com/profile.php?id=61590596057471) · WhatsApp [OuyangJason](https://wa.me/OuyangJason?s=t) / 微信 `O19111295446` · 开源主页 [LANLinkshopping-Local-version](https://github.com/c2jxmrywdn-del/LANLinkshopping-Local-version)。

> [!CAUTION]
> **知识产权与维权警告**：本项目（架构设计、功能闭环、UI/UX 视觉体系、前后端与 AI 代码、接口与数据库结构及文档）均为作者拥有独立知识产权的原创毕业设计成果，仅限个人技术学习、学术交流与毕业设计演示用途。未经作者书面授权，严禁任何商业化营利使用（付费课程 / 外包交付 / SaaS 售卖 / 商业包装）、署名篡改、去除版权信息或冒用为独立原创成果（含毕设答辩、竞赛评奖）。作者已对研发历程与提交历史全网公证固证，保留律师函、平台举证下架与依法诉讼等一切维权措施。

---

## 📄 许可证说明

本项目依据 **GNU General Public License v3.0（GPL-3.0）** 授权发布；任何包含本项目源代码的衍生作品必须以相同协议开源（强传染性 Copyleft）。完整协议文本见根目录 [LICENSE](./LICENSE) 文件。

---

<div align="center">

**特别感谢**：[Microsoft Agent-Lightning](https://github.com/microsoft/agent-lightning) · **Qwen 大模型团队** · [21st.dev](https://21st.dev) · [SQLBot（dataease）](https://github.com/dataease/SQLBot)（本平台对话式数据分析能力基于 SQLBot 构建）· [MinIO](https://github.com/minio/minio)（本平台对象存储服务） · [MinIO Java SDK（minio-java）](https://github.com/minio/minio-java)（适用于 Java 的 MinIO 客户端 SDK）




Made with ❤️ for Modern B2B E-Commerce & Open Innovation · 2026

</div>
