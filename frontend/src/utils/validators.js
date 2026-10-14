// 通用校验工具：账号中心 / 登录注册共用

// 中国大陆手机号
export const REG_PHONE = /^1[3-9]\d{9}$/

// RFC 风格邮箱（实用子集）
export const REG_EMAIL = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/

/**
 * 出生日期校验：不晚于今天、年龄满 14 岁、不早于 1900 年
 * @param {string|Date} v yyyy-MM-dd 或 Date
 * @returns {{ok:boolean, msg:string}}
 */
export function checkBirthday(v) {
  if (!v) return { ok: true, msg: '' } // 允许为空（选填场景）
  const d = v instanceof Date ? v : new Date(v)
  if (isNaN(d.getTime())) return { ok: false, msg: '出生日期格式不正确' }
  const now = new Date()
  if (d.getTime() > now.getTime()) return { ok: false, msg: '出生日期不能晚于今天' }
  if (d.getFullYear() < 1900) return { ok: false, msg: '出生日期不能早于 1900 年' }
  // 计算周岁
  let age = now.getFullYear() - d.getFullYear()
  const mDiff = now.getMonth() - d.getMonth()
  if (mDiff < 0 || (mDiff === 0 && now.getDate() < d.getDate())) age--
  if (age < 14) return { ok: false, msg: '年龄需满 14 周岁' }
  return { ok: true, msg: '' }
}

/**
 * 密码强度评估：0-4 分
 * 长度计分 + 字符种类（小写/大写/数字/特殊）计分
 * @returns {{score:number, label:string}}
 */
export function passwordStrength(pw) {
  if (!pw) return { score: 0, label: '' }
  let score = 0
  if (pw.length >= 8) score++
  if (pw.length >= 12) score++
  let kinds = 0
  if (/[a-z]/.test(pw)) kinds++
  if (/[A-Z]/.test(pw)) kinds++
  if (/\d/.test(pw)) kinds++
  if (/[^A-Za-z0-9]/.test(pw)) kinds++
  if (kinds >= 3) score++
  if (kinds >= 4) score++
  if (score > 4) score = 4
  const labels = ['', '弱', '中', '强', '很强']
  return { score, label: labels[score] }
}

/**
 * 个人简介净化：剔除 HTML 标签与 script 片段，最长 500 字
 */
export function sanitizeBio(s) {
  if (!s) return ''
  let out = String(s)
  // 先剔除 script/style 整块片段
  out = out.replace(/<\s*(script|style)[^>]*>[\s\S]*?<\s*\/\s*\1\s*>/gi, '')
  // 再剔除所有标签
  out = out.replace(/<[^>]*>/g, '')
  return out.slice(0, 500)
}
