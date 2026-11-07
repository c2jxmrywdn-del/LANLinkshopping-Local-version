/* 安全加固批次：物流越权 + key 外带 + 存储 MQ 台账 + 工具链解锁
 * 输入：① 本会话评审实测（LogisticsWaybillService traces 无属主校验；前端明文 securityJsCode/VITE_AMAP_*）
 *      ② MinIO/MQ 7 项瑕疵台账（lanlink-storage-mq-rollout，方案 out/security-hardening-plan.md）
 *      ③ Lombok 1.18.36 × JDK24 致本机 mvn test 编译期崩溃
 * 拓扑：台账核实员(只读) → 三修复线并行(物流安全/存储MQ/工具链，文件不相交)
 *      → 统一门禁(vitest+build+本机mvn test[若解锁])+backend容器重建(查exitCode+健康等待)
 *      → 独立安全复核(curl 越权实测/泄露面 grep/回退开关)
 */

interface LedgerItem {
  /** 台账编号（如 M1…M7 对应 MinIO 7 项 / L1 物流越权 / L2 key 外带） */
  id: string
  title: string
  severity: "low" | "medium" | "high"
  /** 是否仍未修（false=已在后续会话修掉，本次跳过） */
  stillPending: boolean
  /** 核实证据：读到的行/命令输出 */
  evidence: string
  /** 修复要点提示（到文件级） */
  planHint: string
}

interface WorkLedger {
  items: LedgerItem[]
  /** out/security-hardening-plan.md 现状（存在/缺失/可引用章节） */
  planDoc: string
  notes: string
}

interface FixResult {
  filesChanged: string[]
  ok: boolean
  summary: string
  notes: string
}

interface ToolchainResult {
  /** 是否完成了 Lombok 升级（false=尝试后回退） */
  upgraded: boolean
  /** 最终生效的 Lombok 版本 */
  lombokVersion: string
  /** 本机 mvn -DskipTests compile 是否已跑通（全量 test 由脚本门禁跑） */
  localCompilePass: boolean
  /** 关键输出尾部（失败原因/版本号证据） */
  output: string
}

interface CheckFinding {
  where: string
  what: string
  evidence: string
  status: "verified" | "unconfirmed"
  severity: "low" | "medium" | "high"
}

interface Finding {
  where: string
  what: string
  evidence: string
  status: "verified" | "unconfirmed"
  severity: "low" | "medium" | "high"
}

interface WorkflowReport {
  conclusion: string
  findings: Finding[]
  verified: string[]
  notCovered: string[]
}

phase("核实安全台账现状")
const ledger = await agent("安全台账核实员", {
  system: "你是只读核实员：逐项核实安全台账的当前状态（可能已有部分被后续会话修掉），绝不修改文件。台账背景：① application.yml S3 凭据回退默认 minioadmin；② 新上传 key products/ 前缀与 public-base-url 双写；③ 桶级匿名读过宽+匿名 LIST；④ /product/image 无 @RequirePerm/限流/CSRF；⑤ MQ 消费者不反查 t_order（伪造 orderNo 可铸积分）；⑥ DLQ 无 max-length、rabbitmq 无数据卷；⑦ 双配置前缀 app.storage vs lanlink.storage（enabled=false 回退可能失效）；L1 物流 traces 无属主校验（LogisticsWaybillService.java:1105-1109，对照 order-waybills :816-833 三分支）；L2 高德 key/securityJsCode 明文进前端（LogisticsDynamicMap.vue:778-823/:848、.env.example:148-150 VITE_AMAP_*）。方案文档 out/security-hardening-plan.md（若在）。注意 git 近期提交可能已修部分项——以当前代码为准。",
}).ask<WorkLedger>(
  `逐项核实上述 9 项（M1…M7 + L1 + L2）当前代码状态：application.yml/StorageProperties、桶策略与 key 规范、/product/image 注解、MQ 消费者（OrderPaidBridgeListener 链路）、compose 卷、双前缀、traces 校验、前端 key 泄露面（含 dist 是否也泄露）。每项给 stillPending/evidence/planHint，输出 WorkLedger。`,
)
const pending = ledger.items.filter(i => i.stillPending)
log(`台账核实：${pending.length}/${ledger.items.length} 项待修（${pending.map(i => i.id).join(", ")}）`)

/* 三条并行修复线（文件不相交：物流 / 存储+MQ / pom.xml） */
const fixLogistics = future(async () => {
  phase("修复物流轨迹越权与 key 外带")
  const items = pending.filter(i => i.id === "L1" || i.id === "L2")
  return agent("物流安全修复员", {
    system: "你是资深后端+前端工程师，按项目 fail-closed 纪律修复两项安全问题。" +
      "L1 越权：给 traces 查询补属主/角色校验，完全对齐 order-waybills 的既有三分支（买家属主/订单相关商户/管理员，非属主抛「无权查看」BusinessException）——fail-closed，不确定归属一律拒绝；补对应单测（模仿仓库既有测试风格；不要跑全量套件，脚本统一跑）。" +
      "L2 key 外带：地图配置收进后端——新增登录可用的地图配置代理端点（返回 key/securityJsCode，挂既有鉴权链；端点与白名单按仓库惯例），前端 LogisticsDynamicMap.vue 改为从该端点取配置（带缓存），**删除 localStorage 手填通道与对 VITE_AMAP_*/securityJsCode 的任何明文引用**；.env.example 相应条目改为后端侧变量说明。若 L1/L2 未在待修清单则不动。i18n 新中文文案先 grep zh-en-extra 防重复。不要重建容器（脚本统一重建），不要跑全量测试与构建。" +
      "完成后返回 FixResult。",
  }).ask<FixResult>(
    `待修项（含 planHint）：${JSON.stringify(items)}\n实施修复。返回 FixResult（filesChanged/ok/summary/notes）。`,
  )
})

const fixStorage = future(async () => {
  phase("修复存储与 MQ 安全清单")
  const items = pending.filter(i => i.id.startsWith("M"))
  return agent("存储 MQ 安全修复员", {
    system: "你是资深后端工程师：按台账与方案文档修复 MinIO/RabbitMQ 安全项。先 Read out/security-hardening-plan.md（存在则按其三档时序与设计实施，不重造方案）。硬约束：① S3 凭据禁止回退 minioadmin——缺失即 fail-fast（对齐 AI_OUTBOUND_KEY_SECRET 纪律）；② key 规范统一（消除 products/products 双写，兼容存量裸 key 展示）；③ 匿名策略最小化（仅展示必需对象可匿名读，禁匿名 LIST）；④ /product/image 补 @RequirePerm/限流（对齐仓库既有拦截器用法）；⑤ MQ 消费者处理 order.paid 前必须反查 t_order 确认订单存在且金额匹配（一条 SELECT 闭合伪造铸积分），补单测；⑥ DLQ 加 max-length 与 rabbitmq 数据卷（compose 改动注意 lanlink-rabbitmq 是独立容器还是 compose 服务，按现状改）；⑦ 统一配置前缀到 lanlink.storage 并保证 enabled=false 回退真实生效（附单测/配置测试）。每项改动补对应测试；不要跑全量套件；不要重建容器（脚本统一）。dev 库验证可用 docker exec（SQL 走文件→docker cp 惯例）。完成后返回 FixResult。",
  }).ask<FixResult>(
    `待修项（含 planHint 与核实证据）：${JSON.stringify(items)}\n方案文档：${ledger.planDoc}\n实施修复。返回 FixResult。`,
  )
})

const fixToolchain = future(async () => {
  phase("解锁本机后端测试工具链")
  return agent("工具链修复员", {
    system: "你是构建工程师：本机 mvn 编译 backend 测试崩溃（Lombok 1.18.36 与本机 JDK 24 不兼容：TypeTag::UNKNOWN / cannot find symbol）。任务：查 Maven Central 上 Lombok 对 JDK 24 的支持版本（≥1.18.38，用 WebSearch/WebFetch 查 release notes），升级 backend/pom.xml 的 lombok.version 到最新稳定版；本机跑 `cmd /c mvn -f backend/pom.xml -DskipTests compile` 确认编译通过（**不要跑全量 test，脚本门禁统一跑**）。若升级后仍崩溃：回退 pom 到原版本并如实返回 localCompilePass=false 与失败尾部（容器内构建不受影响，门禁会降级）。同时确认根目录 Dockerfile/compose 不因版本变更受损（Lombok 向后兼容 JDK17，理应无碍）。",
  }).ask<ToolchainResult>(
    `执行 Lombok 升级与本地编译验证。返回 ToolchainResult（upgraded/lombokVersion/localCompilePass/output——output 贴版本号与编译结论关键行）。`,
  )
})

/* 汇合：统一门禁 + 容器重建 + 健康等待 */
phase("统一门禁与容器重建")
const [logiR, stoR, toolR] = await Promise.all([fixLogistics, fixStorage, fixToolchain])
log(`三线汇合：物流 ${logiR.filesChanged.length} 文件 / 存储MQ ${stoR.filesChanged.length} 文件 / 工具链 ${toolR.upgraded ? "Lombok→" + toolR.lombokVersion : "未升级"}`)

let gateOk = false
let gateTail = ""
const fixer = agent("门禁修复员")
for (let round = 1; round <= 2; round++) {
  log(`第 ${round} 轮门禁：vitest + build${toolR.localCompilePass ? " + 本机 mvn test" : ""}`)
  const t = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "test"], { timeoutMs: 420000, output: { tail: 2000 } })
  const b = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "run", "build"], { timeoutMs: 420000, output: { tail: 1200 } })
  let m: WorldRunResult | null = null
  if (toolR.localCompilePass) {
    m = await world.run("cmd", ["/c", "mvn", "-f", "backend/pom.xml", "test"], { timeoutMs: 1200000, output: { tail: 2500 } })
  }
  const mOk = m === null || m.exitCode === 0
  if (t.exitCode === 0 && b.exitCode === 0 && mOk) {
    gateOk = true
    log(`第 ${round} 轮门禁通过`)
    break
  }
  gateTail = [t, b, m].filter(x => x !== null).map(x => (x as WorldRunResult).stdout + (x as WorldRunResult).stderr).join("\n").slice(-1800)
  if (round === 2) {
    log("两轮修复后仍有失败")
    break
  }
  log(`第 ${round} 轮门禁失败，交修复员（只动失败相关文件，防并行竞态）`)
  await fixer.ask(`测试/构建失败，请修复（不要跑全量套件与构建，脚本会重跑）：\n${gateTail}`)
}

// backend 容器重建（教训：显式 build 后 up 分两步，且必须查 exitCode）
const build = await world.run("cmd", ["/c", "docker", "compose", "build", "backend"], { timeoutMs: 1500000, output: { tail: 1500 } })
if (build.exitCode !== 0) {
  log("backend 镜像构建失败（exitCode=" + build.exitCode + "）——如实入报告")
} else {
  const up = await world.run("cmd", ["/c", "docker", "compose", "up", "-d", "backend"], { timeoutMs: 180000, output: { tail: 800 } })
  if (up.exitCode !== 0) log("backend up 失败（exitCode=" + up.exitCode + "）")
}
let healthy = false
for (let i = 0; i < 24; i++) {
  const h = await world.run("cmd", ["/c", "docker", "inspect", "-f", "{{.State.Health.Status}}", "shopping-backend-1"], { timeoutMs: 30000, output: { tail: 200 } })
  if (h.stdout.trim() === "healthy") { healthy = true; break }
  await world.run("cmd", ["/c", "timeout", "/t", "5", "/nobreak"], { timeoutMs: 30000, output: "discard" })
}
log(`backend 容器：${healthy ? "healthy" : "未在 2 分钟内转 healthy"}`)

/* 独立安全复核（实测优先） */
phase("独立安全复核")
const checks = await agent("安全复核员", {
  system: "你是只读安全复核员：以实测为准，绝不修改文件。可用 docker exec 查库/容器（SQL 走文件→docker cp 惯例）、curl 实测端点（演示账号：买家 13900000001、商户 13911112222，密码均 123456；AuthInterceptor 的 401/403 是 body.code 且 HTTP 200——断言查 json.code）。",
}).ask<CheckFinding[]>(
  `复核清单（实施摘要：物流=${logiR.summary}；存储MQ=${stoR.summary}）：\n` +
  `1) L1 越权实测：登录买家，从库中找一个非本人运单的 waybillNo，curl 拉 traces——期待 fail-closed 拒绝；再用本人订单对照通过。\n` +
  `2) L2 泄露面：rg securityJsCode/VITE_AMAP 于 frontend/src 与 dist/——期待明文引用清零；地图配置代理端点存在且未登录不可用（或按设计的鉴权口径）。\n` +
  `3) M1：application.yml 无 minioadmin 回退（grep 实证）；M5：消费者反查 t_order 代码+单测在；M7：enabled=false 回退开关生效证据（配置/单测）。\n` +
  `4) M2/M3/M4/M6 按台账逐项代码核对（桶策略/匿名 LIST/限流注解/DLQ 与卷）。\n` +
  `5) 通用：改动是否破坏既有契约（对外端点字段只增不改）。\n` +
  `每条 where/what/evidence/status/severity；无法实测的如实标 unconfirmed。`,
)

const findings: Finding[] = [
  ...checks.filter(f => f.status === "unconfirmed" || f.severity !== "low"),
  ...(gateOk ? [] : [{ where: "frontend/backend", what: "门禁两轮后仍失败", evidence: gateTail, status: "verified" as const, severity: "high" as const }]),
  ...(build.exitCode !== 0 ? [{ where: "docker compose build backend", what: "backend 镜像构建失败，新代码未部署", evidence: build.stdout + build.stderr, status: "verified" as const, severity: "high" as const }] : []),
  ...(!healthy && build.exitCode === 0 ? [{ where: "shopping-backend-1", what: "容器 2 分钟内未转 healthy", evidence: "docker inspect 健康轮询超时", status: "verified" as const, severity: "high" as const }] : []),
]
for (const f of findings) report(f)

await artifact.markdown("report", [
  "# 安全加固批次报告",
  "",
  `台账 ${ledger.items.length} 项，核实待修 ${pending.length} 项；三线并行修复完成，门禁${gateOk ? "全绿" : "有失败项"}，backend 容器${healthy ? "已重建并 healthy" : "状态异常（见 findings）"}。`,
  "",
  "## 修复明细",
  `- 物流（L1 越权 + L2 key 外带）：${logiR.summary}`,
  `- 存储 MQ（M 系）：${stoR.summary}`,
  `- 工具链：Lombok ${toolR.lombokVersion}${toolR.localCompilePass ? "，本机 mvn test 已解锁" : "，本机仍未通（容器构建不受影响）"}`,
  "",
  "## 复核结论",
  ...checks.map(f => `- ${f.where}：${f.what}（${f.status}）`),
].join("\n"), { title: "安全加固批次报告", description: "物流越权/key 外带/存储 MQ 台账的修复与实测复核。", primary: true })

return {
  conclusion: `安全批次：核实待修 ${pending.length} 项已修复（物流越权 fail-closed、地图 key 收进后端代理、MinIO/MQ 台账项按方案落地）` +
    (gateOk ? "，门禁全绿" : "，但门禁有失败项需跟进") +
    (healthy ? "，backend 容器已重建 healthy。" : "，容器状态需关注。") +
    (toolR.localCompilePass ? " 本机后端测试工具链已解锁（Lombok " + toolR.lombokVersion + "）。" : ""),
  findings,
  verified: [
    "cmd /c npm --prefix frontend test",
    "cmd /c npm --prefix frontend run build",
    ...(toolR.localCompilePass ? ["cmd /c mvn -f backend/pom.xml test（本机，首次解锁）"] : ["docker compose build backend（容器内门禁替代）"]),
    "docker compose build backend exitCode + shopping-backend-1 健康轮询",
    "安全复核员：越权 curl 实测 / 泄露面 grep / 台账逐项代码核对",
  ],
  notCovered: [
    "MQ 伪造 orderNo 的端到端伪造事件注入实测（以消费者反查代码+单测+复核为准）",
    "MinIO 桶策略对线上真实桶的变更需在下次容器栈重建时随 init 生效",
    ...(toolR.localCompilePass ? [] : ["本机 mvn test 仍不可用（Lombok 升级未解决），后续后端验证继续走容器内构建"]),
  ],
} as WorkflowReport
