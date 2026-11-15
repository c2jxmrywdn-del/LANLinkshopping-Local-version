import { describe, expect, it } from 'vitest'
import { buildCombos, comboKey, buildValueIdKey, comboValueIds, valueIdKeyOf } from './skuCombos.js'

describe('SkuEditor buildCombos', () => {
  it('generates cartesian product, one value per dimension', () => {
    const specs = [{ name: '颜色', values: ['红', '蓝'] }, { name: '尺寸', values: ['S', 'L'] }]
    const combos = buildCombos(specs)
    expect(combos).toHaveLength(4)
    expect(combos[0]).toEqual([{ specIndex: 0, valueIndex: 0 }, { specIndex: 1, valueIndex: 0 }])
    expect(combos[3]).toEqual([{ specIndex: 0, valueIndex: 1 }, { specIndex: 1, valueIndex: 1 }])
    combos.forEach(c => expect(c).toHaveLength(specs.length))
  })
  it('returns empty for no specs', () => { expect(buildCombos([])).toEqual([]) })
  it('handles single dimension', () => {
    const combos = buildCombos([{ name: '型号', values: ['A', 'B', 'C'] }])
    expect(combos).toHaveLength(3)
    expect(combos[2]).toEqual([{ specIndex: 0, valueIndex: 2 }])
  })
})

describe('SkuEditor comboKey', () => {
  const fullSpecs = [
    { name: '颜色', values: ['红', '蓝'] },
    { name: '尺寸', values: ['S', 'L'] },
  ]

  // 回归 Critical#1：skuRows 若按下标（specIndex:valueIndex）做键，删除「红」会让「蓝」的
  // valueIndex 从 1 变成 0，幸存组合被重新分配到「红」那一行，静默继承其价格/库存/编码。
  // 内容键必须在删除兄弟值前后保持完全一致。
  it('is stable across removal of a sibling value (no re-keying of survivors)', () => {
    const keyOf = (specs, matcher) => {
      const combo = buildCombos(specs).find(matcher)
      expect(combo).toBeTruthy()
      return comboKey(combo, specs)
    }
    // 全量规格下 (蓝,S) 组合：颜色维度 valueIndex=1
    const before = keyOf(fullSpecs, c => c[0].valueIndex === 1 && c[1].valueIndex === 0)
    // 删除「红」后重建：颜色维度 valueIndex 前移，但值文本仍是「蓝」
    const shrunkSpecs = [{ name: '颜色', values: ['蓝'] }, { name: '尺寸', values: ['S', 'L'] }]
    const after = keyOf(shrunkSpecs, c => c[0].valueIndex === 0 && c[1].valueIndex === 0)
    expect(after).toBe(before)
    // 反证：按下标做键会漂移，说明该断言确实覆盖了原缺陷
    const indexKey = (combo) => combo.map(r => `${r.specIndex}:${r.valueIndex}`).join('|')
    const beforeIndex = indexKey(buildCombos(fullSpecs).find(c => c[0].valueIndex === 1 && c[1].valueIndex === 0))
    const afterIndex = indexKey(buildCombos(shrunkSpecs).find(c => c[0].valueIndex === 0 && c[1].valueIndex === 0))
    expect(afterIndex).not.toBe(beforeIndex)
  })

  it('distinguishes different combos', () => {
    const combos = buildCombos(fullSpecs)
    const keys = combos.map(c => comboKey(c, fullSpecs))
    expect(new Set(keys).size).toBe(combos.length)
  })

  it('keeps same-text values in different dimensions distinct', () => {
    const specs = [{ name: '颜色', values: ['红'] }, { name: '尺寸', values: ['红'] }]
    const combos = buildCombos(specs)
    expect(combos).toHaveLength(1)
    expect(comboKey(combos[0], specs)).toBe('0=红|1=红')
    const swapped = [{ name: '颜色', values: ['蓝'] }, { name: '尺寸', values: ['红'] }]
    expect(comboKey(buildCombos(swapped)[0], swapped)).not.toBe(comboKey(combos[0], specs))
  })

  it('tolerates missing spec/value slots without throwing', () => {
    expect(comboKey([{ specIndex: 0, valueIndex: 5 }], [{ name: '颜色', values: ['红'] }])).toBe('0=')
    expect(comboKey([{ specIndex: 9, valueIndex: 0 }], [])).toBe('9=')
  })
})

describe('SkuEditor valueId backfill key', () => {
  // 服务端 SkuTreeView.specs[].values[] 带 valueId；load() 据此建 text→valueId 映射
  const specs = [
    { name: '颜色', values: ['红', '蓝'], valueIdByText: { 红: 20, 蓝: 21 } },
    { name: '尺寸', values: ['S', 'L'], valueIdByText: { S: 22, L: 23 } },
  ]

  it('buildValueIdKey sorts numerically ascending (matches backend Collections.sort)', () => {
    // 字典序会把 "100" 排在 "20" 前，与后端 List<Long> 排序不一致
    expect(buildValueIdKey([100, 20])).toBe('20,100')
    expect(buildValueIdKey(['23', '20'])).toBe('20,23')
    expect(buildValueIdKey([7])).toBe('7')
  })

  it('comboValueIds resolves each dimension to its valueId', () => {
    const combo = [{ specIndex: 0, valueIndex: 1 }, { specIndex: 1, valueIndex: 0 }]
    expect(comboValueIds(combo, specs)).toEqual([21, 22])
    expect(valueIdKeyOf(combo, specs)).toBe('21,22')
  })

  it('gives every combo a distinct key', () => {
    const keys = buildCombos(specs).map(c => valueIdKeyOf(c, specs))
    expect(new Set(keys).size).toBe(4)
    expect(keys).toContain('20,22')
    expect(keys).toContain('21,23')
  })

  // 回归 Important#4：规格值文本合法可含 ';' / ':'（后端只校验空白/长度/合规），
  // 因此 specText 拼串会让两个不同组合塌缩成同一文本 → 按文本回填会静默串价/串库存/串编码。
  // valueId 键必须保持区分。
  it('distinguishes combos whose specText collapses to the same string', () => {
    const ambiguous = [
      { name: '材质', values: ['a', 'a;等级:b'], valueIdByText: { a: 30, 'a;等级:b': 31 } },
      { name: '等级', values: ['b;等级:c', 'c'], valueIdByText: { 'b;等级:c': 32, c: 33 } },
    ]
    const combos = buildCombos(ambiguous)
    expect(combos).toHaveLength(4)
    // 后端 SkuTreeView 的 specText 口径
    const textKeyOf = c => c
      .map(r => `${ambiguous[r.specIndex].name}:${ambiguous[r.specIndex].values[r.valueIndex]}`)
      .join(';')
    const textKeys = combos.map(textKeyOf)
    // 文本键确实有歧义：4 个组合只产生 3 个不同文本
    expect(new Set(textKeys).size).toBe(3)
    // valueId 键无歧义：4 个组合 4 个不同键
    const idKeys = combos.map(c => valueIdKeyOf(c, ambiguous))
    expect(new Set(idKeys).size).toBe(4)
    expect(idKeys).not.toContain(null)
  })

  it('returns null when a valueId is missing so the caller can fall back', () => {
    const noIds = [{ name: '颜色', values: ['红'] }]
    expect(comboValueIds([{ specIndex: 0, valueIndex: 0 }], noIds)).toBeNull()
    expect(valueIdKeyOf([{ specIndex: 0, valueIndex: 0 }], noIds)).toBeNull()
    expect(valueIdKeyOf([{ specIndex: 0, valueIndex: 9 }], specs)).toBeNull()
  })
})
