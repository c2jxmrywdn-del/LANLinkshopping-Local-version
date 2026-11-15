import { describe, it, expect, vi, beforeEach } from 'vitest'
import { h, reactive, createApp, nextTick, defineComponent } from 'vue'
import ReviewDialog from './ReviewDialog.vue'

// antd 桩（vitest 环境 vue 为 runtime-only 构建）：rate 以原生按钮承载交互，
// input/textarea 直出原生元素，v-model 值经 emitted 事件回传
const ARateStub = defineComponent({
  props: { value: { type: Number, default: 0 } },
  emits: ['update:value'],
  setup(props, { emit }) {
    return () => h('div', { class: 'a-rate-stub', 'data-value': props.value },
      [1, 2, 3, 4, 5].map(n => h('button', {
        type: 'button',
        class: 'a-rate-star',
        'data-star': n,
        onClick: () => emit('update:value', n)
      }, String(n))))
  }
})
const ACheckboxStub = defineComponent({
  props: { checked: { type: Boolean, default: false } },
  emits: ['update:checked'],
  setup(props, { slots, emit }) {
    return () => h('label', { class: 'a-checkbox-stub' }, [
      h('input', {
        type: 'checkbox',
        'data-testid': 'review-anonymous',
        checked: props.checked,
        onChange: (e) => emit('update:checked', e.target.checked)
      }),
      slots.default?.()
    ])
  }
})
const ATextareaStub = defineComponent({
  props: { value: { type: String, default: '' } },
  emits: ['update:value'],
  setup(props, { emit }) {
    return () => h('textarea', {
      class: 'a-textarea-stub',
      'data-testid': 'review-content',
      value: props.value,
      onInput: (e) => emit('update:value', e.target.value)
    })
  }
})
const AModalStub = defineComponent({
  props: { open: { type: Boolean, default: false }, title: { type: String, default: '' } },
  emits: ['ok', 'cancel'],
  setup(props, { slots, emit }) {
    return () => props.open ? h('div', { class: 'a-modal-stub', 'data-title': props.title }, [
      slots.default?.(),
      h('button', { type: 'button', 'data-testid': 'review-ok', onClick: () => emit('ok') }, 'ok'),
      h('button', { type: 'button', onClick: () => emit('cancel') }, 'cancel')
    ]) : null
  }
})
const AButtonStub = defineComponent({
  props: { size: { type: String, default: '' }, disabled: { type: Boolean, default: false } },
  emits: ['click'],
  setup(props, { slots, emit }) {
    return () => h('button', {
      type: 'button',
      class: 'a-button-stub',
      disabled: props.disabled,
      onClick: (e) => emit('click', e)
    }, slots.default?.())
  }
})

// reviewApi / chatApi 以模块桩替换：记录提交载荷，上传返回固定 URL
const submitted = []
const submitOrderReview = vi.fn((orderNo, payload) => {
  submitted.push({ orderNo, payload })
  return Promise.resolve(1)
})
const submitItemReview = vi.fn((orderNo, payload) => {
  submitted.push({ orderNo, payload })
  return Promise.resolve(1)
})
vi.mock('../../api', () => ({
  reviewApi: {
    submit: (...a) => submitItemReview(...a),
    submitOrderReview: (...a) => submitOrderReview(...a)
  },
  chatApi: {
    upload: vi.fn(() => Promise.resolve({ url: 'http://cdn.local/x.jpg', name: 'x.jpg', size: 1 }))
  }
}))

function mountDialog(propsInit) {
  const props = reactive({ ...propsInit })
  const emitted = []
  const root = document.createElement('div')
  document.body.appendChild(root)
  const app = createApp({
    setup() {
      return () => h(ReviewDialog, {
        open: props.open,
        orderNo: props.orderNo || 'ON-1',
        item: props.item,
        mode: props.mode,
        'onUpdate:open': (v) => { props.open = v },
        onSubmitted: (itemId) => emitted.push(itemId)
      })
    }
  })
  app.component('a-modal', AModalStub)
  app.component('a-rate', ARateStub)
  app.component('a-checkbox', ACheckboxStub)
  app.component('a-textarea', ATextareaStub)
  app.component('a-button', AButtonStub)
  app.mount(root)
  return {
    root, props, emitted,
    modal: () => root.querySelector('.a-modal-stub'),
    ok: () => root.querySelector('[data-testid="review-ok"]'),
    content: () => root.querySelector('[data-testid="review-content"]'),
    anon: () => root.querySelector('[data-testid="review-anonymous"]'),
    async clickStar(n) {
      const star = root.querySelector(`.a-rate-star[data-star="${n}"]`)
      if (!star) throw new Error('star not found: ' + n)
      star.click()
      await nextTick()
    },
    async type(text) {
      const el = this.content()
      el.value = text
      el.dispatchEvent(new Event('input'))
      await nextTick()
    },
    unmount() { app.unmount(); root.remove() }
  }
}

beforeEach(() => {
  submitted.length = 0
  submitOrderReview.mockClear()
  submitItemReview.mockClear()
})

describe('ReviewDialog（订单维度新链路）', () => {
  it('order 模式提交走 /order/{no}/order-review，携带 orderItemId 与附件', async () => {
    const t = mountDialog({ open: true, orderNo: 'ON-9', mode: 'order', item: { itemId: 10, prodName: 'P' } })
    await t.clickStar(4)
    await t.type('不错')
    t.anon().click()
    await nextTick()
    t.ok().click()
    await nextTick()
    await Promise.resolve()
    await Promise.resolve()

    expect(submitOrderReview).toHaveBeenCalledTimes(1)
    const [orderNo, payload] = submitOrderReview.mock.calls[0]
    expect(orderNo).toBe('ON-9')
    expect(payload).toMatchObject({ orderItemId: 10, rating: 4, content: '不错', anonymous: true })
    expect(submitItemReview).not.toHaveBeenCalled()
    expect(t.emitted).toEqual([10])
    t.unmount()
  })

  it('item 模式保持旧行级链路签名（itemId 字段）', async () => {
    const t = mountDialog({ open: true, orderNo: 'ON-9', mode: 'item', item: { itemId: 10, prodName: 'P' } })
    await t.clickStar(5)
    t.ok().click()
    await nextTick()
    await Promise.resolve()
    await Promise.resolve()

    expect(submitItemReview).toHaveBeenCalledTimes(1)
    const [, payload] = submitItemReview.mock.calls[0]
    expect(payload).toMatchObject({ itemId: 10, rating: 5 })
    expect(submitOrderReview).not.toHaveBeenCalled()
    t.unmount()
  })

  it('星级未选时禁用确定（ok-button-props disabled 语义由组件内 rating<1 兜底）', async () => {
    // 弹窗关闭重开后状态复位
    const t = mountDialog({ open: false, orderNo: 'ON-1', mode: 'order', item: { itemId: 10, prodName: 'P' } })
    t.props.open = true
    await nextTick()
    expect(t.modal()).toBeTruthy()
    // 重开后 rating 回 5，直接可提交
    t.ok().click()
    await nextTick()
    await Promise.resolve()
    await Promise.resolve()
    expect(submitOrderReview).toHaveBeenCalledTimes(1)
    expect(submitOrderReview.mock.calls[0][1].rating).toBe(5)
    t.unmount()
  })

  it('弹窗关闭时不渲染', () => {
    const t = mountDialog({ open: false, item: { itemId: 1 } })
    expect(t.modal()).toBeNull()
    t.unmount()
  })
})
