/* 前台 UI 小清新天空蓝改造工作流
 * 截图排查（2 审计员并行）→ 21st.dev 参照（1 研究员）→ 设计师方案 → 评审定稿 → 并行实施 → 构建门禁 → 对比度复核
 */

interface Issue {
  /** 页面与路由 */
  page: string
  /** 具体元素 */
  element: string
  /** 一句话问题 */
  problem: string
  /** 当前实现（色值/类名/文件位置） */
  current: string
  severity: "high" | "medium" | "low"
}

interface AuditResult {
  issues: Issue[]
  notes: string[]
}

interface DesignRef {
  name: string
  source: string
  visualNotes: string
  applicableTo: string
}

interface ChangeGroup {
  /** 分组名（实施代理名） */
  group: string
  title: string
  /** 本组独占修改的文件（与其他组互不相交） */
  files: string[]
  newFiles: string[]
  /** 具体改法（色值/字级/对比度目标） */
  tasks: string[]
}

interface DesignSpec {
  palette: string
  typography: string
  groups: ChangeGroup[]
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

artifact.board("fixes", {
  title: "可读性修复看板",
  key: "title",
  status: "status",
  columns: ["待修", "已修复"],
  cardTitle: "title",
  detail: [{ field: "detail", label: "说明" }],
})

const SHOTS = [
  "C:\\Users\\35144\\.zcode\\cli\\image-cache\\session_460dc2025d88c704d503e0eba4d9585a\\image-49e102854b9b3d856cf1ffb66636327a.png",
  "C:\\Users\\35144\\.zcode\\cli\\image-cache\\session_460dc2025d88c704d503e0eba4d9585a\\image-08e2fd513be09b4ce1a30d7e2f3641a4.png",
  "C:\\Users\\35144\\.zcode\\cli\\image-cache\\session_460dc2025d88c704d503e0eba4d9585a\\image-78f38490d17192511307075fb7af42da.png",
  "C:\\Users\\35144\\.zcode\\cli\\image-cache\\session_460dc2025d88c704d503e0eba4d9585a\\image-40e6e6c71d0130ff90906293a6f528ba.png",
  "C:\\Users\\35144\\.zcode\\cli\\image-cache\\session_460dc2025d88c704d503e0eba4d9585a\\image-4a86d18661686e33793bb6e35d638831.png",
  "C:\\Users\\35144\\.zcode\\cli\\image-cache\\session_460dc2025d88c704d503e0eba4d9585a\\image-a9092f188f2f231ea76c9087e5be0e10.png",
  "C:\\Users\\35144\\.zcode\\cli\\image-cache\\session_460dc2025d88c704d503e0eba4d9585a\\image-b5f32f8680d721096898b39e1c123950.png",
]
const SHOT_HINTS = [
  "首页 Hero（用户红框标注：浅蓝渐变底上的元素对比度问题）",
  "客服弹窗（红框：头部小字被半透明蓝底遮盖，几乎看不清）",
  "技术服务 Hero（白字大标题在浅蓝底上对比度极低、副文案几乎不可见、ghost 按钮白字看不清）",
  "技术服务·八大服务卡（白字标题/文案在浅蓝底上看不清）",
  "技术服务·政策专区 CTA（白色大字+浅白文案看不清）",
  "关于我们 Hero（深海军蓝底——用户点名改掉，换小清新天空蓝）",
  "关于我们·平台定位卡（深色系卡片）",
]
const DESIGN_BRIEF =
  "设计基准：苹果 HIG（清晰层级、留白、系统字重），底色改小清新天空蓝（如 #E8F4FD/#DBEAFE 级浅底），文字一律深色墨水字（#0F172A 系）保正文对比度 ≥4.5:1；" +
  "深色蓝底（#13233A/#1F3864 大块 Hero 底）全部改浅；强调色保留天蓝 #38BDF8/#0EA5E9 点缀；字体只调字号/字重/行高层级，不换字体家族；" +
  "禁 emoji、图标用 lucide；只动样式与模板文案类，不改业务逻辑；遵循仓库 --ll-* CSS 变量体系（theme.css）。"

phase("逐页排查可读性问题")
const auditA = agent("视觉审计员·首页与客服", {
  system: "你是前端可读性审计员：用 Read 打开用户截图逐张核对，再打开对应 Vue/CSS 源码定位问题元素的类名与色值。只报告真实存在的对比度/遮盖问题，给出精确 file:line。不要修改任何文件。",
})
const auditB = agent("视觉审计员·技术服务与关于", {
  system: "你是前端可读性审计员：用 Read 打开用户截图逐张核对（技术服务页的白字浅底问题最严重），再打开对应 Vue/CSS 源码定位问题元素与色值，并顺带扫描其余前台页面同类「浅底白字/深蓝大底」模式。不要修改任何文件。",
})
const auditAP = auditA.ask<AuditResult>(
  `审计截图（用 Read 逐张看）：\n${SHOTS.slice(0, 2).map((s, i) => `${i + 1}. ${s}（${SHOT_HINTS[i]}）`).join("\n")}\n工作区：Shopping。重点页面：首页 Home.vue（Hero）、客服弹窗 SupportChat.vue（头部遮字）、全局导航。对照源码定位每个问题元素的类名/色值/file:line，输出 issues 与 notes。`,
)
const auditBP = auditB.ask<AuditResult>(
  `审计截图（用 Read 逐张看）：\n${SHOTS.slice(2).map((s, i) => `${i + 1}. ${s}（${SHOT_HINTS[i + 2]}）`).join("\n")}\n工作区：Shopping。重点页面：技术服务页（白字浅底最严重）、关于我们 About.vue（深蓝 Hero 改浅）、政策专区；再扫描直播/账期/订单/活动等页同类问题。对照源码定位 file:line，输出 issues 与 notes。`,
)

phase("爬取 21st.dev 设计参照")
const refP = agent("21st.dev 研究员", {
  system: "你负责去 21st.dev 收集设计参照：Hero 区、浅色渐变底、玻璃拟态卡片、小清新天空蓝变体。用 WebFetch/WebSearch 抓 21st.dev 组件页，提炼可翻译成 CSS 的要点（色值、渐变角度、光斑、字级层级、入场动效）。引用给 URL。",
})
const refR = refP.ask<DesignRef[]>(
  `为「LANLinkshopping 前台 UI 小清新天空蓝改造」收集 4-6 个设计参照。${DESIGN_BRIEF}\n适用页面：首页 Hero、技术服务 Hero、关于我们 Hero、政策 CTA、服务卡片区。`,
)

const [issuesA, issuesB, designRefs] = await Promise.all([auditAP, auditBP, refR])
const allIssues = [...issuesA.issues, ...issuesB.issues]
log(`排查完成：${allIssues.length} 个可读性问题，设计参照 ${designRefs.length} 个`)
for (const it of allIssues.filter(x => x.severity === "high")) {
  report({ title: it.page + " · " + it.element, status: "待修", detail: it.problem.slice(0, 110) }, "fixes")
}

phase("制定设计规范与改动清单")
const designer = agent("设计师", {
  system: "你是资深 UI 设计工程师：把问题清单与参照翻译成可执行的文件级改动清单。先 Read 涉及源码确认可落地；文件分组必须互不相交（并行实施）。色值必须给对比度承诺（正文 ≥4.5:1）。约束冲突如实写进 risks。",
})
let spec = await designer.ask<DesignSpec>(
  `制定「小清新天空蓝 + Apple 基准」改造方案。${DESIGN_BRIEF}\n\n【问题清单】${JSON.stringify(allIssues)}\n\n【设计参照】${JSON.stringify(designRefs)}\n\n要求：1) palette/typography 给统一规范（色值+对比度承诺+字级层级）；2) groups 按文件互斥分组（全局样式组/首页客服组/技术服务组/关于我们组/其余页面组），tasks 可执行到类名与色值；3) 全局样式组负责 --ll-* 变量与共享组件，页面组只改各自页面。`,
)
log(`设计师产出 ${spec.groups.length} 个实施分组`)

phase("评审定稿")
const reviewer = agent("设计评审员", {
  system: "你是设计评审员：实际 Read 方案涉及的文件核对可落地性（类名存在、色值合理、分组互斥、对比度达标），挑会出问题的地方，不要客气地通过。",
})
for (let round = 1; round <= 2; round++) {
  const review = await reviewer.ask<PlanReview>(
    `审查这份 UI 改造方案（第 ${round} 轮）：${JSON.stringify(spec)}\n请实际 Read 涉及文件后判断。`,
  )
  if (review.approved) {
    log(`第 ${round} 轮评审通过`)
    break
  }
  log(`第 ${round} 轮评审提出 ${review.comments.length} 条意见`)
  spec = await designer.ask<DesignSpec>(
    `评审意见如下，逐条修订后返回完整新方案：\n${review.comments.map((c, i) => `${i + 1}. ${c}`).join("\n")}`,
  )
}

phase("并行实施各组改动")
log(`并行实施 ${spec.groups.length} 组改动`)
const impls: ImplResult[] = await Promise.all(
  spec.groups.map(async (g, i) => {
    try {
      const r = await agent(`实施·${g.group}`, {
        system: "你是资深前端工程师，执行 UI 改造：只动样式、模板结构与文案类，不改业务逻辑与接口调用。只允许修改任务给出的 files 与新建 newFiles 中列出的文件——其他代理并行改别的文件。遵循苹果基准与天空蓝色板。不要跑全量测试套件与完整构建（脚本统一跑）；若范围外才能解决，ok=false 并在 notes 说明。",
      }).ask<ImplResult>(
        `实施「${g.title}」。任务：${JSON.stringify(g.tasks)}\n允许修改：${JSON.stringify(g.files)}\n允许新建：${JSON.stringify(g.newFiles)}\n完成后返回 ImplResult（filesChanged 按实际、summary 写实现要点与自查方式）。`,
      )
      report({ title: g.title, status: r.ok ? "已修复" : "有遗留", detail: (r.summary || r.notes).slice(0, 110) }, "fixes")
      return r
    } catch (e) {
      report({ title: g.title, status: "有遗留", detail: "实施异常待修复环节处理" }, "fixes")
      return { group: g.group, filesChanged: [], summary: "", notes: "实施异常：" + String(e), ok: false } as ImplResult
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
  await agent("样式修复工程师").ask(
    `前端测试/构建失败，请修复（只动样式与模板相关代码，不改业务逻辑；不要跑全量套件与构建，脚本会重跑）：\n${gateTail}`,
  )
}

phase("对比度复核")
const checker = agent("对比度复核员", {
  system: "你是可读性复核员：Read 改造后的样式与页面源码，抽查「深色文字/浅色底」关键组合的实际色值并按 WCAG 相对亮度公式计算对比度，逐个给出数值；发现 <4.5:1 的正文组合如实列出。也可以用浏览器 MCP 打开 http://localhost:5174/ 对应页面目检（若可用）。不要修改文件。",
})
const contrast = await checker.ask<Finding[]>(
  `复核改造后的可读性。设计承诺：${JSON.stringify(spec.palette)}\n抽查范围：${JSON.stringify(spec.groups.map(g => g.files))} 中的 Hero/卡片/按钮关键组合。每条 Finding：where=页面+元素，what=对比度数值与是否达标，evidence=色值与计算过程。`,
)

phase("汇总交付")
const okCount = impls.filter(r => r.ok).length
await artifact.markdown("report", [
  "# 前台 UI 小清新天空蓝改造报告",
  "",
  `按 7 张用户截图排查出 ${allIssues.length} 个可读性问题，参照 21st.dev 与苹果基准完成 ${spec.groups.length} 组改造。${gateOk ? "测试与构建门禁通过。" : "门禁仍有失败项。"}`,
  "",
  "## 统一规范",
  `- 配色：${spec.palette}`,
  `- 字体：${spec.typography}`,
  "",
  "## 各组改动",
  ...spec.groups.map(g => `- **${g.title}**：${g.tasks.slice(0, 2).join("；")}${g.tasks.length > 2 ? " 等" : ""}`),
  "",
  "## 对比度复核",
  ...contrast.map(f => `- ${f.where}：${f.what}（${f.status}）`),
].join("\n"), { title: "UI 改造实施报告", description: "可读性问题排查、天空蓝改造方案与对比度复核结论。", primary: true })

return {
  conclusion: `排查出 ${allIssues.length} 个可读性问题，完成 ${spec.groups.length} 组天空蓝+Apple 基准改造（${okCount}/${impls.length} 组成功），` +
    (gateOk ? "测试与构建门禁通过，" : "门禁仍有失败，") + `对比度复核 ${contrast.filter(f => f.status === "verified").length}/${contrast.length} 条已确认。`,
  findings: [
    ...impls.filter(r => !r.ok).map(r => ({
      where: r.filesChanged.join(", ") || r.group,
      what: r.notes || "实施未完成",
      evidence: r.summary || "ok=false",
      status: "unconfirmed" as const,
      severity: "medium" as const,
    })),
    ...contrast.filter(f => f.severity !== "low").map(f => ({
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
    "对比度复核员逐组合计算 WCAG 对比度",
    "方案经评审员至多两轮修订",
  ],
  notCovered: ["商户中心（--mc-* 体系）不在本轮范围；改造后页面的浏览器全页目检由用户在 5174 预览确认"],
} as WorkflowReport
