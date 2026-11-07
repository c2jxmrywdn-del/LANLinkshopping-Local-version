/* VIP P1 真实订阅闭环（设计已定稿 out/vip-subscription-design.md，本轮=现状核对+实施尾洞）
 * 用户拍板定案（替代原 D1-D4 待拍板）：D1 按年默认（已落地）；D2 双倍积分=「身份 VIP ∪ 有效订阅」，
 * 抽公共方法（建议 MembershipService.multiplierOf，动 MembershipOrderListener:51-52 + MembershipOrderMqConsumer:56-57 两调用点）；
 * D3 cancel=到期失效+撤销自动续费+保留历史（推荐案）；D4/D7 按设计稿定义执行。
 * 约束：退款+地图批次 dwfrun-2190d284 正在实施（zh-en-extra.js/前端文件/构建竞态）——核对只读，实施尾洞等清零。
 */

interface P1Item {
  /** 设计稿 P1 条目号（①-⑨） */
  id: string
  title: string
  /** 现状：涉及文件（path:line）与已存在/缺失判定 */
  current: string
  /** 需要新增/改动的差量（到文件级） */
  delta: string
}

interface P1Workplan {
  items: P1Item[]
  /** 支付渠道分支点与 notify 回调结构现状（复用 OrderPaid 的哪些件） */
  paymentRails: string
  /** 风险与依赖（如渠道沙箱可用性、审计/配额复用点） */
  risks: string[]
  notes: string
}

interface ImplSummary {
  fixed: string[]
  skipped: string[]
  gates: string
}

phase("核对 P1 现状与设计对齐")
const plan = await agent("订阅闭环核对员", {
  system: "你是只读核对员（不改文件）：把 out/vip-subscription-design.md 的 P1 清单（①-⑨，见 §P1 与 D1-D4 定义）逐条对到当前代码。已知现状：P0 阶段前端 subscriptionApi.plans/my/purchase 已存在且 purchase 当前是直接生效（无支付两段式）——核对后端订阅域现有表/端点/状态机；PaymentService 与 POST /payment/create 五渠道分发、三渠道 notify 回调的分支结构（订单支付已用，订阅单要加分支）；MembershipOrderListener.java:51-52 与 MembershipOrderMqConsumer.java:56-57 双倍积分判据现状；前端 PaymentMethodSelector/paymentApi.query 轮询/退款 /payment/refund 的既有形态。注意退款+地图批次正在改 MyOrders/地图/zh-en-extra/后端退款校验——只读不受影响。",
}).ask<P1Workplan>(
  "逐条输出 P1Item（id/title/current/delta），另给 paymentRails（订阅单要接入的支付件清单与分支点）与 risks。中文。",
)
log("P1 核对完成：" + plan.items.length + " 条差量")

return await hole<ImplSummary>(
  "实施 P1 订阅闭环",
  "核对完成：" + JSON.stringify(plan) + "\n" +
  "拍板定案：D1 按年默认（已落地）；D2 双倍积分=身份 VIP ∪ 有效订阅（抽 MembershipService.multiplierOf 公共方法，改两调用点+单测）；D3 cancel=到期失效+撤销自动续费+保留历史；D4/D7 按设计稿定义执行。「自动续费」展示随本批恢复（前端诚实化文案改回真实状态）。\n" +
  "填充本洞时定义实施拓扑。前置确认：退款+地图批次 dwfrun-2190d284 已完成（zh-en-extra.js/前端/构建竞态解除）；实施=后端订阅闭环+前端支付对接全量，门禁 vitest+build+本机 mvn test，部署前后端镜像，运行时复测（沙箱渠道全链路：purchase→支付→notify→订阅生效→续费→cancel 到期失效→双倍积分实测→GET /subscription/orders→price-preview）。", async () => {
  phase("落地订阅支付闭环全栈")
  const impl = await agent("订阅闭环实施工程师", {
    system: "你是资深全栈工程师：按 out/vip-subscription-design.md 的 P1 清单（①-⑨）与核对员工作 plan 实施（plan 是权威差量清单，先通读设计稿相关章节）。拍板定案：D1 按年默认（已落地勿动）；D2 双倍积分=身份 VIP ∪ 有效订阅，抽公共方法 MembershipService.multiplierOf 并改 MembershipOrderListener:51-52 与 MembershipOrderMqConsumer:56-57 两调用点+单测；D3 cancel=到期失效+撤销自动续费+保留历史（已取消用户可见历史并续费恢复）；D4/D7 按设计稿定义执行；「自动续费」展示随本批恢复真实状态（前端 P0 诚实化文案按订阅单实际 auto_renew 渲染）。核对员点名的关键约束：① 金额单位——price_cents 现语义为「整数元」（8888=¥8,888，schema.sql:1285 注释与 SubscriptionPlan.java:11 一致）：t_subscription_order 金额字段沿用同一语义并加显式注释钉死，与 PaymentService 的金额换算保持一致（订单侧 DECIMAL totalAmount，订阅侧换算处写明）；② PaymentService.create 强绑 t_order（mustOwn:484-488/金额 totalAmount/isPaid/setPrepay 落 t_order）——按设计抽出单据抽象或重载支持订阅单，/payment/create 入参 orderNo 用单据类型命名空间区分（如 SUB+序号），不破坏既有订单支付路径（既有测试不许红）；③ 三渠道 notify 回调（PaymentService.java:158-241）加订阅单分支：按 order_no 前缀分流，金额校验反查订阅单，幂等=订阅单 pay_status 状态机（防重放），驱动 t_user_subscription 生效/顺延（续费=原到期日起顺延，失效续订=新起算）；④ 自动续费调度：@Scheduled 每日 cron（注意时区成对教训——容器 TZ=Asia/Shanghai 已固化，LocalDate.now() 与 cron 保持同源）扣款走 mock/沙箱渠道+宽限期+到期提醒与降级通知（复用既有 NotifyService/站内信模式）+扣款失败重试上限后置 cancelled；⑤ purchase 两段式：创建待支付订阅单→PaymentService.create 渠道分发→返回支付参数；前端 MembershipCenter.vue buy() 改接支付流（复用 PaymentMethodSelector + paymentApi.query 轮询，参考订单支付既有前端流）；⑥ 新增 GET /subscription/orders（本人分页）与前端 SubscriptionOrders.vue 挂 /membership/orders（会员页入口链接）；⑦ price-preview：按当前成长值可达档位（非最高档）返回立省金额；⑧ 客服通道 gating（D7）按设计稿。硬约束：不动退款+地图刚部署的文件（RefundEvidence/LogisticsDynamicMap/traces/存储凭据）；不动 VIP P0 组件结构（VipPlanGrid/VipCompareTable/VipIdentityBar 只允许接真实数据）；i18n 新键先 grep zh-en-extra.js 防重；并发/幂等一律条件更新+状态机；单测覆盖 notify 分支幂等/双倍积分两身份/cancel 语义/price-preview（模仿仓库风格）；不跑全量套件与构建（脚本统一跑）。",
  }).ask<string>(
    "按设计稿 P1 清单与核对工作 plan 实施。返回：改动/新增文件清单 + 每项一句话说明 + 金额单位处理说明 + skipped 项及原因。",
  )
  log("P1 实施：" + impl.slice(0, 150))

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

  phase("重建部署与沙箱支付复测")
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

  const checks = await agent("订阅闭环复测员", {
    system: "你是只读复测员：API 实测 + 浏览器目检（自己新开独立标签页 http://localhost:5173，买家 13900000001/123456；用户自己在用 IAB——绝不碰用户标签页）。401/403 是 body.code 且 HTTP 200，断言查 json.code。绝不修改文件。",
  }).ask<string[]>(
    "沙箱全链路复测：① 买家 purchase 年度档 → 返回待支付订阅单+支付参数（不再是零元直开）；② 沙箱渠道支付 → notify 回调 → 订阅生效（t_user_subscription active + t_subscription_order paid，重复 notify 幂等不重复顺延）；③ 双倍积分：订阅生效用户与 VIP 身份用户各验证 ×2 判据（API 或库证）；④ cancel → 到期失效语义（status 变更+auto_renew 撤销+历史保留）+ GET /subscription/orders 可见；⑤ price-preview 返回可达档立省金额；⑥ /membership/orders 页面渲染与「自动续费」真实状态展示；⑦ 既有订单支付回归（下一单走订单支付链路仍正常——PaymentService 抽象未破坏原路径）。每条一行结论+证据，失败如实标注。输出 string[]。",
  )
  for (const c of checks) report({ check: c })

  await artifact.markdown("report", [
    "# VIP P1 订阅闭环交付报告",
    "",
    "## 实施",
    impl,
    "",
    "## 门禁与部署",
    "- 门禁：" + (gateOk ? "vitest + build + 本机 mvn test 全绿" : "有失败项（见复测/结果）"),
    "- backend " + (deployOk && healthy ? "healthy" : "异常") + "；frontend " + (feOk ? "已重建部署" : "未更新"),
    "",
    "## 沙箱全链路复测",
    ...checks.map(c => "- " + c),
  ].join("\n"), { title: "VIP P1 订阅闭环报告", description: "真实两段式订阅支付闭环、自动续费、双倍积分与到期语义的交付与复测。", primary: true })

  return {
    fixed: [impl.slice(0, 900)],
    skipped: [
      "真实渠道商户号（微信/支付宝/Stripe 生产凭据）——沙箱/mock 渠道验证，生产凭据待用户提供",
      "自动续费真实代扣（渠道侧委托扣款签约）——沙箱内模拟扣款调度",
      "订阅专属客服通道 gating 细节（D7 按设计稿最小实现）",
    ],
    gates: (gateOk ? "vitest+build+本机 mvn test 全绿" : "门禁有失败项") + ";backend " + (deployOk && healthy ? "healthy" : "异常") + ";frontend " + (feOk ? "已重建部署" : "未更新") + ";沙箱复测 " + checks.length + " 条",
  } as ImplSummary
},
)
