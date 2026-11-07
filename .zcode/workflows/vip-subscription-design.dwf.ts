/* VIP 会员转化入口 + 会员页 VIP 订阅 UI/UX（调研设计先行，实施尾洞等待）
 * 背景：① 在有需要的地方加跳转 VIP 会员页的转化入口；② 会员系统页内单独做 VIP 订阅 UI/UX；
 *      ③ 参照 21st.dev 会员订阅组件（Magic UI=MIT 可抄值 / Ruixen=只借鉴视觉）；④ 按本项目适配。
 * 约束：安全批次 dwfrun-f73766fa 与商户审计 dwfrun-705b3a47 在跑——本工作流只读+写 out/ 设计稿，
 *      源码实施放在尾洞（两批清零后由主会话填充，避免并行编辑与 dist/ 构建竞态）。
 */

interface ComponentCandidate {
  /** 组件名与形态（定价表/订阅卡/权益对比/VIP 徽章等） */
  name: string
  /** 21st.dev 来源 URL */
  source: string
  /** MIT=可抄值 / borrow-visual-only=只借鉴视觉 / unknown */
  license: "MIT" | "borrow-visual-only" | "unknown"
  /** 视觉与交互模式要点（布局/动效/层级） */
  visualPattern: string
  /** 对本项目（小清新天空蓝+液态玻璃+Apple 风+Lucide）的适配取舍 */
  fitNotes: string
}

interface MemberAudit {
  /** 会员系统现状：页面（含路由）、api 封装、后端端点、等级/积分/权益模型 */
  pages: string[]
  endpoints: string[]
  levels: string
  /** 适合放转化入口的位置清单（页面:位置:形态建议） */
  conversionPoints: { where: string; form: string }[]
  /** 做 VIP 订阅的能力缺口（后端缺什么/前端缺什么） */
  gaps: string[]
  notes: string
}

interface DesignDoc {
  path: string
  /** VIP 订阅信息架构一句话（档位/权益/定价策略展示） */
  vipInfoArch: string
  /** 转化入口清单（页面:位置:形态） */
  entries: string[]
  /** 实施分阶段（P0 UI+入口接线 / P1 权益闭环等） */
  phases: string[]
  /** 需用户拍板（如是否接真实支付闭环） */
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

phase("并行调研 21st.dev 组件与会员现状")
const [comps, audit] = await Promise.all([
  agent("会员组件调研员", {
    system: "你是 21st.dev 组件调研员（只读，不改源码）：抓取会员订阅/定价/VIP 相关 UI 组件。纪律：① Magic UI 系（MIT）可抄值，Ruixen 系（no-license）只借鉴视觉，license 不明的标 unknown；② GitHub raw 直连会 SSL 失败，组件源码可从 21st.dev 页面内嵌代码或 npm/npmmirror 拿；③ 项目设计语言=小清新天空蓝（--ll-* token）+ 液态玻璃（.ll-glass--day）+ Apple 排版 + Lucide 图标（禁 emoji）+ 手机断点 ≤991/≤700——每个候选给适配取舍。",
  }).ask<ComponentCandidate[]>(
    `在 21st.dev 检索会员/订阅/定价类组件（pricing table、subscription card、plan compare、VIP badge、billing toggle 等），取 4-6 个最贴合「B2B 商城 VIP 会员订阅」的候选：每个给 name/source/license/visualPattern/fitNotes。输出 ComponentCandidate[]（中文）。`,
  ),
  agent("会员现状审计员", {
    system: "你是只读审计员（不改文件）：盘点本项目会员体系现状。线索：路由 /membership 与 views/Membership.vue（或同名）、api/index.js 的 membershipApi（checkin/exchange/invite 等）、后端 Membership 相关 Controller（积分/签到/兑换/邀请/等级权益）、活动体系 F1-F4（领券中心/任务激励/大促会场）与结算页会员相关块、/me 用户中心；项目技能 lanlink-membership-policy-sync 的 SKILL.md 有政策口径。另给出适合放「加入 VIP」转化入口的位置清单（首页/商品详情/结算页会员价对比/活动中心/用户中心/订单完成页等，每处标注形态：横幅/悬浮卡/空态引导/结算对比行）。注意安全批次正在改物流与存储文件——与会员无关，忽略其变更。",
  }).ask<MemberAudit>(
    `盘点会员系统现状并给转化点位建议：pages/endpoints/levels/conversionPoints/gaps（gaps 重点回答：做真实 VIP 订阅购买闭环还缺哪些后端件——套餐表/订购端点/支付对接/权益下发）。输出 MemberAudit（中文）。`,
  ),
])
log(`调研完成：${comps.length} 个组件候选 + 会员现状（${audit.conversionPoints.length} 个候选点位）`)

phase("设计 VIP 订阅方案")
const designer = agent("VIP 方案设计师", {
  system: "你是资深产品设计+前端架构：综合组件候选与现状审计，写中文设计方案到 out/vip-subscription-design.md（Write 工具）。必含：① 会员页 VIP 订阅区块信息架构（档位卡片/权益对比/计费周期切换/当前身份态/CTA——参考候选的版式但按天空蓝+液态玻璃+Apple 风重设，token 化实现不硬编码色值）；② 转化入口清单（每处 where/形态/触发时机与频控——转化优先但不骚扰：如结算页会员价对比、商品详情价格区、首页会员中心入口、活动中心空态）；③ 与现有积分/签到体系的关系（VIP 与积分双轨口径，不冲突）；④ 分阶段实施（P0=UI+纯前端入口接线+既有端点复用；P1=真实订阅闭环所需后端件清单）；⑤ 复用与新增文件清单（到文件级）；⑥ 手机断点与无障碍要点。所有新中文文案先按 zh-en-extra 纪律预演（先 grep 防重复）。",
})
let design = await designer.ask<DesignDoc>(
  `组件候选：${JSON.stringify(comps)}\n会员现状：${JSON.stringify(audit)}\n写成设计方案到 out/vip-subscription-design.md 并返回 DesignDoc。`,
)
log(`方案成稿：${design.vipInfoArch}`)

phase("独立评审定稿")
const reviewer = agent("方案评审员", {
  system: "你是独立评审员：Read out/vip-subscription-design.md 全文，专挑问题——转化点位是否过度打扰（频控缺失）、与现有积分/活动体系口径冲突、token 化是否彻底（硬编码色值=打回）、档位信息是否有真实权益支撑（空承诺=打回）、实施清单是否遗漏路由/i18n/移动端、license 是否合规（Ruixen 值不能照抄）。需要证据，反对是常态。不改文件。",
})
for (let round = 1; round <= 2; round++) {
  const review = await reviewer.ask<ReviewResult>(
    `评审 ${design.path}（第 ${round} 轮）：${round > 1 ? "上轮问题已修订，复核是否闭合并继续挑新问题。" : "全面挑问题。"}`,
  )
  if (review.approved) {
    log(`评审通过（第 ${round} 轮）`)
    break
  }
  if (round === 2) {
    log("两轮评审后仍有未闭合问题，如实入 decisions")
    design.decisions = [...design.decisions, ...review.issues.map(i => `评审遗留：${i}`)]
    break
  }
  log(`评审提出 ${review.issues.length} 个问题，交设计师修订`)
  design = await designer.ask<DesignDoc>(
    `评审意见（逐条修订 ${design.path} 并在文中回应）：${JSON.stringify(review.issues)}\n修订后重新返回 DesignDoc。`,
  )
}

/* 实施尾洞：两个在跑批次清零后由主会话填充（并行编辑 + dist/ 构建竞态禁令） */
return await hole<ImplSummary>(
  "实施 VIP 会员改版",
  `设计方案已定稿：${design.path}（入口 ${design.entries.length} 处；分阶段：${design.phases.join("；")}）。需拍板：${design.decisions.join("；")}。\n` +
  `填充本洞时定义实施拓扑。前置确认：安全批次 dwfrun-f73766fa 与商户修复批次均已完成（并行编辑与构建禁令解除）；若用户对 decisions 中「是否做 P1 真实订阅支付闭环」未表态，P0 优先。`, async () => {
  phase("实施 VIP P0 组件与入口")
  const impl = await agent("VIP 实施工程师", {
    system: "你是资深前端工程师：按 out/vip-subscription-design.md 的 P0 清单实施（后端零改动）。范围：① 会员页订阅区块重构——新组件 frontend/src/components/membership/VipPlanGrid.vue / VipCompareTable.vue / VipIdentityBar.vue（身份态条三态：未订阅/生效中/已取消；按月/按年分段切换；档位卡 radio 选择态与提交分离；年度卡推荐徽标+划线对比价的 Apple 价格层级；两列权益对比表，已订阅列 .ll-glass--day--deep 高亮，lucide 勾叉带 aria；唯一 CTA「立即开通」+信任行；「自动续费」标注全线隐藏改「到期可一键续费」诚实文案）并接进会员页替换旧区块；② 假折扣口径修复——ProductDetail.vue 与 Mall.vue 删 0.95 硬编码，新增 frontend/src/store/membership.js（Pinia，读真实 discountRate：用既有会员端点，取不到则优雅回退 1.0 并不展示对比价）+ frontend/src/store/membership.test.js（先例 store/settings.test.js）；③ 入口接线设计稿 1/2/3/4/5/6/7/9 八处 + 负频控体系（localStorage 记关闭时间，低频横幅/卡片 N 天内不再强推）；④ i18n——设计稿附录 A 17 条词条追加 zh-en-extra.js:2642 段（先 grep 该文件防重复防覆盖）+ 顺带修 MembershipBenefits.vue:2 倍数挂轨文案，跑 frontend/scripts/check-i18n-duplicates.mjs 验证；⑤ 组件测试——VipPlanGrid radio 语义/键盘换挡、VipIdentityBar 三态、VipCompareTable aria（npx vitest run src/components/membership）。硬约束：全部 token 化（--ll-primary/--ll-brand-gradient/--ll-glass-day-*/--ll-ink-on-glass，仅 :root 可追加引用既有 token 的局部变量，零硬编码色值、零新 npm 依赖）；Lucide 图标禁 emoji；手机断点 ≤991/≤700；不跑全量测试与构建（脚本统一跑）；不动后端与物流/存储/商户中心文件（其他批次已改完）。",
  }).ask<string>(
    "按设计稿 P0 清单实施。返回汇总文本：改动/新增文件清单 + 每项一句话说明 + skipped 项及原因（入口 8 与 D6 权益承诺列默认不做）。",
  )
  log("VIP P0 实施：" + impl.slice(0, 150))

  phase("VIP 门禁与前端部署")
  let gateOk = false
  let gateTail = ""
  const fixer = agent("VIP 门禁修复员")
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
    log("frontend 镜像：" + (feOk ? "已重建部署" : "未更新"))
  }

  phase("VIP 页面目检")
  const checks = await agent("VIP 目检复核员", {
    system: "你是只读复核员：浏览器实测（自己新开独立标签页，http://localhost:5173，买家 13900000001/123456；用户自己在用 IAB——绝不碰用户已开的标签页）+ 代码核对，绝不修改文件。",
  }).ask<string[]>(
    "复核清单：① /membership 订阅区块四段式渲染（身份态条/分段切换/档位卡/权益对比表/CTA）与按月按年切换交互；② token 化抽查（新组件无硬编码色值）；③ 入口点位抽查至少 3 处真实渲染且负频控生效（localStorage 关闭标记）；④ 假折扣已删（ProductDetail/Mall 无 0.95）且价格区读真实 discountRate 或优雅回退；⑤ 手机断点 ≤700 布局；⑥ 组件测试与 check-i18n-duplicates 通过。每条一行结论+证据，发现问题如实标注。输出 string[]。",
  )
  for (const c of checks) report({ check: c })

  await artifact.markdown("report", [
    "# VIP 会员改版 P0 交付",
    "",
    "## 实施",
    impl,
    "",
    "## 门禁与部署",
    "- 门禁：" + (gateOk ? "vitest + build 全绿" : "有失败项（见目检/结果）"),
    "- frontend " + (feOk ? "已重建部署（5173 即时可见）" : "未更新"),
    "",
    "## 目检复核",
    ...checks.map(c => "- " + c),
  ].join("\n"), { title: "VIP 会员改版 P0 报告", description: "会员页订阅区块重构、8 入口接线、假折扣修复的交付与复核。", primary: true })

  return {
    fixed: [impl.slice(0, 900)],
    skipped: [
      "P1 真实订阅闭环全量（t_subscription_order/两段式支付/notify 回调/自动续费调度/cancel 语义/双倍积分判据/price-preview/支付对接）——D1-D4 待用户拍板",
      "入口 8（设计稿暂缓项）",
      "/user/vip/benefits 权益承诺列 D6（不默认随 P0）",
      "「自动续费」展示待 P1 落地后恢复（P0 全线诚实化）",
    ],
    gates: (gateOk ? "vitest+build 全绿" : "门禁有失败项") + ";frontend " + (feOk ? "已重建部署" : "未更新") + ";目检 " + checks.length + " 条",
  } as ImplSummary
},
)
