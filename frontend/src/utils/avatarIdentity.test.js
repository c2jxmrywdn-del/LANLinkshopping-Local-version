import { describe, expect, it } from 'vitest'
import { AVATAR_GRADIENTS, avatarGradientOf, gradientIndexOf, initialsOf, orgShortNameOf, orgInitialsOf } from './avatarIdentity'

function channel(c) {
  const s = c / 255
  return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
}
function luminance(hex) {
  const h = hex.replace('#', '')
  const [r, g, b] = [0, 2, 4].map((i) => channel(parseInt(h.slice(i, i + 2), 16)))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

describe('用户头像身份工具（utils/avatarIdentity）', () => {
  it('正常：中文名取「名」的 1–2 字', () => {
    expect(initialsOf('欧阳杰')).toBe('阳杰')
    expect(initialsOf('李雷')).toBe('李雷')
    expect(initialsOf('雷')).toBe('雷')
  })

  it('正常：拉丁名取「首词首字母 + 末词首字母」', () => {
    expect(initialsOf('ouyang jason')).toBe('OJ')
    expect(initialsOf('Maria van der Berg')).toBe('MB')
  })

  it('正常：单词名取前两位字母', () => {
    expect(initialsOf('jason')).toBe('JA')
  })

  it('边界：分隔符 / 下划线 / 点 / 多余空格归一', () => {
    expect(initialsOf('  ouyang_jason  ')).toBe('OJ')
    expect(initialsOf('a.b')).toBe('AB')
    expect(initialsOf('欧 阳 杰')).toBe('阳杰')
  })

  it('异常：空值与纯符号返回空串（交由组件回退中性标记）', () => {
    expect(initialsOf('')).toBe('')
    expect(initialsOf(null)).toBe('')
    expect(initialsOf(undefined)).toBe('')
    expect(initialsOf('--- ***')).toBe('')
    expect(initialsOf(123)).toBe('12') // 数字名退化为前两位
  })

  it('确定性：同一 seed 恒定映射到同一调色板项', () => {
    expect(gradientIndexOf('欧阳杰')).toBe(gradientIndexOf('欧阳杰'))
    expect(avatarGradientOf('ouyang jason').css).toBe(avatarGradientOf('ouyang jason').css)
  })

  it('调色板契约：两端相对亮度均 ≤0.18（保证白色缩写 ≥4.5:1）', () => {
    for (const [from, to] of AVATAR_GRADIENTS) {
      expect(luminance(from)).toBeLessThanOrEqual(0.18)
      expect(luminance(to)).toBeLessThanOrEqual(0.18)
    }
  })

  it('调色板契约：全部为合法 6 位十六进制色', () => {
    for (const [from, to] of AVATAR_GRADIENTS) {
      expect(from).toMatch(/^#[0-9A-Fa-f]{6}$/)
      expect(to).toMatch(/^#[0-9A-Fa-f]{6}$/)
    }
  })
})

describe('企业名头像缩写（orgShortNameOf / orgInitialsOf）', () => {
  it('正常：剥离组织形式后缀得到商号', () => {
    expect(orgShortNameOf('华晨建材集团有限公司')).toBe('华晨建材')
    expect(orgShortNameOf('阿里云计算有限公司')).toBe('阿里云计算')
    expect(orgShortNameOf('中国建筑股份有限公司')).toBe('中国建筑')
    expect(orgShortNameOf('顺丰速运集团')).toBe('顺丰速运')
  })

  it('正常：无后缀 / 空值原样返回', () => {
    expect(orgShortNameOf('华晨建材')).toBe('华晨建材')
    expect(orgShortNameOf('')).toBe('')
    expect(orgShortNameOf(null)).toBe('')
  })

  it('边界：纯后缀名不剥成空串（长度保护）', () => {
    expect(orgShortNameOf('有限公司').length).toBeGreaterThan(0)
    expect(orgShortNameOf('公司').length).toBeGreaterThan(0)
  })

  it('缩写：取商号前 2 字，避免「公司」等后缀字', () => {
    expect(orgInitialsOf('华晨建材集团有限公司')).toBe('华晨')
    expect(orgInitialsOf('中国建筑股份有限公司')).toBe('中国')
    expect(orgInitialsOf('顺丰速运集团')).toBe('顺丰')
    expect(orgInitialsOf('店')).toBe('店')
  })

  it('缩写：拉丁企业名回退 initialsOf 规则', () => {
    expect(orgInitialsOf('Acme Corp')).toBe('AC')
    expect(orgInitialsOf('')).toBe('')
  })
})
