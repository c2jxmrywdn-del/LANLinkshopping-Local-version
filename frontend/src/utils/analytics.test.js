/**
 * Amplitude 门面单测：覆盖 ① 空 key 降级（整库不初始化、事件 no-op）
 * ② 有 key + 授权门控组合 ③ 授权撤回 setOptOut / 恢复 ④ 事件发射与页面上下文
 * ⑤ 登录/登出用户识别 ⑥ 事件属性不携带 PII。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'

const sdkCalls = { init: [], track: [], setUserId: [], reset: [], setOptOut: [] }

vi.mock('@amplitude/unified', () => ({
  initAll: vi.fn((...args) => { sdkCalls.init.push(args) }),
  track: vi.fn((...args) => { sdkCalls.track.push(args) }),
  setUserId: vi.fn((...args) => { sdkCalls.setUserId.push(args) }),
  reset: vi.fn(() => { sdkCalls.reset.push(1) }),
  setOptOut: vi.fn((...args) => { sdkCalls.setOptOut.push(args) })
}))

// import.meta.env 在 vitest 下可写；jsdom 已由 vitest.config.js 提供 window/localStorage
vi.mock('./cookieConsent', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, hasCookieConsent: vi.fn(() => mockConsent.analytics) }
})

let mockConsent = { analytics: false }

// Node ≥25 的实验性 webstorage 在无 --localstorage-file 时 localStorage 不可写（setItem/removeItem/clear
// 缺失，见本仓 vitest 运行警告），analytics.js 均在调用时取值，这里替换为可写的内存实现（同 vipPrompt.test.js）
const makeStorage = () => {
  const m = new Map()
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => { m.set(k, String(v)) },
    removeItem: (k) => { m.delete(k) },
    clear: () => m.clear()
  }
}
globalThis.localStorage = makeStorage()
globalThis.sessionStorage = makeStorage()

async function freshModule() {
  vi.resetModules()
  // freshModule 模拟全新页面加载：注销上一个模块实例注册的授权监听器并清掉单例状态
  // （直接 delete 键会泄漏监听器——新模块将看不到旧 handler 而无法 removeEventListener）
  const prev = window.__lanlinkAnalyticsConsentHandler
  if (prev) window.removeEventListener('ll-cookie-consent-changed', prev)
  delete window.__lanlinkAnalyticsState
  delete window.__lanlinkAnalyticsConsentHandler
  sdkCalls.init.length = 0
  sdkCalls.track.length = 0
  sdkCalls.setUserId.length = 0
  sdkCalls.reset.length = 0
  sdkCalls.setOptOut.length = 0
  return await import('./analytics.js')
}

beforeEach(() => {
  mockConsent = { analytics: false }
  import.meta.env.VITE_AMPLITUDE_API_KEY = ''
  globalThis.localStorage.clear()
  globalThis.sessionStorage.clear()
})

describe('analytics 双门控降级', () => {
  it('空 key：initAnalytics 不初始化 SDK，trackEvent/setUserId/reset 全部 no-op', async () => {
    const a = await freshModule()
    expect(a.initAnalytics()).toBe(false)
    expect(sdkCalls.init).toHaveLength(0)
    a.trackEvent('page_view', {})
    a.setAnalyticsUser(42)
    a.resetAnalyticsUser()
    expect(sdkCalls.track).toHaveLength(0)
    expect(sdkCalls.setUserId).toHaveLength(0)
    expect(sdkCalls.reset).toHaveLength(0)
  })

  it('有 key 但未授权 analytics：不初始化，事件 no-op', async () => {
    import.meta.env.VITE_AMPLITUDE_API_KEY = 'test-key'
    mockConsent.analytics = false
    const a = await freshModule()
    expect(a.initAnalytics()).toBe(false)
    expect(sdkCalls.init).toHaveLength(0)
    a.trackEvent('login', {})
    expect(sdkCalls.track).toHaveLength(0)
  })

  it('有 key 且已授权：initAll 携带 CSP 关闭项与会话回放降采样', async () => {
    import.meta.env.VITE_AMPLITUDE_API_KEY = 'test-key'
    mockConsent.analytics = true
    const a = await freshModule()
    expect(a.initAnalytics()).toBe(true)
    expect(sdkCalls.init).toHaveLength(1)
    const [key, opts] = sdkCalls.init[0]
    expect(key).toBe('test-key')
    expect(opts.analytics.autocapture).toBe(true)
    expect(opts.analytics.customEnrichment).toBe(false)
    expect(opts.sessionReplay.sampleRate).toBeLessThan(1)
  })

  it('重复 initAnalytics 幂等：SDK initAll 只执行一次', async () => {
    import.meta.env.VITE_AMPLITUDE_API_KEY = 'test-key'
    mockConsent.analytics = true
    const a = await freshModule()
    expect(a.initAnalytics()).toBe(true)
    expect(a.initAnalytics()).toBe(true)
    expect(sdkCalls.init).toHaveLength(1)
  })
})

describe('授权变更', () => {
  it('撤回授权：setOptOut(true)，事件停止发射', async () => {
    import.meta.env.VITE_AMPLITUDE_API_KEY = 'test-key'
    mockConsent.analytics = true
    const a = await freshModule()
    a.initAnalytics()
    a.onConsentChanged({ categories: { analytics: false } })
    expect(sdkCalls.setOptOut).toContainEqual([true])
    a.trackEvent('page_view', {})
    expect(sdkCalls.track).toHaveLength(0)
  })

  it('未初始化时撤回：不触发任何 SDK 调用', async () => {
    import.meta.env.VITE_AMPLITUDE_API_KEY = 'test-key'
    const a = await freshModule()
    a.onConsentChanged({ categories: { analytics: false } })
    expect(sdkCalls.setOptOut).toHaveLength(0)
    expect(sdkCalls.init).toHaveLength(0)
  })

  it('撤回后重新同意：初始化并以 setOptOut(false) 恢复采集', async () => {
    import.meta.env.VITE_AMPLITUDE_API_KEY = 'test-key'
    mockConsent.analytics = false
    const a = await freshModule()
    a.onConsentChanged({ categories: { analytics: false } })
    mockConsent.analytics = true
    a.onConsentChanged({ categories: { analytics: true } })
    expect(sdkCalls.init).toHaveLength(1)
    expect(sdkCalls.setOptOut).toContainEqual([false])
    a.trackEvent('page_view', {})
    expect(sdkCalls.track).toHaveLength(1)
  })

  it('ll-cookie-consent-changed 事件驱动：同意即初始化，撤回即停采', async () => {
    import.meta.env.VITE_AMPLITUDE_API_KEY = 'test-key'
    const a = await freshModule()
    // 真实顺序：cookieConsent 先写存储再广播；门面回调里会向 hasCookieConsent 复核
    mockConsent.analytics = true
    window.dispatchEvent(new CustomEvent('ll-cookie-consent-changed', { detail: { categories: { analytics: true } } }))
    expect(sdkCalls.init).toHaveLength(1)
    mockConsent.analytics = false
    window.dispatchEvent(new CustomEvent('ll-cookie-consent-changed', { detail: { categories: { analytics: false } } }))
    expect(sdkCalls.setOptOut).toContainEqual([true])
  })
})

describe('事件发射', () => {
  it('trackEvent 自动附页面路径上下文，事件属性透传', async () => {
    import.meta.env.VITE_AMPLITUDE_API_KEY = 'test-key'
    mockConsent.analytics = true
    const a = await freshModule()
    a.initAnalytics()
    a.trackEvent('product_view', { prod_id: 123, mer_id: 9 })
    expect(sdkCalls.track).toHaveLength(1)
    const [name, props] = sdkCalls.track[0]
    expect(name).toBe('product_view')
    expect(props.prod_id).toBe(123)
    expect(props.mer_id).toBe(9)
    expect(typeof props.page_path).toBe('string')
  })

  it('props 与默认 page_path 合并时调用方属性优先', async () => {
    import.meta.env.VITE_AMPLITUDE_API_KEY = 'test-key'
    mockConsent.analytics = true
    const a = await freshModule()
    a.initAnalytics()
    a.trackEvent('page_view', { page_path: '/custom' })
    expect(sdkCalls.track[0][1].page_path).toBe('/custom')
  })
})

describe('用户识别', () => {
  it('setAnalyticsUser 传字符串化 userId；空值不调用', async () => {
    import.meta.env.VITE_AMPLITUDE_API_KEY = 'test-key'
    mockConsent.analytics = true
    const a = await freshModule()
    a.initAnalytics()
    a.setAnalyticsUser(1001)
    expect(sdkCalls.setUserId[0][0]).toBe('1001')
    a.setAnalyticsUser(null)
    a.setAnalyticsUser('')
    expect(sdkCalls.setUserId).toHaveLength(1)
  })

  it('未初始化时 setAnalyticsUser/resetAnalyticsUser 均不触碰 SDK', async () => {
    import.meta.env.VITE_AMPLITUDE_API_KEY = 'test-key'
    const a = await freshModule()
    a.setAnalyticsUser(1001)
    a.resetAnalyticsUser()
    expect(sdkCalls.setUserId).toHaveLength(0)
    expect(sdkCalls.reset).toHaveLength(0)
  })

  it('resetAnalyticsUser 已初始化时调用 SDK reset', async () => {
    import.meta.env.VITE_AMPLITUDE_API_KEY = 'test-key'
    mockConsent.analytics = true
    const a = await freshModule()
    a.initAnalytics()
    a.resetAnalyticsUser()
    expect(sdkCalls.reset).toHaveLength(1)
  })
})

describe('PII 纪律', () => {
  it('事件属性仅由调用方传入的键构成，门面不注入用户信息', async () => {
    import.meta.env.VITE_AMPLITUDE_API_KEY = 'test-key'
    mockConsent.analytics = true
    const a = await freshModule()
    a.initAnalytics()
    a.setAnalyticsUser(77)
    a.trackEvent('add_to_cart', { prodId: 5, quantity: 2 })
    const props = sdkCalls.track[0][1]
    const forbidden = ['phone', 'nickname', 'nickname', 'password', 'email']
    for (const k of forbidden) expect(props).not.toHaveProperty(k)
    expect(Object.keys(props).sort()).toEqual(['page_path', 'prodId', 'quantity'].sort())
  })
})
