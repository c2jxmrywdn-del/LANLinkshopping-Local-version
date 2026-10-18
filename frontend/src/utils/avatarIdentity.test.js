import { describe, expect, it } from 'vitest'
import { AVATAR_GRADIENTS, avatarGradientOf, gradientIndexOf, initialsOf } from './avatarIdentity'

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
