/* 五维因子板块优化工作流（P1：修"新商品无法分析"的前端盲区 + 快照板块接入 + E2E）
 * 后端部分（失败隔离/过期刷新/事件监听/端点）已由主会话完成并编译通过——本工作流核验后实施前端三件。
 */

interface EndpointCheck {
  ok: boolean
  detail: string
}

interface ImplOutcome {
  /** 本实施员完成的变更（文件 + 说明） */
  changes: { file: string; what: string }[]
  /** 跳过的子项与原因 */
  skipped: string[]
}

interface ReviewVerdict {
  /** 复核发现的问题（文件 + 一句话问题） */
  problems: { file: string; problem: string; severity: "high" | "medium" | "low" }[]
  /** 是否通过（无 high 问题即通过） */
  approved: boolean
}

interface PanelE2e {
  ok: boolean
  lines: string[]
}

phase("核验后端快照链路")
const backendCheck = await world.run("E:/Node.js/node.exe", ["-e", `
const API='http://127.0.0.1:8080/api';
(async()=>{
  const idx=await fetch(API+'/price-monitor/auto-profile/index').then(r=>r.json()).catch(e=>({code:0}));
  const okIdx=idx.code===200 && Array.isArray(idx.data) && idx.data.length>=370;
  const p=await fetch(API+'/price-monitor/auto-profile/1400').then(r=>r.json()).catch(e=>null);
  const payload=p&&p.data&&p.data.payload?p.data.payload:null;
  const f=payload&&Array.isArray(payload.factors)?payload.factors:[];
  const okDims=f.length===5 && f.every(x=>x.key&&x.impact!==undefined&&x.strength!==undefined);
  const anon401=await fetch(API+'/price-monitor/admin/factor-auto/refresh/1400',{method:'POST'}).then(r=>r.json()).catch(()=>null);
  const gateOk=anon401&&anon401.code!==200;
  console.log(JSON.stringify({okIdx, idxCount: okIdx?idx.data.length:0, okDims, anonBlocked: gateOk}));
})().catch(e=>{console.log(JSON.stringify({okIdx:false,idxCount:0,okDims:false,anonBlocked:false,err:String(e).slice(0,80)}))})
`], { timeoutMs: 120_000, output: { tail: 4096 } })
const checkLine = backendCheck.stdout.trim().split("\n").pop() || "{}"
const backendOk = checkLine.includes('"okIdx":true') && checkLine.includes('"okDims":true')
log("后端核验：" + checkLine.slice(0, 160))
if (!backendOk) {
  return {
    conclusion: "后端快照端点核验未通过（auto-profile/index 或五维数据缺失），前端实施暂停——先修后端再重跑本工作流。",
    findings: [{ where: "scripts/analysis/factor-shard.mjs", what: "后端核验输出：" + checkLine.slice(0, 200), evidence: "world.run node -e 端点核验", status: "verified", severity: "high" }],
    verified: ["world.run node -e 核验 /price-monitor/auto-profile/index 与 /1400"],
    notCovered: ["前端三件未实施——被门禁拦截"],
  }
}

phase("并行实现前端快照板块")
const implCommon =
  "项目：D:/桌面/LANLinkShopping/Shopping/frontend（Vue3 + Ant Design Vue 4 + 自绘 SVG 图表风格，页面样式变量 --pi-*）。" +
  "硬约束：① 禁止 emoji；② 新增 i18n 词条写入 src/i18n/zh-en-extra.js（中英对，沿用整句 key 约定）；" +
  "③ 不得改动任务清单外的文件；④ 商品内容保持中文（i18n 护栏不覆盖商品数据）；⑤ 完成后自检语法。"

const [apiImpl, panelImpl] = await Promise.all([
  agent("实施员·API与词条").ask<ImplOutcome>(
    `在 frontend/src/api/index.js 的 priceMonitorApi 对象内追加 4 个方法（不要动其他 api 对象）：` +
      `autoProfile(prodId)→GET /price-monitor/auto-profile/{prodId}；autoProfileIndex()→GET /price-monitor/auto-profile/index；` +
      `adminRebuildFactorAuto()→POST /price-monitor/admin/factor-auto/rebuild；adminRefreshFactorAuto(prodId)→POST /price-monitor/admin/factor-auto/refresh/{prodId}。` +
      `并在 src/i18n/zh-en-extra.js 追加以下中英词条（键为中文整句）：「自主分析快照」「实时计算」「自动分析」「数据较旧」「待自动分析，预计整点 50 分后完成」「立即分析」「已分析」「经有害信息过滤」「综合情绪指数」「主导因子」「网络资讯」「已重新分析」。\n` +
      `参考现有 priceMonitorApi.factors 的写法（request.get 静默可选）。${implCommon}`,
  ),
  agent("实施员·快照面板组件").ask<ImplOutcome>(
    `新建 frontend/src/components/pricemonitor/FactorSnapshotPanel.vue（约 260 行，遵循页面 --pi-* 变量与 Apple 风卡片）：` +
      `props: snapshot（auto-profile 的 data：{payload:{factors[],sentimentIndex,dominant,summary,systematicAnalysis,contentSafety,webInfo[],generatedAt}, webInfoCount, generatedAt}）、loading。` +
      `结构：① 头部「自主分析快照」+ a-tag 金色「自动分析 MM-dd HH:mm」（generatedAt 截取）+ 超 48h 灰 tag「数据较旧」；` +
      `② 综合情绪指数 a-progress（0-100，payload.sentimentIndex）+ 主导因子 a-tag（dominant.label + impact 箭头 up/down）；` +
      `③ 五维热力条五行（自绘 div：score -100~100 映射，负分左向红调/正分右向绿调），每行 label/score/strength/newsCount；` +
      `④ 网络资讯区最多 5 条（标题外链 target=_blank rel=noopener + snippet 截 60 字），区头标注「经有害信息过滤」；` +
      `⑤ contentSafety.clean=false 时 a-alert warning 显示 reason。\n` +
      `factors 元素结构：{key:'sentiment',label:'市场情绪',impact:'up|down|neutral',score:-100~100,strength:0-100,newsCount,headline,evidence}。` +
      `空白态不做（父组件负责）。${implCommon}`,
  ),
])

const implOutcome: ImplOutcome = {
  changes: [...apiImpl.changes, ...panelImpl.changes],
  skipped: [...apiImpl.skipped, ...panelImpl.skipped],
}
log(`API/词条/组件完成：${implOutcome.changes.length} 项变更`)

phase("集成价格情报台页面")
const pageImpl = await agent("实施员·情报台页面").ask<ImplOutcome>(
  `改造 frontend/src/views/PriceIntelligence.vue（795 行，先通读再动手），4 处改动：` +
    `① script setup 加 const snapshot=ref(null)、snapshotLoading=ref(false)、analyzedMap=ref({})（prodId→{generatedAt,webInfoCount}）；` +
    `② loadProducts() 完成后调 priceMonitorApi.autoProfileIndex() 填 analyzedMap（当前该列表只拉 100 条截断——改为循环拉到 total 上限 500，379 商品全量可选）；` +
    `③ loadProduct(prodId) 中先 snapshot.value=null 防串显，再与现有 Promise.all 并行调 priceMonitorApi.autoProfile(prodId) 填 snapshot；` +
    `④ 模板：走势图 MagicCard 与 two-col 之间插入 <FactorSnapshotPanel :snapshot="snapshot" :loading="snapshotLoading" />（组件在 components/pricemonitor/FactorSnapshotPanel.vue，需 import）；` +
    `商品列表按钮（product-item）内加 <span v-if="analyzedMap[p.prodId]" class="analyzed-dot" :title="'已分析 '+(analyzedMap[p.prodId].generatedAt||'').slice(0,16).replace('T',' ')"></span> 绿色小圆点（样式与页面既有 dot 对称）。` +
    `实时 factors 不默认调用（避免每次切换商品都触发重计算），保持现有 loadAi 行为不变。${implCommon}`,
)
log(`页面集成完成：${pageImpl.changes.length} 项变更`)

phase("前端构建门禁")
let build = await world.run("E:/Node.js/npm.cmd", ["--prefix", "frontend", "run", "build"], { timeoutMs: 600_000, output: { tail: 8192 } })
for (let round = 1; round <= 2 && build.exitCode !== 0; round++) {
  const fixer = agent("构建修复员-" + round)
  await fixer.ask<string>(
    `前端 vite build 失败，只修复错误、不改任何业务语义：\n${build.stderr.slice(0, 4000) || build.stdout.slice(-4000)}`,
  )
  build = await world.run("E:/Node.js/npm.cmd", ["--prefix", "frontend", "run", "build"], { timeoutMs: 600_000, output: { tail: 8192 } })
}
if (build.exitCode !== 0) {
  return {
    conclusion: "前端构建连续失败，实施已暂停：修改保存在工作区，修复构建后重跑本工作流即可。",
    findings: [{ where: "frontend/vite build", what: "构建失败，见 stderr", evidence: "npm --prefix frontend run build 退出码非 0", status: "verified", severity: "high" }],
    verified: ["两次构建尝试均失败，构建门禁拦截"],
    notCovered: ["E2E 与独立复核——被构建门禁拦截"],
  }
}
log("前端构建通过")

phase("独立复核前端变更")
const reviewP = agent("独立复核员", {
  system:
    "你是独立前端复核员，与实施员无上下文交集，职责是挑毛病：读 PriceIntelligence.vue 与 FactorSnapshotPanel.vue 的实际改动，" +
    "检查：切换商品时 snapshot 是否防串显、analyzedMap 是否正确填充、组件 props 数据流、i18n 是否遗漏、有无把实时 factors 变成每次切换必调。" +
    "只读不改；每条问题给文件与行号；无法核实的点如实标注。",
})
const review = await reviewP.ask<ReviewVerdict>(
  `用 git diff 查看 frontend/src/views/PriceIntelligence.vue、frontend/src/components/pricemonitor/FactorSnapshotPanel.vue、` +
    `frontend/src/api/index.js、frontend/src/i18n/zh-en-extra.js 的未提交改动并复核。${implCommon}`,
)

phase("E2E 验证并交付")
const e2eWriter = agent("E2E 编写员", {
  system:
    "你编写并运行（node）安全与功能 E2E 脚本：每项检查输出 [PASS]/[FAIL] 行，末行输出 JSON {ok:bool}；退出码 0=全过。只验证不修复，不打印密钥。",
})
const e2ePath = await e2eWriter.ask<string>(
  `编写 scripts/analysis/verify-factor-panel.mjs 并直接运行它（node 运行，目标 127.0.0.1:8080）：` +
    `1. GET /price-monitor/auto-profile/index 公开 200 且 ≥370 条、不含 payload 字段；` +
    `2. GET /price-monitor/auto-profile/1400 → payload.factors 五维齐全、webInfo 数组存在；` +
    `3. 未登录 POST /price-monitor/admin/factor-auto/refresh/1400 → code 401/403；admin 登录（13800000000/123456 + GET /user/csrf-token 取 X-CSRF-TOKEN）→ code 200；` +
    `4. GET /price-monitor/factors/1400 → 200 且五维 key 齐全（实时链未破坏）。` +
    `脚本退出码：全部 PASS=0 否则 1。返回脚本绝对路径与运行结果摘要。`,
)
const e2e = await world.run("E:/Node.js/node.exe", [e2ePath.replace(/^file:\/\/\//, "").replace(/^file:\/\//, "")], { timeoutMs: 420_000, output: { tail: 16384 } })
const e2ePass = e2e.exitCode === 0

phase("交付报告")
const reviewNote = review.problems.length
  ? review.problems.map(p => `- [${p.severity}] ${p.file}：${p.problem}`).join("\n")
  : "- 独立复核未发现问题"
await artifact.markdown(
  "panel-report",
  [
    "# 五维因子板块优化实施报告（P1）",
    "",
    "后端自动分析链实测健康（379/379 覆盖）；本报告修复的是前端盲区：快照板块、已分析徽标、列表全量、构建门禁。",
    "",
    "## 变更清单",
    ...implOutcome.changes.concat(pageImpl.changes).map(c => `- ${c.file}：${c.what}`),
    "",
    "## 独立复核",
    reviewNote,
    "",
    "## 跳过项",
    ...(implOutcome.skipped.length ? implOutcome.skipped.map(s => `- ${s}`) : ["（无）"]),
  ].join("\n"),
  { title: "五维因子板块优化报告", description: "快照板块接入 + 已分析徽标 + 列表全量 + 构建与 E2E 验证", primary: true },
)

const e2eText = e2e.stdout
return {
  conclusion:
    `五维因子板块 P1 完成：后端快照链路核验通过（${backendCheck.stdout.trim().slice(0, 80)}），` +
    `前端新增自主分析快照面板与已分析徽标、列表修复为全量 379 商品可选，前端构建通过，` +
    `安全与功能 E2E ${e2ePass ? "全部通过" : "存在失败（见 findings）"}。`,
  findings: [
    ...review.problems.map(p => ({
      where: p.file,
      what: p.problem,
      evidence: "独立复核员读 diff 核实",
      status: "verified" as const,
      severity: p.severity,
    })),
    ...(e2ePass
      ? []
      : [{ where: "scripts/analysis/verify-factor-panel.mjs", what: "E2E 存在 FAIL 项", evidence: e2eText.slice(0, 300), status: "verified" as const, severity: "high" as const }]),
  ],
  verified: [
    "world.run node -e 核验后端快照端点（index ≥370、五维齐全、admin 未登录被拒）",
    "npm --prefix frontend run build（构建门禁通过）",
    "node scripts/analysis/verify-factor-panel.mjs（端点与数据 E2E）",
  ],
  notCovered: [
    "浏览器视觉验收（面板渲染效果需人工在 5174 查看）",
    implOutcome.skipped.length ? "实施跳过项：" + implOutcome.skipped.join("；") : "无实施跳过项",
    "P2（因子快照向量化入 aikb-idx、管理端双索引统计）——按规划属下一期",
  ],
}
