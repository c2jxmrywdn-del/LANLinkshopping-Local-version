/* 液态玻璃 + 动态底色改造工作流
 * 参照爬取 → 设计方案 → 评审 → 并行实施（文件互斥四组）→ 构建门禁 → 对比度复核
 * 背景：上轮已完成可读性修复（hero 全部浅底深字）；本轮任务＝①所有 Hero 底色动态变化 ②增加液态玻璃质感 ③修复首页右侧 BrandSymbol 玻璃方块看不清（旧深底时代白玻璃白字遗留）
 */

interface DesignRef {
  name: string
  source: string
  cssNotes: string
  appliedTo: string
}

interface GlassTask {
  title: string
  files: string[]
  newFiles: string[]
  tasks: string[]
}

interface GlassSpec {
  tokens: string
  heroDrift: string
  groups: GlassTask[]
  risks: string[]
}

interface PlanReview {
  approved: boolean
  comments: string[]
}

interface ImplResult {
  group: string
  filesChanged: string[]
  summary: string
  notes: string
  ok: boolean
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

const DESIGN_BRIEF =
  "约束：苹果基准 + 小清新天空蓝浅底（上轮已落地的可读性修复不得回退——所有玻璃/文字组合正文对比度 ≥4.5:1，文字一律深色 #13233A/#0F172A/#475569 系）；" +
  "动效轻量优雅（底色漂移 8-20s 区间、光斑 transform 漂移、液态高光扫过 6-10s），全部动画必须有 prefers-reduced-motion 与 [data-perf-tier] 兜底；" +
  "遵循仓库 --ll-* 变量体系与 liquidGlass.css 既有玻璃工具；禁 emoji；只动样式与模板装饰性结构，不改业务逻辑；" +
  "大背景动画用 background-position/transform 合成层友好实现，避免每帧重绘大面积。"

phase("爬取液态玻璃与动态底参照")
const refAgent = agent("液态玻璃研究员", {
  system: "你是 UI 研究员：去 21st.dev 与业界（Magic UI MIT 实现、Apple iOS 26 Liquid Glass web 复刻、CodePen）收集可执行 CSS 参数。用 WebFetch/WebSearch。引用给 URL。只收集不改文件。",
})
const refs = await refAgent.ask<DesignRef[]>(
  `为「Hero 底色动态变化 + 液态玻璃质感」收集 4-6 个参照：\n` +
  `1) 动态渐变底：background-size 200-300% + position 循环的色标数/时长/缓动基准；aurora/mesh gradient 光斑漂移的 transform 模式\n` +
  `2) 液态玻璃：backdrop-filter 参数（blur/saturate 量级）、多层 inset 高光（顶光/底反光）、边缘渐变描边、液态高光扫过动画（sheen sweep 角度/时长）、SVG displacement 滤镜方案与性能取舍\n` +
  `3) 浅天蓝底 #F2F9FF/#E8F4FD/#DBEAFE 上的玻璃安全参数（玻璃上的文字必须深色系）\n` +
  `适用面：首页 brand-hero（右侧有 BrandSymbol 玻璃方块+两块漂浮玻璃画框）、技术服务 tech-hero（统计卡玻璃化）、关于我们 about-hero。每条给 name/source/cssNotes/appliedTo。`,
)
log(`参照收集完成：${refs.length} 条`)

phase("制定玻璃化改造方案")
const designer = agent("玻璃设计师", {
  system: "你是资深 UI 设计工程师：把参照翻译成文件级可执行方案。先 Read 涉及文件（frontend/src/views/Home.vue 的 brand-hero/brand-pane/brand-symbol 区、styles/techservice.css 的 .tech-hero/.tech-hero-stat、views/AboutUs.vue 的 .about-hero、styles/motion.css、styles/liquidGlass.css、components/BrandSymbol.vue）确认现状；文件分组互不相交；动效全部带 prefers-reduced-motion 与 [data-perf-tier] 兜底；对比度 ≥4.5:1 不回退。约束冲突写 risks。",
})
let spec = await designer.ask<GlassSpec>(
  `制定「Hero 底色动态化 + 液态玻璃质感」方案。${DESIGN_BRIEF}\n\n【参照】${JSON.stringify(refs)}\n\n` +
  `【已知现状】① 首页 brand-hero：底 linear-gradient(135deg,#F2F9FF,#E3F1FD,#D6ECFA) 静止；conic 极光层 ::before（低饱和天空系）；noise 层；斜向扫光 .brand-sweep；两块漂浮玻璃画框 .brand-pane（blur 10px）；右侧 BrandSymbol.vue .symbol-core 白玻璃白字（.core-lan #fff/.core-link 金 soft/small 白.5 7px）+ 轨道白.16 描边——旧深底时代遗留，浅底上看不清（用户画圈点名的组件）。\n` +
  `② 技术服务 .tech-hero（techservice.css，13 子页共用）：浅蓝渐变静止 + .tech-hero-stat 玻璃卡 rgba(255,255,255,.65)。\n` +
  `③ 关于我们 .about-hero（AboutUs.vue 双定义 :607+:923 已统一浅蓝渐变静止）。\n` +
  `④ liquidGlass.css 已有玻璃工具；motion.css 已有 blur-up 变体与 catch-all :not 链（新动画不得破坏它）。\n\n` +
  `要求：tokens（新增 keyframes/工具类放哪、命名）；heroDrift（三处 Hero 底色动态化的统一实现模式与参数）；groups 文件互斥分组（建议：A 全局动效基建 motion.css/theme.css/liquidGlass.css；B 首页 Home.vue+BrandSymbol.vue；C 技术服务 techservice.css；D 关于我们 AboutUs.vue），每组 tasks 可执行到类名/色值/动画参数；risks。`,
)
log(`设计师产出 ${spec.groups.length} 组方案`)

phase("评审定稿")
const reviewer = agent("玻璃方案评审员", {
  system: "你是方案评审员：实际 Read 方案涉及文件核对（现状描述是否属实、动画参数是否优雅不突兀、reduced-motion 兜底是否齐全、文件互斥、对比度不回退、性能合成层友好）。挑问题，不要客气地通过。",
})
for (let round = 1; round <= 2; round++) {
  const review = await reviewer.ask<PlanReview>(
    `审查这份液态玻璃+动态底方案（第 ${round} 轮）：${JSON.stringify(spec)}\n请实际 Read 涉及文件后判断。`,
  )
  if (review.approved) {
    log(`第 ${round} 轮评审通过`)
    break
  }
  log(`第 ${round} 轮评审提出 ${review.comments.length} 条意见`)
  spec = await designer.ask<GlassSpec>(
    `评审意见如下，逐条修订后返回完整新方案：\n${review.comments.map((c, i) => `${i + 1}. ${c}`).join("\n")}`,
  )
}

phase("并行实施各组")
log(`并行实施 ${spec.groups.length} 组`)
const impls: ImplResult[] = await Promise.all(
  spec.groups.map(async (g, i) => {
    try {
      const r = await agent(`实施·${g.title}`, {
        system: "你是资深前端工程师，执行液态玻璃+动态底改造：只动样式与模板装饰性结构，不改业务逻辑与接口调用。只允许修改任务给出的 files 与新建 newFiles 中列出的文件——其他代理并行改别的文件。所有新动画必须带 prefers-reduced-motion 兜底；遵循既有 token 体系。不要跑全量测试套件与完整构建（脚本统一跑）；范围外才能解决的 ok=false 并在 notes 说明。",
      }).ask<ImplResult>(
        `实施「${g.title}」。任务：${JSON.stringify(g.tasks)}\n允许修改：${JSON.stringify(g.files)}\n允许新建：${JSON.stringify(g.newFiles)}\n完成后返回 ImplResult（filesChanged 按实际、summary 写实现要点与自查方式）。`,
      )
      report({ title: g.title, status: r.ok ? "已修复" : "有遗留", detail: (r.summary || r.notes).slice(0, 110) }, "glass")
      return r
    } catch (e) {
      report({ title: g.title, status: "有遗留", detail: "实施异常待修复环节处理" }, "glass")
      return { group: g.title, filesChanged: [], summary: "", notes: "实施异常：" + String(e), ok: false } as ImplResult
    }
  }),
)
// 汇合点：门禁要等全部组落盘
log(`实施完成：${impls.filter(r => r.ok).length}/${impls.length} 组自报成功`)

phase("构建门禁")
let gateOk = false
let gateTail = ""
for (let round = 1; round <= 3; round++) {
  log(`第 ${round} 轮门禁：vitest + vite build`)
  const t = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "test"], { timeoutMs: 420000, output: { tail: 2500 } })
  const b = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "run", "build"], { timeoutMs: 420000, output: { tail: 1500 } })
  if (t.exitCode === 0 && b.exitCode === 0) {
    gateOk = true
    log(`第 ${round} 轮门禁通过`)
    break
  }
  gateTail = (t.stdout + t.stderr + b.stdout + b.stderr).slice(-1500)
  if (round === 3) {
    log("三轮修复后仍有失败")
    break
  }
  log(`第 ${round} 轮门禁失败，交修复工程师`)
  await agent("动效修复工程师").ask(
    `前端测试/构建失败，请修复（只动样式/动画/模板装饰代码；不要跑全量套件与构建，脚本会重跑）：\n${gateTail}`,
  )
}

phase("动效与可读性复核")
const checker = agent("动效复核员", {
  system: "你是复核员：Read 改造后的样式与页面源码复核——①三处 Hero 均有底色漂移动画且参数在 8-20s 区间、reduced-motion/perf-tier 兜底齐全；②液态玻璃参数（backdrop-filter/inset 高光/描边）与参照一致；③BrandSymbol 玻璃方块文字对比度（深字浅玻璃 ≥4.5:1）；④motion.css catch-all :not 链未被破坏；⑤对比度不回退（正文 ≥4.5:1）。也可以用浏览器 MCP 打开 http://localhost:5174/ 目检（若可用）。不要修改文件。",
})
const checks = await checker.ask<Finding[]>(
  `复核改造结果。方案：${JSON.stringify(spec)}\n实施结果：${JSON.stringify(impls)}\n` +
  `抽查：Home brand-hero/BrandSymbol、techservice .tech-hero/.tech-hero-stat、AboutUs .about-hero。每条 Finding：where/what/evidence/status/severity。`,
)

phase("汇总交付")
const okCount = impls.filter(r => r.ok).length
await artifact.markdown("report", [
  "# 液态玻璃 + 动态底色改造报告",
  "",
  `三处 Hero 底色动态化 + 液态玻璃质感 + BrandSymbol 画圈组件修复。${gateOk ? "测试与构建门禁通过。" : "门禁仍有失败项。"}`,
  "",
  "## 动效与玻璃规范",
  `- 底色漂移：${spec.heroDrift}`,
  `- Token/工具类：${spec.tokens}`,
  "",
  "## 各组改动",
  ...spec.groups.map(g => `- **${g.title}**：${g.tasks.slice(0, 2).join("；")}${g.tasks.length > 2 ? " 等" : ""}`),
  "",
  "## 复核结论",
  ...checks.map(f => `- ${f.where}：${f.what}（${f.status}）`),
].join("\n"), { title: "液态玻璃改造实施报告", description: "动态底色、液态玻璃质感与画圈组件修复的方案与复核结论。", primary: true })

return {
  conclusion: `完成三处 Hero 底色动态化 + 液态玻璃质感 + BrandSymbol 画圈组件可读性修复（${okCount}/${impls.length} 组成功），` +
    (gateOk ? "测试与构建门禁通过。" : "门禁仍有失败，需人工跟进。"),
  findings: [
    ...impls.filter(r => !r.ok).map(r => ({
      where: r.filesChanged.join(", ") || r.group,
      what: r.notes || "实施未完成",
      evidence: r.summary || "ok=false",
      status: "unconfirmed" as const,
      severity: "medium" as const,
    })),
    ...checks.filter(f => f.severity !== "low").map(f => ({
      where: f.where, what: f.what, evidence: f.evidence, status: f.status, severity: f.severity,
    })),
    ...(gateOk ? [] : [{
      where: "frontend", what: "构建/测试门禁三轮后仍失败", evidence: gateTail,
      status: "verified" as const, severity: "high" as const,
    }]),
  ],
  verified: [
    "cmd /c npm --prefix frontend test（vitest 全套）",
    "cmd /c npm --prefix frontend run build",
    "动效复核员逐项核对漂移参数/兜底/对比度",
    "方案经评审员至多两轮修订",
  ],
  notCovered: ["商户中心不在本轮；SVG displacement 高级折射方案如被选用需按实施 notes 单独验证性能"],
} as WorkflowReport
