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
  "填充本洞时定义实施拓扑。前置确认：VIP P1（dwfrun-3844fafa）与 AI 润色（dwfrun-d3a72aa3）均已完成（schema.sql/DataInitializer/zh-en-extra.js/构建竞态全部解除）；实施含目录种子+推荐规则+前端卡片，门禁 vitest+build+本机 mvn test，部署前后端镜像，运行时复测（大件商品推荐命中新承运商、国际标注诚实展示、既有运费回归）。",
)
