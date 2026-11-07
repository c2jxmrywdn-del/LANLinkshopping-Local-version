import { describe, it, expect, beforeEach } from 'vitest'
import { tryExposePrompt, dismissPrompt, isPromptDismissed, sessionExposureCount, VIP_PROMPT_PRIORITY } from './vipPrompt'

// Node ≥25 的实验性 webstorage 在无 --localstorage-file 时 localStorage 不可写（setItem/removeItem/clear 缺失，
// 见本仓 vitest 运行警告），vipPrompt.js 均在调用时取值，这里替换为可写的内存实现
const makeStorage = () => {
  const m = new Map()
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, String(v)),
    removeItem: (k) => m.delete(k),
    clear: () => m.clear()
  }
}
const LS = makeStorage()
const SS = makeStorage()
globalThis.localStorage = LS
globalThis.sessionStorage = SS

describe('vipPrompt 负频控（设计稿 §2：关闭 7 天冷却 + 会话曝光 ≤2 + 优先级）', () => {
  beforeEach(() => {
    LS.clear()
    SS.clear()
  })

  it('会话曝光上限：同类引导累计曝光 ≤2 次后拒绝', () => {
    expect(tryExposePrompt('pdp')).toBe(true)
    expect(tryExposePrompt('pdp')).toBe(true)
    expect(tryExposePrompt('pdp')).toBe(false)
    expect(sessionExposureCount()).toBe(2)
  })

  it('优先级保留：超上限后仅放行更高优先级入口（结算 > 订单回执 > 商品详情）', () => {
    expect(tryExposePrompt('pdp')).toBe(true)
    expect(tryExposePrompt('pdp')).toBe(true)
    // 订单回执(3) > 商品详情(2)：放行
    expect(tryExposePrompt('order')).toBe(true)
    // 结算(4) > 订单回执(3)：放行
    expect(tryExposePrompt('checkout')).toBe(true)
    // 商品详情(2) < 已曝光最高(4)：拒绝
    expect(tryExposePrompt('pdp')).toBe(false)
  })

  it('同优先级不互相挤占：已曝光两个 checkout 后，另一个 checkout 不放行', () => {
    expect(tryExposePrompt('checkout')).toBe(true)
    expect(tryExposePrompt('checkout')).toBe(true)
    expect(tryExposePrompt('checkout')).toBe(false)
  })

  it('关闭冷却：dismiss 后 7 天内不再放行，更早的记录已过冷却', () => {
    dismissPrompt('checkout')
    expect(isPromptDismissed('checkout')).toBe(true)
    expect(tryExposePrompt('checkout')).toBe(false)
    // 模拟 8 天前关闭：冷却已过
    LS.setItem('ll_vip_prompt_checkout', String(Date.now() - 8 * 24 * 60 * 60 * 1000))
    expect(isPromptDismissed('checkout')).toBe(false)
    expect(tryExposePrompt('checkout')).toBe(true)
  })

  it('优先级常量序：结算 > 订单回执 > 商品详情 > 签到 toast', () => {
    expect(VIP_PROMPT_PRIORITY.checkout).toBeGreaterThan(VIP_PROMPT_PRIORITY.order)
    expect(VIP_PROMPT_PRIORITY.order).toBeGreaterThan(VIP_PROMPT_PRIORITY.pdp)
    expect(VIP_PROMPT_PRIORITY.pdp).toBeGreaterThan(VIP_PROMPT_PRIORITY.checkin)
  })
})
