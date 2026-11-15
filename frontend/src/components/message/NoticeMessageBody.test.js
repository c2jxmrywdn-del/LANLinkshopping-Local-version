import { describe, it, expect, vi, beforeEach } from 'vitest'
import { h, createApp, nextTick } from 'vue'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

import NoticeMessageBody from './NoticeMessageBody.vue'

function mount(item) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp({ render: () => h(NoticeMessageBody, { item }) })
  app.mount(host)
  return { app, host }
}

const welcome = {
  type: 'system',
  title: '欢迎使用 LANLinkshopping',
  content: '平台已为你开通采购账号，可前往个人中心完善资料。',
  relatedNo: ''
}

describe('NoticeMessageBody 非订单通知下钻', () => {
  beforeEach(() => push.mockClear())

  it('系统欢迎消息渲染正文并给「前往账户设置」入口', async () => {
    const { app, host } = mount(welcome)
    await nextTick()

    expect(host.querySelector('.notice-text').textContent).toContain('完善资料')
    const links = [...host.querySelectorAll('.notice-link')]
    expect(links.some((l) => l.textContent.includes('前往账户设置'))).toBe(true)
    app.unmount(); host.remove()
  })

  it('点击二级入口跳到 /me/settings', async () => {
    const { app, host } = mount(welcome)
    await nextTick()

    const btn = [...host.querySelectorAll('.notice-link')]
      .find((l) => l.textContent.includes('前往账户设置'))
    btn.click()
    expect(push).toHaveBeenCalledWith('/me/settings')
    app.unmount(); host.remove()
  })

  it('点击三级入口跳到 /me/address', async () => {
    const { app, host } = mount(welcome)
    await nextTick()

    const btn = [...host.querySelectorAll('.notice-link')]
      .find((l) => l.textContent.includes('管理收货地址'))
    expect(btn).toBeTruthy()
    btn.click()
    expect(push).toHaveBeenCalledWith('/me/address')
    app.unmount(); host.remove()
  })

  it('安全类系统通知给「查看登录记录」', async () => {
    const { app, host } = mount({ type: 'system', title: '账号安全提醒', content: '检测到异地登录。' })
    await nextTick()

    const btn = [...host.querySelectorAll('.notice-link')]
      .find((l) => l.textContent.includes('查看登录记录'))
    expect(btn).toBeTruthy()
    btn.click()
    expect(push).toHaveBeenCalledWith('/me/login-log')
    app.unmount(); host.remove()
  })

  it('促销通知给活动中心与领券中心', async () => {
    const { app, host } = mount({ type: 'promotion', title: '双十一大促', content: '全场满减。' })
    await nextTick()

    const labels = [...host.querySelectorAll('.notice-link')].map((l) => l.textContent)
    expect(labels.some((t) => t.includes('查看活动中心'))).toBe(true)
    expect(labels.some((t) => t.includes('去领券中心'))).toBe(true)
    app.unmount(); host.remove()
  })

  it('价格通知给价格情报入口', async () => {
    const { app, host } = mount({ type: 'price', title: '价格预警', content: '降价 3%。' })
    await nextTick()

    const btn = [...host.querySelectorAll('.notice-link')]
      .find((l) => l.textContent.includes('查看价格情报'))
    expect(btn).toBeTruthy()
    btn.click()
    expect(push).toHaveBeenCalledWith('/price-monitor')
    app.unmount(); host.remove()
  })

  it('普通系统通知只有个人中心入口，不渲染多余按钮', async () => {
    const { app, host } = mount({ type: 'system', title: '系统维护通知', content: '今晚维护。' })
    await nextTick()

    const links = [...host.querySelectorAll('.notice-link')]
    expect(links.length).toBe(1)
    expect(links[0].textContent).toContain('进入个人中心')
    app.unmount(); host.remove()
  })
})
