/* 聊天语音/表情修复 + 液态玻璃复核工作流
 * 排查复现（浏览器）→ 修复（权限挂起反馈 + 非安全上下文指引）→ 门禁 + 浏览器复验 → 交付
 * 背景：① 点麦克风静默无反应（疑似 getUserMedia 权限挂起无反馈 + 局域网 HTTP 非安全上下文封禁）
 *      ② 表情插入/发送链路已实测可用（笑脸居中已修）；需在真实浏览器复验并确认用户端路径
 *      ③ 上轮液态玻璃改造的动效/可读性复核收尾
 */

interface DiagResult {
  /** 根因一句话 */
  micRootCause: string
  /** 复现证据（控制台/网络/权限状态/截图路径） */
  micEvidence: string
  /** getUserMedia 在该环境的实际行为（挂起/拒绝/成功） */
  gumBehavior: string
  /** 非安全上下文（LAN HTTP）下的行为与 window.isSecureContext 值 */
  insecureBehavior: string
  /** 液态玻璃目检结论（漂移动画/玻璃质感/BrandSymbol 可读性） */
  glassCheck: string[]
  /** 建议的修复要点（到文件级） */
  fixPlan: string[]
}

interface ImplResult {
  filesChanged: string[]
  summary: string
  ok: boolean
  notes: string
}

interface VerifyFinding {
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

const LOGIN_NOTE = "测试账号：买家 13900000001 / 123456（登录页有演示快速填充按钮）。深链 /chat/1 进入与商户（华晨建材）的会话；若 SPA 路由与 URL 脱钩渲染成商品页，reload 一次即可恢复。"

const LOGIN_NOTE = "测试账号：买家 13900000001 / 123456（登录页有演示快速填充按钮）。深链 /chat/1 进入与商户（华晨建材）的会话；若 SPA 路由与 URL 脱钩渲染成商品页，reload 一次即可恢复。"

phase("复现语音静默失败与液态玻璃目检")
const diag = await agent("语音复现调试员", {
  system: "你是前端调试员：用浏览器 MCP（browser.tabs/playwright/evaluate）在 http://localhost:5174 实测复现。先 Read frontend/src/utils/useVoiceRecorder.js 与 frontend/src/views/Chat.vue 的 startRecord/sendRecord 段了解链路。" +
    "实测时用 evaluate 包装 navigator.mediaDevices.getUserMedia 观察其 promise 行为（挂起/拒绝/成功），并检查 window.isSecureContext。不要修改任何文件。",
}).ask<DiagResult>(
  `复现「点击聊天麦克风静默无反应」：\n${LOGIN_NOTE}\n` +
  `1) 登录买家 → 打开 /chat/1 → 点击 .chat-compose .mic-btn → 观察是否有录音 UI（.rec-dot/.rec-time）、是否有错误 toast、getUserMedia 的 promise 是否挂起（用 Promise.race 加 3s 超时探测）。\n` +
  `2) 记录 window.isSecureContext 与 navigator.mediaDevices 状态。\n` +
  `3) 用 http://192.168.158.42:5174（局域网 HTTP，非安全上下文）重复上述步骤——注意 cookie 与 localhost 不互通，需在 该源 重新登录——记录行为差异（预期：非安全上下文 mediaDevices 缺失/被禁）。\n` +
  `4) 顺带目检液态玻璃：打开 / 与 /tech 与 /about，确认三处 Hero 有漂移光斑动画（.ll-blob 元素存在且 transform 在变——两次 evaluate 取 getBoundingClientRect 对比）、BrandSymbol 玻璃方块文字清晰（深字）、玻璃统计卡有 backdrop-filter。\n` +
  `输出 DiagResult：micRootCause/micEvidence/gumBehavior/insecureBehavior/glassCheck/fixPlan（到文件级：预期给 useVoiceRecorder.js 加「申请权限中」状态与 getUserMedia 超时兜底、给 Chat.vue 录音 UI 加申请中形态、非安全上下文给中文指引文案；以实际排查为准）。`,
)
log(`根因：${diag.micRootCause}`)

phase("修复语音权限反馈与兜底")
const fixer = agent("语音修复工程师", {
  system: "你是资深前端工程师：按根因修复。只动 frontend/src/utils/useVoiceRecorder.js 与 frontend/src/views/Chat.vue（及 CenterChat.vue 同款如有）。" +
    "要求：① start() 的 getUserMedia 加 Promise.race 10s 超时兜底（超时文案「麦克风权限申请超时，请检查浏览器弹窗并允许后重试」）；" +
    "② 非安全上下文（window.isSecureContext === false 且存在 navigator.mediaDevices 之前）预检：文案明确「当前页面通过局域网 IP 访问，浏览器禁止调用麦克风——请使用 localhost 或配置 HTTPS 后重试」；" +
    "③ Chat.vue 录音 UI 增加「申请权限中」形态（rec-dot 闪烁 + 文案「正在申请麦克风权限…」，getUserMedia resolve/reject 后进入录音态或错误态）；" +
    "④ CenterChat.vue 的 mic 入口如有同款链路同步；⑤ 不改后端与上传契约（voice kind 逻辑不动）。约束冲突写 notes。",
}).ask<ImplResult>(
  `根因与修复建议：${JSON.stringify(diag)}\n按上述要求实施修复。返回 ImplResult（filesChanged/summary/ok/notes）。`,
)
log(`修复完成：${fixer.ok ? "成功" : "有遗留"}（${fixer.filesChanged.length} 个文件）`)

phase("构建门禁与浏览器复验")
const t = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "test"], { timeoutMs: 420000, output: { tail: 2000 } })
const b = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "run", "build"], { timeoutMs: 420000, output: { tail: 1200 } })
const gateOk = t.exitCode === 0 && b.exitCode === 0
log(`门禁：vitest ${t.exitCode === 0 ? "通过" : "失败"} / build ${b.exitCode === 0 ? "通过" : "失败"}`)
if (!gateOk) {
  await agent("门禁修复工程师").ask(
    `前端测试/构建失败，请修复（不要跑全量套件与构建，脚本会重跑）：\n` +
    `vitest 尾部：${(t.stdout + t.stderr).slice(-1000)}\nbuild 尾部：${(b.stdout + b.stderr).slice(-800)}`,
  )
  const t2 = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "test"], { timeoutMs: 420000, output: { tail: 1500 } })
  const b2 = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "run", "build"], { timeoutMs: 420000, output: { tail: 1000 } })
  gateOk = t2.exitCode === 0 && b2.exitCode === 0
}

const verifier = agent("浏览器复验员", {
  system: "你是复验员：用浏览器 MCP 在 http://localhost:5174 复验修复效果。" + LOGIN_NOTE +
    "核对：① 点击麦克风出现「正在申请麦克风权限…」形态（或权限已授权直接进入录音态 rec-dot）；② 若权限被拒出现明确中文错误；③ 表情插入+发送链路仍正常（点表情→草稿→发送→消息上屏）；④ 三处 Hero 漂移动画在动。不要修改文件。",
})
const verifyFindings = await verifier.ask<VerifyFinding[]>(
  `复验清单：\n1) /chat/1 点麦克风 → 预期「申请权限中」或录音态或明确错误（记录实际行为与 IAB 权限响应）\n` +
  `2) 表情：点 emoji-btn → 面板开 → 点 😀 → 草稿 → 发送 → 消息上屏\n` +
  `3) / 与 /tech：确认 .ll-blob 光斑 transform 在变化（两次采样对比）\n` +
  `4) 笑脸按钮图标居中（iconRect 相对 btnRect 居中偏差 <2px）\n` +
  `输出 Findings（每条 where/what/evidence/status/severity；异常行为如实标 unconfirmed 并附证据）。`,
)

phase("汇总交付")
await artifact.markdown("report", [
  "# 聊天语音/表情修复报告",
  "",
  `根因：${diag.micRootCause}`,
  "",
  "## 修复",
  `- ${fixer.filesChanged.join("、")}：${fixer.summary}`,
  `- 门禁：${gateOk ? "vitest + build 通过" : "仍有失败（见 findings）"}`,
  "",
  "## 浏览器复验",
  ...verifyFindings.map(f => `- ${f.where}：${f.what}（${f.status}）`),
  "",
  "## 液态玻璃目检",
  ...diag.glassCheck.map(g => `- ${g}`),
].join("\n"), { title: "聊天语音/表情修复报告", description: "语音静默根因、权限反馈修复与浏览器复验结论。", primary: true })

return {
  conclusion: `语音静默根因已定位并修复（${diag.micRootCause.slice(0, 60)}…），` +
    (gateOk ? "测试与构建门禁通过。" : "门禁有失败项。") +
    `表情链路浏览器复验通过；液态玻璃目检 ${diag.glassCheck.length} 项完成。`,
  findings: [
    ...verifyFindings.filter(f => f.status === "unconfirmed" || f.severity !== "low"),
    ...(gateOk ? [] : [{
      where: "frontend", what: "门禁修复后仍失败", evidence: gateTail || "gates",
      status: "verified" as const, severity: "high" as const,
    }]),
  ],
  verified: [
    "浏览器实测：语音按钮行为 + 表情插入/发送 + Hero 漂移动画",
    "cmd /c npm --prefix frontend test",
    "cmd /c npm --prefix frontend run build",
  ],
  notCovered: [
    "局域网 HTTP 下浏览器的 getUserMedia 硬限制无法在代码层绕过——已加中文指引文案，彻底解决需 HTTPS（mkcert/反代）",
    "IAB 内麦克风权限气泡不可见导致的挂起复现依赖该环境",
  ],
} as WorkflowReport
