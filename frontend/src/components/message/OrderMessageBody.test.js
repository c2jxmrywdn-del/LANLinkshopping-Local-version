import { describe, it, expect, vi, beforeEach } from 'vitest'
import { h, createApp, nextTick } from 'vue'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

import OrderMessageBody from './OrderMessageBody.vue'

const ORDER_NO = '20261107072056914277'

function mount(item) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp({ render: () => h(OrderMessageBody, { item }) })
  app.mount(host)
  return { app, host }
}

function msg(over = {}) {
  return {
    type: 'order', title: '订单已完成',
    content: `订单 ${ORDER_NO} 已确认收货，交易完成。`,
    relatedNo: ORDER_NO, ...over
  }
}

describe('OrderMessageBody 订单消息下钻', () => {
  beforeEach(() => push.mockClear())

  it('订单消息渲染正文并去掉重复订单号', async () => {
    const { app, host } = mount(msg())
    await nextTick()

    const text = host.querySelector('.order-text').textContent
    expect(text).toContain('已确认收货')
    expect(text).not.toContain(ORDER_NO)
    app.unmount(); host.remove()
  })

  it('提供「查看订单详情」二级入口，点击跳到 /orders/:orderNo', async () => {
    const { app, host } = mount(msg())
    await nextTick()

    const links = host.querySelectorAll('.order-link')
    expect(links.length).toBe(1)
    expect(links[0].textContent).toContain('查看订单详情')
    links[0].click()
    expect(push).toHaveBeenCalledWith(`/orders/${ORDER_NO}`)
    app.unmount(); host.remove()
  })

  it('退款消息额外提供「查看退款进度」三级入口', async () => {
    const { app, host } = mount(msg({ title: '退款成功' }))
    await nextTick()

    const links = [...host.querySelectorAll('.order-link')]
    expect(links.length).toBe(2)
    const refund = links.find((l) => l.textContent.includes('查看退款进度'))
    expect(refund).toBeTruthy()
    refund.click()
    expect(push).toHaveBeenCalledWith(`/orders/${ORDER_NO}?tab=refund`)
    app.unmount(); host.remove()
  })

  it('物流消息额外提供「查看物流轨迹」三级入口', async () => {
    const { app, host } = mount(msg({ title: '商家已发货' }))
    await nextTick()

    const logistics = [...host.querySelectorAll('.order-link')]
      .find((l) => l.textContent.includes('查看物流轨迹'))
    expect(logistics).toBeTruthy()
    logistics.click()
    expect(push).toHaveBeenCalledWith(`/orders/${ORDER_NO}?tab=logistics`)
    app.unmount(); host.remove()
  })

  it('订单号非法时不渲染任何按钮（宁可少入口也不给坏链）', async () => {
    const { app, host } = mount(msg({ relatedNo: 'x' }))
    await nextTick()

    expect(host.querySelectorAll('.order-link').length).toBe(0)
    expect(host.querySelector('.order-text').textContent).toContain('已确认收货')
    app.unmount(); host.remove()
  })

  it('缺订单号时同样只渲染文字', async () => {
    const { app, host } = mount(msg({ relatedNo: '' }))
    await nextTick()

    expect(host.querySelector('.order-links')).toBeNull()
    app.unmount(); host.remove()
  })
})
