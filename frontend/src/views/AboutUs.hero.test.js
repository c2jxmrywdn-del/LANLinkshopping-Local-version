import { describe, it, expect } from 'vitest'
import aboutSource from './AboutUs.vue?raw'

/**
 * 关于页 Hero 视觉契约：纯色平面底，不允许"会动/会扫"的玻璃与光栅。
 *
 * <p>历史问题（用户多次反馈，截图红圈处）：
 * ① `.about-hero` 用 `linear-gradient(135deg, …5 段浅蓝…)`，在大面积浅底上形成
 *    一条明显的斜向明暗带，看起来像光栅扫过；
 * ② `.hero-stat` 是完整 day-glass 材质：140deg 双层渐变描边 + blur(16px)，
 *    把下层渐变透上来放大斜带；
 * ③ `.hero-stat::before` 还有 6s 循环的 `llDaySheen` 斜向扫光动画。
 *
 * <p>这里用静态断言锁死：Hero 区不得再出现这些规则，避免后续"美化"又把它们加回来。
 */
describe('AboutUs Hero 纯色平面底', () => {
  const src = aboutSource.replace(/\r\n/g, '\n')

  /**
   * 取某个选择器**最后一条顶层规则体**。
   *
   * <p>同一选择器可能在文件里定义多次（基础 + 覆盖），CSS 以后者为准；
   * 但媒体查询内也有同名规则（只改 padding 等），那些不属于"基础样式"，
   * 因此只在顶层（花括号深度为 0）匹配 `选择器 {`。
   */
  function rule(selector) {
    let last = null
    let from = 0
    for (;;) {
      const idx = src.indexOf(selector, from)
      if (idx < 0) break
      // 只接受 "选择器 {" 这种紧邻形态，且必须处于顶层
      const isTopLevel = depthAt(idx) === 0
      if (!isTopLevel || src[idx + selector.length - 1] !== '{') {
        from = idx + 1
        continue
      }
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
      last = src.slice(idx, end)
      from = end
    }
    return last
  }

  /** 计算某个下标处的花括号嵌套深度（用于排除 @media 内的同名规则） */
  function depthAt(pos) {
    let depth = 0
    let inComment = false
    for (let i = 0; i < pos; i++) {
      if (!inComment && src[i] === '/' && src[i + 1] === '*') { inComment = true; i++; continue }
      if (inComment) {
        if (src[i] === '*' && src[i + 1] === '/') { inComment = false; i++ }
        continue
      }
      if (src[i] === '{') depth++
      else if (src[i] === '}') depth--
    }
    return depth
  }

  /**
   * Hero 底色：还原浅蓝层次，但必须垂直方向。
   *
   * <p>斜向（135deg）会在浅底上形成明显斜带（"光栅"），是此前被反复反馈的问题；
   * 垂直方向（180deg）保留同样的上浅—中深—下浅层次，不会产生斜向条纹。
   */
  it('Hero 底色为垂直浅蓝渐变，不含斜向角度与径向亮斑', () => {
    const heroRule = rule('.about-hero {')
    expect(heroRule).not.toBeNull()
    expect(heroRule).toContain('linear-gradient(180deg')
    // 保留原始色阶
    expect(heroRule).toContain('#F2F9FF')
    expect(heroRule).toContain('#E0F2FE')
    expect(heroRule).toContain('#BAE6FD')
    // 禁止斜向角度与径向亮斑
    expect(heroRule).not.toMatch(/135deg|120deg|140deg|145deg/)
    expect(heroRule).not.toContain('radial-gradient')
  })

  it('统计卡不再使用玻璃材质（无 backdrop-filter / 渐变描边）', () => {
    const statRule = rule('.hero-stat {')
    expect(statRule).not.toBeNull()
    expect(statRule).toContain('background: #FFFFFF')
    expect(statRule).not.toContain('backdrop-filter')
    expect(statRule).not.toContain('140deg')
    expect(statRule).not.toContain('padding-box')
  })

  it('已删除统计卡的扫光与反光伪元素', () => {
    expect(rule('.hero-stat::before')).toBeNull()
    expect(rule('.hero-stat::after')).toBeNull()
  })

  it('已删除 Hero 的网格纹理伪元素', () => {
    expect(rule('.about-hero::after')).toBeNull()
  })

  it('样式区不再引用扫光动画 llDaySheen（注释说明除外）', () => {
    // 去掉注释后检查，避免把解释性注释误判为实现
    const withoutComments = src.replace(/\/\*[\s\S]*?\*\//g, '')
    expect(withoutComments).not.toContain('llDaySheen')
  })

  it('LL 徽标块为纯白平面（不再透出下层色差）', () => {
    const coreRule = rule('.orbit-core {')
    expect(coreRule).toContain('background: #FFFFFF')
    expect(coreRule).not.toContain('backdrop-filter')
    expect(coreRule).not.toContain('145deg')
  })

})
