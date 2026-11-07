/* 退款凭证强制 + 物流地图主流化（调研设计先行，实施尾洞串行等待）
 * 任务 1：退款申请必须附证据——图片/视频，或与商家联系后的文件说明（截图实锤：现弹窗仅原因文本 0/200）。
 * 任务 2：物流地图完全不行（自绘深色矢量"全景感知"风），接入主流地图——已有选型报告
 *        out/logistics-map-integration-plan.md（高德 JS API 2.0 主选）+ 安全批次已部署 /logistics/map-config
 *        （key 后端下发）。本任务=把 AMap 引擎做成真底图+真轨迹+车标+回放。
 * 约束：商户修复批次 dwfrun-705b3a47 与 VIP 实施（待填洞）在前——本工作流只读+写 out/，
 *      实施尾洞等前两批清零后由主会话填充（zh-en-extra.js 同文件 + dist/ 构建竞态）。
 */

interface RefundAudit {
  /** 现状：退款申请前端弹窗/端点/后端 DTO 与存储（path:line） */
  current: string
  /** 可复用的上传能力（chat 双上传端点？通用上传？大小/类型限制） */
  uploadCaps: string
  /** 证据要落到哪（表/列/JSON 契约建议）与商户端/管理端审核展示面 */
  evidenceContract: string
  /** 实施要点清单（到文件级） */
  fixPlan: string[]
  notes: string
}

interface MapAudit {
  /** 现状：LogisticsDynamicMap 双引擎、amap 路径半成品点（amapTruckMarker 等）、map-config 消费（path:line） */
  current: string
  /** .env/compose 是否已有真实高德 key（只报有无，不回传值） */
  keyStatus: string
  /** 轨迹数据现状：trace 坐标字段/坐标系假设/回放数据源（traces 端点） */
  trackData: string
  /** 主流化实施要点（到文件级，按 out/logistics-map-integration-plan.md 的 P0/P1 切片） */
  fixPlan: string[]
  notes: string
}

interface DesignDoc {
  path: string
  /** 退款凭证契约一句话 */
  refundContract: string
  /** 地图主流化切片一句话 */
  mapSlice: string
  /** 实施阶段 */
  phases: string[]
  /** 需用户拍板（如高德 key 提供、视频上限） */
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

phase("并行审计退款链路与地图现状")
const [refund, map] = await Promise.all([
  agent("退款链路审计员", {
    system: "你是只读审计员（不改文件）：盘点退款申请全链路。线索：买家端「申请退款」弹窗（截图：订单金额+原因 textarea 0/200+提交申请）在 OrderDetail/MyOrders 类页面；后端退款申请端点与 DTO（RefundService/order 相关，注意另一批次改的是 CreditTerm 账期与广告，与本链路不冲突）；上传能力：聊天媒体上传双端点（照片/文档/语音契约、zip 族落 .bin）可否复用于退款凭证；存储与审核展示面（商户端 CenterOrders/管理端退款审核页）。注意商户批次正在改 zh-en-extra.js 与 center 页——只读不受影响。",
  }).ask<RefundAudit>(
    "输出 RefundAudit：current（现状链路 path:line）/uploadCaps（可复用上传端点与限制）/evidenceContract（证据落库契约建议：类型枚举 image/video/file + URL + 名称，落哪张表哪列，SchemaInitializer 幂等迁移模式）/fixPlan（到文件级：买家弹窗上传区+已联系商家文件说明路径、后端校验 ≥1 证据、商户/管理端展示）。中文。",
  ),
  agent("地图现状审计员", {
    system: "你是只读审计员（不改文件）：盘点物流地图现状。线索：frontend/src/components/logistics/LogisticsDynamicMap.vue（安全批次刚改过：:836/:849 消费后端 map-config 下发的 key/securityJsCode，localStorage 手填通道已删）、双引擎结构（:446）、amapTruckMarker 单命中半成品、自绘矢量底图=用户截图里的深色全景感知风；选型报告 out/logistics-map-integration-plan.md（高德 JS API 2.0 主选+WGS-84 权威存储+展示时转 GCJ-02）；traces 端点与 trace 坐标字段（LogisticsTrace.java 有坐标/时间/状态字段但 schema 建表无坐标列——确认实际数据源）；后端 /logistics/map-config 读哪个 env 变量、.env 里有没有真实 key（只报有无不回传值）。",
  }).ask<MapAudit>(
    "输出 MapAudit：current（双引擎与 amap 半成品清单 path:line）/keyStatus（真实 key 有无）/trackData（回放数据源与坐标系现状）/fixPlan（AMap 引擎做成真底图+真轨迹 Polyline+车标 marker+对接既有 0.5x/1x/2x 回放条+HUD 保留真实数据；无 key 时优雅降级并提示；到文件级）。中文。",
  ),
])
log("审计完成：退款链路 + 地图现状")

phase("方案成稿")
const designer = agent("退款地图设计师", {
  system: "你是资深全栈设计师：综合两路审计写中文设计方案到 out/refund-map-design.md（Write 工具）。退款凭证：证据类型 image/video/file（与商家联系后的说明文件）、≥1 必填校验（前端+后端双层）、上传复用既有端点、落库契约、买家弹窗 UI（上传区+文件说明路径+既有 Apple 风弹窗内嵌）、商户端/管理端审核展示、大小/数量限制、i18n（先 grep zh-en-extra 防重）。地图：AMap 引擎真底图+轨迹线+车标+回放对接（0.5x/1x/2x 与进度条）、WGS-84→GCJ-02 转换、无 key 优雅降级+引导文案、HUD 数据面板保留、双引擎去留取舍、按选型报告 P0 切片不给空承诺。全部 token 化（--ll-*/.ll-glass--day）零硬编码色值、Lucide 禁 emoji、手机断点 ≤991/≤700、分阶段与文件级清单、需拍板项单列。",
})
let design = await designer.ask<DesignDoc>(
  "退款审计：" + JSON.stringify(refund) + "\n地图审计：" + JSON.stringify(map) + "\n写成方案到 out/refund-map-design.md 并返回 DesignDoc。",
)
log("方案成稿：" + design.mapSlice)

phase("独立评审定稿")
const reviewer = agent("方案评审员", {
  system: "你是独立评审员：Read out/refund-map-design.md 全文，专挑问题——证据校验是否双层（只前端=打回）、上传复用是否破坏聊天契约、视频大小上限是否给默认值、地图是否给了无 key 也可运行的降级路径、坐标系转换方向是否正确（高德吃 GCJ-02）、是否误引入新 npm 依赖、文件级清单是否漏 i18n/测试/移动端。需要证据，反对是常态。不改文件。",
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
  "实施退款凭证与主流地图",
  "方案已定稿：" + design.path + "（退款契约：" + design.refundContract + "；地图切片：" + design.mapSlice + "）。需拍板：" + design.decisions.join("；") + "\n" +
  "填充本洞时定义实施拓扑。前置确认：商户修复批次与 VIP 实施均已完成（zh-en-extra.js 与构建竞态解除）；实施后需前后端镜像重建部署并运行时复测（退款被拒路径+地图真实底图目检——若用户尚未提供真实高德 key，底图目检以降级路径+代码核对为准并如实标注）。", async () => {
  phase("落地退款凭证契约与高德引擎")
  const impl = await agent("退款地图实施工程师", {
    system: "你是资深全栈工程师：按 out/refund-map-design.md 实施（先通读全文与 §6 评审回应表）。拍板项按以下定案执行：① video 证据本期做，单文件 ≤20MB（multipart 全局上限内零配置）；② 证据必填统一 ≥1，数量上限 9（i18n 键随之）；③ 「已联系商家」按协商凭证指引新增文案；④ 两弹窗既有硬编码中文顺带接 t()；⑤ config/RefundSchemaInitializer.java 双类并存问题已由主会话解决（现仅存 support 版，勿再动）；⑥ t_order_rma 整表迁 schema.sql 留 P1；⑦ 回放不加视角跟随；⑧ 双引擎保留 vector 兜底；⑨ 字典 datum 实测：用已配置的真实 key 对 1-2 城做高德逆地理抽查，按实测决定 wgs84ToGcj02 是否直通（保留函数与开关，结论写进返回）；⑩ AMAP 凭据处置（B8）已由主会话完成——.env.example 已占位化、.env 已配用户确认的真值且 map-config 实测下发成功，直接沿用勿重做；⑪ HUD 车牌/温湿度虚构兜底随 A4 一并移除。加载器（用户明确指令）：引入官方 @amap/amap-jsapi-loader@^1.0.1（用户提供的 JSAPI_Vue3_2 模板范式，参考 D:/TEMP/amap-vue3/src/components/MapContainer.vue）：替换 LogisticsDynamicMap.vue 手写脚本注入——先设 window._AMapSecurityConfig={securityJsCode}（值来自后端 map-config 下发）再 AMapLoader.load({key, version:'2.0', plugins})，卸载 destroy；失败分支照设计 failAmap 收口。硬约束：证据 JSON 契约与 RefundEvidenceValidator 五要素照设计（数量/type 白名单/URL 前缀+无 ../、type↔落盘扩展名一致/URL userId 段归属/File.exists）；链A新列 t_order.refund_evidences TEXT（OrderRmaService:91 调用点传 null）+ 链B proof_images 改 TEXT 双同步；校验/契约补单测（模仿仓库风格）；i18n 12 条新键入 zh-en-extra.js（先 grep 防重——该文件刚清理过 41 条冗余）；token 化零硬编码色值；手机断点 ≤991/≤700；不跑全量套件与构建（脚本统一跑）；不动 VIP 组件/物流越权（已部署）/存储凭据相关文件。",
  }).ask<string>(
    "按设计稿与上述定案实施。返回：改动/新增文件清单 + 每项一句话说明 + datum 实测结论 + skipped 项及原因。",
  )
  log("退款+地图实施：" + impl.slice(0, 150))

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

  const checks = await agent("退款地图复测员", {
    system: "你是只读复测员：API 实测 + 浏览器目检（自己新开独立标签页 http://localhost:5173，买家 13900000001/123456、商户 13911112222/123456；用户自己在用 IAB——绝不碰用户标签页）。401/403 是 body.code 且 HTTP 200，断言查 json.code。绝不修改文件。",
  }).ask<string[]>(
    "复测清单：① 买家无证据提交退款被拒（json.code!=200，提示需要凭证）；② 带 ≥1 条证据 URL（先经设计指定上传端点真实上传取得 /api/uploads/ 前缀 URL）提交成功，商户端/管理端审核面可见 Gallery；③ 订单物流页地图：真实高德底图+两段 Polyline+车标渲染（浏览器截图路径）；④ 回放 0.5x/1x/2x 与进度条驱动车标移动；⑤ 无 key 降级蒙层与引导文案（代码核对即可，不清线上 key）；⑥ datum 实测结论已落地（直通/转换哪个生效）；⑦ 未知路径返回 404 兜底页。每条一行结论+证据，发现问题如实标注。输出 string[]。",
  )
  for (const c of checks) report({ check: c })

  await artifact.markdown("report", [
    "# 退款凭证与主流地图交付报告",
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
  ].join("\n"), { title: "退款凭证与主流地图报告", description: "退款证据契约两链统一、AMap 官方加载器接入与运行时复测。", primary: true })

  return {
    fixed: [impl.slice(0, 900)],
    skipped: [
      "video 50MB 上限（需调全局 multipart + 部署侧核对，待拍板；本期 20MB）",
      "证据差异化必填（按原因分类）待拍板——本期统一 ≥1",
      "t_order_rma 整表迁 schema.sql（P1）",
      "回放视角跟随（P0 不做）",
      "双引擎长期去留（P1 GPS 数据上线后重议）",
    ],
    gates: (gateOk ? "vitest+build+本机 mvn test 全绿" : "门禁有失败项") + ";backend " + (deployOk && healthy ? "healthy" : "异常") + ";frontend " + (feOk ? "已重建部署" : "未更新") + ";复测 " + checks.length + " 条",
  } as ImplSummary
},
)
