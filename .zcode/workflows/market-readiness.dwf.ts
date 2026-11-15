/* 市场化标准差距分析：架构 + 业务链路 + 体验 + 合规运营 → 对标淘宝/1688/京东 → 优先级路线图
 * 用户要求：先子代理排查、调研、规划，看具体怎么推进；不会的上网搜。
 * 约束：审计/调研只读，规划写 out/；实施按规划在尾洞之后另行排队（P1/AI 润色/承运商三批在前）。
 */

interface DomainFinding {
  /** 差距点：领域:位置（文件/流程段） */
  where: string
  /** 一句话差距描述（对标基准下缺什么/弱什么） */
  what: string
  /** 证据：读到的行/命令输出 */
  evidence: string
  /** 对标淘宝/1688/京东的哪条标准 */
  benchmark: string
  /** 建议优先级：P0=上线前必须 / P1=市场竞争力 / P2=锦上添花 */
  priority: "P0" | "P1" | "P2"
  /** 修复方向（到文件/子系统级） */
  fixHint: string
}

interface AuditResult {
  findings: DomainFinding[]
  /** 已核实的覆盖清单（避免重复审计的子系统） */
  covered: string[]
}

interface MarketResearch {
  /** 对标平台的标准做法（分买家旅程/商家工具/信任基建三段） */
  buyerJourney: string[]
  merchantTools: string[]
  trustInfra: string[]
  /** 1688 特有（B2B：账期/批发阶梯/样品单/询价/代发/产业带） */
  b2bSpecific: string[]
  /** 关键结论的来源 URL */
  evidence: string
}

interface Roadmap {
  path: string
  /** 差距矩阵一句话总览 */
  gapSummary: string
  /** P0 清单（每条一句话+预估人日） */
  p0: string[]
  /** P1 清单 */
  p1: string[]
  /** P2 清单 */
  p2: string[]
  /** 建议推进批次顺序（依赖关系） */
  batchOrder: string[]
  /** 需用户拍板 */
  decisions: string[]
}

interface ReviewResult {
  approved: boolean
  issues: string[]
}

/** 单批次实施结果（每批一轮：实施+门禁+部署+复测） */
interface BatchSummary {
  /** 批次名（按路线图 batchOrder） */
  batch: string
  fixed: string[]
  skipped: string[]
  gates: string
  /** 路线图中尚未实施的批次（推进下一批的依据；空=全部完成） */
  remaining: string[]
}

interface WorkflowReport {
  conclusion: string
  findings: string[]
  verified: string[]
  notCovered: string[]
}

phase("四路并行审计项目现状")
const [arch, biz, front, ops] = await Promise.all([
  agent("架构审计员", {
    system: "你是只读架构审计员：评估 backend/ai-gateway/frontend 整体架构的市场化成熟度——模块边界与事务一致性、API 契约一致性（R 包装/错误语义/分页契约）、配置与部署成熟度（profile/迁移/回滚）、可观测性（metrics/logging/告警——已有 Prometheus+Grafana ai_gateway 面板，后端业务指标呢）、性能热点（N+1/缓存策略/连接池）。已建成子系统勿重复审：商户中心/活动体系/账期/直播/价格监控五维因子/AI KB 客服/法律合规中心/MinIO+MQ/聊天媒体/退款凭证+RMA/搜索融合 MMR/GSP 广告/指标看板/任务激励（多轮安全加固已做）。聚焦「市场化上线还差什么」。",
  }).ask<AuditResult>(
    "输出 AuditResult：findings（where/what/evidence/benchmark/priority/fixHint，中文，priority 按上线门槛判）+ covered。中文。",
  ),
  agent("业务链路审计员", {
    system: "你是只读业务链路审计员：沿买家完整旅程（搜索→详情→加购→结算→支付→物流→签收→评价→售后→复购）与商家完整旅程（入驻→发品→接单→履约→结算→提现）逐段核对代码与页面，找断链/缺环/弱环。已有件：搜索融合打分/商品详情/购物车/五渠道支付/物流四件套+地图+SLA/退款凭证+RMA 逆向/评价(有 ProductReview 模块)/账期/优惠券/签到任务/大促会场/会员订阅(P1 进行中)。重点找：评价系统是否完整闭环（买家可评？商户可回复？好评率进搜索权重？）、发票、多地址/批量下单、售后工单、客服转人工、商家运营工具（数据分析/竞对）、消息触达（短信/webhook）。",
  }).ask<AuditResult>(
    "输出 AuditResult（同结构，benchmark 引用淘宝/京东对应能力）。中文。",
  ),
  agent("前端体验审计员", {
    system: "你是只读前端审计员：对标淘宝/京东的前端市场化标准——首屏性能（路由懒加载/图片懒加载/CDN/骨架屏）、SEO（SPA meta/预渲染——B2B 站搜索引擎流量是命脉）、移动端完整度（断点已有，触达路径核）、可访问性基础、错误监控（sentry 类）、埋点分析（traffic 埋点已有，转化漏斗呢）、空态/加载态一致性（多轮整改后抽查）。技术栈 Vue3+Vite+antd+Pinia，无 SSR。",
  }).ask<AuditResult>(
    "输出 AuditResult（同结构）。中文。",
  ),
  agent("合规运营审计员", {
    system: "你是只读合规运营审计员：市场化上线门槛盘点——安全残留（多轮加固后有无漏项：密钥/越权/CSRF/限流覆盖面）、法务合规（法律合规中心五文档已有；营业执照公示/ICP 备案位/平台资质展示位）、运营后台完备度（Admin 系列页：商品审核/订单干预/商户管理/营销配置/数据看板——哪些还缺）、数据出口（导出/报表）。",
  }).ask<AuditResult>(
    "输出 AuditResult（同结构）。中文。",
  ),
])

phase("对标调研：淘宝/京东/1688 标准")
const [bench, b2b] = await Promise.all([
  agent("市场标准调研员", {
    system: "你是电商行业调研员：用 WebSearch/WebFetch 调研淘宝与京东面向市场的标准化能力清单（买家旅程各段的行业标准体验、信任基建：评价体系/担保交易/售后 SLA/极速退款、商家工具：店铺数据/营销中心/客服系统、搜索与推荐机制）。输出要点化，来源附 URL。",
  }).ask<MarketResearch>(
    "输出 MarketResearch：buyerJourney/merchantTools/trustInfra 三段要点清单 + evidence（来源 URL）。中文。",
  ),
  agent("B2B 标准调研员", {
    system: "你是 B2B 电商调研员：用 WebSearch/WebFetch 调研 1688 的 B2B 特有能力清单——账期与诚 e 保、批发阶梯价、样品单、询价单（RFQ）、代发/分销、产业带专题、企业采购买家体系（对公转账/增值税发票/采购多级审批）。本项目=工业品 B2B 商城，已有：账期 CreditTerm/批发属性/询价客服/增值税材料上传。输出差距对照。",
  }).ask<MarketResearch>(
    "输出 MarketResearch：b2bSpecific（1688 能力清单+本项目差距对照）+ evidence。中文。",
  ),
])
log("四路审计 + 两路对标调研完成")

phase("规划市场化路线图")
const plannerAgent = agent("市场化规划师", {
  system: "你是资深电商产品架构师：合并四路审计与两路调研，产出可执行的市场化差距路线图 out/market-readiness-plan.md（Write 工具）。结构：① 执行摘要（现状一句话：哪些已达标）；② 差距矩阵（领域×对标标准，标 P0/P1/P2）；③ P0 清单（上线前必须，每条含差距描述/fixHint/预估人日/依赖）；④ P1 清单（市场竞争力）；⑤ P2 清单；⑥ 推进批次建议（按依赖排序，每批一轮工作流的粒度，标注哪些可与进行中的 P1/润色/承运商批次并行）；⑦ 需拍板项（预算类/重架构类）。诚实原则：已有件不虚报差距；人日估算给区间；引用审计 path:line 与调研来源。",
})
let plan = await plannerAgent.ask<Roadmap>(
  "四路审计：\n架构：" + JSON.stringify(arch) + "\n业务链路：" + JSON.stringify(biz) + "\n前端体验：" + JSON.stringify(front) + "\n合规运营：" + JSON.stringify(ops) + "\n对标调研：\n淘宝/京东：" + JSON.stringify(bench) + "\n1688 B2B：" + JSON.stringify(b2b) + "\n写成路线图到 out/market-readiness-plan.md 并返回 Roadmap。",
)
log("路线图成稿：" + plan.gapSummary)

phase("独立评审定稿")
const reviewer = agent("路线图评审员", {
  system: "你是独立评审员：Read out/market-readiness-plan.md 全文，专挑问题——已有件是否被误报为差距（对照 covered 清单）、P0 是否真为上线门槛（把竞争力项混进 P0=打回）、人日是否明显失真、依赖排序是否可执行、拍板项是否完整（预算/重架构类别漏）。需要证据，反对是常态。不改文件。",
})
for (let round = 1; round <= 2; round++) {
  const review = await reviewer.ask<ReviewResult>(
    "评审 " + plan.path + "（第 " + round + " 轮）：" + (round > 1 ? "上轮问题已修订，复核是否闭合并继续挑新问题。" : "全面挑问题。"),
  )
  if (review.approved) {
    log("评审通过（第 " + round + " 轮）")
    break
  }
  if (round === 2) {
    log("两轮评审后仍有未闭合问题，如实入 decisions")
    plan.decisions = [...plan.decisions, ...review.issues.map(i => "评审遗留：" + i)]
    break
  }
  log("评审提出 " + review.issues.length + " 个问题，交规划师修订")
  plan = await plannerAgent.ask<Roadmap>(
    "评审意见（逐条修订 " + plan.path + " 并在文中回应）：" + JSON.stringify(review.issues) + "\n修订后重新返回 Roadmap。",
  )
}

await artifact.markdown("report", [
  "# 市场化标准差距分析",
  "",
  plan.gapSummary,
  "",
  "## P0（上线前必须）",
  ...plan.p0.map(x => "- " + x),
  "",
  "## P1（市场竞争力）",
  ...plan.p1.map(x => "- " + x),
  "",
  "## P2",
  ...plan.p2.map(x => "- " + x),
  "",
  "## 建议推进批次",
  ...plan.batchOrder.map(x => "- " + x),
  "",
  "## 需拍板",
  ...plan.decisions.map(x => "- " + x),
  "",
  "完整路线图：" + plan.path + "（含差距矩阵与逐条证据）",
].join("\n"), { title: "市场化差距路线图", description: "对标淘宝/1688/京东的差距矩阵与优先级推进计划。", primary: true })

return await hole<BatchSummary>(
  "推进第一批实施",
  "路线图已定稿：" + plan.path + "（" + plan.gapSummary + "）。批次顺序：" + plan.batchOrder.join("；") + "。需拍板：" + plan.decisions.join("；") + "\n" +
  "填充本洞时按 batchOrder 首批定义实施拓扑（每批=实施+门禁 vitest/build/mvn test+前后端部署+运行时复测）；本批完成后的 fill 若尚有剩余批次，以新的尾洞（同一接口）继续接力，直至 remaining 为空后返回整体 WorkflowReport。前置确认：进行中的 P1（dwfrun-5349bf12）/AI 润色（dwfrun-effd87ea）/承运商（dwfrun-987e699c）管线清零后本批才动文件（schema.sql/zh-en-extra.js/构建竞态）；路线图条目若与这三批重叠须先核对现状避免重复实施。", async () => {
  phase("落地 Amplitude 埋点接入")
  const impl = await agent("批次 A 实施工程师", {
    system: "你是资深前端工程师：按 out/market-readiness-plan.md 的 P0-8 条目实施 Amplitude 埋点接入（先读该条目的差距描述/fixHint/验收标准）。要点：① npm 引入 @amplitude/analytics-browser（唯一新增依赖，锁版本）；② init 由 VITE_AMPLITUDE_KEY 环境变量门控——key 为空时整库不初始化（零开销降级），key 由用户在 .env 提供（本批先留空占位+文档注释，不虚构 key）；③ 埋点事件计划（照设计稿 P0-8 的清单，若设计未细化则按转化漏斗最小集）：page_view（路由切换）/product_view（带 prodId）/add_to_cart/checkout_start/payment_success/refund_apply/signup/login，事件属性带 userId（登录态）与页面上下文；④ 与既有 trafficApi.track 并存不冲突（Amplitude 是外部漏斗分析，traffic 是站内风控/遥测——两套职责不同，事件互不转发）；⑤ 用户识别：登录后 setUserId，登出 resetUserId；⑥ i18n 无新增中文文案则不涉 zh-en-extra（如需则先 grep）；⑦ util 单测（事件发射/空 key 降级/mock amplitude）；⑧ 不跑全量套件与构建（脚本统一跑）；⑨ 不动其他批次文件（订阅/润色/承运商/退款凭证）。",
  }).ask<string>(
    "按 P0-8 实施。返回：改动/新增文件清单 + 每项一句话 + 事件计划清单 + skipped 项及原因。",
  )
  log("批次 A 实施：" + impl.slice(0, 150))

  phase("统一门禁")
  let gateOk = false
  let gateTail = ""
  const fixer = agent("门禁修复员")
  for (let round = 1; round <= 2; round++) {
    log("第 " + round + " 轮门禁：vitest + build")
    const t = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "test"], { timeoutMs: 420000, output: { tail: 2000 } })
    const b = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "run", "build"], { timeoutMs: 420000, output: { tail: 1200 } })
    if (t.exitCode === 0 && b.exitCode === 0) {
      gateOk = true
      log("第 " + round + " 轮门禁通过")
      break
    }
    gateTail = (t.stdout + t.stderr + b.stdout + b.stderr).slice(-1600)
    if (round === 2) {
      log("两轮修复后仍有失败")
      break
    }
    await fixer.ask("测试/构建失败，请修复（只动失败相关文件；不要跑全量套件与构建，脚本会重跑）：\n" + gateTail)
  }
  let feOk = false
  if (gateOk) {
    const buildF = await world.run("cmd", ["/c", "docker", "compose", "build", "frontend"], { timeoutMs: 900000, output: { tail: 1200 } })
    const upF = buildF.exitCode === 0
      ? await world.run("cmd", ["/c", "docker", "compose", "up", "-d", "frontend"], { timeoutMs: 180000, output: { tail: 800 } })
      : buildF
    feOk = buildF.exitCode === 0 && upF.exitCode === 0
  }
  log("frontend " + (feOk ? "已重建部署" : "未更新"))

  phase("埋点复测")
  const checks = await agent("批次 A 复测员", {
    system: "你是只读复测员：浏览器实测（自己新开独立标签页 http://localhost:5173，买家 13900000001/123456；绝不碰用户标签页）+ 代码核对，绝不修改文件。Amplitude key 当前为空——验证降级路径（SDK 不初始化、无报错、事件调用被安全跳过）+ 代码核对事件计划完整性。",
  }).ask<string[]>(
    "复测清单：① 空 key 降级：浏览整站无 console 报错、网络面板无 amplitude 请求；② 事件埋点代码核对：page_view/product_view/add_to_cart/checkout_start/payment_success/refund_apply 六事件在对应页面/动作接线；③ 登录态 setUserId/resetUserId 接线核对；④ trafficApi 既有埋点不受影响（商品页操作时 traffic 请求仍发出）；⑤ util 单测通过。每条一行结论+证据。输出 string[]。",
  )
  for (const c of checks) report({ check: c })

  await artifact.markdown("report", [
    "# 市场化批次 A（Amplitude 快赢包）交付",
    "",
    "## 实施",
    impl,
    "",
    "## 门禁与部署",
    "- 门禁：" + (gateOk ? "vitest + build 全绿" : "有失败项（见复测/结果）"),
    "- frontend " + (feOk ? "已重建部署" : "未更新"),
    "",
    "## 复测",
    ...checks.map(c => "- " + c),
  ].join("\n"), { title: "批次 A 快赢包报告", description: "P0-8 Amplitude 埋点接入（空 key 降级）交付与复测。", primary: true })

  return await hole<BatchSummary>(
    "推进第二批实施",
    "批次 A（P0-8 Amplitude）完成：" + (gateOk ? "门禁全绿" : "门禁有失败项") + "，frontend " + (feOk ? "已部署" : "未更新") + "。\n" +
    "剩余批次（按路线图顺序）：批次 B 合规安全包（P0-2 CSRF 长尾+P0-1 公示区+P1-11 限流长尾；前置=合并进行中批次的 csrfPolicy.js 改动）；批次 E1 Flyway 迁移底座（无前置，是 D/C/H2 的前置）；批次 D 最小出金（依赖 E1+拍板⑦-15）；批次 C 评价闭环（依赖 E1）；批次 E2 部署与可观测（依赖拍板⑦-12/⑦-7）；批次 F 前端线上保障；批次 G1/G2；批次 H1/H2；批次 I；批次 J 按拍板裁剪。待用户拍板项（批次 B/D/E2/G2/H2/I 前置）：短信服务商选型/告警送达通道/Sentry 选型/Stripe 密钥轮换（用户操作）/ICP 备案 owner/EDI 许可/转人工值班/生产形态唯一化/Flyway baseline 对账/评价数据模型/提现账本落点。填充本洞时按剩余批次顺序取下一批定义拓扑（拍板缺失的批次跳过并在 skipped 注明，优先无拍板阻塞的批次）。", async () => {
    phase("实施批次 B 合规安全包")
    const impl = await agent("合规安全实施工程师", {
      system: "你是资深后端+前端工程师：按 out/market-readiness-plan.md 的批次 B 清单实施（先通读该计划中 P0-2 CSRF 长尾、P0-1 公示区、P1-11 限流长尾三条目及其 fixHint/验收标准）。环境已修复（backend healthy、AI 链路 outcome=ok、frontend 已部署最新版）——可正常实测验证。硬约束：① CSRF 长尾——rg 全仓找未进 csrfPolicy.js 的 POST 端点（对照 csrfPolicy.js 现有清单逐一核对，特别是其他批次新加的端点如 /product/ai/*），补 WebConfig 注册 + 前端 csrfPolicy.js 双端；② 公示区——MainLayout 页脚加营业执照/ICP 备案号/平台资质三个展示位（占位文案+结构，真实证照号留空待用户提供；ICP 备案号 owner 待拍板，先渲染占位）；③ 限流长尾——审计 AuthRateLimitInterceptor POLICIES 覆盖面，对写操作端点补缺失的限流策略（对照 existing 先例格式）；④ 每项补测试；⑤ i18n 新键先 grep zh-en-extra 防重；⑥ token 化零硬编码色值；⑦ 不跑全量套件与构建（脚本统一跑）；⑧ 不动其他批次文件（VIP/润色/承运商已部署区）。",
    }).ask<string>(
      "按批次 B 三条目实施。返回：改动/新增文件清单 + 每项一句话说明 + skipped 项及原因。",
    )
    log("批次 B 实施：" + impl.slice(0, 150))

    phase("统一门禁")
    let gateOk = false
    let gateTail = ""
    const fixer = agent("批次 B 门禁修复员")
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

    phase("重建部署与合规复测")
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

    const checks = await agent("批次 B 合规复测员", {
      system: "你是只读复测员：API 实测 + 浏览器目检（自己新开独立标签页 http://localhost:5173；用户自己在用 IAB——绝不碰用户标签页）。401/403 是 body.code 且 HTTP 200，断言查 json.code。绝不修改文件。",
    }).ask<string[]>(
      "复测清单：① CSRF 长尾：对新增保护的写端点不带 CSRF 调用期待拒绝、带 CSRF 期待通过（各抽 2 个端点实测）；② 公示区：浏览器目检页脚渲染营业执照/ICP/资质三个占位展示位；③ 限流：对补了限流的写端点连续调用超阈值期待限流响应（或代码核对策略注册）；④ 既有功能回归：润色端点/物流推荐/订阅下单各冒烟一次 200；⑤ 404 兜底页仍正常。每条一行结论+证据。输出 string[]。",
    )
    for (const c of checks) report({ check: c })

    await artifact.markdown("report", [
      "# 市场化批次 B（合规安全包）交付",
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
    ].join("\n"), { title: "批次 B 合规安全包报告", description: "CSRF 长尾、公示区占位、限流长尾的交付与复测。", primary: true })

    return await hole<BatchSummary>(
      "推进第三批实施",
      "批次 B 完成：" + (gateOk ? "门禁全绿" : "门禁有失败项") + "，backend " + (deployOk && healthy ? "healthy" : "异常") + "，frontend " + (feOk ? "已部署" : "未更新") + "。实施摘要：" + impl.slice(0, 400) + "\n" +
      "剩余批次（按路线图）：批次 E1 Flyway 迁移底座（无前置，是 D/C/H2 的前置）；批次 D 最小出金（依赖 E1+拍板⑦-15）；批次 C 评价闭环（依赖 E1）；批次 E2 部署与可观测（依赖拍板⑦-12/⑦-7）；批次 F 前端线上保障；批次 G1/G2；批次 H1/H2；批次 I；批次 J 按拍板裁剪。待拍板项仍开放：短信服务商/告警通道/Sentry 选型/Stripe 轮换/ICP owner/EDI 许可/转人工值班/生产形态唯一化/Flyway baseline 对账/评价数据模型/提现账本落点。填充本洞时按剩余批次顺序取下一批（优先无拍板阻塞的 E1）定义拓扑。", async () => {
      phase("实施批次 E1 Flyway 迁移底座")
      const impl = await agent("Flyway 实施工程师", {
        system: "你是资深后端工程师：按 out/market-readiness-plan.md 的 P0-5 条目实施 Flyway 迁移底座（先读该条目的差距描述/fixHint/验收标准）。现状：backend/src/main/resources/db-migrations/ 已有 2 个手写 SQL（V2026_10__member_level_policy_transition.sql、V2026_11__campaign_unify_migration.sql），pom.xml 无 flyway 依赖，application.yml 无 flyway 配置——当前靠 SchemaInitializer 手写 ALTER。任务：① pom.xml 加 flyway-core + flyway-mysql 依赖（版本对齐 Spring Boot 3.5.3 BOM）；② application.yml 配 flyway.locations=classpath:db-migrations、baseline-on-migrate=true（**baseline 时点拍板⑦-14 未完成：先设 baselineVersion=20261111.0.0 并在返回 notes 注明——db-migrations 两 SQL 是否已在生产库手工执行未核实，baseline 前对账列为待办**）；③ 迁移 SQL 位置不变（db-migrations），已有 2 个 V 文件被 Flyway 纳管；④ 后续新迁移一律 V2026_12__xxx.sql 格式，**SchemaInitializer 逐步退役计划写进 notes（本批不动既有 Initializer，避免与已部署行为冲突）**；⑤ application-dev.yml 与 prod profile 各自确认 flyway.enabled（dev 可 true，prod 视 baseline 结论）；⑥ 单测：Flyway 配置加载+迁移文件命名规范校验；⑦ 不跑全量套件（脚本统一跑）；⑧ 不动其他批次文件。",
      }).ask<string>(
        "按 P0-5 实施 Flyway 底座。返回：改动文件清单 + 每项一句话 + baseline 处理说明 + skipped 项及原因。",
      )
      log("批次 E1 实施：" + impl.slice(0, 150))

      phase("统一门禁")
      let gateOk = false
      let gateTail = ""
      const fixer = agent("批次 E1 门禁修复员")
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

      phase("重建部署与迁移验证")
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
      log("backend " + (deployOk && healthy ? "healthy" : "异常"))

      const checks = await agent("批次 E1 复测员", {
        system: "你是只读复测员：docker logs + docker exec mysql 验证 Flyway 实际执行情况。绝不修改文件。注意 backend 可能重启过（IP 变化正常）。",
      }).ask<string[]>(
        "复测清单：① backend 启动日志含 Flyway 迁移执行行（Migrating schema / Successfully applied 或 baseline）；② flyway_schema_history 表存在且有记录（docker exec mysql 查）；③ 两个既有 V 文件被纳管（history 表 version 含 2026.10/2026.11 或 baseline 记录）；④ SchemaInitializer 既有行为未破坏（t_logistics_provider 等表仍在，business 功能冒烟 /api/logistics/providers 200）；⑤ 启动无 Flyway 报错。每条一行结论+证据。输出 string[]。",
      )
      for (const c of checks) report({ check: c })

      await artifact.markdown("report", [
        "# 市场化批次 E1（Flyway 迁移底座）交付",
        "",
        "## 实施",
        impl,
        "",
        "## 门禁与部署",
        "- 门禁：" + (gateOk ? "vitest + build + 本机 mvn test 全绿" : "有失败项（见复测/结果）"),
        "- backend " + (deployOk && healthy ? "healthy" : "异常"),
        "",
        "## 复测",
        ...checks.map(c => "- " + c),
      ].join("\n"), { title: "批次 E1 Flyway 底座报告", description: "数据库迁移底座 Flyway 接入的交付与复测。", primary: true })

      return await hole<BatchSummary>(
        "推进第四批实施",
        "批次 E1 完成：" + (gateOk ? "门禁全绿" : "门禁有失败项") + "，backend " + (deployOk && healthy ? "healthy" : "异常") + "。实施摘要：" + impl.slice(0, 400) + "\n" +
        "剩余批次（按路线图）：批次 D 最小出金（依赖 E1✅+拍板⑦-15 提现账本落点——**拍板缺失**）；批次 C 评价闭环（依赖 E1✅+拍板⑦-16 评价数据模型——**拍板缺失**）；批次 E2 部署与可观测（依赖拍板⑦-12/⑦-7——**拍板缺失**）；批次 F 前端线上保障（P0-7 最小上报无拍板阻塞，P1-13 Splash）；批次 G1 nginx 静态压缩（无依赖）；批次 H1 阶梯价（无 E1 依赖）；批次 H2 开票+多地址（依赖 E1✅）；批次 I 运营与触达（多项依赖拍板）；批次 J 按拍板裁剪。填充本洞时优先取无拍板阻塞的批次（F→G1→H1→H2），依赖拍板的批次在返回中列入 skipped 并注明所需拍板项。", async () => {
        phase("实施批次 F 前端线上保障")
        const impl = await agent("批次 F 前端保障工程师", {
          system: "你是资深前端+后端工程师：按 out/market-readiness-plan.md 的 P0-7 与 P1-13 条目实施批次 F（先读两条目的差距/fixHint/验收标准）。环境已修复（backend healthy、AI 链路 ok、frontend 已部署）——可正常实测。任务：① P0-7 最小错误上报——frontend/src/utils/errorReport.js：app.config.errorHandler + window error/unhandledrejection 双监听，去重（同 message+stack 60s 窗口一次）+ 单会话上限（20 条）防风暴；上报通道**复用 traffic events 基建**（api/index.js 的 trafficApi 静默通道，source_type=frontend_error，payload 仅 page_path/message 前 500 字/stack 前 1500 字/userAgent，**零 PII**）——后端 TrafficController 校验该 source_type 合法后落 t_traffic_event；不需要 Cookie 授权门控（运行时错误属运维遥测），但 silent 通道不弹错。② main.js 收口安装（与 analytics init 并列一行式）；③ P1-13 Splash——index.html 内联首屏 Splash（纯 CSS，品牌渐变 + logo 占位，app mount 后自动淡出，防首屏白屏；禁外部资源零依赖）；④ 后端：TrafficController/TrafficService 放行 source_type=frontend_error + 长度护栏（超 2000 字截断）；⑤ 单测：errorReport 去重/上限/字段裁剪 + Splash 挂载；⑥ i18n 新键先 grep zh-en-extra 防重；⑦ 不跑全量套件（脚本统一跑）；⑧ 不动其他批次文件。",
        }).ask<string>(
          "按 P0-7/P1-13 实施批次 F。返回：改动/新增文件清单 + 每项一句话说明 + skipped 项及原因。",
        )
        log("批次 F 实施：" + impl.slice(0, 150))

        phase("统一门禁")
        let gateOk = false
        let gateTail = ""
        const fixer = agent("批次 F 门禁修复员")
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

        phase("重建部署与错误上报复测")
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

        const checks = await agent("批次 F 复测员", {
          system: "你是只读复测员：浏览器实测（自己新开独立标签页 http://localhost:5173；用户自己在用 IAB——绝不碰用户标签页）+ API 实测 + docker exec 查库。401/403 是 body.code 且 HTTP 200，断言查 json.code。绝不修改文件。",
        }).ask<string[]>(
          "复测清单：① 错误上报：浏览器 console 触发一个受控异常（evaluate throw）→ 观察是否有 frontend_error 上报请求发出（或按设计被去重/上限拦截），t_traffic_event 落 source_type=frontend_error 行；② Splash：清缓存硬刷新首页 → 首帧是否出现品牌 Splash（无白屏）；③ 既有回归：trafficApi 正常事件（商品点击）仍落库、润色端点冒烟（真实中文标题）、404 兜底页正常；④ Flyway 回归：backend 启动无 Flyway 报错（docker logs 查）。每条一行结论+证据。输出 string[]。",
        )
        for (const c of checks) report({ check: c })

        await artifact.markdown("report", [
          "# 市场化批次 F（前端线上保障）交付",
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
        ].join("\n"), { title: "批次 F 前端线上保障报告", description: "最小错误上报与 Splash 的交付与复测。", primary: true })

        return await hole<BatchSummary>(
          "推进第五批实施",
          "批次 F 完成：" + (gateOk ? "门禁全绿" : "门禁有失败项") + "，backend " + (deployOk && healthy ? "healthy" : "异常") + "，frontend " + (feOk ? "已部署" : "未更新") + "。实施摘要：" + impl.slice(0, 400) + "\n" +
          "剩余批次（按路线图）：批次 G1 nginx 静态压缩（无依赖）；批次 H1 阶梯价（无 E1 依赖）；批次 H2 开票+多地址（依赖 E1✅）；批次 D 最小出金/C 评价闭环/E2 部署与可观测/I 运营与触达（依赖拍板⑦-15/⑦-16/⑦-12+⑦-7/⑦-10 等——**拍板缺失**）；批次 J 按拍板裁剪。填充本洞时优先取无拍板阻塞的批次（G1→H1→H2），依赖拍板的批次列入 skipped 并注明所需拍板项。", async () => {
          phase("实施批次 G1 nginx 静态压缩")
          const impl = await agent("G1 nginx 工程师", {
            system: "你是资深运维工程师：按 out/market-readiness-plan.md 的 P1-16 条目实施 nginx 静态压缩（先读该条目的差距/fixHint/验收标准）。现状：frontend/nginx.conf 与 nginx.conf.template 为容器内实际生效配置。任务：① gzip 主开关 + gzip_types（text/css、application/javascript、application/json、image/svg+xml、text/xml）+ gzip_min_length 1024 + gzip_comp_level 5 + gzip_vary on——对 .js/.css/.svg/.json 文本资源开气动压缩（资源文件名带 contenthash，无需预压缩）；② proxy 路径（/api）不开 gzip（后端自行处理）；③ 顺带核对 hashed 静态资源的 Cache-Control（/assets/.*immutable）——已有则不动，缺失则补 expires 30d+immutable 一行；④ nginx.conf 与 nginx.conf.template 双文件同步改；⑤ 不改业务代码，不动其他批次文件。",
          }).ask<string>(
            "按 P1-16 实施。返回：改动文件清单 + 每项一句话 + skipped 项及原因。",
          )
          log("批次 G1 实施：" + impl.slice(0, 150))

          phase("部署与压缩验证")
          const deployG1 = await world.run("cmd", ["/c", "docker", "compose", "up", "-d", "--force-recreate", "frontend"], { timeoutMs: 300000, output: { tail: 800 } })
          let feOk = deployG1.exitCode === 0
          let gzipJs = ""
          let gzipCss = ""
          if (feOk) {
            await world.run("cmd", ["/c", "timeout", "/t", "8", "/nobreak"], { timeoutMs: 30000, output: "discard" })
            const v1 = await world.run("cmd", ["/c", "curl", "-s", "-I", "-H", "Accept-Encoding: gzip", "http://localhost:5173/"], { timeoutMs: 30000, output: { tail: 600 } })
            gzipJs = v1.stdout
            const asset = await world.run("cmd", ["/c", "curl", "-s", "http://localhost:5173/", "-o", "NUL", "-w", "%{http_code}"], { timeoutMs: 30000, output: { tail: 40 } })
            log("frontend 重启：" + (feOk ? "OK" : "失败") + "；首页编码探测头已取（asset=" + asset.stdout.trim() + "）")
          } else {
            log("frontend 重建失败")
          }

          const checks = await agent("G1 复测员", {
            system: "你是只读复测员：用 PowerShell Invoke-WebRequest/curl 实测响应头。绝不修改文件。",
          }).ask<string[]>(
            "复测清单：① 对 frontend 容器内最大的一个 /assets/*.js 实测 curl -H 'Accept-Encoding: gzip' -I → Content-Encoding: gzip 且 Content-Type 为 javascript；② 同样测一个 .css；③ /api 路径不带 gzip（后端自行处理）；④ Cache-Control：hashed 资源带 immutable（若实施）；⑤ 页面 200 冒烟。每条一行结论+证据（含实测头）。输出 string[]。",
          )
          for (const c of checks) report({ check: c })

          await artifact.markdown("report", [
            "# 市场化批次 G1（nginx 静态压缩）交付",
            "",
            "## 实施",
            impl,
            "",
            "## 部署",
            "- frontend " + (feOk ? "已重建" : "失败"),
            "",
            "## 复测",
            ...checks.map(c => "- " + c),
          ].join("\n"), { title: "批次 G1 nginx 压缩报告", description: "静态资源 gzip 压缩与缓存头的交付与复测。", primary: true })

          return await hole<BatchSummary>(
            "推进第六批实施",
            "批次 G1 完成：frontend " + (feOk ? "已重建" : "失败") + "。实施摘要：" + impl.slice(0, 400) + "\n" +
            "剩余批次（按路线图）：批次 H1 阶梯价（无 E1 依赖，tierPriceJson 已存在）；批次 H2 开票+多地址（依赖 E1✅）；批次 D 最小出金/C 评价闭环/E2 部署与可观测/I 运营与触达（依赖拍板⑦-15/⑦-16/⑦-12+⑦-7/⑦-10 等——**拍板缺失**）；批次 J 按拍板裁剪。填充本洞时优先取无拍板阻塞的批次（H1→H2），依赖拍板的批次列入 skipped 并注明所需拍板项。", async () => {
            phase("实施批次 H1 阶梯批发价")
            const impl = await agent("阶梯价实施工程师", {
              system: "你是资深全栈工程师：按 out/market-readiness-plan.md 的 P1-17 条目实施阶梯批发价闭环（先读该条目的差距/fixHint/验收标准——证据已核实：entity/Product.java:28 tierPriceJson 字段已存在，无新表，纯存量表逻辑改动）。现状缺口：商户无编辑入口（ProductController tierPrice 零命中）、详情无阶梯展示、下单不按阶梯计价、无 MOQ。任务：① 数据契约——tierPriceJson 统一为 [{minQty:10, price:88.00},...] 降序 minQty（数量区间 [minQty, 下一档)），写校验（minQty 严格递增、price 递减或相等、≥1 条、合法 JSON，非法拒绝并给可读文案）；② 商户编辑——商品发布/编辑表单加阶梯价编辑区（动态行增删 + 每行数量/单价校验，走既有 ProductController.publish/myUpdate 链路落 tierPriceJson，零新端点优先）；③ 买家侧——ProductDetail 价格区展示阶梯表（当前数量命中档高亮）+ SKU 选择联动；④ 下单计价——OrderService 计价处按购买数量命中阶梯单价（**与 PricingFacade/商户促销互斥关系沿用既有优惠逻辑，阶梯价为基础价替换而非叠加**；价格快照落 t_order_item 保证审计一致）；⑤ MOQ——tierPriceJson 非空时最小档 minQty 为起订量（cart/checkout 校验不足给可读提示）；⑥ 单测：阶梯命中边界（正好 minQty/区间内/超最大档取最低价）、与促销互斥、MOQ 校验、快照一致性；⑦ 前端 token 化、Lucide 禁 emoji、手机断点、i18n 先 grep zh-en-extra 防重；⑧ 不跑全量套件（脚本统一跑）；⑨ 不动其他批次文件（VIP/润色/承运商/G1 已部署区）。注意 price 精度用 BigDecimal/分，禁止 double。",
            }).ask<string>(
              "按 P1-17 实施阶梯批发价闭环。返回：改动/新增文件清单 + 每项一句话 + 数据契约样例 + skipped 项及原因。",
            )
            log("H1 实施：" + impl.slice(0, 150))

            phase("统一门禁")
            let gateOk = false
            let gateTail = ""
            const fixer = agent("H1 门禁修复员")
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

            phase("重建部署与阶梯价端到端复测")
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

            const checks = await agent("H1 复测员", {
              system: "你是只读复测员：API 实测（PowerShell Invoke-RestMethod + UTF-8 字节体，中文 JSON 直传会坏）+ docker exec 查库（shopping-mysql-1，root/local-validation-password，库 lanlink_shopping）+ 浏览器目检（自己新开独立标签页 http://localhost:5173，商户 13911112222/买家 13900000001，密码均 123456；用户自己在用 IAB——绝不碰用户标签页）。绝不修改文件。",
            }).ask<string[]>(
              "端到端复测：① 商户编辑商品设阶梯价（如 1-9 件 100 元 / 10-49 件 88 元 / 50+ 件 80 元）→ t_product.tier_price_json 落库校验；② 详情页阶梯表渲染+当前档高亮；③ 买家下单 15 件 → 单价命中 88 档（订单金额=15×88 快照一致）；④ 买家下单 5 件 → 命中 100 档；⑤ 买家下单 60 件 → 命中 80 档；⑥ MOQ：设 minQty=10 后买 5 件被拒且文案可读；⑦ 与商户促销互斥回归：有阶梯价商品叠加促销券下单，价格快照与既有优惠逻辑一致；⑧ 既有无阶梯价商品下单回归（走原单价不受影响）。每条一行结论+证据。输出 string[]。",
            )
            for (const c of checks) report({ check: c })

            await artifact.markdown("report", [
              "# 市场化批次 H1（阶梯批发价）交付",
              "",
              "## 实施",
              impl,
              "",
              "## 门禁与部署",
              "- 门禁：" + (gateOk ? "vitest + build + 本机 mvn test 全绿" : "有失败项（见复测/结果）"),
              "- backend " + (deployOk && healthy ? "healthy" : "异常") + "；frontend " + (feOk ? "已重建部署" : "未更新"),
              "",
              "## 端到端复测",
              ...checks.map(c => "- " + c),
            ].join("\n"), { title: "批次 H1 阶梯批发价报告", description: "B2B 阶梯价编辑/展示/计价/MOQ 闭环的交付与端到端复测。", primary: true })

            return await hole<BatchSummary>(
              "推进第七批实施",
              "批次 H1 完成：" + (gateOk ? "门禁全绿" : "门禁有失败项") + "，backend " + (deployOk && healthy ? "healthy" : "异常") + "，frontend " + (feOk ? "已部署" : "未更新") + "。实施摘要：" + impl.slice(0, 400) + "\n" +
              "剩余批次（按路线图）：批次 H2 开票+多地址（依赖 E1✅，11–18 人日）；批次 D 最小出金/C 评价闭环/E2 部署与可观测/I 运营与触达（依赖拍板⑦-15/⑦-16/⑦-12+⑦-7/⑦-10 等——**拍板缺失**）；批次 J 按拍板裁剪。填充本洞时实施 H2（无拍板阻塞，最后一批无依赖项），D/C/E2/I 列 skipped 注明所需拍板项。", async () => {
              phase("实施批次 H2 开票与多地址")
              const impl = await agent("H2 实施工程师", {
                system: "你是资深全栈工程师：按 out/market-readiness-plan.md 的 P1-5（增值税发票）与 P1-8（多收货地址增强）条目实施批次 H2（先读两条目的差距/fixHint/验收标准，证据均经两轮复核）。环境健康可实测。硬约束：① 开票——checkout/订单加发票需求字段（抬头类型个人/单位、抬头名称、税号 15-20 位大写字母数字校验、内容明细选商品类别/明细），落订单表新增列（命名 invoice_* 前缀，幂等 ALTER 走独立 SchemaInitializer 模式），管理端订单详情展示发票信息；② 多地址——收货地址簿已存在（AddressPanel），本批补：下单时直接从地址簿选择（checkout 地址区加地址簿选择器，非手写重输）+ 默认地址标记与自动选中；③ 阶梯价/H1 已部署——不动 tierPrice 相关文件；④ 前端 token 化、Lucide 禁 emoji、手机断点、i18n 先 grep zh-en-extra 防重；⑤ 每项补测试（后端字段校验/快照落库，前端组件交互）；⑥ 不跑全量套件（脚本统一跑）；⑦ 不动其他批次已部署区（VIP/润色/承运商/G1/F）。",
              }).ask<string>(
                "按 P1-5/P1-8 实施批次 H2。返回：改动/新增文件清单 + 每项一句话 + 数据库迁移说明 + skipped 项及原因。",
              )
              log("批次 H2 实施：" + impl.slice(0, 150))

              phase("统一门禁")
              let gateOk = false
              let gateTail = ""
              const fixer = agent("H2 门禁修复员")
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

              phase("重建部署与 H2 端到端复测")
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

              const checks = await agent("H2 复测员", {
                system: "你是只读复测员：API 实测（PowerShell Invoke-RestMethod + UTF-8 字节体）+ docker exec 查库（shopping-mysql-1，root/local-validation-password，库 lanlink_shopping）+ 浏览器目检（自己新开独立标签页 http://localhost:5173，买家 13900000001/商户 13911112222，密码均 123456；用户自己在用 IAB——绝不碰用户标签页）。绝不修改文件。",
              }).ask<string[]>(
                "端到端复测：① 开票：买家下单选单位抬头+税号（合法 15-20 位）→ 订单表 invoice_* 落库、管理端订单详情可见；非法税号（含小写/特殊字符）被拒；② 开票：个人抬头下单 → 落库正确；③ 多地址：地址簿有 2+ 地址时下单页出现选择器、默认地址自动选中、切换后订单收货信息随之变化；④ 新增地址勾默认 → 地址簿与下单选择器同步；⑤ 阶梯价回归（H1）：设了阶梯价的商品下单 15 件单价命中对应档（快照一致）；⑥ 润色/物流推荐/订阅冒烟 200。每条一行结论+证据。输出 string[]。",
              )
              for (const c of checks) report({ check: c })

              await artifact.markdown("report", [
                "# 市场化批次 H2（开票+多地址）交付",
                "",
                "## 实施",
                impl,
                "",
                "## 门禁与部署",
                "- 门禁：" + (gateOk ? "vitest + build + 本机 mvn test 全绿" : "有失败项（见复测/结果）"),
                "- backend " + (deployOk && healthy ? "healthy" : "异常") + "；frontend " + (feOk ? "已重建部署" : "未更新"),
                "",
                "## 端到端复测",
                ...checks.map(c => "- " + c),
              ].join("\n"), { title: "批次 H2 开票与多地址报告", description: "增值税发票需求与地址簿下单闭环的交付与端到端复测。", primary: true })

              return await hole<BatchSummary>(
                "推进第八批实施",
                "批次 H2 完成：" + (gateOk ? "门禁全绿" : "门禁有失败项") + "，backend " + (deployOk && healthy ? "healthy" : "异常") + "，frontend " + (feOk ? "已部署" : "未更新") + "。实施摘要：" + impl.slice(0, 400) + "\n" +
                "剩余批次全部依赖拍板：批次 D 最小出金（拍板⑦-15 提现账本落点：payment/wallet 扩展 vs 新 merchant-finance）；批次 C 评价闭环（拍板⑦-16 评价数据模型：独立 t_order_review vs 复用 productreview，另需 SearchRankScorer 回归）；批次 E2 部署与可观测（拍板⑦-12 生产形态唯一化 + ⑦-7 告警送达通道）；批次 I 运营与触达（拍板⑦-9 短信服务商/⑦-11 转人工值班）；批次 J 按拍板裁剪。填充本洞时：若用户已回复拍板项则按解锁批次实施；若仍无拍板，则实施 G2 中不依赖拍板的部分（SEO 基线中 robots/sitemap 修复与 P1-15 空态文案——独立于生产形态），并把全部待拍板批次汇总列入 skipped。", async () => {
                phase("实施批次 G2 独立部分：SEO 基线修复与空态文案")
                const impl = await agent("G2 实施工程师", {
                  system: "你是资深前端工程师：按 out/market-readiness-plan.md 的 P1-19 与 P1-15 条目，实施**不依赖拍板⑦-12（生产形态）**的部分。任务：① robots.txt/sitemap.xml——public/ 下已有旧版，按当前路由清单重写：robots.txt 允许抓取前台页面、Disallow /admin/**、/merchant/**、/me/**、/cart、/checkout、/api（登录后私有区）；sitemap.xml 仅列公开页（/、/mall、/activity、/coupons、/tasks、/venue、/membership、/credit-term、/price-monitor、/techservice/**、/live、/about 等公开路由，禁止私域路由），lastmod 用当天日期；② index.html 全局 TDK 基线——title/description/keywords 写 B2B 平台通用文案（按路由 CSR 覆盖留待生产形态拍板，本批仅全局兜底不虚构具体商品数据）；③ P1-15 空态文案——审计 Cart.vue「购物车是空的」等空态/加载态，补齐 i18n 双语（zh/en 成对，先 grep zh-en-extra 防重），空态统一用 a-empty + 行动按钮（去逛逛/返回）；④ meta 标签 og:title/og:description 基线；⑤ 不动路由结构（CSR 保持，SSR/预渲染待拍板⑦-12）；⑥ 单测：sitemap 路由清单与 router 公开路由一致性校验；⑦ 不跑全量套件（脚本统一跑）；⑧ 不动其他批次文件。",
                }).ask<string>(
                  "按 P1-19 SEO 基线 + P1-15 空态文案实施。返回：改动/新增文件清单 + 每项一句话 + skipped 项及原因（TDK 按路由注入/预渲染属拍板⑦-12 后的增强）。",
                )
                log("批次 G2-独立部分：" + impl.slice(0, 150))

                phase("统一门禁")
                let gateOk = false
                let gateTail = ""
                const fixer = agent("G2 门禁修复员")
                for (let round = 1; round <= 2; round++) {
                  log("第 " + round + " 轮门禁：vitest + build")
                  const t = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "test"], { timeoutMs: 420000, output: { tail: 2000 } })
                  const b = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "run", "build"], { timeoutMs: 420000, output: { tail: 1200 } })
                  if (t.exitCode === 0 && b.exitCode === 0) {
                    gateOk = true
                    log("第 " + round + " 轮门禁通过")
                    break
                  }
                  gateTail = (t.stdout + t.stderr + b.stdout + b.stderr).slice(-1800)
                  if (round === 2) {
                    log("两轮修复后仍有失败")
                    break
                  }
                  await fixer.ask("测试/构建失败，请修复（只动失败相关文件；不要跑全量套件与构建，脚本会重跑）：\n" + gateTail)
                }

                phase("部署与 SEO 复测")
                const deployF = await world.run("cmd", ["/c", "docker", "compose", "up", "-d", "--force-recreate", "frontend"], { timeoutMs: 300000, output: { tail: 800 } })
                let feOk = deployF.exitCode === 0
                const checks = await agent("G2 复测员", {
                  system: "你是只读复测员：PowerShell Invoke-WebRequest 实测静态文件与页面 + docker exec 核对容器内文件。绝不修改文件。",
                }).ask<string[]>(
                  "复测清单：① curl http://localhost:5173/robots.txt → 内容含 Disallow: /admin 与 /api，200；② curl http://localhost:5173/sitemap.xml → 200 且仅含公开路由（无 /admin//merchant//me//checkout）；③ curl 首页 HTML → 含 og:title/og:description 与 B2B 平台 TDK 基线；④ 空态 i18n：cart 空态文案 zh/en 成对（rg locale 文件核对）；⑤ 站点 200 冒烟。每条一行结论+证据。输出 string[]。",
                )
                for (const c of checks) report({ check: c })

                await artifact.markdown("report", [
                  "# 市场化批次 G2-独立部分（SEO 基线+空态文案）交付",
                  "",
                  "## 实施",
                  impl,
                  "",
                  "## 门禁与部署",
                  "- 门禁：" + (gateOk ? "vitest + build 全绿" : "有失败项（见复测/结果）"),
                  "- frontend " + (feOk ? "已重建部署" : "未更新"),
                  "",
                  "## 复测",
                  ...checks.map(c => "- " + c),
                ].join("\n"), { title: "批次 G2 SEO 基线报告", description: "robots/sitemap/TDK 基线与空态文案的交付与复测（独立于生产形态拍板的部分）。", primary: true })

                const pendingBatches = [
                  "批次 D 最小出金（待拍板⑦-15：提现账本落点 payment/wallet 扩展 vs 新 merchant-finance）",
                  "批次 C 评价闭环（待拍板⑦-16：评价数据模型 独立 t_order_review vs 复用 productreview；另需 SearchRankScorer 回归）",
                  "批次 E2 部署与可观测（待拍板⑦-12 生产形态唯一化 + ⑦-7 告警送达通道）",
                  "批次 I 运营与触达（待拍板⑦-9 短信服务商 + ⑦-11 转人工值班）",
                  "批次 J 按拍板裁剪（P2 池 16 项）",
                  "批次 G2 增强部分（待拍板⑦-12：按路由 TDK 注入/预渲染/SSR）",
                ]
                return await hole<BatchSummary>(
                  "推进第九批实施（待拍板汇总确认）",
                  "批次 G2-独立部分完成：" + (gateOk ? "门禁全绿" : "门禁有失败项") + "，frontend " + (feOk ? "已部署" : "未更新") + "。实施摘要：" + impl.slice(0, 300) + "\n" +
                  "剩余批次全部依赖拍板（D/C/E2/I/J/G2增强）。填充本洞时：若用户已回复拍板项则按解锁批次实施；若仍无拍板，则实施批次 I 中不依赖拍板的部分（转人工值班已有后端 waiting_agent 队列 admin 认领就绪——补前端「转人工」入口与值班占位说明，不依赖短信/值班拍板），并把全部待拍板批次汇总列入 skipped。", async () => {
                  phase("实施批次 I 独立部分：客服转人工前端入口")
                  const impl = await agent("转人工实施工程师", {
                    system: "你是资深前端工程师：按 out/market-readiness-plan.md 的 P1-20 条目实施「客服转人工」前端入口（后端 waiting_agent 队列与 admin 认领已就绪，见 CustomerCommunicationService.java:134/162/166/245 的 status 枚举——不依赖短信/值班拍板）。先读 SupportChat.vue 全文与 supportApi.ask/askStream 链路，再读计划 P1-20 的验收标准。任务：① SupportChat 对话区加「转人工」按钮（Lucide 头戴耳机图标，禁 emoji；发送后走 supportApi 落一条 needs_human 消息或按后端既有契约——先查后端是否已有转人工消息类型，没有则用普通消息 + 前端本地标记进入人工会话态）；② 人工会话态 UI：进入后显示「已转接人工客服，工作日 9:00-18:00 在线」占位说明 + 当前排队状态（值班排班待拍板⑦-11，占位文案如实写「当前时段无值班客服将进入留言队列」）；③ 消息中心管理端（CustomerCommunicationInbox）已有 status=waiting_agent 筛选——买家侧转人工消息需让后台能看到（核对消息类型字段映射，缺则补）；④ 手机断点 + token 化 + i18n 先 grep 防重；⑤ 单测：转人工按钮交互/状态切换；⑥ 不跑全量套件（脚本统一跑）；⑦ 不动其他批次已部署区。",
                  }).ask<string>(
                    "按 P1-20 独立部分实施。返回：改动/新增文件清单 + 每项一句话 + 后端契约核对结论 + skipped 项及原因。",
                  )
                  log("批次 I-独立部分：" + impl.slice(0, 150))

                  phase("统一门禁")
                  let gateOk = false
                  let gateTail = ""
                  const fixer = agent("I 门禁修复员")
                  for (let round = 1; round <= 2; round++) {
                    log("第 " + round + " 轮门禁：vitest + build")
                    const t = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "test"], { timeoutMs: 420000, output: { tail: 2000 } })
                    const b = await world.run("cmd", ["/c", "npm", "--prefix", "frontend", "run", "build"], { timeoutMs: 420000, output: { tail: 1200 } })
                    if (t.exitCode === 0 && b.exitCode === 0) {
                      gateOk = true
                      log("第 " + round + " 轮门禁通过")
                      break
                    }
                    gateTail = (t.stdout + t.stderr + b.stdout + b.stderr).slice(-1800)
                    if (round === 2) {
                      log("两轮修复后仍有失败")
                      break
                    }
                    await fixer.ask("测试/构建失败，请修复（只动失败相关文件；不要跑全量套件与构建，脚本会重跑）：\n" + gateTail)
                  }

                  phase("部署与转人工复测")
                  const deployF = await world.run("cmd", ["/c", "docker", "compose", "up", "-d", "--force-recreate", "frontend"], { timeoutMs: 300000, output: { tail: 800 } })
                  let feOk = deployF.exitCode === 0

                  const checks = await agent("I 复测员", {
                    system: "你是只读复测员：浏览器实测（自己新开独立标签页 http://localhost:5173，买家 13900000001/123456；用户自己在用 IAB——绝不碰用户标签页）+ docker exec 查库。绝不修改文件。",
                  }).ask<string[]>(
                    "复测清单：① SupportChat 出现「转人工」按钮；② 点击后进入人工会话态（占位说明+排队状态文案，值班排班待拍板⑦-11 故为占位）；③ 转人工消息落库（t_support_ticket 或 CustomerCommunication 表，按实际契约）且管理端 inbox 可见 status=waiting_agent；④ AI 自动回复在人工态暂停（或按设计继续）；⑤ 既有 AI 问答回归正常；⑥ 站点 200 冒烟。每条一行结论+证据。输出 string[]。",
                  )
                  for (const c of checks) report({ check: c })

                  await artifact.markdown("report", [
                    "# 市场化批次 I-独立部分（客服转人工入口）交付",
                    "",
                    "## 实施",
                    impl,
                    "",
                    "## 门禁与部署",
                    "- 门禁：" + (gateOk ? "vitest + build 全绿" : "有失败项（见复测/结果）"),
                    "- frontend " + (feOk ? "已重建部署" : "未更新"),
                    "",
                    "## 复测",
                    ...checks.map(c => "- " + c),
                  ].join("\n"), { title: "批次 I 转人工入口报告", description: "客服转人工前端入口的交付与复测（值班排班待拍板）。", primary: true })

                  const pendingBatches = [
                    "批次 D 最小出金（待拍板⑦-15：提现账本落点 payment/wallet 扩展 vs 新 merchant-finance + 人工对公流程确认）",
                    "批次 C 评价闭环（待拍板⑦-16：评价数据模型 独立 t_order_review vs 复用 productreview；SearchRankScorer 回归）",
                    "批次 E2 部署与可观测（待拍板⑦-12 生产形态唯一化：Railway 单容器 vs 云 compose；⑦-7 告警送达通道：SMTP vs 企微/钉钉 webhook）",
                    "批次 I 剩余（待拍板⑦-9 短信服务商选型/预算/签名备案周期 + ⑦-11 值班排班：谁接/时段/SLA/升级路径）",
                    "批次 J 按拍板裁剪（P2 池 16 项：营销自动化/数据工具深度/保险承诺类等）",
                    "批次 G2 增强（待拍板⑦-12：按路由 TDK 注入/预渲染/SSR 方案）",
                  ]
                  return await hole<BatchSummary>(
                    "推进第十批实施（全量待拍板汇总确认）",
                    "批次 I-独立部分完成：" + (gateOk ? "门禁全绿" : "门禁有失败项") + "，frontend " + (feOk ? "已部署" : "未更新") + "。实施摘要：" + impl.slice(0, 300) + "\n" +
                    "剩余批次全量依赖拍板，无独立可推进项：\n" + pendingBatches.join("\n") + "\n" +
                    "填充本洞时：不再派实施子代理（H1 阶梯批发价已由主会话实施并部署）。直接产出收尾汇总：待拍板批次全量清单（D/C/E2/I/G2增强/J池），并如实注明 H1 主会话实施情况，然后返回 BatchSummary。",
                  )
                },
                )
              },
              )
            },
            )
          },
          )
        },
        )
      },
      )
    },
    )
  },
  )
},
)
