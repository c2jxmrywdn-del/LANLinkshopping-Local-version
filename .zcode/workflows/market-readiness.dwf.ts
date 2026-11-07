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
  "填充本洞时按 batchOrder 首批定义实施拓扑（每批=实施+门禁 vitest/build/mvn test+前后端部署+运行时复测）；本批完成后的 fill 若尚有剩余批次，以新的尾洞（同一接口）继续接力，直至 remaining 为空后返回整体 WorkflowReport。前置确认：进行中的 P1（dwfrun-5349bf12）/AI 润色（dwfrun-effd87ea）/承运商（dwfrun-987e699c）管线清零后本批才动文件（schema.sql/zh-en-extra.js/构建竞态）；路线图条目若与这三批重叠须先核对现状避免重复实施。",
)
