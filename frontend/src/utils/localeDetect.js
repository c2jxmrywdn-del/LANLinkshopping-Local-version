/**
 * 系统语言自动识别（多维特征加权决策）
 *
 * 维度与权重（离线优先，网络兜底）：
 *   时区           3.0   —— 离线可得、稳定，是"人在哪"的最强单信号
 *   浏览器语言[0]  2.5   —— 用户显式声明过的首选语言
 *   浏览器语言[1+] 1.2   —— 次级偏好
 *   Intl 解析区域  1.5   —— 浏览器 UI 默认区域（指纹维度之一）
 *   Intl 数字区域  1.0   —— 交叉印证（指纹维度之二）
 *   IP 归属地      2.5   —— 仅在离线信号不明确时调用（有界超时 + 服务端缓存）
 *
 * 决策规则：
 *  - 已有用户显式选择（localStorage）→ 直接采用，**不做任何自动覆盖**；
 *  - 离线信号高置信（占比 ≥0.66 且主信号 ≥3）→ 直接定夺，**跳过 IP 请求**（零延迟、少隐私外发）；
 *  - 离线信号冲突/不足 → 用 IP 归属地兜底后再加权投票；
 *  - 全部无信号 → 回落站点默认 zh-CN。
 *
 * 纯函数与副作用分离：localeFromX / scoreSignals 不触网不读全局，便于单测。
 */

export const SUPPORTED_LOCALES = ['zh-CN', 'en-US']
export const DEFAULT_LOCALE = 'zh-CN'
export const LOCALE_STORAGE_KEY = 'll_locale'

/** 中文圈国家/地区（服务端 geo 提示与前端判定共用同一口径） */
export const ZH_COUNTRIES = ['CN', 'HK', 'TW', 'MO']

/** 中文圈 IANA 时区（取主名） */
const ZH_TIMEZONES = [
  'Asia/Shanghai', 'Asia/Chongqing', 'Asia/Harbin', 'Asia/Urumqi',
  'Asia/Taipei', 'Asia/Hong_Kong', 'Asia/Macau'
]

/** 语言标签 → 支持的语言（zh* → zh-CN，en* → en-US，其余忽略） */
export function localeFromLanguageTag(tag) {
  if (!tag || typeof tag !== 'string') return null
  const t = tag.trim().toLowerCase()
  if (t.startsWith('zh')) return 'zh-CN'
  if (t.startsWith('en')) return 'en-US'
  return null
}

/** IANA 时区 → 语言（命中中文圈时区返回 zh-CN，否则 null 交由其它维度裁决） */
export function localeFromTimezone(timezone) {
  if (!timezone || typeof timezone !== 'string') return null
  return ZH_TIMEZONES.includes(timezone) ? 'zh-CN' : null
}

/** 国家/地区码 → 语言（中文圈 zh-CN；其余国家按 en-US 兜底） */
export function localeFromCountry(code) {
  if (!code || typeof code !== 'string') return null
  const c = code.trim().toUpperCase()
  if (!/^[A-Z]{2}$/.test(c)) return null
  return ZH_COUNTRIES.includes(c) ? 'zh-CN' : 'en-US'
}

/**
 * 多信号加权投票。
 * @param {{timezone?:string, languages?:string[], intlLocale?:string, numberLocale?:string, ip?:string}} signals
 * @returns {{locale: string|null, confidence: number, scores: Record<string, number>, total: number}}
 */
export function scoreSignals(signals = {}) {
  const scores = { 'zh-CN': 0, 'en-US': 0 }
  const add = (loc, weight) => { if (loc && loc in scores) scores[loc] += weight }

  add(localeFromTimezone(signals.timezone), 3)

  const langs = Array.isArray(signals.languages) ? signals.languages : []
  langs.forEach((tag, i) => add(localeFromLanguageTag(tag), i === 0 ? 2.5 : 1.2))

  add(localeFromLanguageTag(signals.intlLocale), 1.5)
  add(localeFromLanguageTag(signals.numberLocale), 1.0)
  add(localeFromCountry(signals.ip), 2.5)

  const total = scores['zh-CN'] + scores['en-US']
  if (total <= 0) return { locale: null, confidence: 0, scores, total: 0 }
  const top = scores['zh-CN'] >= scores['en-US'] ? 'zh-CN' : 'en-US'
  return { locale: top, confidence: scores[top] / total, scores, total }
}

/** 离线信号是否已足够明确（可跳过 IP 请求） */
export function isConfident(result) {
  return Boolean(result?.locale) && result.confidence >= 0.66 && result.scores[result.locale] >= 3
}

/** 采集离线维度：时区 / 语言列表 / Intl 解析区域 / Intl 数字区域（浏览器指纹的稳定项） */
export function collectOfflineSignals() {
  const out = { timezone: null, languages: [], intlLocale: null, numberLocale: null }
  if (typeof window === 'undefined') return out
  try {
    const resolved = Intl.DateTimeFormat().resolvedOptions()
    out.timezone = resolved.timeZone || null
    out.intlLocale = resolved.locale || null
  } catch (e) { /* 环境异常忽略 */ }
  try {
    out.numberLocale = Intl.NumberFormat().resolvedOptions().locale || null
  } catch (e) { /* 忽略 */ }
  try {
    const nav = typeof navigator === 'undefined' ? null : navigator
    if (nav && Array.isArray(nav.languages) && nav.languages.length) out.languages = [...nav.languages]
    else if (nav && nav.language) out.languages = [nav.language]
  } catch (e) { /* 忽略 */ }
  return out
}

function withTimeout(promise, ms) {
  return new Promise((resolve) => {
    let done = false
    const timer = setTimeout(() => { if (!done) { done = true; resolve(null) } }, ms)
    promise.then(
      (v) => { if (!done) { done = true; clearTimeout(timer); resolve(v) } },
      () => { if (!done) { done = true; clearTimeout(timer); resolve(null) } }
    )
  })
}

/** 默认的服务端地理提示请求（同源、短超时、失败即静默降级） */
async function fetchCountryHint(timeoutMs) {
  if (typeof fetch !== 'function') return null
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const res = await fetch('/api/i18n/locale-hint', {
      signal: controller.signal, credentials: 'same-origin', cache: 'no-store'
    })
    if (!res.ok) return null
    const body = await res.json().catch(() => null)
    return body?.data?.country || null
  } catch (e) {
    return null
  } finally {
    clearTimeout(timer)
  }
}

/**
 * 执行自动识别。**无副作用**：不写入 localStorage、不修改 locale，由调用方决定何时生效。
 * @param {{saved?:string|null, timeoutMs?:number, fetchHint?:Function, signals?:object}} [opts]
 *        signals 可显式注入（便于单测；缺省时自行采集浏览器离线信号）
 * @returns {Promise<{locale:string, confidence:number, source:string, signals:object}>}
 */
export async function detectLocale(opts = {}) {
  const saved = opts.saved || null
  if (saved && SUPPORTED_LOCALES.includes(saved)) {
    return { locale: saved, confidence: 1, source: 'stored', signals: {} }
  }

  const offline = opts.signals || collectOfflineSignals()
  const first = scoreSignals(offline)
  if (isConfident(first)) {
    // 高置信：直接用离线结论，不发起 IP 请求（更快、也更少隐私外发）
    return { locale: first.locale, confidence: first.confidence, source: 'offline', signals: offline }
  }

  const timeoutMs = Number.isFinite(opts.timeoutMs) ? opts.timeoutMs : 1200
  const fetcher = opts.fetchHint || (() => fetchCountryHint(timeoutMs))
  const country = await withTimeout(Promise.resolve().then(fetcher), timeoutMs + 100)

  const merged = { ...offline, ip: country || undefined }
  const result = scoreSignals(merged)
  if (result.locale) {
    return { locale: result.locale, confidence: result.confidence, source: country ? 'offline+ip' : 'offline', signals: merged }
  }
  return { locale: DEFAULT_LOCALE, confidence: 0, source: 'default', signals: merged }
}
