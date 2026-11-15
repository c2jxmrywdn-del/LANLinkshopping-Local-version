import { describe, it, expect } from 'vitest'
import aboutSource from './AboutUs.vue?raw'

/**
 * 联系我们卡片 · WhatsApp 动作按钮排版契约。
 *
 * <p>回归缺陷：两个按钮用 `flex: 1 1 auto`，会按内容宽度分配空间——
 * 「直接打开 WhatsApp」文案更长，按钮被撑宽，「显示二维码」变窄，
 * 文字在各自按钮里看起来偏左（实际是按钮不等宽 + 未显式居中）。
 */
describe('AboutUs 联系卡片 · WhatsApp 按钮', () => {
  const src = aboutSource.replace(/\r\n/g, '\n')

  function blockAfter(selector) {
    const idx = src.indexOf(selector)
    expect(idx, `应存在样式 ${selector}`).toBeGreaterThan(-1)
    // 规则体可能含多行与注释，取到匹配的花括号闭合（按层级计数）
    const open = src.indexOf('{', idx)
    let depth = 0
    let end = open
    for (let i = open; i < src.length; i++) {
      if (src[i] === '{') depth++
      else if (src[i] === '}') {
        depth--
        if (depth === 0) { end = i + 1; break }
      }
    }
    return src.slice(idx, end)
  }

  /** 取某选择器的【全部】规则体（同一选择器在文件里可能定义多次，后者覆盖前者）。 */
  function allBlocks(selector) {
    const out = []
    let from = 0
    for (;;) {
      const idx = src.indexOf(selector, from)
      if (idx < 0) break
      const open = src.indexOf('{', idx)
      let depth = 0
      let end = open
      for (let i = open; i < src.length; i++) {
        if (src[i] === '{') depth++
        else if (src[i] === '}') {
          depth--
          if (depth === 0) { end = i + 1; break }
        }
      }
      out.push(src.slice(idx, end))
      from = end
    }
    expect(out.length, `应至少存在一条 ${selector}`).toBeGreaterThan(0)
    return out
  }

  it('两个按钮等宽分配（flex basis 为 0，不按内容宽度撑开）', () => {
    const blocks = allBlocks('.whatsapp-actions .ant-btn {')
    // 生效的是最后一条（CSS 后者覆盖前者）
    const effective = blocks[blocks.length - 1]
    expect(effective).toContain('flex: 1 1 0')
    // whatsapp 相关规则里不得再残留 auto basis（否则窄屏下又会被内容撑开）
    const whatsappRules = src.split('\n').filter((l) => l.includes('.whatsapp-actions'))
    expect(whatsappRules.join('\n')).not.toContain('flex: 1 1 auto')
  })

  it('按钮内容水平垂直居中', () => {
    const blocks = allBlocks('.whatsapp-actions .ant-btn {')
    const effective = blocks[blocks.length - 1]
    expect(effective).toContain('justify-content: center')
    expect(effective).toContain('align-items: center')
    expect(effective).toContain('text-align: center')
  })

  it('按钮内部 span 再兜一次居中（避免被上层 text-align 影响）', () => {
    const rule = blockAfter('.whatsapp-actions .ant-btn > span')
    expect(rule).toContain('text-align: center')
    expect(rule).toContain('width: 100%')
  })

  it('「直接打开 WhatsApp」按钮仍指向官方 wa.me 链接且新窗口打开', () => {
    expect(src).toContain('href="https://wa.me/OuyangJason?s=t"')
    expect(src).toContain('target="_blank"')
    expect(src).toContain('rel="noopener noreferrer"')
  })
})
