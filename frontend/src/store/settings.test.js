import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../api', () => ({
  userApi: {
    getSettings: vi.fn(),
    putSettings: vi.fn(),
    resetSettings: vi.fn()
  }
}))
vi.mock('../utils/idb', () => ({ getCache: vi.fn(), setCache: vi.fn() }))
vi.mock('../i18n', () => ({ setLocale: vi.fn(), locale: { value: 'zh-CN' } }))

import { useSettingsStore } from './settings'
import { userApi } from '../api'

const settled = {
  notify: { email: true, push: true, sms: false, groups: { order: true, promotion: false, system: true } },
  appearance: { theme: 'system', language: 'zh-CN', fontSize: 'standard' },
  privacy: { recommend: true, ads: false }
}

describe('settings store', () => {
  let store
  beforeEach(() => {
    setActivePinia(createPinia())
    store = useSettingsStore()
    vi.clearAllMocks()
    userApi.getSettings.mockResolvedValue(JSON.parse(JSON.stringify(settled)))
  })
  afterEach(() => {
    document.documentElement.removeAttribute('data-theme')
    document.documentElement.style.fontSize = ''
  })

  it('load 拉取完整设置并应用外观', async () => {
    await store.load()
    expect(store.settings.appearance.theme).toBe('system')
    expect(store.loaded).toBe(true)
  })

  it('update 乐观合并成功', async () => {
    await store.load()
    const merged = JSON.parse(JSON.stringify(settled))
    merged.notify.sms = true
    userApi.putSettings.mockResolvedValue(merged)
    const data = await store.update({ notify: { sms: true } })
    expect(data.notify.sms).toBe(true)
    expect(store.settings.notify.email).toBe(true) // 其余默认保留
  })

  it('update 失败回滚并抛出', async () => {
    await store.load()
    userApi.putSettings.mockRejectedValue(new Error('网络错误'))
    await expect(store.update({ appearance: { theme: 'dark' } })).rejects.toThrow()
    expect(store.settings.appearance.theme).toBe('system') // 已回滚
  })

  it('主题应用：dark → data-theme=dark，light → light', async () => {
    await store.load()
    store.settings = { ...store.settings, appearance: { ...settled.appearance, theme: 'dark' } }
    store.applyAppearance()
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')

    store.settings.appearance.theme = 'light'
    store.applyAppearance()
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })

  it('字号应用：large → 19.2px', async () => {
    await store.load()
    store.settings.appearance.fontSize = 'large'
    store.applyAppearance()
    expect(document.documentElement.style.fontSize).toBe('19.2px')
  })

  it('clear 复原默认外观', async () => {
    await store.load()
    store.clear()
    expect(store.settings).toBeNull()
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })
})