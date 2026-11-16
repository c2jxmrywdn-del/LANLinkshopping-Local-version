<div align="center">

# LANLinkshopping

### Aggregated Diversified Solutions · Modern Industrial-Grade B2B E-Commerce Full-Stack Platform

**Spring Boot 3.5.3 + Vue 3 (Vite 6) + Redis Stack HNSW + MinIO S3 + RabbitMQ 4.0 + Local Dual-LLM Orchestration + Dify Workflows + Apple UI Interaction Standards**

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

[Features](#-features--business-matrix) · [Architecture](#-system-architecture--technology-landscape) · [Developer Guide](#-developer-handbook--debugging-guide) · [Quick Start](#-quick-start--environment-setup) · [Core APIs](#-core-api-overview) · [Twitter (X)](https://x.com/Orion_Yves_Jude) · [Facebook](https://www.facebook.com/profile.php?id=61590596057471) · [Contact & Declaration](#-contact--originality-declaration) · [License](#-license)

**English** · [简体中文](./README.md)

</div>

---

## 📖 Project Overview & Positioning

**LANLinkshopping** is an **aggregated, multi-industry, B2B integrated full-stack e-commerce mid-platform** designed for modern industrial supply chains, bulk production-material group procurement, enterprise collaboration, and technology-transfer commercialization. It deeply covers the five core transaction loops of **unified identity & access control · merchant self-service onboarding with qualification review · 24-industry product mall · intelligent collaborative recommendation · shopping cart · order payment & reconciliation fulfillment**, and has evolved major industrial mid-platform capabilities including: **industrial technology-service trading center, logistics GIS trajectory & trunk dispatch hub, enterprise periodic subscription procurement, enterprise credit-term calculator, after-sale multi-media evidence chain, campaign venue & coupon center, AI-powered product title polish, live commerce, Apple-morphic fluid interaction system, local dual-LLM orchestration (Qwen2.5-7B + Qwen2.5-VL), MinIO object storage & RabbitMQ transactional messaging, SQLBot intelligent data-query integration (LLM + RAG Text-to-SQL)**.

The 2026-11 market-readiness evolution additionally delivers: **merchant fund withdrawal & finance closed loop, merchant exit & closure lifecycle governance, independent order-review closed loop, SEO baseline & per-route TDK injection, concurrency-correctness hardening with regression tests, local LLM fine-tuning & post-training (`training/`), and full-chain observability & resilience (`monitoring/` & `scripts/`)**.

---

## 🏛️ System Architecture & Technology Landscape

### 1. Overall Architecture Topology

- **Presentation layer**: desktop Web + mobile H5 (Vue 3 + Vite 6 + Ant Design Vue) with the Apple-morphic fluid interaction system (Dock / Dynamic Island / FluidTabs / GlowCard / 3D carousel).
- **Gateway & ingress**: Nginx static acceleration & reverse proxy (ports 5173 / 5174) → Spring Boot `/api` pre-filters; security line of CSRF double-submit, TOTP 2FA, tiered rate-limit, AES-256-GCM and brute-force lockout.
- **Core business & mid-platform layer**: 16 services from unified auth (RBAC 5-level / Google & Apple OAuth) through merchant onboarding, product & category, tech-service, logistics GIS, order & payment, campaign, subscription, after-sale, live commerce, risk control, A/B testing, legal compliance, merchant exit, finance to order review.
- **Transactional middleware**: Outbox workers (OrderOutbox & NotifyOutbox) + RabbitMQ 4.0 (consumer dedup, `q.notify.dlq`) + MinIO S3 private-bucket proxy + Mailpit email sandbox.
- **AI & multi-agent hub**: FastAPI AI gateway → llama.cpp dual-model router (Qwen2.5-7B + Qwen2.5-VL hot-swap) with hardware-aware scheduling; Dify workflows, MCP tool bridge, AI title polish, support RAG, SQLBot Text-to-SQL and local fine-tuning.
- **Data & storage**: MySQL 8.0 (InnoDB row locks), Redis Stack 7.4 (HNSW vector index `aikb-idx` + cache + rate counters), bge-m3 local 1024-dim embeddings.
- **Observability & resilience**: Prometheus metrics, Grafana dashboards, admin real-time metrics screen, chaos drills & daemon health watchdog across all layers.

### 2. Technology Stack Summary

| Dimension | Technology | Version / Spec | Role |
|------|---------|-----------|----------------|
| **Backend** | Spring Boot | 3.5.3 (Java 17 LTS) | Core application container base |
| | MyBatis-Plus | 3.5.10.1+ | Persistence, pagination & SQL-injection-safe dynamic queries |
| | Spring Security | 6.x | RBAC 5-level auth, BCrypt salted hashing, TOTP 2FA |
| | Spring Cloud | 2025.1.x (skeleton) | Microservice pre-research (Nacos, Gateway, OpenFeign) |
| | Flyway | 11.7.2 | Versioned DB migration (baseline 2026.11.1) |
| **Frontend** | Vue.js | 3.5.x (Composition API) | Reactive SFC components |
| | Build tool | Vite 6.4.5 | ESM dev cold-start, HMR, Rollup production chunking |
| | UI library | Ant Design Vue 4.x | Enterprise-grade mid/back-office UI |
| | Icons | Lucide Vue Next | Site-wide pure-vector SVG governance, zero emoji |
| | State/routing | Pinia + Vue Router 4.x | Global reactive state, route guards |
| **Messaging & Storage** | Object storage | MinIO (S3-compatible) + AWS SDK v2 | Encrypted storage; private-bucket backend proxy |
| | Message queue | RabbitMQ 4.0 | Transactional Outbox, dedup, DLQ fallback |
| | Database | MySQL 8.0+ (InnoDB) | Row-level locks, ACID, indexed constraints |
| | Cache & vectors | Redis Stack 7.4 (RediSearch) | HNSW vector index, cache, rate-limit counters |
| **AI / LLM** | Inference base | llama.cpp (Docker + cuBLAS) | Local Qwen2.5-7B & Qwen2.5-VL (RTX 4070 Laptop 8G baseline; scale to 14B/32B/72B freely on larger hardware) |
| | OS | Ubuntu 22.04 / 24.04 LTS | Native Linux or WSL2 containers & fine-tuning |
| | GPU | CUDA 12.4 / 12.8 / 13.0 | cuBLAS & FlashAttention-2 acceleration |
| | AI Gateway | FastAPI + Uvicorn | OpenAI-compatible API, tool calling, streaming |
| | Embeddings | BAAI/bge-m3 | 1024-dim multilingual dense vectors |
| | Multi-agent | Dify DSL + MCP | Agent workflow orchestration; MCP read-only KB search (`lanlink_kb_search`) |
| | Text-to-SQL | SQLBot (dataease) | LLM + RAG conversational data analysis, MCP-accessible |
| | Fine-tuning | Unsloth + TRL + PyTorch | Industrial intent LoRA SFT, AGL/GRPO RL, GGUF export |
| **Ops & Observability** | Metrics | Prometheus + Grafana | QPS/TPS, P99 latency, GPU VRAM & temperature |
| | Resilience | Chaos / Watchdog | Fault drills, daemon health probing, self-healing |

---

## ✨ Core Business & Mid-Platform Capability Matrix

### 1. Basic Transaction Closed Loop (Five Core Modules)

One loop from identity to settlement ([`OrderController.java`](backend/src/main/java/com/lanlink/shopping/controller/OrderController.java)):

- **Auth Center**: phone registration/login, RBAC 5-level matrix (`GUEST`/`BUYER`/`VIP`/`MERCHANT`/`ADMIN`), TOTP 2FA, CSRF double-submit, brute-force lockout.
- **Merchant onboarding / Multi-industry mall / Smart cart**: USCC-validated application with hard paid-in-capital gate (≥ ¥100,000), 24-industry catalog with tiered wholesale pricing & multi-spec SKUs, atomic spec-stock cart validation.
- **Order & payment**: transactional atomic stock deduction with full order snapshots, Alipay/WeChat/wallet sandbox payments, points deduction (1000 pts = ¥1) and credit-term deferred settlement.

### 2. Industrial Technology-Service & Tech-Transfer Mid-Platform

Full-lifecycle industrial tech trading in [`module/techservice`](backend/src/main/java/com/lanlink/shopping/module/techservice):

- E-contracts with staged technical milestones, third-party escrow ledger and per-stage fund release upon node acceptance.
- Expert pool with online booking & paid diagnostics, pilot-line and shared-lab slot reservations.
- Bottleneck-demand bounties, achievement appraisal (TRL / commercialization / patent value) and equipment full-lifecycle hosting & maintenance.

### 3. Logistics GIS Trajectory & Trunk Hub

National trunk-logistics dispatch hub (frontend [`logisticsGis.js`](frontend/src/utils/logisticsGis.js) + admin console):

- WGS-84 / GCJ-02 / BD-09 high-precision bidirectional coordinate conversion with out-of-mainland boundary detection.
- Haversine-based inter-hub mileage & in-transit timeline inference; 24h dwell triggers yellow alert, 48h triggers red emergency dispatch with ops-side re-urge / reassignment / redirection.
- Reconciliation automation: per-carrier signed-waybill aggregation, batch statement audit, fee deductions and insurance claims.

### 4. Campaign Venue & Coupon Center

- Venue aggregation with tiered spend-threshold, newcomer-exclusive and flash-discount coupons; one-click batch claiming via Redis atomic-counter anti-abuse.
- Checkout-time optimal coupon-pack matching ([`PricingFacade.java`](backend/src/main/java/com/lanlink/shopping/integration/pricing/PricingFacade.java)) with post-payment status write-off.

### 5. Enterprise Periodic Subscription Procurement & Task Center

- Weekly/monthly auto-drafted purchase orders for high-frequency consumables ([`SubscriptionOrders.vue`](frontend/src/views/SubscriptionOrders.vue)), one-click confirmation to avoid production stoppage.
- `BUYER` → `VIP` membership tasks unlocking points rebates and credit-line boosts.

### 6. Enterprise Credit-Term Calculator & Risk Engine

- Multi-factor credit scoring (registered capital, invoicing history, fulfillment rating, purchase frequency) outputting dynamic credit lines up to ¥5M ([`creditCalculator.js`](frontend/src/utils/creditCalculator.js)).
- T+30 / T+60 / T+90 flexible terms; overdue accounts accrue daily 0.05% tiered interest and are auto-blocked at checkout until settled.

### 7. After-Sale Negotiation & Multi-Media Evidence Chain

- Refund-only / return-and-refund / damaged-replacement / on-site repair multi-track flows with buyer/seller/platform three-way arbitration history.
- Evidence (damage photos, inspection reports, repair videos) stored in MinIO S3 private encrypted buckets via hotlink-protected backend proxy ([`refundEvidence.js`](frontend/src/utils/refundEvidence.js)) — tamper-proof and confidential.

### 8. AI Product Title Polish & Selling-Point Highlights

Local Qwen2.5 engine solves non-standard industrial product naming ([`AiTitlePolish.vue`](frontend/src/components/merchant/AiTitlePolish.vue)): auto-extracts "brand + core model + industrial spec + execution standard + use case", filters exaggerated claims, and restructures into B2B-SEO-weighted titles with one-click selling-point tags.

### 9. Apple UI Morphic Fluid Interaction & Next-Gen Component Library

Pure-vector morphic component library: macOS fluid Dock, iPhone Dynamic Island capsule, FluidTabs, GlowBorderCard, RadarPulseBadge, silky mobile drawer and 3D depth card carousel — zero emoji via centralized Lucide SVG icon governance ([`AppleDock.vue`](frontend/src/components/ui/AppleDock.vue)).

### 10. Local Dual-LLM Orchestration & MCP Vector Hub

- Docker-hosted llama.cpp dual-model hot-swap Router: **Qwen2.5-7B (text)** + **Qwen2.5-VL (vision)**; hardware-VRAM-aware scheduler with CPU offload & degradation ([`hardware_scheduler.py`](ai-gateway/app/hardware_scheduler.py)).
- Gateway-side read-only vector-KB MCP tool `lanlink_kb_search` over the Redis Stack HNSW index `aikb-idx`, exposed at `/mcp` for external agents.
- bge-m3 1024-dim embeddings; Dify multi-agent workflows.

> [!NOTE]
> **💻 OS, CUDA & local hardware adaptation**: recommended Ubuntu 22.04 / 24.04 LTS (native Linux or Windows 11 WSL2) with CUDA 12.4 / 12.8 (`cu128`) / 13.0 (`cu130`), NVIDIA driver ≥ 550.x / 560.x. Development & benchmarking baseline: Intel Core i7-14650HX / NVIDIA RTX 4070 Laptop 8GB / 24GB DDR5 — local inference & fine-tuning therefore defaults to Qwen2.5-7B (`Qwen2.5-7B-Instruct-Q4_K_M`) + Qwen2.5-VL dual-model hot-swap. On RTX 3090/4090 24G or A100/H100 clusters, seamlessly scale to Qwen 2.5 14B/32B/72B or FP16/BF16 — adjust model mounts in `.env` & `docker-compose.yml` only, zero business-code changes.

### 11. Bidirectional Compliance Terms & Penalty Governance Hub (Legal Compliance & Penalty System)

8 bilingual legal agreements with active opt-in and version tracking ([`LegalCenter.vue`](frontend/src/views/LegalCenter.vue)):

- **Merchant side**: paid-in capital ≥ ¥100,000 hard gate enforced end-to-end; genuine-guarantee (ten-times compensation) and 48-hour shipping commitments; annual 12-point dynamic scoring with tiered penalties (warning demotion → forced delisting → withdrawal restriction → closure → deposit forfeiture) and a 5-working-day appeal channel.
- **Buyer side**: honest-trading clauses (no order farming, malicious refunds or review extortion), authentic-evidence disputes, 0.05%/day credit-term penalty interest, tiered penalties up to permanent ban & industry blacklist.

### 12. Merchant Exit & Closure Lifecycle Governance Hub (Merchant Exit & Closure Governance)

Irreversible soft-close loop "Apply → Ops review → 30-day statutory notice (withdrawable) → Freeze & liquidation → Formal closure", aligned with E-Commerce Law Art. 16:

- Five-state CAS state machine (`ACTIVE → EXIT_REQUESTED → PUBLIC_NOTICE → FROZEN → CLOSED`) with full transition audit; six exit gates returning `GateReport{ready, blockers[]}` for frontend ✅/❌ self-check ([`MerchantExitService.java`](backend/src/main/java/com/lanlink/shopping/module/merchantexit/service/MerchantExitService.java)).
- Write-operation freeze during exit flow (publish/SKU/RFQ/campaign blocked); 30-day notice with scheduled auto-freeze and merchant withdrawal any time.
- Append-only deposit ledger (PAY / REFUND / DEDUCT with `balance_after` snapshots) — ledger-only, no real fund transfers (second-clearing compliance boundary).

### 13. Concurrency Correctness & Architecture Governance (2026-11 Hardening)

Systematic hardening against the "works single-instance, breaks multi-instance/high-concurrency" defect class, each with a falsifiable regression test: conditional-update CAS on ad-campaign payment & waybill status; atomic cart accumulation (`INSERT ... ON DUPLICATE KEY UPDATE`, real-machine 5 concurrent ×100 → exactly 500); cumulative-refund watchdog preventing partial refunds amplified to full; batch settlement prefetch (500-cap); MySQL named-lock job mutex (`DbJobLock`); self-proxy transaction fix; median-based price baseline cutting URGENT misjudgments 38→16. Frontend: `app.mount` no longer blocked by language-detection, 401 clears session synchronously, hls.js lazy-loaded. Deployment: nginx variable upstreams + `resolver`, stale same-name container auto-recycle in `start-local-env.ps1`.

### 14. Merchant Fund Withdrawal & Finance Closed Loop (merchant-finance, 2026-11)

- Withdrawal full state machine `0 pending → 1 paid / 2 rejected / 3 withdrawn`, all transitions conditional-update CAS; payout is offline corporate-transfer registration + voucher upload — the platform initiates no outbound payment calls (second-clearing boundary) ([`WithdrawalService.java`](backend/src/main/java/com/lanlink/shopping/module/finance/service/WithdrawalService.java)).
- Append-only finance ledger with per-row `balance_after` snapshots; merchant-row exclusive lock (`SELECT ... FOR UPDATE` on `t_merchant`) as the first transaction statement plus `FOR UPDATE` locked aggregates — a real two-connection concurrent regression test asserts exactly one success and one rejection.
- Withdrawable balance formula (settled net − paid − in-flight frozen); bank accounts stored masked (first-4/last-4 only); field-level JWE encryption (RSA-2048 + A256GCM) with startup key verification.

### 15. Order Review Closed Loop (independent order-dimension model, 2026-11)

Dedicated `t_order_review` table (multi-product orders split per `t_order_item`; unique key blocks duplicate reviews with business code 409); same-transaction rating aggregation to `t_product_rating_stat` exposing average / good-rate / star distribution; buyer & merchant dual-end UI; SearchRankScorer regression stays green ([`OrderReviewService.java`](backend/src/main/java/com/lanlink/shopping/module/review/service/OrderReviewService.java)).

### 16. SEO Baseline & Per-Route TDK Injection (CSR, 2026-11)

`robots.txt` / `sitemap.xml` static assets unit-tested against the route table; 34 public routes carry `meta.seo` injected via the router afterEach hook with product-detail real-name rewrite and private-route global fallback ([`seo.js`](frontend/src/utils/seo.js)); alert delivery dual-channel (email fallback + IM webhook via adapter, real addresses root-`.env`-only, never committed).

### 17. Market-Readiness Batch Evolution Overview (Benchmarked against Taobao/1688/JD, 2026-11)

Eleven hardening batches: analytics dual-gating (Amplitude consent + opt-out, six-funnel events), compliance expansion (CSRF interceptor, login rate-limit, footer disclosures), Flyway migration governance (baseline 2026.11.1), frontend minimal error reporting + inline Splash, nginx dual gzip & immutable caching, tiered pricing (write-side contract, merchant editor, MOQ, order snapshots), VAT invoicing + multi-address checkout, SEO baseline (see §16), human-handoff support inbox, plus the dedicated loops in §14 (withdrawal) and §15 (order review).

### 18. SQLBot Intelligent Data-Query Integration (LLM + RAG Text-to-SQL, 2026-11)

[dataease/SQLBot](https://github.com/dataease/SQLBot) as the platform's conversational data-analysis engine: ask questions in natural language, get generated SQL and executed results (container UI `127.0.0.1:8000`, built-in MCP at `8001`).

- Data source: `lanlink_shopping` database via dedicated read-only account `sqlbot_ro` (SELECT on business tables only; 6 core tables, 144 fields synced); gateway registers the `lanlink_ask_data` MCP tool so external AI models query platform data via `/v1/chat/completions` (tools=auto).
- LLM wiring: SQLBot model points to the local [`lanlink-ai-gateway`](ai-gateway/) with `extra_body: {"use_kb": false}` to protect Text-to-SQL prompts from gateway-side RAG rewriting; semantic-cache write gate disabled for sql_gen requests — 10/10 consecutive acceptance (5 direct + 5 MCP-chain).

---

## 🛠️ Developer Handbook & Debugging Guide

### 1. Local Network Topology & Port Matrix

| Port | Service | Process/Container | Binding | Purpose |
|:---:|:---|:---|:---:|:---|
| **8080** | Spring Boot backend | `java.exe` | `127.0.0.1` | Core RESTful API, global context-path `/api` |
| **5173** | Frontend static / LAN acceptance | `nginx` (Docker) | `0.0.0.0` | Mobile real-device access `http://<LAN-IP>:5173` |
| **5174** | Frontend dev server | `Vite` | `127.0.0.1` | HMR dev port, proxies `/api` to backend 8080 |
| **3306** | MySQL 8.0 | `mysqld` / Docker | `127.0.0.1` | Business persistence DB, charset `utf8mb4_unicode_ci` |
| **6379** | Redis Stack 7.4 | `redis-stack-server` | `127.0.0.1` | RediSearch, `aikb-idx` HNSW vector index, cache |
| **9000** | MinIO S3 API | `minio` (Docker) | `127.0.0.1` | S3-compatible storage API |
| **9001** | MinIO Web console | `minio` (Docker) | `127.0.0.1` | Object storage management panel |
| **5672** | RabbitMQ AMQP | `rabbitmq` (Docker) | `127.0.0.1` | AMQP client connections |
| **15672** | RabbitMQ management | `rabbitmq` (Docker) | `127.0.0.1` | Queue monitoring, DLQ `q.notify.dlq` visibility |
| **8000** | SQLBot data-query UI + API | `sqlbot` (Docker) | `127.0.0.1` | Conversational data analysis (LLM + RAG Text-to-SQL) |
| **8001** | SQLBot MCP service | `sqlbot` (Docker) | `127.0.0.1` | SQLBot MCP (SSE) for external AI model access |
| **9093** | Alertmanager UI | `alertmanager` (Docker) | `127.0.0.1` | Alert console (delivery via DingTalk/Feishu adapters) |
| **18123** | llama.cpp inference | `llama-cpp` (Docker) | `127.0.0.1` | Container 8123 → host 18123, GGUF dual-model inference |
| **1025** | Mailpit SMTP | `mailpit` (Docker) | `127.0.0.1` | Local test email delivery |
| **8081** | Mailpit Web inbox | `mailpit` (Docker) | `127.0.0.1` | Local mail inbox, captures verification emails |
| **3000** | Batch Import Workflow | Node.js Worker | `127.0.0.1` | Bulk product & procurement import service |

> [!IMPORTANT]
> Except for the frontend port (bound to all NICs for LAN multi-device acceptance), all databases, caches, object storage, message queues and AI services **bind strictly to `127.0.0.1` loopback only**, preventing LAN port probing and unauthorized penetration.

### 2. Environment Variables & Credentials (`.env`)

Follows the **12-Factor App** convention; root `.env.example` documents all required keys (MySQL datasource, Redis, llama.cpp endpoint, MinIO storage, RabbitMQ channel switches, SQLBot credentials, DingTalk/Feishu alert webhooks).

> [!WARNING]
> `docker-compose.yml` enforces `${VAR:?...}` hard validation on storage & MQ credentials — missing `.env` or empty secrets abort `docker compose up` immediately. **Never commit `.env` with real passwords.**

### 3. Transactional Outbox Mechanism

Business logic writes a `PENDING` event into `order_outbox`/`notify_outbox` in the same local transaction; a worker ([`OrderOutboxWorker.java`](backend/src/main/java/com/lanlink/shopping/integration/mq/OrderOutboxWorker.java)) pulls and publishes to RabbitMQ, consumers dedup by the unique `messageId` against Redis, and messages failing 3 consecutive times route to `q.notify.dlq` with admin-back-office query and manual redelivery.

### 4. Object-Store Private Isolation & Secure Proxy

MinIO buckets are **private read-only** by default; uploads pass backend validation of MIME type, extension and magic bytes with only `object_key` stored in DB; viewing originals requires a valid JWT call to `/api/storage/file/{key}` where the backend verifies permission and tenant isolation before streaming.

---

## 🚀 Quick Start & Environment Setup

### 1. Prerequisites

| Component | Recommended | Check |
|:---|:---|:---|
| **OS** | Ubuntu 22.04 / 24.04 LTS (or Windows 11 WSL2) | `lsb_release -a` |
| **GPU & CUDA** | NVIDIA driver ≥ 550.x + CUDA 12.4/12.8/13.0 | `nvidia-smi` / `nvcc -V` |
| **JDK / Maven** | 17 LTS / 3.8+ (`./mvnw` included) | `java -version` / `mvn -version` |
| **Node.js** | 18+ / 20+ LTS | `node -v` |
| **Docker & Compose** | 24+ / Compose v2 | `docker compose version` |
| **MySQL / Python** | 8.0+ / 3.10+ | `mysql --version` / `python --version` |

### 2. One-Click Local Start (Recommended)

On Windows, double-click **`启动本地环境.bat`** or run in PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File start-local-env.ps1
```

> **Pipeline actions**: reads/validates `.env`; checks Docker daemon; starts Redis Stack, MinIO, RabbitMQ, Mailpit & llama.cpp; initializes schema; starts Spring Boot backend (8080) and Vite dev server (5174/5173); runs full-chain health probes.

### 3. Manual Step-by-Step

```bash
# 1. Start middleware containers (Redis Stack, MinIO, RabbitMQ, Mailpit)
docker compose up -d

# 2. (Optional) SQLBot data-query service — UI http://127.0.0.1:8000 (admin / SQLBot@123456), MCP :8001
docker compose -f docker-compose.sqlbot.yml up -d

# 3. Initialize database schema
mysql -uroot -p --default-character-set=utf8mb4 < backend/src/main/resources/sql/schema.sql

# 4. Start backend (Port 8080)
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 5. Start frontend (Port 5174 / 5173)
cd frontend
npm install
npm run dev
```

Visit **`http://localhost:5174`** (or `http://localhost:5173`). Mobile devices on the same LAN can access `http://<LAN-IP>:5173`.

---

## 🗂️ Repository Directory Map

- `backend/` — Spring Boot 3.5.3 core: controllers, services, MyBatis-Plus mappers, entities, `integration/` (mq/listener/pricing), `module/` mid-platform packages (techservice, finance, merchantexit, review, rfq, settlement, sku, aikb, aitool, ...), `crypto/` (JWE field-level encryption), `support/` (DbJobLock, idempotent schema initializers); Flyway migrations & `sql/schema.sql` seed data.
- `frontend/` — Vue 3 + Vite 6: views, `components/ui` Apple-morphic library, Pinia store, utils (GIS topology, coord transforms, credit calculator, analytics, seo); `public/products/` with 120+ real industry materials & CREDITS.json.
- `ai-gateway/` (FastAPI gateway, hardware scheduler, MCP KB spec) · `sqlbot-patches/` (read-only local patches) · `training/` (LoRA/SFT/GRPO/GGUF) · `workflow/` (Dify DSL) · `monitoring/` (Prometheus, Grafana, alert adapters) · `scripts/` (chaos, stress, watchdog) · `docker-compose.yml` & `docker-compose.sqlbot.yml` · `start-local-env.ps1` / `启动本地环境.bat` · [LICENSE](./LICENSE).

---

## 🧭 Demo Accounts

### Built-in Demo Account Matrix (initial password `123456` for all)

| Phone | Role | Demo Scenarios & Permissions |
|:---:|:---:|:---|
| **`13800000000`** | **Platform Ops (ADMIN)** | Merchant qualification final review, tech-service vendor certification, product publication monitoring, logistics GIS anomaly dispatch, full-stack metrics dashboard, AI gateway scheduling config. |
| **`13700000002`** | **Merchant (MERCHANT)** | Merchant center workbench, industrial product publishing, AI title polish, streaming console, outbound shipping & trajectory entry, finance reconciliation. |
| **`13900000001`** | **Buyer (BUYER)** | Browse 24-industry mall, collaborative recommendations, live shop-while-streaming, campaign coupons & spend-threshold checkout, credit-term application, tech-service tickets & after-sale evidence. |

> SQLBot data-query admin console uses a separate account: `admin / SQLBot@123456` (image default; not linked to platform demo accounts).

---

## 📡 Core API Overview (unified prefix `/api`)

| Domain | Core Endpoints | Methods | Description |
|:---|:---|:---:|:---|
| **Auth** | `/auth/login` `/auth/register` `/auth/me` | `POST` `POST` `GET` | Phone identity auth, registration, current identity & permission matrix |
| **Industrial Tech-Service** | `/tech/contracts` `/tech/experts` `/tech/demands` `/tech/pilot/bookings` | `GET/POST` | E-contract signing & milestone acceptance, expert consultation, demand bounty, pilot reservations |
| **Logistics GIS** | `/admin/logistics/waybills` `/admin/logistics/exceptions` `/admin/logistics/traces/sync` | `GET` `POST` | Waybill lifecycle, dwell-timeout anomalies, trunk trajectory batch sync |
| **Campaign & Coupons** | `/campaign/list` `/campaign/coupons/claim` `/pricing/quote` | `GET` `POST` | Campaign list, tiered coupon claiming, checkout best-discount trial |
| **Enterprise Credit Terms** | `/credit/account/info` `/credit/apply` `/credit/bills` | `GET` `POST` `GET` | Credit-line query, online application, periodic bills |
| **AI Hub** | `/support/ask` `/v1/hardware` `/mcp` | `POST` `GET` `POST` | Local-KB support Q&A, hardware scheduling status, MCP tool-call protocol entry |
| **Products & Trading** | `/product/page` `/cart/add` `/order/checkout` `/order/pay/{orderNo}` | `GET` `POST` | Paginated search, atomic cart ops, transactional checkout, sandbox payment |
| **Merchant Exit** | `/merchant/exit/status` `/merchant/exit/request` `/admin/merchant-exit/list` `/admin/merchant-exit/{merId}/close` | `GET` `POST` | Exit status & six-gate self-check, exit application (immediate write-freeze), ops review list, statutory-notice closure |
| **Merchant Finance** | `/merchant/finance/balance` `/merchant/finance/withdrawals` `/admin/finance/withdrawals/{id}/approve` | `GET` `POST` | Withdrawable balance (anti-double-spend), withdrawal apply/freeze, admin approval with voucher |
| **Order Review** | `/order/{orderNo}/order-review` `/product/detail/{id}/order-reviews` | `POST` `GET` | Order-dimension review submission (per-item, anti-duplicate), public rating summary & list |

---

## 🧪 Automated Testing & Quality Assurance

Current measured baseline (2026-11-15):

| Suite | Command | Result |
|:---|:---|:---|
| Backend unit + integration | `cd backend && mvn test` | **2046 passed / 0 failed / 1 skipped** |
| Frontend unit tests | `cd frontend && npm test` | **630 passed / 0 failed (71 test files)** |
| Frontend production build | `cd frontend && npm run build` | Pass (206 JS chunks, 5.5 MB raw / 1.87 MB gzip) |

API latency measured (local Docker, n=10): `GET /api/product/page` median **25 ms**, `GET /api/product/detail/{id}` median **8 ms**, both under the 200 ms target. Not yet verified: 1000 QPS stress test and first-screen LCP < 2s — not claimed as passing.

Resilience tooling: chaos fault injection `python scripts/chaos_resilience_test.py`, high-concurrency stress benchmark `python scripts/stress_benchmark.py`, daemon health watchdog `python scripts/daemon_health_watchdog.py`.

---

## 🔒 Security Protection System

Passwords stored with BCrypt strong-salt hashing and full Google Authenticator TOTP 2FA support; enterprise USCC, bank accounts and settlement credentials encrypted at rest with AES-256-GCM plus a field-level JWE (RSA-2048 + A256GCM) upgrade path; real-time PII masking across the support/chat pipeline (phone numbers, ID numbers, enterprise fields); interface protection via CSRF double-submit, IP & user tiered sliding-window rate limits, brute-force lockout and real-time trade risk-event audit; commercially-friendly product-material licenses archived in [`frontend/public/products/CREDITS.json`](frontend/public/products/CREDITS.json).

---

## 📬 Contact & Originality Declaration

### 👨‍💻 Author & Contact

Independently conceived, architected and developed by **Jason Ouyang (欧阳)**:

- **Role**: Project initiator / sole developer (GitHub: [@c2jxmrywdn-del](https://github.com/c2jxmrywdn-del))
- **Email**: [3514485358@qq.com](mailto:3514485358@qq.com)
- **X / Twitter**: [@Orion_Yves_Jude](https://x.com/Orion_Yves_Jude)
- **Facebook**: [Jason Ouyang](https://www.facebook.com/profile.php?id=61590596057471)
- **Messaging**: WhatsApp · [OuyangJason](https://wa.me/OuyangJason?s=t) / WeChat: `O19111295446`
- **Open-source home**: [https://github.com/c2jxmrywdn-del/LANLinkshopping-Local-version](https://github.com/c2jxmrywdn-del/LANLinkshopping-Local-version)

### ⚠️ Originality & Legal Rights Declaration (No Commercialization, Misappropriation or Plagiarism)

> [!CAUTION]
> This project (including but not limited to platform architecture, technology selection, business loops, frontend & mobile UI/UX visual system, Apple UI component library, backend & AI sandbox implementation, API definitions, database schema and documentation) is the author's original graduation-project work with independent IP and copyright. It is for **personal technical learning, academic exchange and graduation-project demonstration only** — without the author's explicit written authorization, no organization, institution, team or individual may use the source code, architecture or derivatives for any commercial for-profit activity, paid courses, outsourced delivery or commercial SaaS resale. Misappropriation, plagiarism, attribution tampering or fabricated independent-original claims (including use for graduation defenses, coursework submissions or competition awards) are strictly prohibited. The full development history, code commits and related achievements have been notarized, and **all lawful remedies** including cease-and-desist letters, platform takedown petitions and IP litigation are reserved. Please respect original work!

---

## 📄 License

This project is released under the **GNU General Public License v3.0 (GNU GPL-3.0)**.

```text
LANLinkshopping — Aggregated Diversified B2B E-Commerce Platform
Copyright (C) 2026 LANLinkshopping the sole editor

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```

- Full license text: [LICENSE](./LICENSE).
- **Any derivative work containing this project's source code must be open-sourced under the same GPL-3.0 license** (strong Copyleft).

---

<div align="center">

**Special thanks to the open-source projects & communities supporting LANLinkshopping:**
- [Microsoft Agent-Lightning](https://github.com/microsoft/agent-lightning) team for their contributions to the open agent ecosystem
- **Qwen LLM team** for open foundation-model & multimodal support
- [21st.dev](https://21st.dev) for modern interaction design inspiration & open components
- [SQLBot (dataease)](https://github.com/dataease/SQLBot) team for their contribution to the open-source Text-to-SQL community — this platform's conversational data-analysis capability is built on SQLBot

Made with ❤️ for Modern B2B E-Commerce & Open Innovation · 2026

</div>
