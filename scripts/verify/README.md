# P0-A 真机验收脚本使用说明（结算双付修复 · 通道 A/B）

本目录提供 **可重复执行** 的 P0-A 验收脚本，用于在本地运行栈（Docker + 真实 MySQL）上验证：

- **通道 A**：结算批次 `PAID` 后，其收益行已转 `PAID` 并移出「可提余额」口径（含历史回填是否生效）；
- **通道 B**：`SettlementBatchService.pay()` 与 `WithdrawalService.apply()` 共用同一把「商户行 `SELECT ... FOR UPDATE`」互斥，
  并发时恰一成一败，不会「提现 + 线下打款」各付一次。

> 结论性文档见 `reports/specs/p0a-settlement-double-pay.md`（通道 A）与 `reports/specs/p0a-channel-b.md`（通道 B）。

---

## 1. 文件与前置条件

| 项 | 要求 |
| --- | --- |
| 脚本 | `scripts/verify/p0a-double-pay.ps1`（Windows PowerShell **5.1+**，已在 5.1.26100 实测 DryRun 通过） |
| 后端 | `shopping-backend-1` 已启动，健康端点 `GET http://127.0.0.1:8080/api/actuator/health` 返回 `{"status":"UP",...}`（context-path 见 `application.yml:4`，健康白名单见 `AuthInterceptor.java:79-83`） |
| 数据库 | 容器 `shopping-mysql-1` 在跑；脚本通过 `docker exec ... mysql` 执行 **SELECT/受控清理**（密码读 `.env` 的 `MYSQL_ROOT_PASSWORD`，可用 `-MySqlPassword` 覆盖） |
| 环境文件 | 仓库根 `.env`（脚本按当前目录或 `$PSScriptRoot/../../.env` 查找） |
| 演示账号 | `LANLINK_DEMO_DATA_ENABLED=true` 时由 `DataInitializer` 播种：运营 `13800000000/123456`、商户 `13700000002/123456`（注意：生产/生产型环境不要使用固定口令账号跑验收） |
| 支付 | `LANLINK_PAYMENT_MOCK=true` 即可；本验收不依赖真实渠道 |

从**仓库根目录**执行：

```powershell
# 只读预检（默认，不发任何写请求）
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify\p0a-double-pay.ps1
```

---

## 2. 两种模式与退出码

### 2.1 `-Mode DryRun`（默认）

只做只读检查，**不登录、不发 POST**（避免写登录日志/业务数据）：

- 前置检查：健康端点、MySQL 连通、7 张验收表存在；
- 只读核对 SQL：回填是否生效 / `PAID` 批次明细不变量 / 可提余额口径 SQL 推导 / `item_count` 巡检 / 孤立 PAID 收益；
- 打印 `-Mode Execute` 将执行的命令与断言，相关断言标记 `SKIP`。

### 2.2 `-Mode Execute`

完整验收（**会产生写操作**，仅限本地/测试环境）：

1. 登录运营/商户账号、取 CSRF；
2. **场景 1**：取已 `PAID` 批次 → 对比 API 可提余额与 SQL 口径 → 发起「超出可提余额 1.00 元」的提现 → 断言 `body.code=400`「可提余额不足」；
3. **场景 2**：并发 `POST /admin/settlement/{id}/pay` 与 `POST /merchant/finance/withdrawals` → 断言恰好一个 `body.code=200`、另一个 400，无 500/双成功；
   - 默认只复用已有 `APPROVED` 批次；用 `-AllowGenerate` 时脚本可通过 admin API 自建批次（需目标商户有 `AVAILABLE` 收益），结束时按主键清理；
4. 清理：删除本次运行创建的提现单/批次并还原其收益行状态（`-NoCleanup` 可保留）。

### 2.3 退出码

| 码 | 含义 |
| --- | --- |
| 0 | 全部断言 PASS（或无 FAIL） |
| 1 | 存在 FAIL（如回填未生效、双付复现、并发双成功） |
| 2 | 前置检查/脚本异常（后端不健康、MySQL 不可连、缺表） |
| 3 | 全部 SKIP（数据前提不满足，如无 PAID/APPROVED 批次） |

---

## 3. 常用命令

```powershell
# 完整验收（复用已有 APPROVED 批次；无则场景2 SKIP）
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify\p0a-double-pay.ps1 -Mode Execute

# 完整验收 + 允许脚本自建批次（要求目标商户有 AVAILABLE 收益）
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify\p0a-double-pay.ps1 -Mode Execute -AllowGenerate

# 通道 B 确定性锁观测：SQL 会话持 t_merchant 行锁 4s，断言 pay 阻塞 ≥2s（与场景2二选一跑，跑两次更稳）
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify\p0a-double-pay.ps1 -Mode Execute -WithLockProbe -AllowGenerate

# 指定批次/账号/DB/容器
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify\p0a-double-pay.ps1 -Mode Execute -SettleId 2 `
  -AdminPhone 13800000000 -AdminPassword 123456 -MerchantPassword 123456 `
  -MySqlContainer shopping-mysql-1 -Database lanlink_shopping

# 保留本次创建的数据排障（会打印主键与 marker）
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify\p0a-double-pay.ps1 -Mode Execute -AllowGenerate -NoCleanup
```

**证据日志**：每次运行写入 `reports/impl/evidence/p0a-acceptance-<yyyyMMdd-HHmmss>.log`（`Start-Transcript` 全量命令+响应+断言）。把该日志路径填进 `reports/impl/p0a-acceptance-template.md` 的「证据」列。

---

## 4. 鉴权与 CSRF（脚本内部实现，供手工复测参考）

1. `POST /api/auth/login`，body `{"phone":"...","password":"..."}`（`LoginDTO` 为 `phone/password`；账号不存在/密码错误返回 `body.code=400`）。
   - 若账号启用两步验证：`data.require2fa=true` → 脚本直接报错退出，请换未启用 TOTP 的验收账号。
2. `GET /api/user/csrf-token`（需登录会话）→ `data.token`。
3. 所有写请求带请求头 `X-CSRF-TOKEN: <token>`；会话由 `JSESSIONID` Cookie 维持（脚本用 `HttpClientHandler.CookieContainer` 自动携带）。
4. **业务错误约定**：`BusinessException` 由 `GlobalExceptionHandler.handleBusiness` 返回 **HTTP 200 + `body.code=400`**（`GlobalExceptionHandler.java:37-40`、`R.java:11-19`）；断言一律看 `body.code`，不要看 HTTP 状态码。
5. 会话级 CSRF 失效（403+CSRF）时重新 `GET /user/csrf-token`（脚本每次运行重新登录+取 token，无此问题）。

手工复测示例（PowerShell+curl 等价）：

```powershell
$s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/auth/login' -WebSession $s `
  -ContentType 'application/json' -Body '{"phone":"13700000002","password":"123456"}'
$token = (Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/user/csrf-token' -WebSession $s).data.token
Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/merchant/finance/balance' -WebSession $s | ConvertTo-Json -Depth 4
```

---

## 5. 结果判读（对照验收模板）

| 用例 | 期望（修复后） | 失败含义 |
| --- | --- | --- |
| `A1` 回填是否生效 | `0` 行「PAID 批次明细仍为 SETTLED/REVERSED」 | 回填未执行：需部署含回填 initializer 的后端并重启，或手工执行 README §6 回填 SQL |
| `A2` PAID 批次明细不变量 | `0` 行非 `PAID` | 通道 A 写入/回填不完整，仍可能双付 |
| `A5` item_count/total_net 巡检 | `0` 个不一致批次 | 历史脏数据；见 §7 巡检 SQL，人工核对（不一定双付） |
| `S1-API` 口径一致性 | `api.available ≈ settled(排除PAID批次)−paid−frozen`（±0.005） | 口径仍包含已 PAID 批次 → 通道 A 失效 |
| `S1-400` 超额提现 | `body.code=400` + message 含「可提余额不足」 | 提现成功 = **双付复现**（脚本 FAIL，并按 marker 清理该提现单） |
| `B1-LOCK` | `elapsed ≥ 2s`（pay 等在商户行锁上） | pay 未取锁 → 通道 B 未生效 |
| `S2-RACE` | 恰好一个 `body.code=200`，另一个 400 | 两个 200 = **双付**；两个失败/500 = 异常 |

`WARN` 只用于巡检类项（A5/A6），不影响退出码。

---

## 6. 只读核对 SQL（要求 3 的三条，脚本内嵌同款）

```sql
-- ① 回填是否生效：PAID 批次明细中仍为 SETTLED/REVERSED 的行数（期望 0）
SELECT COUNT(*) FROM t_merchant_earning e
  JOIN t_merchant_settlement_item si ON si.earning_id = e.earning_id
  JOIN t_merchant_settlement s       ON s.settle_id  = si.settle_id
 WHERE s.status = 'PAID' AND e.status IN ('SETTLED','REVERSED');

-- ② 可提余额口径（SQL 推导；与 GET /merchant/finance/balance 的 data.available 对比，±0.005）
SELECT
  COALESCE(SUM(CASE WHEN e.status IN ('SETTLED','REVERSED') THEN e.net_amount ELSE 0 END),0)
  - COALESCE((SELECT SUM(amount) FROM t_merchant_withdrawal WHERE mer_id = <MER_ID> AND status = 1),0)
  - COALESCE((SELECT SUM(amount) FROM t_merchant_withdrawal WHERE mer_id = <MER_ID> AND status = 0),0)
FROM t_merchant_earning e
WHERE e.mer_id = <MER_ID>
  AND NOT EXISTS (
      SELECT 1 FROM t_merchant_settlement_item si
        JOIN t_merchant_settlement s ON s.settle_id = si.settle_id
       WHERE si.earning_id = e.earning_id AND s.status = 'PAID');

-- ③ 残留「批次已 PAID、收益仍 SETTLED」/ 明细非 PAID（期望 0）
SELECT COUNT(*) FROM t_merchant_earning e
  JOIN t_merchant_settlement_item si ON si.earning_id = e.earning_id
  JOIN t_merchant_settlement s       ON s.settle_id  = si.settle_id
 WHERE s.status = 'PAID' AND e.status <> 'PAID';
```

> 回填 SQL（通道 A，幂等；本脚本不自动执行，由部署/运维按需执行）：
> ```sql
> UPDATE t_merchant_earning e
>   JOIN t_merchant_settlement_item si ON si.earning_id = e.earning_id
>   JOIN t_merchant_settlement s       ON s.settle_id  = si.settle_id
>    SET e.status = 'PAID'
>  WHERE s.status = 'PAID' AND e.status IN ('SETTLED','REVERSED');
> ```

---

## 7. 巡检 SQL（人工排障用，脚本 A5/A6 已内嵌部分）

```sql
-- 批次汇总 vs 实际明细（条数/净额/空批次）
SELECT s.settle_id, s.settle_no, s.mer_id, s.status, s.item_count,
       COUNT(si.id) AS actual_item_count,
       s.total_net, COALESCE(SUM(si.net_amount),0) AS items_net_sum
FROM t_merchant_settlement s
LEFT JOIN t_merchant_settlement_item si ON si.settle_id = s.settle_id
GROUP BY s.settle_id, s.settle_no, s.mer_id, s.status, s.item_count, s.total_net
HAVING COUNT(si.id) <> s.item_count
    OR (s.status <> 'DRAFT' AND COALESCE(SUM(si.net_amount),0) <> s.total_net)
    OR (s.status <> 'DRAFT' AND COUNT(si.id) = 0);

-- 父子归属：PAID 批次明细对应收益必须 PAID（期望 0 行）
SELECT s.settle_no, e.earning_id, e.type, e.status
FROM t_merchant_settlement s
JOIN t_merchant_settlement_item si ON si.settle_id = s.settle_id
JOIN t_merchant_earning e ON e.earning_id = si.earning_id
WHERE s.status='PAID' AND e.status <> 'PAID';

-- 孤立 PAID 收益（不在任何 PAID 批次明细内）
SELECT e.earning_id FROM t_merchant_earning e
WHERE e.status='PAID' AND NOT EXISTS (
  SELECT 1 FROM t_merchant_settlement_item si
  JOIN t_merchant_settlement s ON s.settle_id=si.settle_id
  WHERE si.earning_id=e.earning_id AND s.status='PAID');
```

---

## 8. 安全与清理

- **只在本地/测试环境运行 `-Mode Execute`**：它会写登录日志、按需创建结算批次、可能创建 1 张提现单（当缺陷复现时），并在 `-AllowGenerate` 时自建批次。
- 清理只针对本次运行记录的主键：
  - 提现单：`apply_note = P0A-ACC-<RunId>` → 先删对应 `t_finance_ledger.biz_no`，再删提现单（按 id）；
  - 自建批次：`settle_id` → 先把明细收益行还原（`EARN→AVAILABLE`、`REVERSE→REVERSED`，仅限 `SETTLED/PAID`），再删明细与批次行；
  - 不删除、不改写任何非本次创建的数据；`-NoCleanup` 时不做任何清理（会打印 marker/主键）。
- 脚本不会执行 `git`、不会重启容器、不会修改 `backend/`、`frontend/`、`schema.sql`、`.env`。

---

## 9. 故障排查

| 现象 | 原因/处理 |
| --- | --- |
| `PRE-1` FAIL | 后端未起/端口不是 8080；`docker ps` 看 `shopping-backend-1`，确认 `127.0.0.1:8080->8080` |
| `PRE-2` FAIL / `docker: not found` | Docker CLI 不在 PATH，或容器名不是 `shopping-mysql-1`（用 `-MySqlContainer`） |
| 登录 `body.code=400`「手机号或密码错误」 | 演示数据未开启（`LANLINK_DEMO_DATA_ENABLED=true` 且 `DataInitializer` 已执行）或密码被改；用 `-AdminPhone/-MerchantPhone` 换真实验收账号 |
| 登录提示 `require2fa` | 账号启用 TOTP；脚本不支持两步验证，换账号 |
| `403 CSRF` | 手工 curl 时未带 `X-CSRF-TOKEN` 或会话丢失；脚本每次重新登录取 token，不应出现 |
| 场景 1 `SKIP` | 库中无 `PAID` 批次：先按真实流程完成 generate→approve→pay（或检查通道 A 是否已部署） |
| 场景 2 `SKIP` | 无 `APPROVED` 批次且未加 `-AllowGenerate`；或目标商户无 `AVAILABLE` 收益（当前环境常见，见 §10） |
| `B1-LOCK` FAIL 且 elapsed<1s | `pay()` 未取商户行锁（通道 B 未生效），或 SQL 锁观测 job 未成功持锁（看日志中 job 输出/权限） |
| MySQL 警告 `Using a password on the command line` | 无害，脚本已重定向 stderr；不影响结果 |
| 证据日志中文乱码 | PowerShell 5.1 控制台编码问题：日志由 `Start-Transcript` 写入，用 VS Code/记事本以 UTF-8 打开 |

---

## 10. 当前环境已知情况（2026-11-11 只读核对）

- 运行栈：`shopping-mysql-1`（127.0.0.1:3306）、`shopping-backend-1`（127.0.0.1:8080/api）均在跑，健康检查 UP。
- 现存 1 个 `PAID` 批次：`settle_id=2`、`mer_id=1`、`total_net=3301.50`、`item_count=1`；其明细 `earning_id=4` 当前仍为 **`SETTLED`**（`schema.sql` 现行表结构见 `t_merchant_earning:844` 段）。
- ⇒ 若未部署「通道 A + 回填 initializer」或未手工回填，DryRun 的 **A1/A2 会 FAIL**（本脚本首次 DryRun 即如实报出），这是预期验收起点，不是脚本缺陷。
- 商户 1 的提现流水：`status=1` 合计 2000.00、`status=0`（冻结）合计 1300.00；回填后其可提余额 SQL 推导为 `max(0, 0 − 2000 − 1300) = 0`（DryRun `S1-SQL` 已输出）。
- 当前库无 `APPROVED` 批次、无 `AVAILABLE` 收益 ⇒ 场景 2 需要先准备数据（见下文）。

### 场景 2 数据准备（二选一）

1. 真实链路：完成一笔订单支付并等待持有期满（`app.settlement.holding-days`，默认 7 天；测试可调短），`POST /admin/settlement/sync` 后即产生 `AVAILABLE` EARN；
2. 或使用 `-AllowGenerate`，由脚本对「有 AVAILABLE 收益」的商户 `generate` + `approve` 自建批次，并在结束时清理（推荐，避免手工造数）。
