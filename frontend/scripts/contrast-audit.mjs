/**
 * 深色/浅色背景区 · WCAG 2.1 对比度审计（整改后复核）
 * 运行：node scripts/contrast-audit.mjs
 * 输出：docs/深色背景可读性-对比度审计报告.md
 *
 * 覆盖范围：站内所有承载文字的深色底/深色渐变区域（前台顶栏、Hero 品牌渐变、
 * 启动过渡、后台深色侧栏、客服头部、后台客服工作台 Hero、会员/商城卡片、
 * 首页 Banner、隐私中心与钱包流水 Hero、深色模式表面），以及本轮一并修正的
 * 浅色底小字号弱对比文字（商城/首页/关于/商品详情/作者卡）。
 *
 * 依据 WCAG 2.1 AA：
 *   - 普通文本与背景 ≥ 4.5:1（大文本 ≥ 3:1，非文本 UI 组件 ≥ 3:1）
 *   - 半透明前景先与所在背景做 alpha 合成，再按合成实色计算（text-shadow 不计入）
 *   - 渐变背景取最亮端作为最不利取值；图片叠罩按"最亮图片=纯白"作为最不利取值
 */
import { writeFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { AVATAR_GRADIENTS } from '../src/utils/avatarIdentity.js'

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..')

/* ---------- 背景取值（与 theme.css / 各页面样式一致） ---------- */
const B = {
  navy: '#1F3864',        // --ll-navy 前台顶栏
  heroBase: '#13233A',    // --ll-brand-base（Hero 渐变起点）
  heroLight: '#233F5F',   // Hero 渐变终点（最亮端 → 最不利）
  heroNode: '#324C6A',    // 关于页 identity-node（玻璃叠在 Hero 上）
  splashMid: '#101B2D',
  splashDeep: '#0B1220',
  heroPrivacy: '#30476F', // 隐私中心 Hero（rgba 深蓝叠浅底合成值）
  heroWalletTx: '#2A4569',
  heroComm: '#274563',    // 客服工作台 Hero 最亮端
  adminDark: '#001529',   // antd 深色侧栏默认底
  cardBlue: '#1F3864',    // 商城卡片渐变（InteractiveProductCard）
  cardRed: '#9B2D20',
  dmSurface: '#16202e',   // 深色模式 --ll-card
  // 浅色底
  white: '#FFFFFF',
  paper1: '#FBF9F1',
  paper2: '#F5F0E8',
  priceCard: '#F6F1E7',
  serviceCard: '#FFFDF8',
  page: '#F5F6F8',
  authorCard: '#F4F1EA',
  promiseBar: '#FAF8F2',
  // 前景
  whiteFg: '#FFFFFF',
  goldSoft: '#F6D38A',
  goldStrong: '#80601F',  // --ll-brand-gold-strong（本轮新增）
  cyan: '#22D3EE',
  gray: '#4b5563',        // --ll-gray 浅底次要文字
  dmInk: '#e8edf5', dmMuted: '#8fa0b5', dmGray: '#9aa7b8',
}

/* ---------- 工具 ---------- */
function hex2rgb(hex) { const h = hex.replace('#', ''); return [0, 2, 4].map(i => parseInt(h.slice(i, i + 2), 16)) }
function channel(c) { const s = c / 255; return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4 }
function luminance(hex) { const [r, g, b] = hex2rgb(hex).map(channel); return 0.2126 * r + 0.7152 * g + 0.0722 * b }
function ratio(a, b) { const la = luminance(a), lb = luminance(b); const [hi, lo] = la >= lb ? [la, lb] : [lb, la]; return (hi + 0.05) / (lo + 0.05) }
function over(fgHex, alpha, bgHex) {
  const f = hex2rgb(fgHex), b = hex2rgb(bgHex)
  const c = f.map((v, i) => Math.round(alpha * v + (1 - alpha) * b[i]))
  return '#' + c.map(v => v.toString(16).padStart(2, '0')).join('').toUpperCase()
}
function grade(r, need) { return r >= need ? 'PASS' : 'FAIL' }

/* Banner：正文叠罩最不利取值（图片按纯白最不利）+ 箭头深色玻璃底衬 */
const BANNER_MASK = '#525866'                    // rgba(15,23,42,.72) over 纯白图
const ARROW_PLATE = (slide) => over('#0F172A', 0.62, slide)

/* ---------- 审计用例（整改后） ---------- */
const cases = [
  // A. 前台顶栏
  { area: '前台顶栏', label: '未选中菜单项（rgba 白 72%）', fg: B.whiteFg, a: 0.72, bg: B.navy, need: 4.5 },
  { area: '前台顶栏', label: '选中菜单项（--ll-cyan）', fg: B.cyan, a: 1, bg: B.navy, need: 4.5 },
  { area: '前台顶栏', label: '品牌字标 shopping（rgba 白 92%）', fg: B.whiteFg, a: 0.92, bg: B.navy, need: 4.5 },

  // B. Hero 品牌渐变（最亮端 #233F5F）
  { area: '首页/关于 Hero', label: 'H1 主标题（纯白）', fg: B.whiteFg, a: 1, bg: B.heroLight, need: 3, scope: '大文本' },
  { area: '首页/关于 Hero', label: '副文案（rgba 白 70%）', fg: B.whiteFg, a: 0.70, bg: B.heroLight, need: 4.5 },
  { area: '关于 Hero', label: '副文案（rgba 白 72%）', fg: B.whiteFg, a: 0.72, bg: B.heroLight, need: 4.5 },
  { area: '首页 Hero', label: '金色眉毛（--ll-brand-gold-soft）', fg: B.goldSoft, a: 1, bg: B.heroLight, need: 4.5 },
  { area: '订单/账期 Hero', label: 'eyebrow / 小字（rgba 白 70%）', fg: B.whiteFg, a: 0.70, bg: B.heroLight, need: 4.5 },
  { area: '账期 Hero', label: '指标标签（rgba 白 62%）', fg: B.whiteFg, a: 0.62, bg: B.heroLight, need: 4.5 },
  { area: '会员中心 Hero', label: 'meta / hint（opacity 72%）', fg: B.whiteFg, a: 0.72, bg: B.heroLight, need: 4.5 },
  { area: '会员中心 Hero', label: 'discount small（opacity 65%）', fg: B.whiteFg, a: 0.65, bg: B.heroLight, need: 4.5 },

  // C. 深海军蓝底板
  { area: '客服头部', label: '副说明（rgba 白 78%）', fg: B.whiteFg, a: 0.78, bg: B.heroBase, need: 4.5 },
  { area: '知识产权徽章', label: '说明（rgba 白 68%）', fg: B.whiteFg, a: 0.68, bg: B.heroBase, need: 4.5 },
  { area: '知识产权徽章', label: '标题（--ll-brand-gold-soft）', fg: B.goldSoft, a: 1, bg: B.heroBase, need: 4.5 },
  { area: '关于 Hero 微标签', label: 'identity-center small（rgba 白 58%，8px）', fg: B.whiteFg, a: 0.58, bg: B.heroBase, need: 4.5 },
  { area: '关于 Hero 微标签', label: 'identity-node span（整改：48%→70%）', fg: B.whiteFg, a: 0.70, bg: B.heroNode, need: 4.5 },
  { area: '关于 Hero 微标签', label: 'orbit-core::after（rgba 白 55%，7px）', fg: B.whiteFg, a: 0.55, bg: B.heroBase, need: 4.5 },
  { area: '关于 Hero', label: '.eyebrow（亮金，落在深色 Hero 上）', fg: '#C8A45C', a: 1, bg: B.heroLight, need: 4.5 },

  // D. 启动过渡 / 品牌符号
  { area: '启动过渡', label: '解码说明（rgba 白 82%）', fg: B.whiteFg, a: 0.82, bg: B.splashMid, need: 4.5 },
  { area: '启动过渡', label: '解码说明（rgba 白 82%）on 最深端', fg: B.whiteFg, a: 0.82, bg: B.splashDeep, need: 4.5 },
  { area: '品牌符号', label: 'SHOPPING 微标（rgba 白 50%）', fg: B.whiteFg, a: 0.50, bg: B.heroBase, need: 4.5 },

  // E. 后台
  { area: '后台侧栏', label: '菜单项（antd rgba 白 65%）', fg: B.whiteFg, a: 0.65, bg: B.adminDark, need: 4.5 },
  { area: '后台侧栏', label: '选中菜单项（纯白）', fg: B.whiteFg, a: 1, bg: B.adminDark, need: 4.5 },
  { area: '客服工作台 Hero', label: 'Kicker（rgba 白 68%，10px）', fg: B.whiteFg, a: 0.68, bg: B.heroComm, need: 4.5 },
  { area: '客服工作台 Hero', label: '说明（rgba 白 76%，13px）', fg: B.whiteFg, a: 0.76, bg: B.heroComm, need: 4.5 },
  { area: '客服工作台 Hero', label: '悬浮小片（rgba 白 78%，9px）', fg: B.whiteFg, a: 0.78, bg: B.heroComm, need: 4.5 },

  // F. 深蓝叠底 Hero
  { area: '隐私中心 Hero', label: '说明（rgba 白 76%）', fg: B.whiteFg, a: 0.76, bg: B.heroPrivacy, need: 4.5 },
  { area: '钱包流水 Hero', label: 'eyebrow（rgba 白 65%）', fg: B.whiteFg, a: 0.65, bg: B.heroWalletTx, need: 4.5 },
  { area: '钱包流水 Hero', label: '金额（--ll-brand-gold-soft）', fg: B.goldSoft, a: 1, bg: B.heroWalletTx, need: 3, scope: '大文本' },

  // G. 商城商品卡（深色玻璃渐变）
  { area: '商城商品卡', label: '卡片 meta（rgba 白 84%）on #1F3864', fg: B.whiteFg, a: 0.84, bg: B.cardBlue, need: 4.5 },
  { area: '商城商品卡', label: '卡片 meta（rgba 白 84%）on #9B2D20', fg: B.whiteFg, a: 0.84, bg: B.cardRed, need: 4.5 },
  { area: '商城商品卡', label: '占位文字（rgba 白 82%）on #9B2D20', fg: B.whiteFg, a: 0.82, bg: B.cardRed, need: 4.5 },

  // H. 首页 Banner（叠罩加深后；图片按纯白最不利）
  { area: '首页 Banner', label: '标题（纯白）on 叠罩', fg: B.whiteFg, a: 1, bg: BANNER_MASK, need: 3, scope: '大文本' },
  { area: '首页 Banner', label: '副文案（opacity 92%）on 叠罩', fg: B.whiteFg, a: 0.92, bg: BANNER_MASK, need: 4.5 },
  { area: '首页 Banner', label: 'CTA（整改：改为 --ll-brand-gold-soft）', fg: B.goldSoft, a: 1, bg: BANNER_MASK, need: 4.5 },
  { area: '首页 Banner 箭头', label: '整改：白色图标 on 深色玻璃底衬 over slide1', fg: B.whiteFg, a: 1, bg: ARROW_PLATE('#3F6FB5'), need: 3, scope: '非文本 UI 组件' },
  { area: '首页 Banner 箭头', label: '整改：白色图标 on 深色玻璃底衬 over slide2', fg: B.whiteFg, a: 1, bg: ARROW_PLATE('#39B58F'), need: 3, scope: '非文本 UI 组件' },
  { area: '首页 Banner 箭头', label: '整改：白色图标 on 深色玻璃底衬 over slide3', fg: B.whiteFg, a: 1, bg: ARROW_PLATE('#D98E3A'), need: 3, scope: '非文本 UI 组件' },

  // I. 深色模式表面
  { area: '深色模式', label: '次要文字 --ll-muted on --ll-card', fg: B.dmMuted, a: 1, bg: B.dmSurface, need: 4.5 },
  { area: '深色模式', label: '提示文字 --ll-gray on --ll-card', fg: B.dmGray, a: 1, bg: B.dmSurface, need: 4.5 },
  { area: '深色模式', label: '主文字 --ll-ink on --ll-card', fg: B.dmInk, a: 1, bg: B.dmSurface, need: 4.5 },
  { area: '深色模式', label: '首页 .sec span（回调亮金）on --ll-page', fg: '#C8A45C', a: 1, bg: '#0d1420', need: 4.5 },
  { area: '深色模式', label: '首页承诺条分隔符（回调亮金）on --ll-card', fg: '#C8A45C', a: 1, bg: B.dmSurface, need: 4.5 },

  // J. 浅色底小字号弱对比文字（本轮一并整改）
  { area: '浅底·商城', label: '卡片次要文字（#999→--ll-gray）', fg: B.gray, a: 1, bg: B.white, need: 4.5 },
  { area: '浅底·首页', label: '分区金色小标签（→gold-strong）', fg: B.goldStrong, a: 1, bg: B.page, need: 4.5 },
  { area: '浅底·首页', label: '承诺条金分隔符（→gold-strong）', fg: B.goldStrong, a: 1, bg: B.promiseBar, need: 4.5 },
  { area: '浅底·关于', label: 'section-kicker（→gold-strong）on 纸底', fg: B.goldStrong, a: 1, bg: B.paper1, need: 4.5 },
  { area: '浅底·关于', label: 'section-kicker（→gold-strong）on 纸底2', fg: B.goldStrong, a: 1, bg: B.paper2, need: 4.5 },
  { area: '浅底·关于', label: 'legal-title span（→gold-strong）on 白底', fg: B.goldStrong, a: 1, bg: B.white, need: 4.5 },
  { area: '浅底·商品详情', label: '金色 eyebrow / 图标（--gold→#80601F）', fg: B.goldStrong, a: 1, bg: B.white, need: 4.5 },
  { area: '浅底·商品详情', label: '辅助说明（→--ll-gray）on 白底', fg: B.gray, a: 1, bg: B.white, need: 4.5 },
  { area: '浅底·商品详情', label: '服务说明（→--ll-gray）on #FFFDF8', fg: B.gray, a: 1, bg: B.serviceCard, need: 4.5 },
  { area: '浅底·商品详情', label: '价格标签（→--ll-gray）on 价格卡', fg: B.gray, a: 1, bg: B.priceCard, need: 4.5 },
  { area: '浅底·作者卡', label: 'eyebrow / 分组小字（→gold-strong）', fg: B.goldStrong, a: 1, bg: B.authorCard, need: 4.5 },
  { area: '浅底·作者卡', label: '次要说明（→--ll-gray）', fg: B.gray, a: 1, bg: B.authorCard, need: 4.5 },

  // K. 中英切换控件（本轮改版为滑块式分段控件）
  { area: '切换器·前台顶栏(dark)', label: '未激活项（rgba 白 74%）on --ll-navy', fg: B.whiteFg, a: 0.74, bg: B.navy, need: 4.5 },
  { area: '切换器·前台顶栏(dark)', label: '激活项（纯白 on 青色滑块叠底）', fg: B.whiteFg, a: 1, bg: over('#22D3EE', 0.22, B.navy), need: 4.5 },
  { area: '切换器·后台头部(light)', label: '未激活项 --ll-muted on 白底', fg: '#64748B', a: 1, bg: B.white, need: 4.5 },
  { area: '切换器·后台头部(light)', label: '激活项（纯白 on --ll-primary）', fg: B.whiteFg, a: 1, bg: '#1E6EB8', need: 4.5 },

  // L. 扫码登录组件（QrCodeScanLogin）
  { area: '扫码登录组件', label: '标题（--ll-ink）on 卡片浅底', fg: '#0f172a', a: 1, bg: '#FBFCFE', need: 4.5 },
  { area: '扫码登录组件', label: '副标题/状态/倒计时（--ll-muted）on 卡片浅底', fg: '#64748b', a: 1, bg: '#FBFCFE', need: 4.5 },
  { area: '扫码登录组件', label: '操作链接（--ll-primary）on 卡片浅底', fg: '#1E6EB8', a: 1, bg: '#FBFCFE', need: 4.5 },
  { area: '扫码登录组件', label: '场景徽标（gold-strong）on 白底', fg: B.goldStrong, a: 1, bg: '#FFFFFF', need: 4.5 },
  { area: '扫码登录组件', label: '演示标签（--ll-gray）on 缩略图底', fg: B.gray, a: 1, bg: '#EEF1F6', need: 4.5 },
  { area: '扫码登录组件', label: '确认态图标（纯白）on --ll-primary', fg: B.whiteFg, a: 1, bg: '#1E6EB8', need: 4.5 },

  // M. 用户头像缩写配色（由调色板直接生成，保证"白字对渐变两端均达标"的契约成立）
  ...AVATAR_GRADIENTS.flatMap((pair, i) => pair.map((c, j) => ({
    area: '用户头像·姓名缩写',
    label: `白色缩写 on 调色板#${i + 1} ${j === 0 ? '起始端' : '末端'}`,
    fg: B.whiteFg, a: 1, bg: c, need: 4.5
  }))),

  // N. 智能客服头像（第二版：浅色品牌底 + 深墨色图形 + 状态点）
  { area: '智能客服头像', label: '客服图形（#1F3864）on 浅底最亮端', fg: '#1F3864', a: 1, bg: '#DCE9FB', need: 4.5 },
  { area: '智能客服头像', label: '眼睛（#EAF2FD）on 深色面部', fg: '#EAF2FD', a: 1, bg: '#1F3864', need: 3, scope: '非文本图形' },
  { area: '智能客服头像', label: '智能火花（gold-strong）on 浅底最亮端', fg: B.goldStrong, a: 1, bg: '#DCE9FB', need: 4.5 },
  { area: '用户头像·中性标记', label: '人物剪影（--ll-muted）on 兜底底色', fg: '#64748B', a: 1, bg: '#F5F0E8', need: 3, scope: '非文本图形' },

  // O. 分栏登录页 + 社交登录入口
  { area: '登录页·品牌栏', label: '副文案（rgba 白 72%）on Hero 渐变最亮端', fg: B.whiteFg, a: 0.72, bg: B.heroLight, need: 4.5 },
  { area: '登录页·品牌栏', label: '要点文字（rgba 白 78%）on Hero 渐变最亮端', fg: B.whiteFg, a: 0.78, bg: B.heroLight, need: 4.5 },
  { area: '登录页·品牌栏', label: '序号徽章（gold-soft）on Hero 渐变最亮端', fg: B.goldSoft, a: 1, bg: B.heroLight, need: 4.5 },
  { area: '登录页·表单卡', label: '分隔线文案（--ll-muted）on 卡片白底', fg: '#64748b', a: 1, bg: '#FFFFFF', need: 4.5 },
  { area: '社交登录入口', label: '品牌按钮标签（--ll-ink）on 按钮白底', fg: '#0f172a', a: 1, bg: '#FFFFFF', need: 4.5 },
  { area: '社交登录入口', label: '「未配置」角标（--ll-gray）on 缩略图底', fg: B.gray, a: 1, bg: '#EEF1F6', need: 4.5 },
  { area: '社交登录入口', label: '探测说明（--ll-muted）on 卡片白底', fg: '#64748b', a: 1, bg: '#FFFFFF', need: 4.5 },

  // P. 登录页动态折叠玻璃着色器背景（GradFlow Glass Fold）之上的文案
  // 最不利取值：着色器最亮像素(#96D7FF)被左侧压暗层 rgba(6,8,30,.72) 覆盖
  { area: '登录页·着色器背景', label: '主张（纯白）on 压暗层×着色器最亮', fg: B.whiteFg, a: 1, bg: '#2E425D', need: 4.5 },
  { area: '登录页·着色器背景', label: '副文案（rgba 白 72%）on 同上', fg: B.whiteFg, a: 0.72, bg: '#2E425D', need: 4.5 },
  { area: '登录页·着色器背景', label: '要点（rgba 白 78%）on 同上', fg: B.whiteFg, a: 0.78, bg: '#2E425D', need: 4.5 },
  { area: '登录页·着色器背景', label: '序号徽章（gold-soft）on 同上', fg: B.goldSoft, a: 1, bg: '#2E425D', need: 4.5 },
  { area: '登录页·着色器背景', label: '表单面板文字（--ll-ink）on 浅面板90%×着色器最亮', fg: '#0f172a', a: 1, bg: '#ECF3FA', need: 4.5 },

  // Q. 过渡动画页：液态金属玻璃光泽峰值下的最不利情形（已含内容压暗层）
  { area: '过渡页·金属扫光峰值', label: '标语（#AEB7C4）on 压暗层×扫光峰值', fg: '#AEB7C4', a: 1, bg: '#31374D', need: 4.5 },
  { area: '过渡页·金属扫光峰值', label: 'LOGO 银色金属最深档 on 同上', fg: '#A9BCD4', a: 1, bg: '#31374D', need: 3, scope: '大号粗体字' },
  { area: '过渡页·金属扫光峰值', label: 'LOGO 金色金属最深档 on 同上', fg: '#B0862C', a: 1, bg: '#31374D', need: 3, scope: '大号粗体字' },
  { area: '过渡页·金属扫光峰值', label: '跳过按钮文字（#E8E3D9）on 深玻璃被扫光提亮', fg: '#E8E3D9', a: 1, bg: '#596477', need: 4.5 },
]

/* ---------- 计算 ---------- */
const rows = cases.map(c => {
  const fgEff = c.a === 1 ? c.fg : over(c.fg, c.a, c.bg)
  const r = ratio(fgEff, c.bg)
  return { ...c, fgEff, r, verdict: grade(r, c.need) }
})
const fail = rows.filter(r => r.verdict === 'FAIL')

/* ---------- 渲染 ---------- */
const lines = []
lines.push('# 深色背景区 · WCAG 2.1 对比度审计报告', '')
lines.push('> 生成时间：' + new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Taipei' }))
lines.push('> 生成脚本：`frontend/scripts/contrast-audit.mjs`（可重复执行）', '')
lines.push('## 1. 审计范围与方法', '')
lines.push('- 覆盖站内所有承载文字的深色底/深色渐变区域：前台顶栏、首页与关于页 Hero 渐变、启动过渡页、')
lines.push('  后台深色侧栏、客服头部、后台客服工作台 Hero、会员中心 Hero、商城商品卡、首页 Banner、')
lines.push('  隐私中心与钱包流水 Hero、深色模式表面；并一并修正浅色底上的小字号弱对比文字。')
lines.push('- 半透明文字先与所在背景做 **alpha 合成**，再按合成后的实色计算对比度（text-shadow 不计入 WCAG）。')
lines.push('- 渐变背景取 **最亮端**；图片叠罩按 **纯白图片** 作为最不利取值。')
lines.push('- 判定标准：普通文本 ≥ 4.5:1，大文本 ≥ 3:1，非文本 UI 组件 ≥ 3:1。')
lines.push('')
lines.push('## 2. 对比度审计明细（' + rows.length + ' 项）', '')
lines.push('| 区域 | 文字 | 合成后前景 | 背景 | 对比度 | 要求 | 判定 |')
lines.push('| --- | --- | --- | --- | --- | --- | --- |')
for (const r of rows) {
  lines.push(`| ${r.area} | ${r.label} | \`${r.fgEff}\` | \`${r.bg}\` | ${r.r.toFixed(2)}:1 | ${r.need}:1 | **${r.verdict}** |`)
}
lines.push('')
lines.push('## 3. 失败项', '')
if (fail.length === 0) {
  lines.push('无。全部用例满足 WCAG 2.1 AA。')
} else {
  lines.push('| 区域 | 文字 | 对比度 | 要求 |')
  lines.push('| --- | --- | --- | --- |')
  for (const r of fail) lines.push(`| ${r.area} | ${r.label} | ${r.r.toFixed(2)}:1 | ${r.need}:1 |`)
}
lines.push('')
lines.push('## 4. 结论', '')
lines.push(fail.length === 0
  ? '✅ 全部 PASS，深色背景区与浅色底小字号文字均满足 WCAG 2.1 AA（普通文本 ≥ 4.5:1）。'
  : `⚠️ 仍有 ${fail.length} 项 FAIL。`)
lines.push('')

mkdirSync(join(root, 'docs'), { recursive: true })
writeFileSync(join(root, 'docs', '深色背景可读性-对比度审计报告.md'), lines.join('\n'), 'utf8')
console.log(lines.join('\n'))
console.log(`\n合计 ${rows.length} 项，PASS ${rows.length - fail.length}，FAIL ${fail.length}`)
console.log('报告已写入 docs/深色背景可读性-对比度审计报告.md')
