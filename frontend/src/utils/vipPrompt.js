// VIP 订阅引导位负频控（out/vip-subscription-design.md §2 总则）：
// ① 可关闭引导位（入口 2/3/6）点「×」后写 localStorage `ll_vip_prompt_{slot}`，7 天冷却不再强推；
// ② 引导类入口（2/3/5/6）每次会话累计曝光 ≤2（sessionStorage 计数），
//    超出上限只放行优先级更高者（结算 > 订单回执 > 商品详情 > 签到 toast）；
// ③ 导航类常驻入口（首页/用户中心/商城角标/会员中心）是页面信息结构，不计曝光、不走本工具。
const KEY_PREFIX = 'll_vip_prompt_'
const SESSION_KEY = 'll_vip_prompt_exposed'
const COOLDOWN_MS = 7 * 24 * 60 * 60 * 1000
const SESSION_CAP = 2

// 曝光优先级：数值越大越优先保留
export const VIP_PROMPT_PRIORITY = { checkin: 1, pdp: 2, order: 3, checkout: 4 }

function dismissedAt(slot) {
  try {
    const t = Number(localStorage.getItem(KEY_PREFIX + slot))
    return Number.isFinite(t) && t > 0 ? t : 0
  } catch (e) { return 0 }
}

/** 是否仍在关闭冷却期（7 天） */
export function isPromptDismissed(slot) {
  const t = dismissedAt(slot)
  return t > 0 && Date.now() - t < COOLDOWN_MS
}

/** 记录「×」关闭时间戳，启动 7 天冷却 */
export function dismissPrompt(slot) {
  try { localStorage.setItem(KEY_PREFIX + slot, String(Date.now())) } catch (e) { /* 隐私模式等：忽略 */ }
}

function exposedSlots() {
  try {
    const v = JSON.parse(sessionStorage.getItem(SESSION_KEY) || '[]')
    return Array.isArray(v) ? v : []
  } catch (e) { return [] }
}

/** 当前会话已曝光次数（调试/埋点用） */
export function sessionExposureCount() { return exposedSlots().length }

/**
 * 尝试占用一次曝光：未冷却且（会话未达上限，或本次优先级高于全部已曝光入口）时放行。
 * 放行即写入会话计数；调用方应在真实渲染后调用（组件挂载即渲染的场合在 mounted 里调）。
 */
export function tryExposePrompt(slot) {
  if (isPromptDismissed(slot)) return false
  const list = exposedSlots()
  const priority = VIP_PROMPT_PRIORITY[slot] ?? 0
  if (list.length >= SESSION_CAP && list.every(s => (VIP_PROMPT_PRIORITY[s] ?? 0) >= priority)) return false
  try { sessionStorage.setItem(SESSION_KEY, JSON.stringify([...list, slot])) } catch (e) { /* 忽略 */ }
  return true
}
