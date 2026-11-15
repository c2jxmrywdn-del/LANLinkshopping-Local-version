import { describe, it, expect, vi } from 'vitest'
import { h, defineComponent, createApp, nextTick } from 'vue'
import MessageItem from './MessageItem.vue'

// 仓库未安装 @vue/test-utils，沿用 createApp 手挂载 + 断言真实 DOM。
const AButtonStub = defineComponent({
  emits: ['click'],
  // 必须透传原生事件：组件内用 @click.stop，Vue 会读取 $event.stopPropagation
  setup: (_, { slots, attrs, emit }) => () => h('button', {
    ...attrs,
    onClick: (e) => emit('click', e)
  }, slots.default?.())
})
const ATagStub = defineComponent({
  props: ['color'],
  setup: (props, { slots, attrs }) => () => h('span', { ...attrs, 'data-color': props.color }, slots.default?.())
})

function mount(props, slots = {}) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp({ render: () => h(MessageItem, props, slots) })
  app.component('AButton', AButtonStub)
  app.component('ATag', ATagStub)
  app.mount(host)
  return { app, host }
}

const base = {
  id: 1, type: 'order', title: '订单已完成',
  content: '订单 20261107072056914277 已确认收货，交易完成。',
  relatedNo: '20261107072056914277', readFlag: 0,
  createTime: '2026-11-15T00:31:12'
}

describe('MessageItem 消息外壳', () => {
  it('渲染标题、类型标签、时间与关联单号', async () => {
    const { app, host } = mount({ item: base })
    await nextTick()

    expect(host.querySelector('.msg-title').textContent).toBe('订单已完成')
    expect(host.querySelector('.msg-tag').textContent).toBe('订单')
    expect(host.querySelector('.msg-time').textContent).toBe('2026-11-15 00:31')
    expect(host.querySelector('.msg-no').textContent).toContain('20261107072056914277')
    app.unmount(); host.remove()
  })

  it('未读消息显示未读点与「标为已读」，点击上抛 read', async () => {
    const onRead = vi.fn()
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp({
      render: () => h(MessageItem, { item: base, onRead })
    })
    app.component('AButton', AButtonStub)
    app.component('ATag', ATagStub)
    app.mount(host)
    await nextTick()

    expect(host.querySelector('.msg-item').classList.contains('unread')).toBe(true)
    const btn = host.querySelector('.msg-read-btn')
    expect(btn).not.toBeNull()
    btn.click()
    expect(onRead).toHaveBeenCalledTimes(1)
    expect(onRead.mock.calls[0][0]).toMatchObject({ id: 1 })
    app.unmount(); host.remove()
  })

  it('已读消息不再显示未读态与已读按钮', async () => {
    const { app, host } = mount({ item: { ...base, readFlag: 1 } })
    await nextTick()

    expect(host.querySelector('.msg-item').classList.contains('unread')).toBe(false)
    expect(host.querySelector('.msg-read-btn')).toBeNull()
    app.unmount(); host.remove()
  })

  it('默认插槽可替换正文（订单类消息注入下钻正文）', async () => {
    const { app, host } = mount({ item: base }, {
      default: () => h('div', { class: 'custom-body' }, '自定义正文')
    })
    await nextTick()

    expect(host.querySelector('.custom-body').textContent).toBe('自定义正文')
    // 默认纯文本不再渲染
    expect(host.querySelector('.msg-content')).toBeNull()
    app.unmount(); host.remove()
  })

  it('无 relatedNo 时不渲染关联单号行', async () => {
    const { app, host } = mount({ item: { ...base, relatedNo: '' } })
    await nextTick()

    expect(host.querySelector('.msg-no')).toBeNull()
    app.unmount(); host.remove()
  })

  it('非订单类型回退为「通知」标签', async () => {
    const { app, host } = mount({ item: { ...base, type: 'unknown' } })
    await nextTick()

    expect(host.querySelector('.msg-tag').textContent).toBe('unknown')
    app.unmount(); host.remove()
  })
})
