/**
 * 首屏启动契约守卫。
 *
 * <p>当前设计（拍板后）：**首屏过渡动画只由 Vue 侧的 SplashScreen 承担**，
 * `index.html` 内不再渲染任何静态骨架屏。历史实现曾有一份内联 `.ll-boot-skeleton`，
 * 与 Vue 光感动画叠在一起会出现"先骨架屏、再光感"的双重加载页，已删除。
 *
 * <p>因此这里守的是「删除后仍然成立」的四条契约：
 * ① index.html 不再出现内联骨架屏节点与样式；
 * ② html 底色与浅色应用页底一致（避免滚动回弹时透出深色）；
 * ③ 弱网降级仍存在：注入 #app + 6s 兜底，不会白屏；
 * ④ 降级提示不引用外链资源。
 */
import { describe, it, expect } from 'vitest'
import indexHtml from '../../index.html?raw'

const html = indexHtml.replace(/\r\n/g, '\n')

describe('首屏启动契约', () => {
  it('不再有内联静态骨架屏（只保留 Vue 侧光感动画）', () => {
    expect(html).not.toContain('ll-boot-skeleton')
    expect(html).not.toContain('ll-boot-wordmark')
    expect(html).not.toContain('ll-boot-bar')
  })

  it('html 底色与应用浅色页底一致，避免滚动回弹透出深色', () => {
    expect(html).toContain('html { background: #F5F6F8; }')
    expect(html).toContain('#app { min-height: 100vh; }')
  })

  it('挂载容器为 #app，且首屏不再预置占位 DOM', () => {
    expect(html).toContain('<div id="app"></div>')
    const bodyStart = html.indexOf('<body>')
    const appDiv = html.indexOf('<div id="app"></div>', bodyStart)
    expect(appDiv).toBeGreaterThan(bodyStart)
  })

  it('弱网降级仍保留：注入 #app + 6s 兜底', () => {
    expect(html).toContain('showNetworkFallback')
    expect(html).toContain("document.getElementById('app')")
    expect(html).toContain(', 6000)')
    expect(html).toContain('MAX_RETRY')
  })

  it('降级逻辑不引用外链资源', () => {
    const start = html.indexOf('function showNetworkFallback')
    expect(start).toBeGreaterThan(-1)
    const blob = html.slice(start, start + 2000)
    expect(blob).not.toMatch(/https?:\/\//)
    expect(blob).not.toMatch(/src="\/\//)
  })
})
