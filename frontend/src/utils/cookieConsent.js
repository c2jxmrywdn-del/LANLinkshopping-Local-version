/**
 * LANLinkshopping Cookie Consent
 * 必要 Cookie 永远启用；其余类别由用户选择。
 */
const STORAGE_KEY = 'll_cookie_consent_v1'
const COOKIE_KEY = 'll_cookie_consent'
const DEFAULTS = { necessary: true, preferences: false, analytics: false, marketing: false }
const CONSENT_VERSION = 1
const CONSENT_MAX_AGE = 31536000
const OPTIONAL_CATEGORIES = ['preferences', 'analytics', 'marketing']

function safeParse(raw) {
  try { return raw ? JSON.parse(raw) : null } catch (e) { return null }
}

function safeStorageGet(key) {
  try { return localStorage.getItem(key) } catch (e) { return null }
}

function safeStorageSet(key, value) {
  try { localStorage.setItem(key, value) } catch (e) {}
}

export function readCookie(name) {
  if (typeof document === 'undefined') return ''
  const item = document.cookie.split('; ').find(row => row.startsWith(name + '='))
  return item ? decodeURIComponent(item.split('=').slice(1).join('=')) : ''
}

export function readCookieConsent() {
  if (typeof window === 'undefined') return null
  const stored = safeParse(safeStorageGet(STORAGE_KEY))
  if (stored && stored.version === CONSENT_VERSION && stored.categories) {
    return { version: CONSENT_VERSION, updatedAt: stored.updatedAt || null, categories: { ...DEFAULTS, ...stored.categories } }
  }
  const cookie = safeParse(readCookie(COOKIE_KEY))
  if (cookie && cookie.version === CONSENT_VERSION && cookie.categories) {
    return { version: CONSENT_VERSION, updatedAt: cookie.updatedAt || null, categories: { ...DEFAULTS, ...cookie.categories } }
  }
  return null
}

export function saveCookieConsent(categories) {
  const consent = {
    version: CONSENT_VERSION,
    updatedAt: new Date().toISOString(),
    categories: { ...DEFAULTS, ...categories, necessary: true }
  }

  if (typeof window !== 'undefined') {
    safeStorageSet(STORAGE_KEY, JSON.stringify(consent))
    // 分析授权撤回后立即废弃本地分析会话，防止后续页面继续沿用旧 session。
    if (!consent.categories.analytics) {
      try { sessionStorage.removeItem('ll_traffic_session') } catch (e) {}
    }
  }

  if (typeof document !== 'undefined') {
    document.cookie = COOKIE_KEY + '=' + encodeURIComponent(JSON.stringify(consent)) +
      '; Max-Age=' + CONSENT_MAX_AGE + '; Path=/; SameSite=Lax'

    for (const category of OPTIONAL_CATEGORIES) {
      const name = 'll_' + category
      if (consent.categories[category]) {
        document.cookie = name + '=1; Max-Age=' + CONSENT_MAX_AGE + '; Path=/; SameSite=Lax'
      } else {
        document.cookie = name + '=; Max-Age=0; Path=/; SameSite=Lax'
      }
    }
  }

  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('ll-cookie-consent-changed', { detail: consent }))
  }
  return consent
}

export function hasCookieConsent(category) {
  const consent = readCookieConsent()
  if (category === 'necessary') return true
  return !!(consent && consent.categories && consent.categories[category])
}

export function openCookiePreferences() {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('ll-open-cookie-preferences'))
  }
}

export function clearOptionalCookies(categories = OPTIONAL_CATEGORIES) {
  if (typeof document === 'undefined') return
  // 仅清理明确命名的可选客户端 Cookie；绝不触碰服务端会话 Cookie。
  for (const category of categories) {
    document.cookie = 'll_' + category + '=; Max-Age=0; Path=/; SameSite=Lax'
  }
  if (categories.includes('analytics') && typeof window !== 'undefined') {
    try { sessionStorage.removeItem('ll_traffic_session') } catch (e) {}
  }
}
