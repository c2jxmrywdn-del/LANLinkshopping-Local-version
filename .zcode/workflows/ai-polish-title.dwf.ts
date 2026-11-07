/* 商品发布「AI 润色」商品名（调研设计先行，实施尾洞串行等待）
 * 需求：① 发布商品表单加「AI 润色」按钮——LLM 润色商品名；② 润色后的商品要能被外部大模型检索到
 *      （aitool 模块的 search_products 工具链——润色必须保留型号/规格/材质等可检索关键词）。
 * 约束：退款+地图批次 dwfrun-2190d284 正在实施（zh-en-extra.js 同文件 + 构建竞态）——
 *      本工作流只读+写 out/，实施尾洞等其清零后由主会话填充。
 */

interface PublishAudit {
  /** 发布表单 UI 位置（标题输入框所在页面与行）与发布/更新端点 */
  publishFlow: string
  /** 标题校验规则（ValidationRules 长度/字符约束） */
  titleRules: string
  /** 可复用的 AI 调用基建：渠道链（mimosa 本地网关优先/90s 超时/熔断）、限流配额闸、审计落库的模式与位置 */
  aiInfra: string
  /** 接入建议（到文件级：按钮位置/端点形态/复用清单） */
  fixPlan: string[]
  notes: string
}

interface SearchAudit {
  /** aitool 模块外部检索链路：工具清单、search_products 执行路径与匹配语义（标题匹配方式） */
  searchChain: string
  /** 守卫链（AiToolGuard 等）与鉴权 */
  guards: string
  /** 润色标题对可检索性的影响与约束建议（保留哪些关键词、改写边界） */
  discoverability: string
  notes: string
}

interface DesignDoc {
  path: string
  /** 端点契约一句话 */
  contract: string
  /** 润色提示词与可检索性约束一句话 */
  promptSpec: string
  /** 实施阶段 */
  phases: string[]
  /** 需用户拍板 */
  decisions: string[]
}

interface ReviewResult {
  approved: boolean
  issues: string[]
}

interface ImplSummary {
  fixed: string[]
  skipped: string[]
  gates: string
}

phase("并行审计发布链路与外部检索链路")
const [pub, search] = await Promise.all([
  agent("商品发布审计员", {
    system: "你是只读审计员（不改文件）：盘点商品发布链路与可复用的 AI 基建。线索：商户发布商品表单（views/merchant/CenterProducts.vue 或商品发布页——找标题输入框）、ProductController publish/myUpdate 端点、ValidationRules 标题约束、AI 调用基建（module/pricemonitor/service/PriceMonitorAiService 的渠道链 orderedChannels/mimosa 本地网关 :8090 优先/90s 超时/2min 熔断、OutboundModelConfigService、1030 批次的 AI 外呼限流+小时配额闸 PRICE_AI_HOURLY_QUOTA=120 与 AI 调用审计落库模式）。注意退款+地图批次正在改 MyOrders/地图/zh-en-extra——与本审计范围不冲突，只读不受影响。",
  }).ask<PublishAudit>(
    "输出 PublishAudit：publishFlow（表单位置 path:line + 端点）/titleRules/aiInfra（可复用件清单与位置）/fixPlan（按钮放哪、端点形态建议 POST /merchant/ai/polish-title 类、复用哪些守卫）。中文。",
  ),
  agent("外部检索审计员", {
    system: "你是只读审计员（不改文件）：盘点外部大模型检索商品的链路。线索：module/aitool/——AiToolsInternalController 与 /v1/ai/tools 工具清单、AiToolExecutionService 的 search_products 执行（匹配语义：标题 LIKE？分词？）、AiToolGuard 守卫、OpenAI function calling 契约（外部 LLM 携带 key 调用）。回答核心问题：商品名润色改写后，外部模型的 search_products 还能不能找到该商品——匹配对标题的哪些部分敏感。",
  }).ask<SearchAudit>(
    "输出 SearchAudit：searchChain（执行路径 path:line + 匹配语义）/guards/discoverability（润色改写边界建议：必须保留型号/规格/材质/品牌等可检索 token；避免同义词替换核心名词）。中文。",
  ),
])
log("审计完成：发布链路 + 外部检索链路")

phase("方案成稿")
const designer = agent("AI 功能设计师", {
  system: "你是资深全栈设计师：综合两路审计写中文设计方案到 out/ai-polish-design.md（Write 工具）。必含：① UI——发布表单标题输入框旁「AI 润色」按钮（Lucide 图标、loading 态、候选卡 2-3 个可点选应用/重试/放弃、应用后回填输入框走正常发布流程）；② 端点契约——商户权限（merchant:manage 类）、请求（当前标题+类目上下文）与响应（candidates 数组）、复用既有 AI 外呼限流+小时配额闸+审计落库（配额独立键 ai-polish）、渠道链降级（mimosa 网关优先→外部→规则兜底=返回失败提示按钮禁用态，不虚构结果）；③ 提示词规范——B2B 商品标题惯例、**强制保留型号/规格/材质/品牌等可检索关键词**（外部模型 search_products 可发现性约束，改写只动营销修饰语）、长度 obey ValidationRules、输出 JSON candidates；④ 不动价格/详情/审核流（润色仅标题草稿，商户确认后才入表单）；⑤ 测试（端点校验/配额/降级 + store 或 util 单测）、i18n（先 grep zh-en-extra 防重）、手机断点、token 化；⑥ 分阶段与文件级清单；⑦ 需拍板项单列（如候选数、配额阈值、是否同步润色商品副标题）。",
})
let design = await designer.ask<DesignDoc>(
  "发布链路审计：" + JSON.stringify(pub) + "\n外部检索审计：" + JSON.stringify(search) + "\n写成方案到 out/ai-polish-design.md 并返回 DesignDoc。",
)
log("方案成稿：" + design.contract)

phase("独立评审定稿")
const reviewer = agent("方案评审员", {
  system: "你是独立评审员：Read out/ai-polish-design.md 全文，专挑问题——可检索性约束是否具体可执行（只说'保留关键词'不落到提示词=打回）、降级是否虚构结果、配额/审计是否真复用既有件还是重造轮子、权限是否商户隔离（A 商户不能润色 B 商户草稿）、候选应用是否绕过标题校验、i18n/测试/移动端是否遗漏。需要证据，反对是常态。不改文件。",
})
for (let round = 1; round <= 2; round++) {
  const review = await reviewer.ask<ReviewResult>(
    "评审 " + design.path + "（第 " + round + " 轮）：" + (round > 1 ? "上轮问题已修订，复核是否闭合并继续挑新问题。" : "全面挑问题。"),
  )
  if (review.approved) {
    log("评审通过（第 " + round + " 轮）")
    break
  }
  if (round === 2) {
    log("两轮评审后仍有未闭合问题，如实入 decisions")
    design.decisions = [...design.decisions, ...review.issues.map(i => "评审遗留：" + i)]
    break
  }
  log("评审提出 " + review.issues.length + " 个问题，交设计师修订")
  design = await designer.ask<DesignDoc>(
    "评审意见（逐条修订 " + design.path + " 并在文中回应）：" + JSON.stringify(review.issues) + "\n修订后重新返回 DesignDoc。",
  )
}

return await hole<ImplSummary>(
  "实施 AI 润色商品名",
  "方案已定稿：" + design.path + "（契约：" + design.contract + "；提示词与可检索性：" + design.promptSpec + "）。需拍板：" + design.decisions.join("；") + "\n" +
  "填充本洞时定义实施拓扑。前置确认：退款+地图批次 dwfrun-2190d284 已完成（zh-en-extra.js 与构建竞态解除）；实施含后端端点与前端按钮，门禁 vitest+build+本机 mvn test，部署前后端镜像并运行时复测（含外部模型 search_products 用润色后标题实测命中）。", async () => {
  phase("落地 AI 润色端点与发布按钮")
  const impl = await agent("AI 润色实施工程师", {
    system: "你是资深全栈工程师：按 out/ai-polish-design.md 实施（先通读全文与 §6 评审回应表——上轮 13 条闭合确认已在文中）。拍板定案：① 权限码 product:publish（/product 兄弟端点先例）；② 候选数契约保持 [1,3]（1 条也返回，务实优先）；③ 配额商户级 AI_POLISH_USER_HOURLY_QUOTA=10/时 + 全局 AI_POLISH_HOURLY_QUOTA=60/时（独立键 ai-polish）；④ 副标题/详情导语润色本期不做；⑤ temperature 0.4 与三条风格差异照设计；⑥ 失败冷却不对齐照设计（前端 60s 展示独立于后端 2min 熔断，失败文案注明稍后再试），对齐改造列 skipped；⑦ 标题 2-100 提升进 ValidationRules 与 docs/数据校验规则契约.md 重建列 P2（本轮只修 ValidationRules.java:12 悬空 javadoc 指向——把该 javadoc 改为准确描述或删除悬空引用）。实现要点照设计：抽公共组件 support/AiQuotaGate + support/AiChannelInvoker（PriceMonitorAiService 迁移列 P2 不动稳定链路）；POST /api/product/ai/polish-title 纯文本进出无 productId；三登记（POLICIES /product/ai/* Policy(10,120) 前缀匹配语义注意勿抄失效的 /** 键 + WebConfig 注册 /product/ai/** + 前后端 CSRF 双端）；mimosa 优先最多 3 渠道 30s 超时→全失败 2min 熔断，前端 silent:true + 面板失败条单一展示 + 按钮禁用，绝不虚构结果；regenerate:true 跳过缓存读仍写缓存、single-flight key 拼标记、真实外呼耗配额；提示词反注入条款照抄 PriceMonitorAiService.java:1716-1725 并强制品牌/型号/材质牌号/规格原文原样连续出现（只动营销修饰语，对齐 lanlink_search_products 整条 LIKE 连续子串匹配 ProductService.java:123）；服务端逐条校验：containsInjectionEcho→sanitize 后长度 2-100→硬 token 完整性（正则 [A-Za-z0-9φΦ][A-Za-z0-9\\-./×x*φΦ]{1,}，提取与比对统一基于 sanitize 后字符串，大小写敏感逐字符比对）。前端：发布表单标题输入框旁「AI 润色」按钮（Lucide 图标、loading、候选卡 2-3 可点选应用/重试/放弃、应用回填输入框走正常发布流）。硬约束：单测覆盖（端点校验/注入拒绝/配额耗尽/渠道全失败熔断/token 完整性含 φ57×3.5 案例/DN15 大小写案例）+ 前端 util 单测；i18n 新键先 grep zh-en-extra.js 防重；token 化零硬编码色值；手机断点；不跑全量套件与构建（脚本统一跑）；不动其他批次文件（VIP P0 组件/退款凭证/地图/承运商）。",
  }).ask<string>(
    "按设计稿与上述定案实施。返回：改动/新增文件清单 + 每项一句话说明 + skipped 项及原因。",
  )
  log("AI 润色实施：" + impl.slice(0, 150))

  phase("统一门禁")
  let gateOk = false
  let gateTail = ""
  const fixer = agent("门禁修复员")
  for (let round = 1; round <= 2; round++) {
    log("第 " + round + " 轮门禁：vitest + build + 本机 mvn test")
    const t = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "test"], { timeoutMs: 420000, output: { tail: 2000 } })
    const b = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "run", "build"], { timeoutMs: 420000, output: { tail: 1200 } })
    const m = await world.run("cmd", ["/c", "mvn", "-f", "backend/pom.xml", "test"], { timeoutMs: 1200000, output: { tail: 2500 } })
    if (t.exitCode === 0 && b.exitCode === 0 && m.exitCode === 0) {
      gateOk = true
      log("第 " + round + " 轮门禁通过")
      break
    }
    gateTail = [t, b, m].map(x => x.stdout + x.stderr).join("\n").slice(-1800)
    if (round === 2) {
      log("两轮修复后仍有失败")
      break
    }
    await fixer.ask("测试/构建失败，请修复（只动失败相关文件；不要跑全量套件与构建，脚本会重跑）：\n" + gateTail)
  }

  phase("重建部署与端到端复测")
  const buildB = await world.run("cmd", ["/c", "docker", "compose", "build", "backend"], { timeoutMs: 900000, output: { tail: 1200 } })
  let deployOk = buildB.exitCode === 0
  let healthy = false
  if (deployOk) {
    const up = await world.run("cmd", ["/c", "docker", "compose", "up", "-d", "backend"], { timeoutMs: 180000, output: { tail: 800 } })
    deployOk = up.exitCode === 0
    for (let i = 0; i < 24; i++) {
      const h = await world.run("cmd", ["/c", "docker", "inspect", "-f", "{{.State.Health.Status}}", "shopping-backend-1"], { timeoutMs: 30000, output: { tail: 200 } })
      if (h.stdout.trim() === "healthy") { healthy = true; break }
      await world.run("cmd", ["/c", "timeout", "/t", "5", "/nobreak"], { timeoutMs: 30000, output: "discard" })
    }
  }
  let feOk = false
  if (deployOk && healthy) {
    const buildF = await world.run("cmd", ["/c", "docker", "compose", "build", "frontend"], { timeoutMs: 900000, output: { tail: 1200 } })
    const upF = buildF.exitCode === 0
      ? await world.run("cmd", ["/c", "docker", "compose", "up", "-d", "frontend"], { timeoutMs: 180000, output: { tail: 800 } })
      : buildF
    feOk = buildF.exitCode === 0 && upF.exitCode === 0
  }
  log("部署：backend " + (deployOk && healthy ? "healthy" : "异常") + " / frontend " + (feOk ? "已更新" : "未更新"))

  const checks = await agent("AI 润色复测员", {
    system: "你是只读复测员：API 实测 + 浏览器目检（自己新开独立标签页 http://localhost:5173，商户 13911112222/123456；用户自己在用 IAB——绝不碰用户标签页）。401/403 是 body.code 且 HTTP 200，断言查 json.code。绝不修改文件。AI 渠道现状：mimosa=本地网关 :8090（ollama qwen2.5，可能慢或离线）；外部渠道 key 是演示残留必失败——降级链兜住属预期。",
  }).ask<string[]>(
    "复测清单：① 商户登录 POST /api/product/ai/polish-title 真实标题（含型号/规格如 304不锈钢管 DN15 φ57×3.5）→ 返回候选（或渠道失败降级提示——两种都如实记录，渠道失败不判 FAIL 只判文案诚实）；② 注入攻击标题（含 ignore previous instructions 类）→ 被拒「标题包含疑似注入内容」；③ 匿名调用 → 401；买家调用 → 权限拒绝；④ 候选硬 token 完整性：若返回候选，验证 304/DN15/φ57×3.5 原样连续出现；⑤ 发布表单按钮真实渲染与应用回填（浏览器）；⑥ 配额：连续调用至商户级 10 次上限 → 配额提示（或说明为何只做代码核对——避免真实耗尽演示额度则代码核对+单测佐证）；⑦ 外部模型可检索性：润色后的标题（若已应用到某商品）经 lanlink_search_products 整条 LIKE 语义验证命中——未应用则用候选串直接模拟 LIKE 验证。每条一行结论+证据。输出 string[]。",
  )
  for (const c of checks) report({ check: c })

  await artifact.markdown("report", [
    "# AI 润色商品名交付报告",
    "",
    "## 实施",
    impl,
    "",
    "## 门禁与部署",
    "- 门禁：" + (gateOk ? "vitest + build + 本机 mvn test 全绿" : "有失败项（见复测/结果）"),
    "- backend " + (deployOk && healthy ? "healthy" : "异常") + "；frontend " + (feOk ? "已重建部署" : "未更新"),
    "",
    "## 复测",
    ...checks.map(c => "- " + c),
  ].join("\n"), { title: "AI 润色商品名报告", description: "发布表单 AI 润色端点、反注入、双层配额与外部模型可检索性对齐。", primary: true })

  return {
    fixed: [impl.slice(0, 900)],
    skipped: [
      "副标题/详情导语润色（本期不做）",
      "失败冷却前后端对齐改造（前端 60s 独立展示）",
      "标题 2-100 提升进 ValidationRules + docs/数据校验规则契约.md 重建（P2；本轮仅修悬空 javadoc）",
      "PriceMonitorAiService 迁移到公共 AiQuotaGate/AiChannelInvoker（P2，不动稳定链路）",
    ],
    gates: (gateOk ? "vitest+build+本机 mvn test 全绿" : "门禁有失败项") + ";backend " + (deployOk && healthy ? "healthy" : "异常") + ";frontend " + (feOk ? "已重建部署" : "未更新") + ";复测 " + checks.length + " 条",
  } as ImplSummary
},
)
