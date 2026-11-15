/**
 * Amplitude 漏斗分析门面（外部转化漏斗）。
 * 职责边界：站内风控/经营遥测走 api/index.js 的 trafficApi.trackPage（自带 analytics
 * 授权门控与会话），两者事件互不转发。
 *
 * 降级纪律：
 * - 双门控：VITE_AMPLITUDE_API_KEY 存在 且 Cookie 授权 analytics=true 才 initAll；
 *   任一不满足时 SDK 不初始化（无网络请求、无事件队列），track/identify 全部 no-op。
 * - 授权变更由 cookieConsent 广播的 ll-cookie-consent-changed 事件驱动：
 *   撤回 → setOptOut(true) 立即停采；重新同意 → init（如尚未）并 setOptOut(false) 恢复。
 * - 用户识别只传数字 userId（服务端 toView 字段），任何事件属性不携带手机号/昵称等 PII。
 */
import * as amplitude from '@amplitude/unified'
import { hasCookieConsent } from './cookieConsent'

// 状态挂 window 单例：Vite HMR/模块重载不会重建 state，也不会叠加授权监听器
// （沿用原 main.js __lanlinkAmplitudeInitialized 的单例守卫思路）。
const win = typeof window !== 'undefined' ? window : undefined
const state = (win && win.__lanlinkAnalyticsState) || { initialized: false, optOut: false }
if (win) win.__lanlinkAnalyticsState = state

function apiKey() {
  const k = import.meta.env.VITE_AMPLITUDE_API_KEY
  return (typeof k === 'string' && k.trim()) || ''
}

/** 双门控初始化；已初始化或门控未过时安全返回。返回是否处于已初始化状态。 */
function doInit() {
  if (state.initialized) return true
  const key = apiKey()
  if (!key) return false
  if (!hasCookieConsent('analytics')) return false
  try {
    amplitude.initAll(key, {
      analytics: {
        autocapture: true,
        // CSP 合规：关闭自定义 enrichment，避免远程配置下发函数字符串、
        // SDK 用 new Function 求值而被 script-src 拦截（显式 false 可阻止远程配置重新开启）
        customEnrichment: false
      },
      // 会话回放含全页面内容，100% 采样对存储与隐私都是过度采集，降至 10%
      sessionReplay: { sampleRate: 0.1 }
    })
    state.initialized = true
    if (state.optOut) amplitude.setOptOut(true)
    return true
  } catch (e) {
    console.warn('[analytics] Amplitude init failed:', e)
    return false
  }
}

export function initAnalytics() {
  return doInit()
}

/** 授权变更入口：cookieConsent 的 ll-cookie-consent-changed 事件与测试直接调用。 */
export function onConsentChanged(detail) {
  const granted = !!(detail && detail.categories && detail.categories.analytics)
  if (granted) {
    state.optOut = false
    doInit()
    if (state.initialized) {
      try { amplitude.setOptOut(false) } catch (e) { /* SDK 异常不外溢 */ }
    }
  } else if (state.initialized) {
    state.optOut = true
    try { amplitude.setOptOut(true) } catch (e) { /* SDK 异常不外溢 */ }
  }
}

if (win) {
  // 替换式注册：模块重载（Vite HMR）时移除旧 handler 再挂新 handler，
  // 任何时刻只有一个活跃监听器，避免旧闭包持有过期 state 造成双写
  if (win.__lanlinkAnalyticsConsentHandler) {
    win.removeEventListener('ll-cookie-consent-changed', win.__lanlinkAnalyticsConsentHandler)
  }
  win.__lanlinkAnalyticsConsentHandler = (e) => onConsentChanged(e && e.detail)
  win.addEventListener('ll-cookie-consent-changed', win.__lanlinkAnalyticsConsentHandler)
}

/** 漏斗事件唯一出口：未初始化或已 optOut 时 no-op；自动附页面路径上下文。 */
export function trackEvent(name, props = {}) {
  if (!state.initialized || state.optOut) return
  try {
    amplitude.track(name, { page_path: window.location.pathname, ...props })
  } catch (e) { /* 埋点永不影响业务流程 */ }
}

/** 登录态识别：setUserId 后 SDK 自动为后续事件补 userId；未初始化时 no-op。 */
export function setAnalyticsUser(userId) {
  if (!state.initialized || userId == null || userId === '') return
  try { amplitude.setUserId(String(userId)) } catch (e) { /* 身份识别失败不影响登录 */ }
}

/** 登出重置：清空 SDK 侧 userId/deviceId 绑定，防止登出后事件继续归因到原账号。 */
export function resetAnalyticsUser() {
  if (!state.initialized) return
  try { amplitude.reset() } catch (e) { /* 重置失败不影响登出 */ }
}
