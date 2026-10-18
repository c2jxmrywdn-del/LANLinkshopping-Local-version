import { describe, expect, it, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { locale } from '../i18n'

// mock api：putSettings 可控成功/失败，getSettings 不参与本用例
vi.mock('../api', () => ({
  userApi: {
    putSettings: vi.fn(),
    getSettings: vi.fn(() => Promise.resolve({ appearance: { language: 'zh-CN' } })),
    resetSettings: vi.fn(() => Promise.resolve({}))
  }
}))
vi.mock('../utils/idb', () => ({
  getCache: vi.fn(() => Promise.resolve(null)),
  setCache: vi.fn()
}))

import { useSettingsStore } from './settings'
import { userApi } from '../api'

describe('settings.setLanguage —— 中英文切换状态一致性（修复"按钮无法使用"弹回问题）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    locale.value = 'zh-CN'
    userApi.putSettings.mockReset()
  })

  it('正常：未登录（未加载）时本地立即切换，不发起持久化', async () => {
    const s = useSettingsStore()
    s.loaded = false
    await s.setLanguage('en-US')
    expect(locale.value).toBe('en-US')
    expect(s.settings.appearance.language).toBe('en-US')
    expect(userApi.putSettings).not.toHaveBeenCalled()
    // 切回
    await s.setLanguage('zh-CN')
    expect(locale.value).toBe('zh-CN')
  })

  it('异常：登录态但持久化失败时【不回滚】已切换语言（关键修复点）', async () => {
    const s = useSettingsStore()
    s.loaded = true
    s.settings = { appearance: { language: 'zh-CN' } }
    userApi.putSettings.mockRejectedValue(new Error('network down'))
    await s.setLanguage('en-US')
    // 旧实现此处会弹回 zh-CN（表现为按钮无效）；修复后必须保持 en-US
    expect(locale.value).toBe('en-US')
    expect(s.settings.appearance.language).toBe('en-US')
  })

  it('正常：登录态持久化成功后服务端值生效且语言不被覆盖', async () => {
    const s = useSettingsStore()
    s.loaded = true
    s.settings = { appearance: { language: 'zh-CN' } }
    userApi.putSettings.mockResolvedValue({ appearance: { language: 'en-US' } })
    await s.setLanguage('en-US')
    expect(locale.value).toBe('en-US')
    expect(userApi.putSettings).toHaveBeenCalledWith({ appearance: { language: 'en-US' } })
  })

  it('边界：非法语言入参归一为 zh-CN', async () => {
    const s = useSettingsStore()
    s.loaded = false
    await s.setLanguage('xx-YY')
    expect(locale.value).toBe('zh-CN')
    await s.setLanguage(null)
    expect(locale.value).toBe('zh-CN')
    await s.setLanguage(undefined)
    expect(locale.value).toBe('zh-CN')
  })

  it('回归：连续快速切换（中→英→中）终态正确，无状态残留', async () => {
    const s = useSettingsStore()
    s.loaded = false
    await s.setLanguage('en-US')
    await s.setLanguage('zh-CN')
    await s.setLanguage('en-US')
    expect(locale.value).toBe('en-US')
    expect(s.settings.appearance.language).toBe('en-US')
  })
})
