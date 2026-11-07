import { describe, it, expect } from 'vitest'
import { analyzeStamp, buildAnalyzedMap } from './analyzeCoverage'

describe('analyzeCoverage 快照覆盖状态纯逻辑', () => {
  it('buildAnalyzedMap：索引行 → prodId 映射（prodId 数值化、webInfoCount 兜底 0）', () => {
    const map = buildAnalyzedMap([
      { prodId: '1069', webInfoCount: 4, generatedAt: '2026-11-04T16:50:02' },
      { prodId: 1108, generatedAt: '2026-11-05 01:50:01' }
    ])
    expect(map[1069]).toEqual({ generatedAt: '2026-11-04T16:50:02', webInfoCount: 4 })
    expect(map[1108]).toEqual({ generatedAt: '2026-11-05 01:50:01', webInfoCount: 0 })
  })

  it('buildAnalyzedMap：null/undefined 行集与非法 prodId 容错', () => {
    expect(buildAnalyzedMap(null)).toEqual({})
    expect(buildAnalyzedMap(undefined)).toEqual({})
    expect(buildAnalyzedMap([{ prodId: 'x' }, {}, { prodId: 12 }])).toEqual({
      12: { generatedAt: '', webInfoCount: 0 }
    })
  })

  it('analyzeStamp：ISO 字符串与 MySQL datetime 统一为 YYYY-MM-DD HH:mm', () => {
    expect(analyzeStamp('2026-11-04T16:50:02')).toBe('2026-11-04 16:50')
    expect(analyzeStamp('2026-11-05 01:50:01')).toBe('2026-11-05 01:50')
  })

  it('analyzeStamp：纪元毫秒可解析，空值返回空串', () => {
    const ms = new Date(2026, 10, 4, 16, 50, 2).getTime()
    expect(analyzeStamp(ms)).toBe('2026-11-04 16:50')
    expect(analyzeStamp('')).toBe('')
    expect(analyzeStamp(null)).toBe('')
  })

  it('analyzeStamp：不可解析值回退截断（与旧版 slice 行为一致）', () => {
    expect(analyzeStamp('2026-11-04T16:50:02.123456789')).toBe('2026-11-04 16:50')
  })
})
