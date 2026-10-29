import { describe, expect, it } from 'vitest'
import { ALL_EMOJIS, EMOJI_GROUPS } from './emojiData'

describe('EMOJI_GROUPS', () => {
  it('分组非空且名称唯一', () => {
    expect(EMOJI_GROUPS.length).toBeGreaterThan(0)
    const names = EMOJI_GROUPS.map(g => g.name)
    expect(new Set(names).size).toBe(names.length)
    for (const g of EMOJI_GROUPS) {
      expect(g.name.trim().length).toBeGreaterThan(0)
      expect(g.emojis.length).toBeGreaterThan(0)
    }
  })

  it('全量表情无重复', () => {
    expect(new Set(ALL_EMOJIS).size).toBe(ALL_EMOJIS.length)
  })

  it('每个表情都是 1~8 个 UTF-16 码元的非空字符串（过滤掉误录入的长文本）', () => {
    for (const e of ALL_EMOJIS) {
      expect(typeof e).toBe('string')
      expect(e.length).toBeGreaterThan(0)
      expect(e.length).toBeLessThanOrEqual(8)
      expect(e.trim()).toBe(e)
    }
  })

  it('flat 与分组数据一致（分组调整不会遗漏扁平列表）', () => {
    expect(ALL_EMOJIS).toEqual(EMOJI_GROUPS.flatMap(g => g.emojis))
  })
})
