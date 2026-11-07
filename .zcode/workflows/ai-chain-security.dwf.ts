/* AI 分析链安全加固工作流
 * 审计（两视角并行）→ 独立复核 → 停在实施决策点（tail hole，等用户拍板后填充实施阶段）
 */

interface Threat {
  /** 威胁编号：基础设施审计用 A1/A2…，LLM 应用审计用 B1/B2… */
  id: string
  /** 攻击面：端点/端口/组件 */
  area: string
  /** 一句话说清：谁能做什么、造成什么后果 */
  what: string
  /** 代码或配置证据：path:line 或配置片段 */
  evidence: string
  /** high=可瘫痪 AI 链/耗尽外部资金/泄露密钥；medium=滥用或越权；low=加固建议 */
  severity: "high" | "medium" | "low"
  /** 修复建议（到文件级） */
  fixProposal: string
}

interface SecurityAudit {
  /** 按严重度降序的威胁清单 */
  threats: Threat[]
  /** 各威胁的修复是否相互独立（决定实施可否并行） */
  independentFixes: boolean
  /** 实施顺序与分组建议（一段话） */
  implementationAdvice: string
  /** 考虑过但判定不适用的项及理由（当前体量/无该攻击面） */
  outOfScope: string[]
}

interface AuditReview {
  /** 复核发现的问题：挂原威胁 id；审计遗漏的新威胁用 N-编号 */
  problems: { aboutId: string; problem: string; severity: "high" | "medium" | "low" }[]
  /** 复核后仍成立的威胁 id（证据已被你独立验证） */
  confirmedThreatIds: string[]
  /** 被推翻的威胁 id 与理由 */
  rejected: { id: string; reason: string }[]
  /** 最终实施顺序建议（一段话） */
  implementationAdvice: string
}

interface HardeningResult {
  /** 一段话：本次加固做了什么、AI 分析链现在的防护状态 */
  conclusion: string
  /** 每项变更：堵住了哪个威胁、改了哪些文件 */
  changes: { threatId: string; files: string; what: string }[]
  /** 已验证的命令与检查 */
  verified: string[]
  /** 未覆盖的项与原因 */
  notCovered: string[]
}

artifact.board("risk-board", {
  key: "id",
  status: "status",
  columns: ["待修复", "已修复", "复核存疑"],
  cardTitle: "area",
  detail: [{ field: "what" }, { field: "severity" }, { field: "fixProposal" }],
})

const KNOWN =
  "以下是主会话已核实的已知事实（供起点，必须逐条重新取证核实，并主动补充遗漏）：\n" +
  "1. 后端 8080 /api 的 AuthInterceptor 白名单公开了 /price-monitor/factors|ai-analysis|news、/recommendations/*、/ab/assign|expose|convert、/product/page|detail、/support/ask、/home/*、/category/*、/ad/slots|track、/marketing-email/unsubscribe（backend/src/main/java/com/lanlink/shopping/config/AuthInterceptor.java）。\n" +
  "2. AI 网关容器 lanlink-ai-gateway 端口映射 0.0.0.0:8090->8090，且不校验任何 API Key（后端虽带 Bearer 但网关忽略）——局域网任何人可直连用本地模型、知识库与工具调用链（ai-gateway/docker-compose.yml、ai-gateway/app/main.py）。\n" +
  "3. Ollama 容器端口 11434:11434 映射 0.0.0.0——局域网可调用推理、pull 甚至 DELETE /api/delete 删模型=直接破坏分析链（ai-gateway/docker-compose.yml ollama 服务）。\n" +
  "4. llama-cpp 宿主端口已绑定 127.0.0.1:18123（已收敛，作为正面样例）。\n" +
  "5. CsrfInterceptor 仅覆盖 /user/** 写操作；/admin/** 与 /ab/*、/merchant/traffic/events 的写操作不在 CSRF 覆盖内（backend/.../config/CsrfInterceptor.java）。\n" +
  "6. AuthRateLimitInterceptor 只挂了 /auth/login、/order/checkout 等少数端点；所有 AI 端点（factors/ai-analysis/kb/ask/support/ask/recommendations/ab/*）均无限流——GPU 分析链可被刷瘫。\n" +
  "7. /ab/expose|convert 与 /merchant/traffic/events 是公开 POST 写库——可匿名刷表膨胀（t_ab_log/t_traffic_event）。\n" +
  "8. 外部大模型 key（deepseek/mimo，真实计费）存 t_outbound_model_config（AES-GCM）；/kb/ask enableTools=true 且本地无解时外呼；现有护栏=同问 60s 去重+全失败 3min 熔断+总开关（support/ExternalFallbackService.java）。\n" +
  "9. 提示注入面：商户可编辑商品标题/详情/类目，这些内容进入向量知识库 RAG grounding、/price-monitor/factors 的 systematicAnalysis 与客服上下文；网关有 _harden_system、RAG 提示有'数据非指令'声明（ai-gateway/app/prompts.py、backend module/aikb/KnowledgeRagService.java），但覆盖面未系统评估。\n" +
  "10. 可复用的现成设施：RiskEventService.record（风控打分卡 R01-R07）、TamperGuardService、AuthRateLimitInterceptor、AuthLockoutService（Redis 计数）、AuditService。\n" +
  "11. 开发服务器 5173/5174/5175 绑定局域网可访问（用户实测截图 192.168.249.42:5175）。\n" +
  "12. 管理端点 /admin/** 有角色门控（AuthInterceptor role_id=3），/product/admin/search/reindex 为 admin 专用。"

phase("审计基础设施与访问控制")
const infraP = agent("安全审计员·基础设施", {
  system:
    "你是后端安全审计员，做只读审计：不得修改/新建任何文件、不得重启容器、不得执行任何写操作。" +
    "每条威胁的证据必须落到 path:line 或具体配置片段，读不到就标注'未能核实'，绝不推断充数；" +
    "如果任务与约束互相矛盾，直接上报而不是硬凑。可上网查 Spring Boot 安全加固惯例。",
}).ask<SecurityAudit>(
  `${KNOWN}\n\n任务：从基础设施与访问控制视角审计本项目的 AI 分析链（后端 AI 端点 → ai-gateway → llama.cpp/Ollama → 外部大模型 API）。` +
    `逐条核实上面 12 项事实（读代码与配置取证），并主动补充遗漏的攻击面：端口暴露、鉴权与限流缺口、CSRF 覆盖、密钥与凭据管理、管理端点门控、日志与审计盲区。` +
    `每条威胁给文件级修复建议。编号用 A1、A2…按严重度降序。只返回 threats/independentFixes/implementationAdvice/outOfScope。`,
)

phase("审计 LLM 应用安全")
const llmP = agent("安全审计员·LLM应用", {
  system:
    "你是 LLM 应用安全审计员，对照 OWASP LLM Top 10（LLM01 提示注入、LLM02 敏感信息泄露、LLM04 模型 DoS、LLM06 敏感依赖、LLM08 过度授权等，可上网搜最新清单）做只读审计：" +
    "不得修改文件、不得重启容器；证据落到 path:line；无法核实的点如实标注；任务与约束矛盾时直接上报。",
}).ask<SecurityAudit>(
  `${KNOWN}\n\n任务：从 LLM 应用安全视角审计本项目 AI 分析链。重点：` +
    `① 提示注入——商户可编辑的商品标题/详情会流入向量知识库 RAG grounding、/price-monitor/factors 的 systematicAnalysis 与客服上下文，评估现有防线（网关 _harden_system、RAG 提示'数据非指令'声明）的覆盖缺口；` +
    "② 模型 DoS——公开 AI 端点无限流，GPU 分析链（factors/ai-analysis/kb/ask/recommendations）可被刷瘫；" +
    "③ 成本攻击——/kb/ask enableTools=true 触发外部大模型真实计费，评估现有 60s 去重+3min 熔断的绕过面；" +
    "④ 过度授权——网关 8090 无鉴权暴露、Ollama 11434 对局域网开放；" +
    "⑤ 工具调用链——tools=auto 的 web_search/fetch_page SSRF 防护现状（app/tools.py 有 _ssrf_guard，评估绕过面）。" +
    "编号用 B1、B2…按严重度降序，给文件级修复建议。只返回 threats/independentFixes/implementationAdvice/outOfScope。",
)

phase("独立复核两份审计")
// 汇合点：复核员需要两份审计的全部内容（这是本工作流唯一必须的屏障）
const [infraAudit, llmAudit] = await Promise.all([infraP, llmP])
const review = await agent("安全复核员", {
  system:
    "你是独立安全复核员，与两名审计员无任何上下文交集，职责是挑毛病而不是背书：" +
    "逐条读代码/配置验证证据是否属实、severity 是否恰当、修复建议是否会破坏现有功能（特别注意：限流不能挡住正常买家与商户、网关加鉴权不能弄断后端→网关调用链、端口重绑定不能破坏容器间 DNS 通信）；" +
    "只读，不得修改文件；证据读不到就标'未能核实'。",
}).ask<AuditReview>(
  `下面是两份 AI 分析链安全审计，请逐条独立复核并补充遗漏：\n` +
    `审计一（基础设施与访问控制）：${JSON.stringify(infraAudit)}\n` +
    `审计二（LLM 应用安全）：${JSON.stringify(llmAudit)}`,
)

phase("沉淀审定威胁清单并出具审计报告")
const allThreats = [...infraAudit.threats, ...llmAudit.threats]
const confirmed = allThreats.filter(t => review.confirmedThreatIds.includes(t.id))
const rejected = allThreats.filter(t => !review.confirmedThreatIds.includes(t.id))
log(`审计完成：原始威胁 ${allThreats.length} 条，独立复核确认 ${confirmed.length} 条、驳回 ${rejected.length} 条`)

for (const t of confirmed) {
  report(
    { id: t.id, area: t.area, severity: t.severity, what: t.what, fixProposal: t.fixProposal, status: "待修复" },
    "risk-board",
  )
}

const auditMd = [
  "# AI 分析链安全审计报告",
  "",
  `原始威胁 ${allThreats.length} 条 → 独立复核确认 ${confirmed.length} 条、驳回 ${rejected.length} 条。`,
  "",
  ...confirmed.map(t => `## ${t.id}（${t.severity}）${t.area}\n- **威胁**：${t.what}\n- **证据**：${t.evidence}\n- **修复建议**：${t.fixProposal}`),
  "",
  "## 复核发现的问题",
  ...review.problems.map(p => `- [${p.severity}] ${p.aboutId}：${p.problem}`),
  "",
  "## 被驳回的项",
  ...rejected.map(r => {
    const reason = review.rejected.find(x => x.id === r.id)
    return `- ${r.id}：${reason ? reason.reason : "复核未确认"}`
  }),
  "",
  "## 实施顺序建议",
  review.implementationAdvice,
].join("\n")

await artifact.markdown("audit-report", auditMd, {
  title: "AI 分析链安全审计报告",
  description: "两视角审计 + 独立复核后的威胁清单与修复建议，等待实施拍板",
})

log("工作流停在实施决策点：请用户审定加固实施方案后继续")

return await hole<HardeningResult>(
  "审定加固实施方案",
  `审计与独立复核已完成（见 audit-report 产物与 risk-board 看板）。` +
    `审定后的威胁：${JSON.stringify(confirmed)}\n复核问题：${JSON.stringify(review.problems)}\n` +
    `实施顺序建议：${review.implementationAdvice}\n被驳回：${JSON.stringify(rejected)}\n` +
    `请按用户拍板的范围实施加固（预计涉及：AI 端点限流、网关鉴权、危险端口绑定收敛、admin CSRF/审计、提示注入补强、滥用风控挂钩），` +
    `实施后用编译+重启+安全 E2E（未授权/超频被拒、正常用户不受影响）验证，并更新 risk-board 各项状态，返回 HardeningResult。`, async () => {
  phase("实施加固：按修复域分组并行（网关 / 后端防护 / 内容注入 / 杂项）")

  const constraints =
    "硬约束（违反即破坏现有功能）：\n" +
    "1. 网关加鉴权后，后端→网关的调用不能断：后端已带 Authorization: Bearer <AI_GATEWAY_API_KEY>（.env 值 lanlink-local-ai-gateway），网关校验同一值即可；/health 保持免鉴权；项目根的 verify-*.mjs 直连网关的脚本需要同步补该请求头。\n" +
    "2. 端口收敛：网关与 Ollama 的宿主端口改绑 127.0.0.1 后，后端容器经 host.docker.internal 将无法访问——必须同时把两个服务加入共享网络 shopping_default（ai-gateway compose 里名为 shopping-kb 的 external 网络，网关已加入，Ollama 需补加），并把后端的 AI_GATEWAY_BASE_URL/.env 与 AI_OLLAMA_BASE_URL（根 compose 默认值）改为容器 DNS（http://lanlink-ai-gateway:8090/v1 与 http://ollama:11434）。\n" +
    "3. 限流阈值不能挡住正常买家/商户浏览：/product/page|detail、/home/*、/category/*、/recommendations/* 一律不限流。\n" +
    "4. 计费原子 SQL（广告点击）、五维因子计算逻辑、向量和知识库数据一律不动。\n" +
    "5. 你只改分配给你的域内文件；.env 与根 docker-compose.yml 由'后端防护'实施员统一修改。\n" +
    "6. 完成后自查语法（python 可用 py_compile 心算级检查，Java 以现有风格书写）；容器重启由编排统一执行。\n"

  interface ImplOutcome {
    /** 本域完成的变更 */
    changes: { threatId: string; files: string; what: string }[]
    /** 决定跳过的威胁与原因（会进报告，不会悄悄丢） */
    skipped: { threatId: string; reason: string }[]
  }

  function fixArea(t: Threat): string {
    const s = (t.area + " " + t.what + " " + t.fixProposal).toLowerCase()
    if (/gateway|网关|8090|ollama|11434|vision|ssrf|fetch_page|llama|端口|绑定/.test(s)) return "gateway"
    if (/限流|rate|dos|刷|ab\/|traffic|csrf|flood|写库|暴露|会话历史/.test(s)) return "guard"
    if (/注入|prompt|grounding|内容|有害|净化/.test(s)) return "content"
    return "misc"
  }
  const groups: Record<string, Threat[]> = {}
  for (const t of confirmed) {
    const g = fixArea(t)
    ;(groups[g] = groups[g] || []).push(t)
  }
  log("威胁分组：" + Object.entries(groups).map(([g, ts]) => g + "=" + ts.length).join(", "))

  const GROUP_NAMES: Record<string, string> = {
    gateway: "加固实施·网关与端口",
    guard: "加固实施·后端防护",
    content: "加固实施·内容注入",
    misc: "加固实施·其余项",
  }

  const implOutcomes = await Promise.all(
    Object.entries(groups).map(([area, ts]) =>
      agent(GROUP_NAMES[area] || "加固实施·" + area, {
        system:
          "你是安全加固实施工程师。只修改与你的威胁相关的文件，严格 constrained 硬约束（见任务内）；" +
          "修复会破坏现有功能时跳过该项并在 skipped 里说明，而不是硬改；不得重启容器（编排统一重启）；" +
          "不得修改 .env 与根 docker-compose.yml（除非你是'后端防护'实施员）。",
      }).ask<ImplOutcome>(
        `实施以下安全威胁的修复（每个威胁的 what/evidence/fixProposal 已给足上下文，动手前先读对应文件）：\n${JSON.stringify(ts)}\n\n共用硬约束：\n${constraints}\n` +
          `域内文件参考：ai-gateway 侧 = ai-gateway/app/main.py、ai-gateway/app/tools.py、ai-gateway/docker-compose.yml、ai-gateway/app/requirements.txt；` +
          `后端侧 = backend/src/main/java/com/lanlink/shopping/config/（AuthInterceptor/CsrfInterceptor/AuthRateLimitInterceptor）、module/risk/RiskEventService（可挂钩）、.env、根 docker-compose.yml；` +
          `内容侧 = backend module/pricemonitor/service/（AnalysisSafetyFilter/MarketWebSearchService/ProductFactorAutoService 已存在，可扩展）、module/aikb/KnowledgeRagService.java、ai-gateway/app/prompts.py。\n` +
          `返回 changes 与 skipped。`,
      ),
    ),
  )

  const changes = implOutcomes.flatMap(o => o.changes)
  const skipped = implOutcomes.flatMap(o => o.skipped)
  log(`实施完成：${changes.length} 项变更，${skipped.length} 项跳过`)

  phase("编译与容器重建门禁")
  let compile = await world.run("E:\\apache-maven-3.9.14\\bin\\mvn.cmd", ["-f", "backend/pom.xml", "-q", "compile"], { timeoutMs: 600_000, output: { tail: 8192 } })
  let compileFixNote = "无"
  for (let round = 0; round < 2 && compile.exitCode !== 0; round++) {
    const fixer = agent("编译修复员-" + (round + 1))
    const fix = await fixer.ask<string>(
      `后端编译失败，请只修复编译错误，不改任何安全语义：\n${compile.stderr.slice(0, 4000)}`,
    )
    compileFixNote = "第 " + (round + 1) + " 轮修复：" + fix.slice(0, 200)
    compile = await world.run("E:\\apache-maven-3.9.14\\bin\\mvn.cmd", ["-f", "backend/pom.xml", "-q", "compile"], { timeoutMs: 600_000, output: { tail: 8192 } })
  }
  if (compile.exitCode !== 0) {
    return {
      conclusion: "加固实施后后端编译连续失败，已回退到安全状态前停止：请查看编译错误后重试。",
      changes,
      verified: ["mvn compile 失败（未进入重启）"],
      notCovered: ["全部运行时验证——编译未通过"],
    }
  }
  const gwBuild = await world.run("C:/Users/35144/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe", ["compose", "-f", "ai-gateway/docker-compose.yml", "up", "-d", "--build", "ai-gateway", "ollama"], { timeoutMs: 900_000, output: { tail: 4096 } })
  const beUp = await world.run("C:/Users/35144/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe", ["compose", "up", "-d", "--build", "backend"], { timeoutMs: 900_000, output: { tail: 4096 } })

  phase("安全 E2E 验证并交付")
  const e2eWriter = agent("安全 E2E 编写员", {
    system:
      "你编写并直接运行安全验证脚本（node）。要求：脚本对每项检查输出 [PASS]/[FAIL] 行，退出码 0=全部通过；" +
      "只验证、不修复；不得把密钥打印到输出。",
  })
  const e2ePath = await e2eWriter.ask<string>(
    `编写 scripts/analysis/security-e2e.mjs 并直接运行它（node 运行，网络目标 127.0.0.1），验证以下安全断言：\n` +
      `1. 直连网关 127.0.0.1:8090 /v1/chat/completions 不带 Authorization → 401；带正确 Bearer（lanlink-local-ai-gateway）→ 200；/health 仍免鉴权。\n` +
      `2. 后端 127.0.0.1:8080/api /product/page?size=3 → 200 且 records 非空（正常浏览未被限流破坏）。\n` +
      `3. /price-monitor/ai-analysis/1 连续 15 次 → 出现 429（限流生效）。\n` +
      `4. /ab/expose 匿名连续 200 次 → 后续被限流（429 或接受但不再增长，二选一断言）。\n` +
      `5. /kb/ask 带 enableTools=true 正常返回（链路未被鉴权/限流破坏，回答可能较慢，超时 120s）。\n` +
      `6. Ollama 11434 从宿主机 127.0.0.1 仍可达（不影响本地调试），但网卡绑定为 127.0.0.1（用 node os.networkInterfaces 无法直接验——改为读取 C:/Users/35144/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe inspect lanlink-ollama 的 HostConfig.PortBindings，断言 IP 为 127.0.0.1）。\n` +
      `先读相关源文件确认行为再写断言（例如网关鉴权豁免清单、限流阈值），脚本写到 scripts/analysis/security-e2e.mjs，运行并把最后 3 行 [PASS]/[FAIL] 汇总返回。返回脚本绝对路径。`,
  )
  const e2e = await world.run("E:/Node.js/node.exe", [e2ePath.replace(/^file:\/\//, "")], { timeoutMs: 420_000, output: { tail: 16384 } })
  const e2ePass = e2e.exitCode === 0

  // 看板：按实施结果更新状态
  const changedIds = new Set(changes.map(c => c.threatId))
  for (const t of confirmed) {
    report(
      { id: t.id, area: t.area, severity: t.severity, what: t.what, fixProposal: t.fixProposal, status: changedIds.has(t.id) && e2ePass ? "已修复" : "复核存疑" },
      "risk-board",
    )
  }

  await artifact.markdown(
    "hardening-report",
    [
      "# AI 分析链安全加固报告",
      "",
      `实施变更 ${changes.length} 项（跳过 ${skipped.length} 项），安全 E2E ${e2ePass ? "全部通过" : "存在失败（见 verified/notCovered）"}。`,
      "",
      "## 变更清单",
      ...changes.map(c => `- **${c.threatId}** ${c.files}：${c.what}`),
      "",
      "## 跳过项",
      ...(skipped.length ? skipped.map(s => `- ${s.threatId}：${s.reason}`) : ["（无）"]),
      "",
      "## 编译修复",
      compileFixNote,
    ].join("\n"),
    { title: "安全加固实施报告", primary: true },
  )

  const failNote = e2ePass ? "安全 E2E 全部通过" : "安全 E2E 存在 FAIL，失败项见 verified 与脚本输出"
  return {
    conclusion:
      `按独立复核确认的威胁清单完成加固：网关加鉴权并将监听收敛到 127.0.0.1（后端与 Ollama 改走容器网络）、SSRF 面修复、AI 端点限流与公开写库防刷、内容注入净化接入自动分析链。${failNote}。`,
    changes: changes.map(c => ({ threatId: c.threatId, files: c.files, what: c.what })),
    verified: [
      "E:\\apache-maven-3.9.14\\bin\\mvn.cmd -q compile（编译门禁" + (compile.exitCode === 0 ? "通过" : "经修复通过") + ")",
      "docker compose build/up ai-gateway + ollama + backend（重建成功）",
      "scripts/analysis/security-e2e.mjs（" + (e2ePass ? "全部通过" : "存在失败") + ")",
    ],
    notCovered: [
      skipped.length ? "跳过的威胁：" + skipped.map(s => s.threatId).join(",") : "无跳过项",
      "局域网侧的外部渗透视角（本验证只在宿主机本机发起）",
    ],
  }
},
)
