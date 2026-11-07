/* 商户中心第二轮全量审计：业务逻辑 + 跳转便携性（只读）
 * 背景：第一轮（2026-11-03/04）21 项已修；本轮复审回归 + 补盲区。
 * 约束：安全批次 dwfrun-f73766fa 正在跑（会改物流/存储文件并重建 backend 容器）——
 *      本工作流全程只读，修复放在尾洞（安全批次完成后再填，规避并行编辑竞态）。
 */

interface AuditFinding {
  /** 位置：文件:行号 / 路由 / 端点 */
  where: string
  /** 一句话说明问题 */
  what: string
  /** 证据：读到的行、命令输出或浏览器实测记录 */
  evidence: string
  /** 用户视角的损失；high=资金/越权/页面不可用 */
  severity: "low" | "medium" | "high"
  /** business=业务逻辑 / nav=跳转便携 / contract=前后端契约 / security=安全 */
  category: "business" | "nav" | "contract" | "security"
  /** 修复要点（到文件级） */
  fixHint: string
}

interface TrackResult {
  findings: AuditFinding[]
  /** 本线实际覆盖的页面/端点清单 */
  coverage: string[]
}

interface AuditDigest {
  /** 审计册落盘路径（out/ 下） */
  path: string
  /** 分级计数：high N / medium N / low N（去重后） */
  counts: string
  /** P0 必修项，每条一句话 */
  top: string[]
  /** 快赢项：小改动高收益 */
  quickWins: string[]
  /** 需要用户拍板的取舍 */
  decisions: string[]
}

interface FixSummary {
  fixed: string[]
  skipped: string[]
  /** 门禁与验证结论 */
  gates: string
}

phase("并行审计商户中心")
const [backend, frontend, nav] = await Promise.all([
  agent("商户后端审计员", {
    system: "你是只读后端审计员：绝不修改任何文件。范围=商户域全部端点：MerchantCenterController、CreditTermController、AdCampaignController、campaign 商户端点（/campaign/merchant/*）、商户结算/钱包/账期、商户物流端点、商户视角的订单状态机与退款。审：越权（merId 隔离 fail-closed）、金额精度与口径、幂等（重复提交/并发）、状态机非法流转、配额并发击穿、分页过滤正确性、异常语义。可用 curl 只读实测（演示商户 13911112222/123456；AuthInterceptor 401/403 是 body.code 且 HTTP 200，断言查 json.code；POST 仅限可逆操作且必须在 finding 里标注已回滚）。" +
      "注意：① 另一安全批次正在修 L1 运单属主校验与 M 系存储凭据——勿重复报；② 已修项勿重复报：订单完成/退款语义、广告原子计费、活动 21 项、商户促销审核流；③ backend 容器可能正被重建——遇 ECONNRESET 先查容器 health，等 45s 重试。",
  }).ask<TrackResult>(
    `全面排查商户中心后端业务逻辑问题。方法：glob 商户相关 Controller/Service → 逐端点读校验链 → 抽查 curl 实测。findings 按真实严重度给（不确定就 medium+备注），coverage 列出你审过的端点。输出 TrackResult（findings/coverage，中文）。`,
  ),
  agent("商户前端审计员", {
    system: "你是只读前端审计员：绝不修改任何文件。范围=frontend/src/views/center/ 全部页面 + components/center/ + api/index.js 商户段封装 + 商户路由。审：request.js 剥壳规则（视图禁读 r.data——曾致整页恒空）、api 封装与后端端点对齐（漏封装=静默瘫痪）、加载/空态/错误态三态齐备、列表筛选分页状态、表单校验与防抖、权限路由守卫、i18n 词条重复（zh-en-extra）、全站禁 emoji（用 lucide）。浏览器实测必须开独立标签页（用户自己在操作 in-app browser，不许动用户的标签页）；用 http://localhost:5173（现为当前构建）登录演示商户 13911112222/123456。",
  }).ask<TrackResult>(
    `全面排查商户中心前端问题：逐页读代码 → 三态与契约核对 → 浏览器抽查关键页（订单/商品/营销/钱包/物流）。输出 TrackResult（findings/coverage，中文）。`,
  ),
  agent("跳转便携性审计员", {
    system: "你是只读导航审计员：绝不修改任何文件。「跳转页面便携性」=商户中心内部及进出商户中心的一切跳转是否顺手：① 梳理路由树（router 商户段）与菜单结构，列出全部商户页面；② 浏览器实测（独立标签页，5173，商户 13911112222/123456）：菜单互跳覆盖度、页面间直达互跳（如订单详情↔商品↔营销有无断链）、面包屑/返回行为、深链直达（未登录访问→/login→登录后是否回跳原页、redirect 参数是否保住）、前台入口（首页/商品详情/我的订单）进商户中心、tab/筛选/分页状态跳转后是否丢失、404/空态引导、手机断点 ≤991/≤700 布局与触达；③ 后端容器可能正被重建——ECONNRESET 等 45s 重试；④ 评估每条跳转的「层级深度」（几步可达高频页）。",
  }).ask<TrackResult>(
    `产出商户中心完整导航审计：findings 每条带实测证据（URL/步骤/截图路径），另在 coverage 里给出「页面清单 + 当前互跳矩阵摘要」。输出 TrackResult（中文）。`,
  ),
])
const all = [...backend.findings, ...frontend.findings, ...nav.findings]
log(`审计完成：后端 ${backend.findings.length} + 前端 ${frontend.findings.length} + 跳转 ${nav.findings.length} = ${all.length} 条原始发现`)

phase("汇总定级成册")
const digest = await agent("审计汇总评级员", {
  system: "你是审计汇总评级员：合并三线发现——跨线重复项去重合并、severity 按用户视角校准（资金/越权/页面不可用=high）、category 归位；用 Write 工具落盘 out/merchant-center-audit-1105.md（结构：执行摘要 → P0/P1/P2 分级清单（每条 where/what/fixHint）→ 快赢项 → 需拍板项 → 附录：三线 coverage 与证据索引）。批判性校准：证据不足的降级并标注。",
}).ask<AuditDigest>(
  `三线审计结果：\n后端：${JSON.stringify(backend)}\n前端：${JSON.stringify(frontend)}\n跳转：${JSON.stringify(nav)}\n` +
  `去重定级成册到 out/merchant-center-audit-1105.md，返回 AuditDigest（path/counts/top/quickWins/decisions，中文）。`,
)
log(`定级完成：${digest.counts}；册子 ${digest.path}`)

/* 修复尾洞：安全批次完成后由主会话填充（并行编辑禁令） */
return await hole<FixSummary>(
  "实施商户中心修复",
  `审计册已就绪：${digest.path}（${digest.counts}）。P0：${digest.top.join("；")}。快赢：${digest.quickWins.join("；")}。需拍板：${digest.decisions.join("；")}。\n` +
  `填充本洞时定义修复拓扑（前置确认：安全批次 dwfrun-f73766fa 已完成，backend 容器已重建——并行编辑禁令解除）。`, async () => {
  interface DeployCheck {
    /** 复测点位置 */
    where: string
    /** 一句话结论 */
    what: string
    /** 证据：命令与输出 */
    evidence: string
    status: "verified" | "unconfirmed"
    severity: "low" | "medium" | "high"
  }

  phase("实施商户中心 P0 与快赢修复")
  const implSummary = await agent("商户修复工程师", {
    system: "你是资深全栈工程师：按 out/merchant-center-audit-1105.md 实施修复（先通读该册）。默认取舍——P0 全做：① CreditTermService.java:249-265 结算单支付补属主校验（fail-closed，非本商户 approved 单拒绝）+ 原子条件更新（UPDATE ... SET status='paid' WHERE id=? AND status='approved'，affected=0 即拒绝，杜绝并发双重建账单）；② AdCampaignService.java:363-394 settle/退款补条件更新或乐观锁（到期定时与手动终止并发只退一次钱包）；③ /ad/track click 保守收紧（匿名 click 加单 IP+目标去重窗口，不动正常投放语义；曝光关联/预扣结算等激进方案列 skipped 待拍板）；④ e-POD 验签契约断裂：前端 CenterLogistics.vue handleAuditSubmit 改发 {action:'approve'|'reject', auditNote} 对齐后端契约；⑤ CenterFinance.vue:262-290 提现假闭环：保守下架入口改「提现功能即将开放，请联系客户经理」诚实文案（真实提现端点待拍板，列 skipped）。快赢全做：CenterPromotion.vue:312 改 walletBalance、merchantOrders 服务层 size 做 Math.min(size,100)、sla-overview 端点补 @RequirePerm、路由加 /:pathMatch(.*) 404 兜底页、CenterOrders tab/page 用 router.replace 写 URL、三处 orderStatus=4 错译改 ORDER_STATUS 字典（4=部分发货）、batchReview 收集 per-id 失败原因返回 {failed:[{merId,reason}]}、i18n 同值冗余词条清理并跑 frontend/scripts/check-i18n-duplicates.mjs 验证。硬约束：后端并发修复一律条件更新（禁全实体写回）；P0-1/P0-2 补单测（模仿既有测试风格）；新中文文案先 grep i18n/zh-en-extra.js 防重复；不跑全量测试与构建（脚本统一跑）；不动物流/存储/VIP 相关文件（其他批次已改完，避免冲突）；改动若与审计册冲突以册内证据为准并写进返回 notes。",
  }).ask<string>(
    "实施上述 P0 与快赢修复。返回一份汇总文本：改动文件清单 + 每项一句话说明 + skipped 项及原因 + 与审计册结论的任何出入。",
  )
  log("商户修复实施：" + implSummary.slice(0, 150))

  phase("统一门禁")
  let gateOk = false
  let gateTail = ""
  const fixer = agent("门禁修复员")
  for (let round = 1; round <= 2; round++) {
    log("第 " + round + " 轮门禁：vitest + build + 本机 mvn test（Lombok 1.18.48 已解锁）")
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

  phase("重建部署前后端与队列迁移")
  const buildB = await world.run("cmd", ["/c", "docker", "compose", "build", "backend"], { timeoutMs: 1500000, output: { tail: 1500 } })
  log("backend 镜像构建：exitCode=" + buildB.exitCode + "（Dockerfile 已加阿里 Maven 镜像源）")
  let deployOk = buildB.exitCode === 0
  if (deployOk) {
    // M6 迁移：RabbitConfig 给同名队列加了 arguments，不删旧队列 backend 重声明会 PRECONDITION_FAILED——先删再拉起（dev 环境消息可弃）
    const del1 = await world.run("cmd", ["/c", "docker", "exec", "lanlink-rabbitmq", "rabbitmqctl", "delete_queue", "q.order.paid.dlq"], { timeoutMs: 60000, output: { tail: 400 } })
    const del2 = await world.run("cmd", ["/c", "docker", "exec", "lanlink-rabbitmq", "rabbitmqctl", "delete_queue", "q.order.paid.membership"], { timeoutMs: 60000, output: { tail: 400 } })
    const del3 = await world.run("cmd", ["/c", "docker", "exec", "lanlink-rabbitmq", "rabbitmqctl", "delete_queue", "q.order.paid.campaign"], { timeoutMs: 60000, output: { tail: 400 } })
    log("队列迁移删除 exitCode：" + del1.exitCode + "/" + del2.exitCode + "/" + del3.exitCode)
    const up = await world.run("cmd", ["/c", "docker", "compose", "up", "-d", "backend"], { timeoutMs: 180000, output: { tail: 800 } })
    deployOk = up.exitCode === 0
  }
  let healthy = false
  for (let i = 0; i < 24; i++) {
    const h = await world.run("cmd", ["/c", "docker", "inspect", "-f", "{{.State.Health.Status}}", "shopping-backend-1"], { timeoutMs: 30000, output: { tail: 200 } })
    if (h.stdout.trim() === "healthy") { healthy = true; break }
    await world.run("cmd", ["/c", "timeout", "/t", "5", "/nobreak"], { timeoutMs: 30000, output: "discard" })
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

  phase("部署复测")
  const checks = await agent("部署复测员", {
    system: "你是只读复测员：对新部署容器做运行时实测，绝不修改文件。重点=安全批次运行时遗留 + 商户 P0 抽测。口径：演示账号买家 13900000001、商户 13911112222，密码均 123456；AuthInterceptor 的 401/403 是 body.code 且 HTTP 200——断言查 json.code；可用 docker exec 查库/查队列。",
  }).ask<DeployCheck[]>(
    "逐项实测：① 非属主商户 GET /api/logistics/trace/1 期待拒绝（code!=200，无权查看/运单不存在）；② 登录态 GET /api/logistics/map-config 期待 200（非 404，高德 key 由后端下发）；③ 买家带 CSRF POST /api/product/image 期待权限拒绝（非参数 400）；④ docker exec lanlink-rabbitmq rabbitmqctl list_queues 期待 dlq 带 x-max-length/x-message-ttl、membership/campaign 带 x-max-length；⑤ 非属主商户对他人 approved 结算单发起支付期待拒绝（对应 CreditTerm 端点，按审计册）；⑥ 未知路径返回 404 兜底页（非整页白屏）；⑦ 商户登录态 CenterPromotion 余额非 ¥0.00（API 实测对应端点）。每项给 where/what/evidence/status/severity；无法实测的标 unconfirmed 并给原因。",
  )

  const deployFindings: DeployCheck[] = []
  if (!gateOk) deployFindings.push({ where: "frontend/backend", what: "门禁两轮后仍失败", evidence: gateTail, status: "verified", severity: "high" })
  if (!deployOk || !healthy) deployFindings.push({ where: "shopping-backend-1", what: "backend 重建/拉起未成功（新代码未部署）", evidence: buildB.stdout + buildB.stderr, status: "verified", severity: "high" })
  if (!feOk) deployFindings.push({ where: "shopping-frontend-1", what: "frontend 镜像未更新", evidence: "buildF/upF 失败或被前置失败短路", status: "verified", severity: "medium" })
  const problemChecks = checks.filter(f => f.status === "unconfirmed" || f.severity !== "low")
  for (const f of [...deployFindings, ...problemChecks]) report(f)

  await artifact.markdown("report", [
    "# 商户中心修复与部署报告",
    "",
    "审计册：out/merchant-center-audit-1105.md（high 5 / medium 11 / low 12）",
    "",
    "## 实施摘要",
    implSummary,
    "",
    "## 门禁与部署",
    "- 门禁：" + (gateOk ? "vitest + build + 本机 mvn test 全绿" : "有失败项（见 findings）"),
    "- backend 镜像重建 " + (deployOk ? "成功" : "失败") + "，容器 " + (healthy ? "healthy" : "异常"),
    "- frontend " + (feOk ? "已重建部署" : "未更新"),
    "",
    "## 复测结论",
    ...checks.map(f => "- " + f.where + "：" + f.what + "（" + f.status + "）"),
  ].join("\n"), { title: "商户中心修复与部署报告", description: "P0 资损/越权/契约修复、门禁、前后端重建部署与运行时复测。", primary: true })

  return {
    fixed: [implSummary.slice(0, 900)],
    skipped: [
      "提现真实端点（方向待拍板）——已先保守下架入口",
      "/ad/track click 激进计费策略（曝光关联/预扣结算）待拍板——已做保守去重收紧",
      "邀约码/加盟码门槛收紧待拍板（维持演示宽松）",
      "并发资损 staging 压测未做（以条件更新+单测+复测为准）",
      "商户中心 14 页硬编码中文接 i18n（双语范围待拍板）",
    ],
    gates: (gateOk ? "vitest+build+本机 mvn test 全绿；" : "门禁有失败项；") +
      "backend 重建" + (deployOk ? "成功" : "失败") + "容器" + (healthy ? "healthy" : "异常") +
      "；frontend " + (feOk ? "已部署" : "未更新") + "；复测 " + checks.length + " 项（问题 " + problemChecks.length + " 项）",
  } as FixSummary
},
)
