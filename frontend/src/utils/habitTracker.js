/**
 * 用户习惯收集（分析类 Cookie 的落地实现）。
 *
 * - 授权门控：仅在 Cookie 偏好中心的"分析 Cookie"开启后记录；撤回授权立即停止并清除。
 * - 存储载体：第一方 Cookie ll_habit（JSON，30 天，SameSite=Lax）——搜索词、类目偏好、
 *   最近浏览商品与访问次数；仅存非敏感浏览偏好，不含任何凭证信息。
 * - 跨设备同步：登录用户将画像节流上报到 /profile/habits/report（服务端按用户存储）；
 *   游客仅保留在本机 Cookie。
 * - 透明可控：画像内容可在"隐私与 Cookie"偏好中心查看，并支持一键清除（本机 + 服务端）。
 */
import { hasCookieConsent } from './cookieConsent'

const COOKIE_KEY = 'll_habit'
const MAX_AGE = 60 * 60 * 24 * 30 // 30 天
const LIMITS = { searchTerms: 10, categories: 8, recentProducts: 12 }
const VISIT_THROTTLE_MS = 30 * 60 * 1000 // 同一会话 30 分钟内多次路由切换只记 1 次访问
const REPORT_THROTTLE_MS = 60 * 1000

function safeParse(raw) {
  try { return raw ? JSON.parse(raw) : null } catch (e) { return null }
}

function emptyProfile() {
  return { v: 1, updatedAt: null, visits: 0, lastVisitAt: 0, searchTerms: {}, categories: {}, recentProducts: [] }
}

function readProfile() {
  if (typeof document === 'undefined') return null
  const row = document.cookie.split('; ').find(r => r.startsWith(COOKIE_KEY + '='))
  const p = safeParse(row ? decodeURIComponent(row.split('=').slice(1).join('=')) : null)
  if (!p || p.v !== 1) return null
  return { ...emptyProfile(), ...p }
}

function writeProfile(p) {
  if (typeof document === 'undefined') return
  p.updatedAt = new Date().toISOString()
  try {
    document.cookie = COOKIE_KEY + '=' + encodeURIComponent(JSON.stringify(p)) +
      '; Max-Age=' + MAX_AGE + '; Path=/; SameSite=Lax'
  } catch (e) { /* 存储不可用时静默：习惯收集永不影响浏览 */ }
}

/** 排序截断：{词: 次数} 取 Top N */
function topEntries(map, n) {
  return Object.entries(map || {}).sort((a, b) => b[1] - a[1]).slice(0, n)
}

function loggedInUser() {
  try { return JSON.parse(localStorage.getItem('ll_user') || 'null') } catch (e) { return null }
}

let lastReportAt = 0
let reportTimer = null

/** 节流上报到服务端（仅登录用户；游客画像只留在本机 Cookie） */
function scheduleReport() {
  if (typeof window === 'undefined') return
  if (reportTimer) return
  const wait = Math.max(0, lastReportAt + REPORT_THROTTLE_MS - Date.now())
  reportTimer = setTimeout(async () => {
    reportTimer = null
    lastReportAt = Date.now()
    if (!hasCookieConsent('analytics')) return
    if (!loggedInUser()) return
    const profile = readProfile()
    if (!profile) return
    try {
      const { default: request } = await import('../api/request')
      await request.post('/user/habits/report', { payload: profile }, { silent: true })
    } catch (e) { /* 同步失败静默：本机 Cookie 仍是权威副本 */ }
  }, wait)
}

function gate() {
  if (!hasCookieConsent('analytics')) return null
  return readProfile() || emptyProfile()
}

/** 记录一次搜索词（商城搜索框提交时调用） */
export function recordSearch(term) {
  const p = gate()
  const t = String(term || '').trim().slice(0, 32)
  if (!p || !t) return
  p.searchTerms[t] = (p.searchTerms[t] || 0) + 1
  // 超限裁剪：保留频次 Top N
  const trimmed = {}
  topEntries(p.searchTerms, LIMITS.searchTerms).forEach(([k, v]) => { trimmed[k] = v })
  p.searchTerms = trimmed
  writeProfile(p)
  scheduleReport()
}

/** 记录一次商品浏览（商品详情加载完成时调用） */
export function recordProductView(product) {
  const p = gate()
  if (!p || !product || product.prodId == null) return
  if (product.categoryName) {
    const c = String(product.categoryName).slice(0, 32)
    p.categories[c] = (p.categories[c] || 0) + 1
    const trimmed = {}
    topEntries(p.categories, LIMITS.categories).forEach(([k, v]) => { trimmed[k] = v })
    p.categories = trimmed
  }
  p.recentProducts = [
    { id: product.prodId, title: String(product.title || '').slice(0, 60), ts: Date.now() },
    ...p.recentProducts.filter(x => x.id !== product.prodId)
  ].slice(0, LIMITS.recentProducts)
  writeProfile(p)
  scheduleReport()
}

/** 记录一次访问（路由切换时调用；30 分钟窗口去重） */
export function recordVisit() {
  const p = gate()
  if (!p) return
  const now = Date.now()
  if (now - (p.lastVisitAt || 0) < VISIT_THROTTLE_MS) return
  p.lastVisitAt = now
  p.visits = (p.visits || 0) + 1
  writeProfile(p)
  scheduleReport()
}

/** 读取当前画像（PrivacyCenter 展示用）；未授权返回 null */
export function readHabits() {
  if (!hasCookieConsent('analytics')) return null
  return readProfile()
}

/** 展示友好的摘要：Top 搜索词/类目、最近浏览 */
export function habitSummary() {
  const p = readHabits()
  if (!p) return null
  return {
    visits: p.visits || 0,
    updatedAt: p.updatedAt,
    topSearches: topEntries(p.searchTerms, 3).map(([term, count]) => ({ term, count })),
    topCategories: topEntries(p.categories, 3).map(([name, count]) => ({ name, count })),
    recentProducts: (p.recentProducts || []).slice(0, 3)
  }
}

/** 清除：撤回授权或用户主动清除时调用（本机 Cookie + 服务端副本） */
export async function clearHabits() {
  if (typeof document !== 'undefined') {
    document.cookie = COOKIE_KEY + '=; Max-Age=0; Path=/; SameSite=Lax'
  }
  if (loggedInUser()) {
    try {
      const { default: request } = await import('../api/request')
      await request.delete('/user/habits', { silent: true })
    } catch (e) { /* 服务端清除失败不阻塞本机清除 */ }
  }
}
