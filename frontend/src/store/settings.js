// 系统设置 store：加载 / 乐观更新 / 重置 + 外观（主题、字号、语言）应用
import { defineStore } from 'pinia'
import { userApi } from '../api'
import { getCache, setCache } from '../utils/idb'
import { setLocale } from '../i18n'

const DEFAULT_APPEARANCE = { theme: 'system', language: 'zh-CN', fontSize: 'standard' }

// 简单深合并（仅处理纯对象，数组/基础类型直接覆盖）
function deepMerge(base, patch) {
  const out = { ...(base || {}) }
  for (const [k, v] of Object.entries(patch || {})) {
    if (v && typeof v === 'object' && !Array.isArray(v) && typeof out[k] === 'object' && out[k] !== null) {
      out[k] = deepMerge(out[k], v)
    } else {
      out[k] = v
    }
  }
  return out
}

let mediaBound = false
function bindMediaOnce(store) {
  if (mediaBound || typeof window === 'undefined' || !window.matchMedia) return
  mediaBound = true
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => store.applyAppearance())
}

export const useSettingsStore = defineStore('settings', {
  state: () => ({
    settings: null,
    loaded: false,
    isDark: false // 供 App.vue 的 antd 主题算法使用
  }),
  actions: {
    /** 先读 IndexedDB 缓存兜底展示，再后台拉取刷新；网络失败且有缓存则静默 */
    async load() {
      const cached = await getCache('settings')
      if (cached) {
        this.settings = cached
        this.loaded = true
        this.applyAppearance()
      }
      try {
        const data = await userApi.getSettings()
        this.settings = data
        this.loaded = true
        setCache('settings', data, 24 * 60 * 60 * 1000)
        this.applyAppearance()
      } catch (e) {
        if (!cached) throw e
      }
    },
    /** 乐观更新：先本地合并生效，失败回滚并抛出 */
    async update(patch) {
      const prev = this.settings ? JSON.parse(JSON.stringify(this.settings)) : null
      this.settings = deepMerge(this.settings || {}, patch)
      this.applyAppearance()
      try {
        const data = await userApi.putSettings(patch)
        this.settings = data
        setCache('settings', data, 24 * 60 * 60 * 1000)
        this.applyAppearance()
        return data
      } catch (e) {
        this.settings = prev
        this.applyAppearance()
        throw e
      }
    },
    async reset() {
      const data = await userApi.resetSettings()
      this.settings = data
      this.loaded = true
      setCache('settings', data, 24 * 60 * 60 * 1000)
      this.applyAppearance()
      return data
    },
    /** 登出后清空并回到默认外观 */
    clear() {
      this.settings = null
      this.loaded = false
      this.applyAppearance()
    },
    /** 根据 settings.appearance 应用主题 / 字号 / 语言（无 settings 时按默认值） */
    applyAppearance() {
      bindMediaOnce(this)
      const ap = (this.settings && this.settings.appearance) || DEFAULT_APPEARANCE
      // 主题
      let dark = ap.theme === 'dark'
      if (ap.theme === 'system' || !ap.theme) {
        dark = typeof window !== 'undefined' && window.matchMedia &&
          window.matchMedia('(prefers-color-scheme: dark)').matches
      }
      document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light')
      this.isDark = dark
      // 字号（基准 16px 的 120% / 80%）
      document.documentElement.style.fontSize =
        ap.fontSize === 'large' ? '19.2px' : ap.fontSize === 'small' ? '12.8px' : ''
      // 语言
      const lang = ap.language || 'zh-CN'
      setLocale(lang)
      document.documentElement.lang = lang
    }
  }
})
