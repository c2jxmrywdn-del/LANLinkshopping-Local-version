/**
 * 用户头像身份工具（参考 21st.dev 头像设计规范）
 *
 * 规范要点：
 *  1) 回退链三层：上传图片 → 姓名缩写 → 中性标记
 *  2) 缩写取「首词首字母 + 末词首字母」；中文名取「名」的 1–2 字
 *  3) 背景色由姓名确定性推导 —— 同一用户在任何位置颜色一致
 *  4) 缩写需视觉居中（由组件侧做光学修正）
 *
 * 配色刻意全部选用深色区间：保证白色缩写字对渐变任一端的对比度均 ≥ 4.8:1。
 */

/**
 * 确定性头像渐变调色板（深色系，白字对比度实测 ≥4.8:1）。
 * 每一项为 [起始色, 结束色]，两色均为深色，避免渐变最亮端压不住白字。
 */
export const AVATAR_GRADIENTS = [
  ['#1F3864', '#24406F'], // 海军蓝（品牌主色系）
  ['#0F766E', '#108076'], // 深青
  ['#9A3412', '#7C2D12'], // 赤陶
  ['#4C1D95', '#55219E'], // 紫罗兰
  ['#1E3A8A', '#1E40AF'], // 蓝
  ['#7F1D1D', '#8A2020'], // 深红
  ['#166534', '#14532D'], // 森林绿
  ['#854D0E', '#713F12']  // 古铜金
]

const CJK_RE = /[\u3400-\u9fff\u3040-\u30ff]/

/**
 * 取头像缩写。
 *  - 中文名：取「名」（末尾 1–2 字）；只有 1 个字时取该字
 *  - 拉丁名：多词取「首词首字母 + 末词首字母」，单词取前两位
 *  - 无有效字符：返回空串（由组件回退到中性标记）
 * @param {string} name
 * @returns {string}
 */
export function initialsOf(name) {
  const raw = String(name == null ? '' : name).trim()
  if (!raw) return ''
  if (CJK_RE.test(raw)) {
    const cjk = raw.replace(/[^\u3400-\u9fff\u3040-\u30ff]/g, '')
    const src = cjk || raw
    return src.length <= 2 ? src : src.slice(-2)
  }
  const parts = raw.split(/[\s._\-·/]+/).filter(Boolean)
  if (parts.length >= 2) {
    const first = parts[0][0] || ''
    const last = parts[parts.length - 1][0] || ''
    return (first + last).toUpperCase()
  }
  const word = parts[0] || raw
  const letters = word.replace(/[^A-Za-z0-9]/g, '')
  if (!letters) return ''
  return (letters.length >= 2 ? letters.slice(0, 2) : letters).toUpperCase()
}

/**
 * 企业/店铺名常见组织形式后缀（从尾部循环剥离，长度递减保证先剥长后缀）。
 */
const ORG_SUFFIXES = [
  '股份有限公司', '有限责任公司', '集团有限公司', '有限公司',
  '集团公司', '控股集团', '实业', '集团', '公司', '厂', '铺', '店', '中心', '部', '行', '社'
]

/**
 * 企业名 → 商号短名：循环剥离组织形式后缀。
 * 「华晨建材集团有限公司」→「华晨建材」；剥空/无后缀时原样返回。
 * @param {string} name
 * @returns {string}
 */
export function orgShortNameOf(name) {
  let s = String(name == null ? '' : name).trim()
  if (!s) return ''
  let changed = true
  while (changed) {
    changed = false
    for (const suf of ORG_SUFFIXES) {
      if (s.length > suf.length && s.endsWith(suf)) {
        s = s.slice(0, -suf.length)
        changed = true
        break
      }
    }
  }
  return s
}

/**
 * 企业/店铺头像缩写：取商号前 1–2 字（「华晨建材集团有限公司」→「华晨」），
 * 与个人名的 initialsOf（取「名」末尾字）区分；拉丁名回退 initialsOf。
 * @param {string} name
 * @returns {string}
 */
export function orgInitialsOf(name) {
  const short = orgShortNameOf(name)
  if (!short) return ''
  const cjk = short.replace(/[^\u3400-\u9fff\u3040-\u30ff]/g, '')
  if (!cjk) return initialsOf(short)
  return cjk.slice(0, 2)
}

/**
 * 由 seed（姓名/手机号/用户 ID）确定性推导调色板下标。
 * 同一 seed 恒返回同一下标 → 同一用户各处颜色一致。
 * @param {string} seed
 * @returns {number}
 */
export function gradientIndexOf(seed) {
  const s = String(seed == null ? '' : seed)
  let h = 0
  for (let i = 0; i < s.length; i++) {
    h = (h * 31 + s.charCodeAt(i)) % 1000003
  }
  return h % AVATAR_GRADIENTS.length
}

/**
 * 取确定性渐变（CSS 值 + 两端颜色，便于断言对比度）。
 * @param {string} seed
 * @returns {{ from: string, to: string, css: string }}
 */
export function avatarGradientOf(seed) {
  const [from, to] = AVATAR_GRADIENTS[gradientIndexOf(seed)]
  return { from, to, css: `linear-gradient(140deg, ${from} 0%, ${to} 100%)` }
}
