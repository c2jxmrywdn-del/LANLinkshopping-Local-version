import { describe, it, expect } from 'vitest'
import activitySource from './ActivityCenter.vue?raw'

/**
 * 活动中心「注册有礼」卡片接入邀请功能的静态契约。
 *
 * <p>需求：邀请链接/二维码从「注册有礼」卡片进入。
 * 这里守护四点，避免后续重构把入口或安全约束改坏：
 * ① 注册有礼卡片有「邀请好友」按钮；
 * ② 点击使用 `.stop`，不会冒泡触发卡片的 goDetail 跳转；
 * ③ 弹窗复用 InviteLinkCard（同一份邀请链路，不另造一套）；
 * ④ 未登录先引导登录（邀请码绑定在账号上，匿名生成无意义）。
 */
describe('ActivityCenter 注册有礼 · 邀请入口', () => {
  const src = activitySource.replace(/\r\n/g, '\n')

  it('注册有礼卡片渲染「邀请好友」按钮', () => {
    expect(src).toContain('邀请好友')
    expect(src).toMatch(/v-if="c\.type === 'register'"[\s\S]{0,200}openInvite/)
  })

  it('邀请按钮阻止冒泡，不会误触发卡片跳转', () => {
    const idx = src.indexOf('openInvite(c)')
    expect(idx).toBeGreaterThan(-1)
    // 同一标签内必须是 @click.stop
    const tagStart = src.lastIndexOf('<button', idx)
    const tag = src.slice(tagStart, idx)
    expect(tag).toContain('@click.stop')
  })

  it('弹窗复用 InviteLinkCard（不另造邀请实现）', () => {
    expect(src).toContain("import InviteLinkCard from '../components/invite/InviteLinkCard.vue'")
    expect(src).toMatch(/<InviteLinkCard[\s\S]{0,80}\/>/)
  })

  it('未登录时先引导登录，不直接打开空邀请卡', () => {
    const idx = src.indexOf('function openInvite')
    expect(idx).toBeGreaterThan(-1)
    const body = src.slice(idx, idx + 400)
    expect(body).toContain('logged()')
    expect(body).toContain('requireLogin()')
  })

  it('「立即参与」按钮在注册卡上仍然保留（邀请不替代参与）', () => {
    expect(src).toContain('@click.stop="join(c)"')
    expect(src).toContain('已参与')
  })
})
