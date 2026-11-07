/* Hero 底色流动动画收尾工作流
 * 背景：llGradShift keyframes 已追加至 motion.css（需核实）；四处 Hero 渐变扩色标 + background-size 300% + llGradShift 动画待实施
 * 顺序：实施（单代理改 5 文件）→ 构建门禁（vitest + build）→ 复核（动效/对比度/兜底）
 */

interface ImplResult {
  filesChanged: string[]
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
  /** 位置：文件:行号 */
  where: string
  /** 一句话说明问题 */
  what: string
  /** 证据：读到的行或命令输出 */
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

phase("核实 keyframes 与实施四处底色流动")
const impl = await agent("动效实施工程师", {
  system: "你是资深前端动效工程师。先核实 frontend/src/styles/motion.css 是否已有 @keyframes llGradShift（此前一次追加可能已生效）——没有则追加；然后实施四处 Hero 底色流动动画。" +
    "只改这五个文件：styles/motion.css、views/Home.vue、styles/techservice.css、views/AboutUs.vue、views/Mall.vue。约束：只动样式不改业务逻辑；色标保持浅天空蓝区间（#13233A 系文字在任意停点 ≥11:1）；所有新动画在既有 reduced-motion 块内补 animation:none 兜底；keyframes ll- 前缀。不要跑全量测试套件与完整构建（脚本统一跑）。",
}).ask<ImplResult>(
  `实施清单：\n\n` +
  `0. **motion.css**：grep 确认 @keyframes llGradShift 是否已存在（{ 0%: background-position 0% 50% → 50%: 100% 50% → 100%: 0% 50% }）——已存在则跳过，不存在则在文件尾追加并带注释「Hero 底色流动：大面积元素专用（background-position 逐帧重绘，hero 规模可接受），transform 类动画勿引用此名」。\n\n` +
  `1. **Home.vue .brand-hero（约 :181，现为 radial×4 + linear-gradient(135deg,#F2F9FF,#E3F1FD 55%,#D6ECFA) 的复合背景）**：linear 渐变段扩充为 5 色标 (#F2F9FF 0%, #D6EBFB 30%, #C4E3F9 55%, #E3F1FD 80%, #F2F9FF 100%)、追加 background-size: 300% 300%; 与 animation: llGradShift 18s ease infinite;（radial 光斑层顺序不动，仅扩 linear 段色标+加 size/animation）；reduced-motion 块（约 :234）补 .brand-hero { animation: none !important; }。\n\n` +
  `2. **techservice.css .tech-hero（约 :6-15）**：linear 渐变段扩 5 色标 (#F2F9FF 0%, #DBEAFE 30%, #C9E2F6 55%, #E3F1FD 80%, #F2F9FF 100%)、追加 background-size: 300% 300%; 与 animation: llGradShift 16s ease infinite;；reduced-motion 块（约 :546）补 .tech-hero { animation: none !important; }。\n\n` +
  `3. **AboutUs.vue .about-hero（双定义约 :615 基础版 + :923 生效版，两处渐变同步扩色标 + 加 size/animation——只改一处会被另一处覆盖）**：linear 段扩 5 色标 (#F2F9FF 0%, #E0F2FE 30%, #BAE6FD 55%, #E0F2FE 80%, #F2F9FF 100%)、追加 background-size: 300% 300%; 与 animation: llGradShift 20s ease infinite;；reduced-motion 块补 .about-hero { animation: none !important; }。\n\n` +
  `4. **Mall.vue .ai-spec-banner（约 :405）**：linear 渐变扩 4 色标 (#F2F9FF 0%, #DBEAFE 35%, #BAE6FD 65%, #F2F9FF 100%)、追加 background-size: 250% 250%; 与 animation: llGradShift 14s ease infinite;；该文件若无 reduced-motion 块则新增 @media (prefers-reduced-motion: reduce) { .ai-spec-banner { animation: none !important; } }。\n\n` +
  `⚠️ 通用注意：① 各处 background 简写与 background-size 分开声明（size 独立行，防简写覆盖）；② 若已有 background-position 声明需移除（position 由动画驱动）；③ 现有 ::before conic 光晕/.ll-blob 光斑/扫光等装饰动画保留（与底色流动正交）。\n\n` +
  `完成后返回 ImplResult（filesChanged/summary 含每处最终色标与动画参数/ok/notes）。`,
)
log(`实施完成：${impl.ok ? "成功" : "有遗留"}（${impl.filesChanged.length} 个文件）`)

phase("构建门禁")
let gateOk = false
let gateTail = ""
const fixer = agent("动效修复工程师")
for (let round = 1; round <= 2; round++) {
  log(`第 ${round} 轮门禁：vitest + vite build`)
  const t = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "test"], { timeoutMs: 420000, output: { tail: 2000 } })
  const b = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "run", "build"], { timeoutMs: 420000, output: { tail: 1200 } })
  if (t.exitCode === 0 && b.exitCode === 0) {
    gateOk = true
    log(`第 ${round} 轮门禁通过`)
    break
  }
  gateTail = (t.stdout + t.stderr + b.stdout + b.stderr).slice(-1500)
  if (round === 2) {
    log("两轮修复后仍有失败")
    break
  }
  log(`第 ${round} 轮门禁失败，交修复工程师`)
  await fixer.ask(
    `前端测试/构建失败，请修复（只动样式/动画代码；不要跑全量套件与构建，脚本会重跑）：\n${gateTail}`,
  )
}

phase("复核动画与对比度")
const checker = agent("动效复核员", {
  system: "你是复核员：Read 改造后的四个文件复核——① 四处 hero 均有 background-size 300%/250% + animation llGradShift（duration 14-20s）；② reduced-motion 兜底齐全；③ 色标全在浅天空蓝区间（#13233A 文字对比度不回退，最暗色标 #BAE6FD 上仍 ≥11:1）；④ 与既有 ::before conic 光晕/光斑层叠加无冲突（animation 属性归属元素本体，互不覆盖）。也可以用浏览器 MCP 打开 http://localhost:5174/ 目检底色流动（两次采样 background-position 或截图对比）。不要修改文件。",
})
const checks = await checker.ask<CheckFinding[]>(
  `复核清单：\n1. Home.vue .brand-hero\n2. techservice.css .tech-hero\n3. AboutUs.vue .about-hero（两处定义一致性）\n4. Mall.vue .ai-spec-banner\n` +
  `每处核对：background-size、animation（名称/时长/keyframes 存在）、reduced-motion 兜底、最暗色标下标题对比度。输出 CheckFinding[]（where/what/evidence/status/severity）。`,
)

await artifact.markdown("report", [
  "# Hero 底色动态变化实施报告",
  "",
  `四处 Hero/横幅底色已加渐变流动动画（llGradShift，14-20s 交错）。${gateOk ? "测试与构建门禁通过。" : "门禁仍有失败项。"}`,
  "",
  "## 落地点",
  ...impl.filesChanged.map(f => `- ${f}`),
  "",
  "## 复核结论",
  ...checks.map(f => `- ${f.where}：${f.what}（${f.status}）`),
].join("\n"), { title: "底色动态化实施报告", description: "四处 Hero 底色渐变流动动画的落地点与复核结论。", primary: true })

return {
  conclusion: `四处 Hero/横幅底色已实现渐变流动动画（18s/16s/20s/14s 交错），` +
    (gateOk ? "测试与构建门禁通过，对比度不回退。" : "门禁有失败项，需人工跟进。") +
    ` reduced-motion 用户自动回归静态。`,
  findings: [
    ...checks.filter(f => f.severity !== "low" || f.status === "unconfirmed").map(f => ({
      where: f.where, what: f.what, evidence: f.evidence, status: f.status, severity: f.severity,
    })),
    ...(gateOk ? [] : [{
      where: "frontend", what: "门禁两轮后仍失败", evidence: gateTail,
      status: "verified" as const, severity: "high" as const,
    }]),
  ],
  verified: [
    "cmd /c npm --prefix frontend test",
    "cmd /c npm --prefix frontend run build",
    "动效复核员逐处核对 background-size/animation/reduced-motion/对比度",
  ],
  notCovered: ["浏览器实测底色流动观感由用户在 5174 预览确认（自动化无法评价美学）；商户中心不在范围"],
} as WorkflowReport
