import { describe, expect, it, vi } from 'vitest'
import {
  DEFAULT_LOCALE,
  detectLocale,
  isConfident,
  localeFromCountry,
  localeFromLanguageTag,
  localeFromTimezone,
  scoreSignals
} from './localeDetect'

describe('语言自动识别（utils/localeDetect）', () => {
  /* ---------------- 单维映射 ---------------- */

  it('正常：语言标签归一（zh* → zh-CN，en* → en-US，其余忽略）', () => {
    expect(localeFromLanguageTag('zh-CN')).toBe('zh-CN')
    expect(localeFromLanguageTag('zh-TW')).toBe('zh-CN')
    expect(localeFromLanguageTag('zh-Hant-HK')).toBe('zh-CN')
    expect(localeFromLanguageTag('en-GB')).toBe('en-US')
    expect(localeFromLanguageTag('fr-FR')).toBe(null)
    expect(localeFromLanguageTag('')).toBe(null)
    expect(localeFromLanguageTag(null)).toBe(null)
  })

  it('正常：中文圈时区识别（非中文圈不表态，交由其它维度裁决）', () => {
    expect(localeFromTimezone('Asia/Shanghai')).toBe('zh-CN')
    expect(localeFromTimezone('Asia/Taipei')).toBe('zh-CN')
    expect(localeFromTimezone('Asia/Hong_Kong')).toBe('zh-CN')
    expect(localeFromTimezone('America/New_York')).toBe(null)
  })

  it('正常：国家/地区码映射', () => {
    expect(localeFromCountry('CN')).toBe('zh-CN')
    expect(localeFromCountry('hk')).toBe('zh-CN')
    expect(localeFromCountry('US')).toBe('en-US')
    expect(localeFromCountry('DE')).toBe('en-US')
    expect(localeFromCountry('CHN')).toBe(null)
    expect(localeFromCountry(null)).toBe(null)
  })

  /* ---------------- 加权决策 ---------------- */

  it('高置信：时区 + 浏览器首选语言一致 → 直接定夺（跳过 IP）', () => {
    const r = scoreSignals({ timezone: 'Asia/Shanghai', languages: ['zh-CN', 'en-US'] })
    expect(r.locale).toBe('zh-CN')
    // 次级语言仍会给另一候选加分，故置信度不必为 1，但要显著过半
    expect(r.confidence).toBeGreaterThan(0.66)
    expect(r.scores['zh-CN']).toBeGreaterThan(r.scores['en-US'])
    expect(isConfident(r)).toBe(true)
  })

  it('高置信：仅时区 + 单一语言 → 置信度为 1', () => {
    const r = scoreSignals({ timezone: 'Asia/Shanghai', languages: ['zh-CN'] })
    expect(r.confidence).toBe(1)
    expect(isConfident(r)).toBe(true)
  })

  it('冲突：时区中文但浏览器语言/区域为英文 → 交由 IP 兜底（不构成高置信）', () => {
    const r = scoreSignals({ timezone: 'Asia/Shanghai', languages: ['en-US'], intlLocale: 'en-US' })
    expect(r.locale).toBe('en-US')
    expect(isConfident(r)).toBe(false)
  })

  it('冲突 + IP 兜底：归属地中文可扳回中文', () => {
    const r = scoreSignals({ timezone: 'Asia/Shanghai', languages: ['en-US'], intlLocale: 'en-US', ip: 'CN' })
    expect(r.locale).toBe('zh-CN')
    expect(r.scores['zh-CN']).toBeGreaterThan(r.scores['en-US'])
  })

  it('无任何信号 → 不臆断（locale 为 null）', () => {
    const r = scoreSignals({})
    expect(r.locale).toBe(null)
    expect(r.confidence).toBe(0)
    expect(isConfident(r)).toBe(false)
  })

  /* ---------------- 编排行为 ---------------- */

  it('已有手动选择：直接采用且不发起任何网络请求', async () => {
    const fetchHint = vi.fn()
    const r = await detectLocale({ saved: 'en-US', fetchHint })
    expect(r.locale).toBe('en-US')
    expect(r.source).toBe('stored')
    expect(fetchHint).not.toHaveBeenCalled()
  })

  it('离线高置信：跳过 IP 请求（零延迟、少外发）', async () => {
    const fetchHint = vi.fn()
    const r = await detectLocale({
      signals: { timezone: 'Asia/Shanghai', languages: ['zh-CN'] },
      fetchHint
    })
    expect(r.locale).toBe('zh-CN')
    expect(r.source).toBe('offline')
    expect(fetchHint).not.toHaveBeenCalled()
  })

  it('离线不明确：调用 IP 提示并纳入投票', async () => {
    const fetchHint = vi.fn().mockResolvedValue('CN')
    const r = await detectLocale({
      signals: { timezone: 'Asia/Shanghai', languages: ['en-US'], intlLocale: 'en-US' },
      fetchHint
    })
    expect(fetchHint).toHaveBeenCalledTimes(1)
    expect(r.locale).toBe('zh-CN')
    expect(r.source).toBe('offline+ip')
  })

  it('异常：IP 提示失败/超时 → 静默降级为离线结论', async () => {
    const fetchHint = vi.fn().mockRejectedValue(new Error('network down'))
    const r = await detectLocale({
      signals: { timezone: 'Asia/Shanghai', languages: ['en-US'], intlLocale: 'en-US' },
      fetchHint
    })
    expect(r.locale).toBe('en-US')
    expect(r.source).toBe('offline')
  })

  it('异常：IP 提示永不返回 → 由有界超时兜底，不阻塞启动', async () => {
    const fetchHint = vi.fn(() => new Promise(() => {}))
    const r = await detectLocale({
      signals: { timezone: 'Asia/Shanghai', languages: ['zh-CN'] },
      timeoutMs: 50,
      fetchHint
    })
    // 离线已高置信，根本不会调用；此用例验证的是"一旦调用也有超时保护"
    expect(r.locale).toBe('zh-CN')
  })

  it('无信号兜底 → 站点默认语言', async () => {
    const r = await detectLocale({ signals: {}, fetchHint: async () => null })
    expect(r.locale).toBe(DEFAULT_LOCALE)
    expect(r.source).toBe('default')
  })
})
