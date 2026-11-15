import { describe, it, expect } from 'vitest'
import { h, createApp, nextTick } from 'vue'
import PlatformInvitedBadge from './PlatformInvitedBadge.vue'
// ?raw 由 Vite 解析（vitest v4 下 import.meta.url 非 file scheme，Windows 非 ASCII 路径不可靠）
import badgeSource from './PlatformInvitedBadge.vue?raw'

// 仓库未安装 @vue/test-utils，沿用 createApp 手挂载方式断言真实编译后的 DOM。
function mount(props) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp({ render: () => h(PlatformInvitedBadge, props) })
  app.mount(host)
  return { app, host }
}

describe('PlatformInvitedBadge 平台特邀商户标识', () => {
  it('show=true 渲染「平台特邀商户」文案', async () => {
    const { app, host } = mount({ show: true })
    await nextTick()

    const el = host.querySelector('.ll-invited-badge')
    expect(el).not.toBeNull()
    expect(el.textContent.trim()).toBe('平台特邀商户')
    app.unmount(); host.remove()
  })

  it('show=false 完全不渲染（普通商户不显示标识）', async () => {
    const { app, host } = mount({ show: false })
    await nextTick()

    expect(host.querySelector('.ll-invited-badge')).toBeNull()
    expect(host.textContent.trim()).toBe('')
    app.unmount(); host.remove()
  })

  it('默认不渲染：未显式传 show 时按 false 处理', async () => {
    const { app, host } = mount({})
    await nextTick()

    expect(host.querySelector('.ll-invited-badge')).toBeNull()
    app.unmount(); host.remove()
  })

  /**
   * 样式契约：jsdom 不计算 scoped 样式，改为对源码做静态断言。
   * 保证「位置固定（inline-flex 胶囊）+ 移动端收缩 + 不换行溢出」这三条不被改坏。
   */
  it('样式固定：inline-flex 胶囊 + 移动端媒体查询 + 不换行', () => {
    const src = badgeSource.replace(/\r\n/g, '\n')
    expect(src).toContain('display: inline-flex')
    expect(src).toContain('border-radius: 999px')
    expect(src).toContain('white-space: nowrap')
    expect(src).toContain('max-width: 100%')
    expect(src).toMatch(/@media \(max-width: 767px\)/)
  })
})
