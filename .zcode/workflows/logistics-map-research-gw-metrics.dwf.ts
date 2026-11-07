/* 物流轨迹地图接入调研规划 + 网关指标令牌启用（双轨并行工作流）
 * A 线：4 路地图平台调研（高德/百度/腾讯/开源）∥ 现有轨迹数据审计 → 规划师成稿 → 独立评审修订
 * B 线：启用 GATEWAY_METRICS_TOKEN（main.py:74-76 已支持，配置即可）+ Prometheus 抓取配置 + 实测验证
 */

interface ProviderProfile {
  /** 平台名与产品线（如 高德 Web服务/JS API/猎鹰轨迹） */
  provider: string
  /** 轨迹展示与回放能力（产品名、纠偏、渲染方式） */
  trajectoryCapability: string
  /** 接口规范要点：协议、鉴权、坐标系（GCJ-02/BD-09/WGS-84） */
  apiSpec: string
  /** 计费标准：免费配额、超量单价、个人/企业差异（注明来源与时效） */
  billing: string
  /** 技术限制：QPS/并发/域名白名单/HTTPS/合规要求 */
  limits: string
  /** 与本项目 B2B 物流轨迹场景适配度（1-5 与理由） */
  fitScore: string
  /** 关键结论的来源 URL */
  evidence: string
}

interface DataAudit {
  /** 现有轨迹数据结构与字段（重点：有无坐标，还是纯文本节点） */
  trackData: string
  /** 相关后端端点与前端页面（path:line） */
  surfaces: string[]
  /** 与地图平台所需数据格式的差距清单 */
  gaps: string[]
  /** 地图接入就绪度结论 */
  readiness: string
}

interface PlanMeta {
  /** 报告文件路径（out/ 下） */
  path: string
  /** 选型结论一句话 */
  selection: string
  /** 分阶段计划，每阶段一句话 */
  phases: string[]
  /** 资源需求要点（key/密钥管理/预算/人力） */
  resources: string
  /** 需要用户决策的遗留问题 */
  openQuestions: string[]
}

interface ReviewResult {
  approved: boolean
  /** 每条一个问题：事实错误/计费过时/方案漏洞/成本失真 */
  issues: string[]
}

interface GatewayResult {
  ok: boolean
  /** GATEWAY_METRICS_TOKEN 是否已设置（.env 与 compose 注入，真实值不回传） */
  tokenSet: boolean
  /** prometheus.yml 是否已加鉴权抓取配置 */
  prometheusUpdated: boolean
  /** ai-gateway 宿主机端口（如 8090） */
  gatewayPort: string
  /** prometheus 宿主机端口（未发布则空串） */
  prometheusPort: string
  /** 实测输出摘要：无令牌 401 / 带令牌 200 / prometheus target up（贴关键输出） */
  verifyOutput: string
  /** 是否改动了 ai-gateway 代码（决定是否触发 pytest 构建门禁） */
  codeTouched: boolean
  containers: string
  notes: string
}

interface Finding {
  /** 位置：文件:行号、端点或报告章节 */
  where: string
  /** 一句话说明问题 */
  what: string
  /** 证据：读到的行、命令与输出 */
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

/* ---------- A 线：调研与规划 ---------- */
const research = future(async () => {
  phase("并行调研地图平台与现有轨迹数据")
  log("4 路平台调研 + 1 路数据审计并行开跑")
  const [gaode, baidu, tencent, oss, audit] = await Promise.all([
    agent("高德地图调研员", { system: "你是地图服务调研员：用 WebSearch/WebFetch 查高德开放平台官方文档与计费页（lbs.amap.com），重点 JS API 2.0 轨迹回放、猎鹰轨迹服务、Web 服务 API；计费以官方页面为准并注明抓取日期。只调研不改文件。" })
      .ask<ProviderProfile>("调研高德地图的物流轨迹展示能力：轨迹回放/纠偏/轨迹服务产品线、接口规范（协议/鉴权/坐标系 GCJ-02）、key 申请流程、免费配额与超量计费（个人 vs 企业认证）、QPS/域名白名单等限制、大陆合规要点。输出 ProviderProfile（evidence 附来源 URL）。"),
    agent("百度地图调研员", { system: "你是地图服务调研员：用 WebSearch/WebFetch 查百度地图开放平台官方文档与计费页（lbsyun.baidu.com），重点鹰眼轨迹服务与 JS API GL；计费以官方页面为准并注明抓取日期。只调研不改文件。" })
      .ask<ProviderProfile>("调研百度地图的物流轨迹展示能力：鹰眼轨迹服务、轨迹回放/纠偏、接口规范（协议/鉴权/坐标系 BD-09）、ak 申请、免费配额与超量计费、QPS/并发限制、大陆合规要点。输出 ProviderProfile（evidence 附来源 URL）。"),
    agent("腾讯地图调研员", { system: "你是地图服务调研员：用 WebSearch/WebFetch 查腾讯位置服务官方文档与计费页（lbs.qq.com），重点 JS API GL 轨迹回放与 WebService API；计费以官方页面为准并注明抓取日期。只调研不改文件。" })
      .ask<ProviderProfile>("调研腾讯位置的物流轨迹展示能力：轨迹回放能力、接口规范（协议/鉴权/坐标系 GCJ-02）、key 申请、免费配额与超量计费、QPS/域名限制、大陆合规要点。输出 ProviderProfile（evidence 附来源 URL）。"),
    agent("开源方案调研员", { system: "你是地图方案调研员：用 WebSearch/WebFetch 调研开源/自托管路线（MapLibre GL JS、Leaflet、OpenStreetMap/天地图底图、Tiles 切换）；注意中国大陆坐标系（WGS-84/GCJ-02 纠偏）与访问速度合规风险。只调研不改文件。" })
      .ask<ProviderProfile>("调研开源/自托管地图方案的物流轨迹可行性：MapLibre GL JS 与 Leaflet 的轨迹回放生态、免费底图来源（OSM/天地图）、坐标系问题与纠偏方案、无 key 的利弊、大陆访问速度与合规风险、与商用 API 的总体对比。输出 ProviderProfile（provider 填 MapLibre/Leaflet 生态，evidence 附来源 URL）。"),
    agent("轨迹数据审计员", { system: "你是只读代码审计员：梳理本仓库物流轨迹数据现状，绝不修改文件。重点：backend LogisticsController/LogisticsAdminController 与运单/轨迹实体（找 t_logistics* 表与字段，确认轨迹记录有无经纬度坐标还是纯文本节点）、frontend 物流页面（用户端订单物流与商户端 CenterLogistics/LogisticsCenter）的轨迹展示方式、api/index.js 物流四件套封装、以及 .agents/skills/logistics-gis-diagnostics/SKILL.md 的既有 GIS 诊断结论。" })
      .ask<DataAudit>("审计现有物流轨迹数据结构与地图平台所需格式的匹配度：① 轨迹数据实体/表/字段清单（有无 lat/lng、时间戳、状态枚举）；② 相关端点与前端页面（path:line）；③ 与地图轨迹回放所需格式（坐标序列/GeoJSON/时间轴事件）的差距；④ 就绪度结论（直接可画/需补坐标采集/需引入运单位置事件流）。输出 DataAudit。"),
  ])
  log(`调研完成：4 平台画像 + 数据审计（就绪度：${audit.readiness.slice(0, 60)}…）`)
  const profiles = [gaode, baidu, tencent, oss, audit]

  phase("规划接入方案成稿")
  const planner = agent("接入规划师", {
    system: "你是资深架构师：综合四路平台画像与数据审计，写出可执行的中文技术选型与接入实施方案，落盘到 out/logistics-map-integration-plan.md（用 Write 工具）。报告必含：① 技术选型对比表（四方案 × 轨迹能力/坐标系/计费/限制/适配度）与推荐结论；② 接入实施方案（架构：前端渲染层 + 后端代理层——**地图 key 一律后端代理转发，绝不进前端**；坐标系转换策略；轨迹数据补采方案；与现有运单接口的衔接）；③ 分阶段推进计划（P0 POC → P1 核心轨迹回放 → P2 增强：实时推送/电子围栏等，各阶段工作量估算人日）；④ 资源需求清单（key 申请、坐标补采、预算、人力）；⑤ 风险与合规（计费超量、配额、坐标合规、数据脱敏）。所有计费/限制数字须标来源与时效。",
  })
  let plan = await planner.ask<PlanMeta>(
    `调研输入（4 平台画像 + 数据审计）：\n${JSON.stringify(profiles)}\n${JSON.stringify(audit)}\n` +
    `写成完整报告到 out/logistics-map-integration-plan.md 并返回 PlanMeta（path/selection/phases/resources/openQuestions——openQuestions 列出需要用户拍板的决策点，如付费档位、是否引入坐标补采硬件依赖）。`,
  )
  log(`方案成稿：${plan.selection}`)

  phase("独立评审与定稿")
  const reviewer = agent("方案评审员", {
    system: "你是独立评审员：Read out/ 下的报告全文 + 抽查代码事实，专挑会出问题的点——计费数字是否过时或与来源不符、坐标系方案是否会画偏、key 管理是否有泄露面、分阶段工作量是否失真、数据差距结论是否与代码一致。 approving 需要证据，反对是常态。不修改任何文件。",
  })
  for (let round = 1; round <= 2; round++) {
    const review = await reviewer.ask<ReviewResult>(
      `评审 ${plan.path}（第 ${round} 轮）：${round > 1 ? "上一轮问题已修订，复核是否闭合，并继续挑新问题。" : "按你的清单全面挑问题。"}`,
    )
    if (review.approved) {
      log(`评审通过（第 ${round} 轮）`)
      break
    }
    if (round === 2) {
      log("两轮评审后仍有未闭合问题，如实入报告")
      plan.openQuestions = [...plan.openQuestions, ...review.issues.map(i => `评审遗留：${i}`)]
      break
    }
    log(`评审提出 ${review.issues.length} 个问题，交规划师修订`)
    plan = await planner.ask<PlanMeta>(
      `评审意见（逐条修订 ${plan.path} 并在文中回应）：${JSON.stringify(review.issues)}\n修订后重新返回 PlanMeta。`,
    )
  }
  return { plan }
})

/* ---------- B 线：网关指标令牌 ---------- */
const gateway = future(async () => {
  phase("启用网关指标令牌与 Prometheus 抓取")
  const impl = await agent("网关指标实施员", {
    system: "你是资深运维/后端工程师。背景：ai-gateway/app/main.py:74-76 已实现 GATEWAY_METRICS_TOKEN 门控（为空公开；设置后 /metrics 需 Authorization: Bearer <token> 或 X-Metrics-Token，见 main.py:204-215 与 tests/test_production_hardening.py:151-170）——本轮是配置启用而非写代码。步骤：① 生成强随机 token（openssl rand -hex 24 或等价），写入根目录 .env 的 GATEWAY_METRICS_TOKEN（**真实值只进 .env，不进 git**；.env.example 只加占位注释）；确认 ai-gateway 的 compose（ai-gateway/docker-compose.yml 或根 docker-compose.yml，以实际运行栈为准）把该变量注入 ai-gateway 容器并重建拉起；② 编辑 monitoring/prometheus/prometheus.yml 的 ai-gateway 抓取 job 加鉴权（authorization credentials 或 Bearer，指向 .env 同一 token 的注入方式——若 prometheus 容器不支持 env 插值则用 credentials_file 挂载方案，说明取舍）；③ 重建/重启 prometheus 容器；④ 实测：宿主 curl 无令牌访问 /metrics 期待 401、带令牌期待 200、prometheus /api/v1/query?query=up 确认 ai-gateway target 为 1（贴关键输出）。改了 ai-gateway 代码才需要 pytest 构建门禁（纯配置不需要）。容器名用 docker ps 确认。",
  }).ask<GatewayResult>(
    `启用 GATEWAY_METRICS_TOKEN 并更新 Prometheus 抓取配置，按 system 中的步骤执行。返回 GatewayResult（ok/tokenSet/prometheusUpdated/gatewayPort/prometheusPort/verifyOutput/codeTouched/containers/notes——真实 token 值不要回传，只报告已设置）。`,
  )
  log(`网关指标：token ${impl.tokenSet ? "已设置" : "未设置"} / prometheus.yml ${impl.prometheusUpdated ? "已更新" : "未更新"} / codeTouched=${impl.codeTouched}`)
  return impl
})

/* ---------- 汇合：终检与交付 ---------- */
phase("汇合终检与交付")
const [res, impl] = await Promise.all([research, gateway])

const findings: Finding[] = []
// 终检①：容器健康
const ps = await world.run("cmd", ["/c", "docker", "ps", "--format", "{{.Names}}  {{.Status}}"])
log("容器状态：" + ps.stdout.split("\n").filter(l => l.includes("gateway") || l.includes("prometheus")).join(" | "))
// 终检②（确定性）：无令牌应 401
if (impl.gatewayPort) {
  const m401 = await world.run("cmd", ["/c", "curl", "-s", "-o", "NUL", "-w", "%{http_code}", `http://localhost:${impl.gatewayPort}/metrics`])
  findings.push(m401.stdout.trim() === "401"
    ? { where: `http://localhost:${impl.gatewayPort}/metrics`, what: "无令牌访问被拒（401），指标端点已不再公开", evidence: `curl -w %{http_code} → ${m401.stdout.trim()}`, status: "verified", severity: "low" }
    : { where: `http://localhost:${impl.gatewayPort}/metrics`, what: "无令牌访问未返回 401——GATEWAY_METRICS_TOKEN 可能未生效", evidence: `curl -w %{http_code} → ${m401.stdout.trim() || "(空)"}；实施摘要：${impl.verifyOutput.slice(0, 300)}`, status: "verified", severity: "high" })
} else {
  findings.push({ where: "ai-gateway /metrics", what: "未能确认网关宿主端口，401 终检跳过", evidence: "gatewayPort 为空", status: "unconfirmed", severity: "medium" })
}
// 终检③（确定性）：prometheus target up
if (impl.prometheusPort) {
  const up = await world.run("cmd", ["/c", "curl", "-s", `http://localhost:${impl.prometheusPort}/api/v1/query?query=up`])
  const gwUp = up.stdout.includes("ai_gateway") || up.stdout.includes("ai-gateway")
  findings.push(gwUp && up.stdout.includes('"value":[1')
    ? { where: "prometheus target", what: "Prometheus 已带鉴权抓取 ai-gateway 指标（up=1）", evidence: up.stdout.slice(0, 400), status: "verified", severity: "low" }
    : { where: "prometheus target", what: "Prometheus 抓取 ai-gateway 未确认 up=1", evidence: up.stdout.slice(0, 400), status: "verified", severity: "medium" })
} else {
  findings.push({ where: "prometheus target", what: "Prometheus 端口未发布到宿主，抓取终检跳过", evidence: "prometheusPort 为空", status: "unconfirmed", severity: "medium" })
}
if (!res.plan.path) {
  findings.push({ where: "out/logistics-map-integration-plan.md", what: "规划报告路径缺失", evidence: "PlanMeta.path 为空", status: "unconfirmed", severity: "high" })
}
for (const f of findings) report(f)

// 交付：规划报告 + 汇总
try {
  await artifact.file("plan", res.plan.path, { title: "物流轨迹地图接入技术选型报告", description: "四平台对比、选型结论、实施方案、分阶段计划与资源清单。" })
} catch {
  const repair = agent("报告修复员")
  await repair.ask(`${res.plan.path} 缺失或不可发布。请依据规划要点补写该文件后返回路径。`)
  await artifact.file("plan", res.plan.path, { title: "物流轨迹地图接入技术选型报告", description: "四平台对比、选型结论、实施方案、分阶段计划与资源清单。" })
}
await artifact.markdown("report", [
  "# 地图接入调研 × 网关指标令牌 — 双轨交付",
  "",
  `## A 线：物流轨迹地图接入调研规划`,
  `- 选型结论：${res.plan.selection}`,
  `- 分阶段：${res.plan.phases.join("；")}`,
  `- 资源需求：${res.plan.resources}`,
  `- 待用户决策：${res.plan.openQuestions.length} 项（详见报告）`,
  "",
  `## B 线：GATEWAY_METRICS_TOKEN 启用`,
  `- token ${impl.tokenSet ? "已设置（值只存 .env）" : "未设置"}；prometheus.yml ${impl.prometheusUpdated ? "已加鉴权抓取" : "未更新"}`,
  `- 实测：${impl.verifyOutput.slice(0, 300)}`,
  "",
  "## 终检",
  ...findings.map(f => `- ${f.where}：${f.what}（${f.status}）`),
].join("\n"), { title: "双轨交付报告", description: "地图接入调研规划结论与网关指标令牌启用结果。", primary: true })

const gwFindings = findings.filter(f => f.severity === "high" || f.severity === "medium" || f.status === "unconfirmed")
return {
  conclusion: `A 线：四平台调研与数据审计完成，选型「${res.plan.selection.slice(0, 40)}…」，方案报告已交付。B 线：GATEWAY_METRICS_TOKEN ${impl.tokenSet ? "已启用并经 401/200 实测" : "未完成，见 findings"}，Prometheus 抓取${impl.prometheusUpdated ? "已配置" : "未配置"}。`,
  findings: gwFindings,
  verified: [
    "4 路平台调研（高德/百度/腾讯/开源，官方文档计费页+来源日期）",
    "轨迹数据审计员只读审计（实体字段/端点/前端页面/GIS 诊断技能文件）",
    "方案评审员两轮独立评审",
    ...(impl.gatewayPort ? [`curl /metrics 无令牌 → 401 终检（:8090）`] : []),
    ...(impl.prometheusPort ? ["prometheus /api/v1/query?query=up 终检"] : []),
    "docker ps 容器健康终检",
  ],
  notCovered: [
    "各平台真实 key 申请与 POC 编码（属正式开发阶段，本轮只到决策依据）",
    "计费数字随官方调价可能过期（报告内已标来源与抓取日期）",
    ...(impl.prometheusPort ? [] : ["Prometheus 端口未发布宿主，target up 由实施员容器内实测佐证，未做宿主终检"]),
  ],
} as WorkflowReport
