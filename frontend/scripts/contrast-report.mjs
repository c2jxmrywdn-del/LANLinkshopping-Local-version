/**
 * 小清新浅天蓝 · WCAG 2.1 对比度测试报告生成器
 * 运行：node scripts/contrast-report.mjs
 * 输出：docs/小清新浅天蓝-对比度测试报告.md
 *
 * 依据 WCAG 2.1 AA：
 *   - 普通文本与背景  ≥ 4.5:1
 *   - 大文本（18pt/14pt 粗体及以上）与背景 ≥ 3:1
 *   - 非文本 UI 组件（边框、图标）与相邻颜色 ≥ 3:1
 *   - 禁用态控件不受对比度要求约束（WCAG 明文豁免）
 */
import { writeFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..')

/* ---------- 色板 ---------- */
const palette = {
  '--ll-skyblue':        { hex: '#A9CADA', name: '浅天蓝（全局点击态填充）' },
  '--ll-skyblue-active': { hex: '#9BC0D9', name: '浅天蓝按压填充' },
  '--ll-skyblue-border': { hex: '#5B8FB5', name: '浅天蓝协调边框（非文本 ≥3:1）' },
  '--ll-skyblue-on':     { hex: '#1F3864', name: '浅天蓝底上的深海军蓝文字' },
  '--ll-skyblue-on-disabled': { hex: '#1F3864', name: '禁用文字（降透明处理）' },
  '--ll-sky':            { hex: '#38BDF8', name: '品牌渐变起点（--ll-brand-gradient 起点）' },
  '--ll-cyan':           { hex: '#22D3EE', name: '品牌渐变终点（--ll-brand-gradient 终点）' },
  '--ll-navy':           { hex: '#1F3864', name: '深海军蓝（加购按钮文字/顶栏）' },
  '--ll-primary':        { hex: '#1E6EB8', name: '天蓝系主按钮色（antd colorPrimary，白字）' },
  '--ll-primary-hover':  { hex: '#2774BD', name: '主按钮悬停' },
  '--ll-primary-active': { hex: '#155B9E', name: '主按钮按压' },
  '--ll-white':          { hex: '#FFFFFF', name: '白底/白字' },
  '--ll-page':           { hex: '#F5F6F8', name: '页面底色' },
  '--ll-ink':            { hex: '#0F172A', name: '主文字' },
}

/* ---------- 工具 ---------- */
function hex2rgb(hex) {
  const h = hex.replace('#', '')
  return [0, 2, 4].map(i => parseInt(h.slice(i, i + 2), 16))
}
function channel(c) {
  const s = c / 255
  return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
}
function luminance(hex) {
  const [r, g, b] = hex2rgb(hex).map(channel)
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}
function ratio(a, b) {
  const la = luminance(a), lb = luminance(b)
  const [hi, lo] = la >= lb ? [la, lb] : [lb, la]
  return (hi + 0.05) / (lo + 0.05)
}
function hsl(hex) {
  let [r, g, b] = hex2rgb(hex).map(v => v / 255)
  const max = Math.max(r, g, b), min = Math.min(r, g, b)
  const l = (max + min) / 2
  let h = 0, s = 0
  if (max !== min) {
    const d = max - min
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
    switch (max) {
      case r: h = ((g - b) / d + (g < b ? 6 : 0)); break
      case g: h = (b - r) / d + 2; break
      default: h = (r - g) / d + 4
    }
    h *= 60
  }
  return `HSL(${Math.round(h)}°, ${Math.round(s * 100)}%, ${Math.round(l * 100)}%)`
}
function rgb(hex) { return `RGB(${hex2rgb(hex).join(', ')})` }
function grade(r, threshold) {
  return r >= threshold ? 'PASS' : 'FAIL'
}
/* 亮度滤镜近似：模拟 hover brightness(1.05) / active brightness(0.94) */
function brightness(hex, factor) {
  const [r, g, b] = hex2rgb(hex).map(v => Math.min(255, Math.round(v * factor)))
  return '#' + [r, g, b].map(v => v.toString(16).padStart(2, '0')).join('').toUpperCase()
}

/* ---------- 测试用例 ---------- */
const cases = [
  // “加入购物车”按钮：深海军蓝 on 品牌渐变（天蓝→青），取两端最不利点
  { label: '加购按钮文字（深海军蓝） on 品牌渐变起点 #38BDF8', fg: '--ll-navy', bg: '--ll-sky', need: 4.5, scope: '普通文本' },
  { label: '加购按钮文字（深海军蓝） on 品牌渐变终点 #22D3EE', fg: '--ll-navy', bg: '--ll-cyan', need: 4.5, scope: '普通文本' },
  { label: '加购按钮悬停（渐变起点 brightness 1.05）文字对比', fg: '--ll-navy', bg: 'br-sky-hover', need: 4.5, scope: '普通文本' },
  { label: '加购按钮按压（渐变起点 brightness 0.94）文字对比', fg: '--ll-navy', bg: 'br-sky-active', need: 4.5, scope: '普通文本' },
  // 全局点击态：普通按钮按压填充
  { label: '普通按钮按压态文字（深海军蓝） on 浅天蓝按压底', fg: '--ll-skyblue-on', bg: '--ll-skyblue-active', need: 4.5, scope: '普通文本' },
  // 主按钮（白字）
  { label: '白色 on 主按钮默认底（antd primary）', fg: '--ll-white', bg: '--ll-primary', need: 4.5, scope: '普通文本' },
  { label: '白色 on 主按钮悬停底', fg: '--ll-white', bg: '--ll-primary-hover', need: 4.5, scope: '普通文本' },
  { label: '白色 on 主按钮按压底', fg: '--ll-white', bg: '--ll-primary-active', need: 4.5, scope: '普通文本' },
  { label: '白色 on 顶栏深海军蓝（MainLayout 菜单/消息）', fg: '--ll-white', bg: '--ll-navy', need: 4.5, scope: '普通文本' },
  { label: '主文字（墨色） on 页面底色', fg: '--ll-ink', bg: '--ll-page', need: 4.5, scope: '普通文本' },
  // 大文本类（≥3:1）
  { label: '深海军蓝 on 品牌渐变起点（加购按钮 14px 加粗以上大文本场景）', fg: '--ll-navy', bg: '--ll-sky', need: 3, scope: '大文本' },
  // 非文本 UI 组件（≥3:1）
  { label: '浅天蓝协调边框 on 白底（按钮描边/焦点框）', fg: '--ll-skyblue-border', bg: '--ll-white', need: 3, scope: '非文本 UI 组件' },
  { label: '浅天蓝协调边框 on 页面底色', fg: '--ll-skyblue-border', bg: '--ll-page', need: 3, scope: '非文本 UI 组件' },
]

/* ---------- 渲染 ---------- */
function resolve(tokenOrKey) {
  if (tokenOrKey in palette) return palette[tokenOrKey].hex
  if (tokenOrKey === 'br-sky-hover') return brightness(palette['--ll-sky'].hex, 1.05)
  if (tokenOrKey === 'br-sky-active') return brightness(palette['--ll-sky'].hex, 0.94)
  throw new Error('未知色板键: ' + tokenOrKey)
}

/* ---------- 渲染 ---------- */
const lines = []
lines.push('# 小清新浅天蓝 · WCAG 2.1 对比度测试报告', '')
lines.push('> 生成时间：' + new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Taipei' }))
lines.push('> 生成脚本：`frontend/scripts/contrast-report.mjs`（可重复执行）', '')
lines.push('## 1. 色板（HEX / RGB / HSL）', '')
lines.push('| Token | 用途 | HEX | RGB | HSL | 饱和度区间(30–45%) |')
lines.push('| --- | --- | --- | --- | --- | --- |')
for (const [k, v] of Object.entries(palette)) {
  const sat = hsl(v.hex).match(/\((\d+)°,\s*(\d+)%,\s*(\d+)%\)/)
  const s = Number(sat[2])
  const inBand = (k.includes('skyblue') && s >= 30 && s <= 45) ? '✅' : '—'
  lines.push(`| \`${k}\` | ${v.name} | ${v.hex} | ${rgb(v.hex)} | ${hsl(v.hex)} | ${inBand} |`)
}
lines.push('', '> 说明：`--ll-primary*` 系列承担白字可读性，饱和度必然高于 45%，不在“浅底填充色”的 30–45% 约束内（WCAG 优先）。', '')
lines.push('## 2. 对比度测试用例', '')
lines.push('| 用例 | 前景 | 背景 | 对比度 | 要求 | 判定 | 依据 |')
lines.push('| --- | --- | --- | --- | --- | --- | --- |')
for (const c of cases) {
  const fgHex = resolve(c.fg), bgHex = resolve(c.bg)
  const r = ratio(fgHex, bgHex)
  lines.push(`| ${c.label} | \`${fgHex}\` | \`${bgHex}\` | ${r.toFixed(2)}:1 | ${c.need}:1 | **${grade(r, c.need)}** | ${c.scope} |`)
}
lines.push('', '## 3. 禁用态与豁免项', '')
lines.push('| 项目 | 结论 | 依据 |')
lines.push('| --- | --- | --- |')
lines.push('| 加购按钮禁用态：品牌渐变 + `opacity: .45` + 文字降透明 | 豁免，无需 ≥4.5:1 | WCAG 2.1 1.4.3 明确豁免“disabled 组件” |')
lines.push('| 白字 on 品牌渐变（亮度高） | 规避方案，白字仅约 1.8–2.3:1 不达标，故加购按钮改用深海军蓝文字 | 1.4.3 普通文本 ≥4.5:1 |')
lines.push('| 全局点击态浅天蓝底上无文字承载体 | 仅作背景填充/边框，非文本组件按 3:1 校验边框 | 1.4.11 非文本对比 |')
lines.push('', '## 4. 色盲可区分性（状态不只依赖颜色）', '')
lines.push('| 状态 | 除颜色外的辅助线索 |')
lines.push('| --- | --- |')
lines.push('| 悬停 | 背景提亮 + 光标为手型 + antd 默认聚焦描边 |')
lines.push('| 点击 | 背景加深 + 边框加深 + 按钮位移反馈（antd 自带） |')
lines.push('| 禁用 | 文字与背景同时降透明（灰化），且 `pointer-events` 不可点 |')
lines.push('| 选中菜单/下拉 | antd 同步渲染高亮底 + 选中图标/勾选态 |')
lines.push('', '## 5. 结论', '')
let fail = cases.filter(c => grade(ratio(resolve(c.fg), resolve(c.bg)), c.need) === 'FAIL').length
lines.push(fail === 0 ? '✅ 全部用例 PASS，满足 WCAG 2.1 AA。' : `⚠️ 有 ${fail} 项用例 FAIL，需要调整。`, '')

mkdirSync(join(root, 'docs'), { recursive: true })
writeFileSync(join(root, 'docs', '小清新浅天蓝-对比度测试报告.md'), lines.join('\n'), 'utf8')
console.log(lines.join('\n'))
console.log('\n报告已写入 docs/小清新浅天蓝-对比度测试报告.md')
