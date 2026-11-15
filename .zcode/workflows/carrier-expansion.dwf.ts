/* 承运商目录扩展：跨越速运 + 工业大件物流 + 国际物流公司（调研设计先行，实施尾洞排队）
 * 需求：① 增加「跨越速运」（工业供应链时效强）等支持工业大件的物流公司；② 增加国际物流公司。
 * 约束：串行洞队列 P1（dwfrun-3844fafa）→ AI 润色（dwfrun-d3a72aa3）→ 本任务实施尾洞。
 *      schema.sql/DataInitializer 是热冲突文件（P1 在改 schema.sql）——审计只读不受影响。
 */

interface CarrierCatalogAudit {
  /** 承运商目录现状：定义位置（schema 种子/DataInitializer/枚举/表结构字段 path:line）与现有清单 */
  catalog: string
  /** 推荐与计费链路：LogisticsCarrierRecommenderService 的推荐规则、运费计算档位（大件/重货是否已有口径）、SLA 看门狗关联 */
  recommender: string
  /** 前端展示面：CarrierRecommendationCards.vue 等卡片与选择器 */
  surfaces: string[]
  /** 扩展接入建议（到文件级：新增承运商的落点、档案字段、前端卡片） */
  fixPlan: string[]
  notes: string
}

interface CarrierProposals {
  /** 每家：名称/定位/大件能力/工业适配/国际线路/建议档案字段与推荐规则贡献 */
  domesticHeavy: string[]
  international: string[]
  /** 国际线路是否可落本期（目录+展示）还是必须连带计价模型（P1 建议） */
  internationalScope: string
  notes: string
}

interface DesignDoc {
  path: string
  /** 目录扩展契约一句话（新增几家、档案字段、类型标签） */
  catalogContract: string
  /** 推荐规则与大件计费口径变化一句话 */
  recommenderChange: string
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

phase("并行审计目录现状与调研候选承运商")
const [audit, proposals] = await Promise.all([
  agent("承运商目录审计员", {
    system: "你是只读审计员（不改文件）：盘点承运商目录全链路。线索：schema.sql / db-migrations / DataInitializer 里的承运商种子与表结构、service/LogisticsCarrierRecommenderService.java（推荐规则）、LogisticsCarrierRecommenderService 相关的运费计算（LogisticsFreightCalculatorTieredTest 暗示档位计费）、LogisticsSlaWatchdogService、前端 components/logistics/CarrierRecommendationCards.vue 与下单选承运商处。注意 P1 批次稍后会改 schema.sql（订阅单）——本审计只读不受影响。",
  }).ask<CarrierCatalogAudit>(
    "输出 CarrierCatalogAudit：catalog（现有承运商清单与落点 path:line）/recommender（推荐与计费对承运商属性的依赖：大件/重货/时效字段是否已存在）/surfaces/fixPlan。中文。",
  ),
  agent("承运商调研员", {
    system: "你是物流行业调研员（只读）：用 WebSearch/WebFetch 调研候选承运商（来源标注时效）。国内工业大件向：跨越速运（工业供应链/时效定位）、德邦快递、顺丰快运、安能物流、中通快运、百世快运等大件快运玩家——各家的 大件/重货 能力、B2B 工业适配、时效档位。国际向：DHL、FedEx、UPS、中远海运（COSCO）、马士基（Maersk）、菜鸟国际等——工业品出口适配、大件/拼箱能力、线路覆盖。",
  }).ask<CarrierProposals>(
    "输出 CarrierProposals：domesticHeavy（建议入选的国内大件承运商清单+每家一句话档案）/international（建议入选的国际承运商+线路）/internationalScope（国际线路本期只做目录+展示标注，还是必须连带国际运费计价模型——给工作量对比与建议）。中文。",
  ),
])
log("审计与调研完成")

phase("方案成稿")
const designer = agent("承运商扩展设计师", {
  system: "你是资深物流域设计师：综合审计与调研写中文方案到 out/carrier-expansion-design.md（Write 工具）。必含：① 目录扩展——新增承运商档案（code/name/类型标签：大件/工业/国际/时效档/默认时效与起价），种子落点选型（schema.sql 种子 vs DataInitializer vs SchemaInitializer——给出与 P1 批次 schema.sql 改动的避让策略）；② 推荐规则——大件/重货商品（体积/重量超阈值）优先推荐大件承运商、国际线路商品的展示规则；③ 运费口径——大件计费是否引入重量/体积档（与既有 Tiered 计费的关系，改动最小化）；④ 前端——CarrierRecommendationCards 与选择器的新类型标签展示（token 化/Lucide/手机断点）、国际承运商「国际线路 · 报价联系客服」类诚实标注；⑤ 测试（推荐规则/计费回归）与 i18n；⑥ 分阶段与文件级清单；⑦ 需拍板项单列（国际计价模型是否本期、各家是否全录）。",
})
let design = await designer.ask<DesignDoc>(
  "目录审计：" + JSON.stringify(audit) + "\n承运商调研：" + JSON.stringify(proposals) + "\n写成方案到 out/carrier-expansion-design.md 并返回 DesignDoc。",
)
log("方案成稿：" + design.catalogContract)

phase("独立评审定稿")
const reviewer = agent("方案评审员", {
  system: "你是独立评审员：Read out/carrier-expansion-design.md 全文，专挑问题——种子落点是否会与 P1 批次的 schema.sql 改动撞车、推荐规则是否会影响既有订单运费回归、国际承运商是否给了「无法在线计价」的诚实标注、大件阈值是否拍脑袋（要有与商品数据的对照）、i18n/测试/手机断点遗漏。需要证据，反对是常态。不改文件。",
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
  "实施承运商扩展",
  "方案已定稿：" + design.path + "（目录契约：" + design.catalogContract + "；推荐规则：" + design.recommenderChange + "）。需拍板：" + design.decisions.join("；") + "\n" +
  "填充本洞时定义实施拓扑。前置确认：VIP P1（dwfrun-3844fafa）与 AI 润色（dwfrun-d3a72aa3）均已完成（schema.sql/DataInitializer/zh-en-extra.js/构建竞态全部解除）；实施含目录种子+推荐规则+前端卡片，门禁 vitest+build+本机 mvn test，部署前后端镜像，运行时复测（大件商品推荐命中新承运商、国际标注诚实展示、既有运费回归）。", async () => {
  phase("落地承运商目录与推荐规则")
  const impl = await agent("承运商扩展实施工程师", {
    system: "你是资深全栈工程师：按 out/carrier-expansion-design.md 实施（先通读全文与 §6 评审回应表）。拍板定案：① 国际计价模型本期不做（目录+展示+静态询价文案；计价模型 2-3 人周列 P1 触发条件=国际询价转化达标）；② 全录：国内大件 3 家（KYE/ANE/BEST）+ 国际 6 家（status=0 落库，P1 防线上线后置 1）；③ 种子价格核对：尝试 WebFetch ky-express.com/deppon.com/sf-freight.com 二次核对，不可达（本机对外域名有 TLS 阻断先例）则按调研值落库并在返回里标注「未独立核对」；④ 顺丰快运/中通快运挂既有 SF/ZT 大件模板（不新增独立结算商）；⑤ 大件计费不加体积档（volume_factor 已覆盖抛货）；⑥ 国际线路商户手动选（scope 分组）；⑦ 国际询价入口=静态客服文案；⑧ 基于当前工作区直接实施（实施时 git 实测与追加段的 hunk 距离，防撞车）；⑨ 商品域重量/体积字段本期不加（验收入参商户手输 weight=80kg/volume=0.4m³ 触发大件，UI 引导文案）；⑩ 管理端新增控件文案纳入 i18n。实现要点照设计：capability_json 统一口径（scope/heavyCargo/pallet/install/guaranteed/costLevel/routeNotes）；种子按业务键幂等（provider_code 唯一键 + template_name NOT EXISTS，弃固定 id+INSERT IGNORE）；双层落点（schema.sql 追加段只写 CREATE TABLE 既有列，运行时补列由 Phase3A/Phase2 初始化器回填）；管理端 ProviderAdminDTO（实体 @JsonIgnore 双向忽略）；大件推荐 heavyCargo 软加权 ×0.85（阈值 >50kg 或 >0.3m³ 或 oversize，沿用 Recommender:151 边界）；国际四面隔离（推荐剔除+status=0 下拉不可见+WaybillService:467-474 scope 拒绝+前端阻断）；¥0 修复=比价恒传 0+「满 ¥{x} 包邮」参数化角标（Calculator 不改）；tiered 模板强制 min_charge>0 兜底 calculateTiered 坏 JSON 静默返 0、saveTemplate 加解析校验；checkVasSupport 改 Jackson 解析补 oversizeOverweight。硬约束：推荐/计费补单测（KYE 阶梯自洽 50kg=30+50×0.3=45、大件加权、国际隔离、¥0 比价）；i18n 新键先 grep zh-en-extra.js 防重；token 化；手机断点；不跑全量套件与构建（脚本统一跑）；不动其他批次文件（VIP P0/P1、退款凭证、AI 润色）。",
  }).ask<string>(
    "按设计稿与上述定案实施。返回：改动/新增文件清单 + 每项一句话说明 + 种子价格核对结果 + skipped 项及原因。",
  )
  log("承运商扩展实施：" + impl.slice(0, 150))

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

  phase("重建部署与运行时复测")
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

  const checks = await agent("承运商扩展复测员", {
    system: "你是只读复测员：API 实测 + 浏览器目检（自己新开独立标签页 http://localhost:5173，商户 13911112222/123456 下单发货页；用户自己在用 IAB——绝不碰用户标签页）。401/403 是 body.code 且 HTTP 200，断言查 json.code。绝不修改文件。",
  }).ask<string[]>(
    "复测清单：① 目录核对：KYE/ANE/BEST + 国际 6 家在库（API 或库证），国际 6 家 status=0；② 大件推荐：weight=80/volume=0.4 发货参数 → 推荐命中大件承运商（KYE/ANE 优先）且软加权生效；③ 国际隔离四面：推荐不出现国际商、下拉不可见、越 scope 下单被拒、前端阻断；④ 运费回归：既有轻小件运费与改造前一致（比价 ¥0 场景恒传 0+包邮角标参数化）；⑤ 前端卡片新类型标签渲染（大件/国际标注）+ 国际承运商「报价联系客服」诚实文案；⑥ 管理端 capability 编辑经 ProviderAdminDTO 正常保存；⑦ 既有订单运费计算回归（Calculator 未动的路径）。每条一行结论+证据，失败如实标注。输出 string[]。",
  )
  for (const c of checks) report({ check: c })

  await artifact.markdown("report", [
    "# 承运商目录扩展交付报告",
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
  ].join("\n"), { title: "承运商目录扩展报告", description: "跨越速运等工业大件承运商与国际承运商目录扩展、推荐规则与隔离。", primary: true })

  return {
    fixed: [impl.slice(0, 900)],
    skipped: [
      "国际运费计价模型（2-3 人周+运价运营，触发条件=国际询价转化达标，届时对接 DHL/FedEx/UPS 官方 rate API）",
      "商品域重量/体积字段与 SKU 带出（另立项）",
      "国际询价工单/接口（另立项，本期静态客服文案）",
      "种子价格独立核对若官网不可达则标注未核对",
    ],
    gates: (gateOk ? "vitest+build+本机 mvn test 全绿" : "门禁有失败项") + ";backend " + (deployOk && healthy ? "healthy" : "异常") + ";frontend " + (feOk ? "已重建部署" : "未更新") + ";复测 " + checks.length + " 条",
  } as ImplSummary
},
)
