/* 五维因子覆盖扩面工作流
 * 症状：价格五维因子区域展示的已分析商品太少。
 * 已知链路：ProductFactorAutoService 首轮扫描无上限，但网络检索（MarketWebSearchService）失败
 * 会让整个快照落库失败 → fail_count>=3 永久隔离 → 覆盖骤减；展示侧 HomeMarketPulse/FactorSnapshotPanel
 * 依赖 t_product_factor_auto 快照。
 * 拓扑：排查员(只读,代码+容器DB实测) → 实施工程师(降级不阻断/隔离复位/启动补跑/前端覆盖展示)
 *       → 门禁(vitest+build,后端动则 mvn test) → 复核员(快照数增长实测+页面目检)
 */

interface FactorAudit {
  /** 覆盖现状实测：在售商品数 / 有快照数 / 隔离失败数 / 最近失败原因（docker exec 查 MySQL 实数） */
  coverageNow: string
  /** 根因清单，每条一句话并带 path:line 证据 */
  rootCauses: string[]
  /** 修复要点，到文件级，含取舍理由 */
  fixPlan: string[]
  /** 前端展示链路结论：五维因子在哪几处展示、商品列表数据来源、为何显得少 */
  displayPaths: string[]
}

interface ImplResult {
  filesChanged: string[]
  /** 是否改了 backend/ 下代码（决定门禁是否跑 mvn test） */
  backendTouched: boolean
  /** 是否已重打包并重建 backend 容器且容器健康 */
  containerRebuilt: boolean
  summary: string
  ok: boolean
  notes: string
}

interface CheckFinding {
  where: string
  what: string
  evidence: string
  status: "verified" | "unconfirmed"
  severity: "low" | "medium" | "high"
}

interface Finding {
  /** 位置：文件:行号或端点 */
  where: string
  /** 一句话说明问题 */
  what: string
  /** 证据：读到的行、命令与输出 */
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

phase("排查五维因子覆盖缺口")
const audit = await agent("覆盖排查员", {
  system: "你是只读排查员：读代码定位根因，用 docker exec 查 MySQL 实数佐证（容器与库名用 docker ps / 配置确认，MySQL root 密码参考仓库 .env.example / docker-compose）。绝不修改任何文件。" +
    "重点文件：backend/src/main/java/com/lanlink/shopping/module/pricemonitor/service/ 下 ProductFactorAutoService.java（:87 首轮无上限、:96 过期重算 LIMIT 40、:124 失败隔离 fail_count>=3 永久排除、:176 analyzeOne 中 webSearch.searchMarketInfo 失败会令整快照失败）、MarketWebSearchService.java（失败模式/外部依赖）、PriceMonitorAiService.factors（本地确定性）、ProductFactorSyncListener（事件驱动）；前端 frontend/src/components/pricemonitor/HomeMarketPulse.vue（首页五维价格因子）、FactorSnapshotPanel.vue、views/PriceIntelligence.vue、api/index.js 约 :736/:761。",
}).ask<FactorAudit>(
  `排查「五维因子展示的已分析商品太少」：\n` +
  `1) 实测覆盖：在售商品总数（status=1 AND review_status=1 AND deleted=0，表名以 @TableName 为准）vs t_product_factor_auto 行数 vs t_product_factor_auto_fail 行数；取 fail 表最近失败原因样本。\n` +
  `2) 根因：网络检索失败是否致命（analyzeOne 无降级）？隔离是否无自动复位？MarketWebSearchService 外部源（Bing RSS？）稳定性与超时？本地五维 factors() 是否本可独立成功？\n` +
  `3) 展示链路：首页 HomeMarketPulse 与 /price-monitor 的 FactorSnapshotPanel 的商品列表各来自哪（快照索引 indexOfAll？on-sale 全量？），「已分析」徽标在哪；看起来少的直接原因。\n` +
  `4) 修复计划到文件级：预期方向——检索失败降级（webInfo 置空+标注 degraded，快照照常落库）、隔离自动复位/重试、启动后延迟异步补跑一次（走 running 守卫）、前端诚实展示覆盖（分析中/未分析态）；以实际排查为准，给取舍。\n` +
  `输出 FactorAudit（coverageNow/rootCauses/fixPlan/displayPaths）。`,
)
log(`排查完成：${audit.rootCauses.length} 条根因`)

phase("实施覆盖扩面修复")
const impl = await agent("修复实施工程师", {
  system: "你是资深全栈工程师：按排查结论实施扩面修复。目标=所有在售商品尽快都有五维因子快照且页面诚实展示。硬约束：① 不破坏既有 API 契约（auto-profile/factors/索引端点字段只增不改）；② 分析失败隔离语义保留给真错误，网络检索类瞬时失败必须降级不阻断；③ 后端改动如涉及调度/线程，沿用 running 守卫与 daemon 线程模式，不阻塞启动；④ 新中文文案进 i18n/zh-en-extra.js 前先 grep 该文件防重复/冲突（常用短词禁入 EXTRA；Vue 插值句子别拆 key）；⑤ UI 遵循现有 pricemonitor 风格（Lucide 图标、禁 emoji）；⑥ 前后端改动都补/改对应测试；⑦ 只动本问题相关文件。不要跑全量测试与构建（脚本统一跑）。" +
    "完成后如动了 backend/：mvn -f backend/pom.xml -DskipTests package 后用 docker compose 重建并拉起 backend 容器（以仓库实际 compose 文件为准），确认容器健康再返回。",
}).ask<ImplResult>(
  `排查结论（含 fixPlan）：${JSON.stringify(audit)}\n` +
  `按 fixPlan 实施覆盖扩面：检索失败降级快照照常落库（webInfo 空数组+degraded 标注）、隔离表自动复位或纳入重试、启动后延迟（约 90s）异步补跑一次 analyzeMissing（enabled 与 running 守卫照走）、前端两处展示补覆盖状态（如已分析 N/M、分析中/未分析徽标与空态指引，用 indexOfAll 或既有端点，不新增重复端点）。返回 ImplResult（filesChanged/backendTouched/containerRebuilt/summary/ok/notes）。`,
)
log(`实施完成：${impl.ok ? "成功" : "有遗留"}（${impl.filesChanged.length} 个文件，后端 ${impl.backendTouched ? "已动" : "未动"}，容器 ${impl.containerRebuilt ? "已重建" : "未重建"}）`)

phase("测试与构建门禁")
let gateOk = false
let gateTail = ""
const fixer = agent("门禁修复工程师")
for (let round = 1; round <= 2; round++) {
  log(`第 ${round} 轮门禁：vitest + build${impl.backendTouched ? " + 后端 mvn test" : ""}`)
  const t = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "test"], { timeoutMs: 420000, output: { tail: 2000 } })
  const b = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "run", "build"], { timeoutMs: 420000, output: { tail: 1200 } })
  let m: WorldRunResult | null = null
  if (impl.backendTouched) {
    m = await world.run("cmd", ["/c", "mvn", "-f", "backend/pom.xml", "test"], { timeoutMs: 1200000, output: { tail: 2000 } })
  }
  const mOk = m === null || m.exitCode === 0
  if (t.exitCode === 0 && b.exitCode === 0 && mOk) {
    gateOk = true
    log(`第 ${round} 轮门禁通过`)
    break
  }
  gateTail = [t, b, m].filter(x => x !== null).map(x => (x as WorldRunResult).stdout + (x as WorldRunResult).stderr).join("\n").slice(-1600)
  if (round === 2) {
    log("两轮修复后仍有失败")
    break
  }
  log(`第 ${round} 轮门禁失败，交修复工程师`)
  await fixer.ask(
    `测试/构建失败，请修复（只动相关代码；不要跑全量套件与构建，脚本会重跑）：\n${gateTail}`,
  )
}

phase("独立复核与实测")
const reviewer = await agent("覆盖复核员", {
  system: "你是只读复核员：用 docker exec 查 MySQL 实数核对覆盖是否真实增长（在售商品数 vs 快照数 vs 隔离数，对比排查基线），Read 改动文件核对降级/复位/补跑逻辑与 API 兼容、i18n 词条冲突；可用浏览器 MCP 打开 http://localhost:5174 （首页与 /price-monitor）目检五维因子区域覆盖展示。绝不修改任何文件。如快照仍在增长中属正常（补跑/定时在跑），如实记录当前计数与趋势。",
}).ask<CheckFinding[]>(
  `复核清单（排查基线：${JSON.stringify(audit.coverageNow)}；实施摘要：${impl.summary}）：\n` +
  `1) 覆盖实测：快照数较基线是否增长、隔离数是否不再累积、失败原因是否已降级消除（webInfo 置空而非整快照失败）。\n` +
  `2) 代码核对：降级路径/隔离复位/启动补跑（守卫与 enabled 开关）/API 字段只增不改。\n` +
  `3) 前端：两处展示的覆盖状态真实渲染（首页五维价格因子 + /price-monitor 快照面板），i18n 词条无重复冲突。\n` +
  `4) 每条给 where/what/evidence/status/severity。`,
)

await artifact.markdown("report", [
  "# 五维因子覆盖扩面报告",
  "",
  `排查基线：${audit.coverageNow}`,
  "",
  "## 根因",
  ...audit.rootCauses.map(r => `- ${r}`),
  "",
  "## 修复",
  `- ${impl.filesChanged.join("、")}`,
  `- ${impl.summary}`,
  `- 门禁：${gateOk ? "vitest + build" + (impl.backendTouched ? " + 后端测试" : "") + " 通过" : "仍有失败（见 findings）"}；backend 容器${impl.containerRebuilt ? "已重建拉起" : "未重建（见 notes）"}`,
  "",
  "## 复核",
  ...reviewer.map(f => `- ${f.where}：${f.what}（${f.status}）`),
].join("\n"), { title: "五维因子覆盖扩面报告", description: "覆盖缺口根因、降级扩面修复与实测复核结论。", primary: true })

const pending = reviewer.filter(f => f.severity !== "low" || f.status === "unconfirmed")
return {
  conclusion: `五维因子已分析商品过少的根因已定位（${audit.rootCauses[0]}），按扩面方案修复` +
    (gateOk ? "，测试与构建门禁通过。" : "，但门禁有失败项需跟进。") +
    `复核${pending.length === 0 ? "无遗留问题" : `有 ${pending.length} 项待关注`}。`,
  findings: [
    ...pending,
    ...(gateOk ? [] : [{
      where: "frontend/backend",
      what: "门禁两轮后仍失败",
      evidence: gateTail,
      status: "verified" as const,
      severity: "high" as const,
    }]),
  ],
  verified: [
    "cmd /c npm --prefix frontend test",
    "cmd /c npm --prefix frontend run build",
    ...(impl.backendTouched ? ["cmd /c mvn -f backend/pom.xml test"] : []),
    "覆盖排查员 docker exec MySQL 实测基线",
    "覆盖复核员实测快照数增长 + 代码核对 + 页面目检",
  ],
  notCovered: [
    "全部在售商品补跑完成时间取决于网络检索速度（复核记录了当时的计数与趋势）",
    "AI 渠道真实调用质量不在本轮（本地五维因子为确定性计算）",
  ],
} as WorkflowReport
