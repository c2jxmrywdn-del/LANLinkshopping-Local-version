import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createApp, h, ref, nextTick } from 'vue'

// @stripe/stripe-js 在网络/浏览器环境会注入外部脚本：单测里整体打桩，
// 只验证"我们如何使用它"（Elements.create('card') 挂载、createPaymentMethod 透传）。
const { loadStripeMock } = vi.hoisted(() => ({ loadStripeMock: vi.fn() }))
vi.mock('@stripe/stripe-js', () => ({ loadStripe: loadStripeMock }))

import StripeCardElement from './StripeCardElement.vue'
import { __resetStripeLoaderForTests } from '../../utils/stripeLoader'

/** 伪 Card Element：记录 mount/unmount/on/update 调用 */
function makeCardElement() {
  const card = {
    mount: vi.fn((el) => {
      el.dataset.stripeCardMounted = '1'
    }),
    unmount: vi.fn(),
    on: vi.fn((event, cb) => {
      if (event === 'change') card.__onChange = cb
    }),
    update: vi.fn()
  }
  return card
}

function makeStripe(card) {
  const create = vi.fn(() => card)
  return {
    elements: vi.fn(() => ({ create })),
    createPaymentMethod: vi.fn(),
    confirmCardPayment: vi.fn(),
    __create: create
  }
}

/** 与仓库既有用例一致：createApp + h 挂真组件，不引入 @vue/test-utils */
function mountCard(props = { publishableKey: 'pk_test_component' }) {
  const holder = ref(null)
  const root = document.createElement('div')
  document.body.appendChild(root)
  const app = createApp({ render: () => h(StripeCardElement, { ...props, ref: holder }) })
  app.mount(root)
  return {
    root,
    holder,
    cleanup() {
      app.unmount()
      root.remove()
    }
  }
}

/** 等 onMounted 里的异步初始化（loadStripe → elements → mount）落定 */
async function settle() {
  for (let i = 0; i < 3; i++) {
    await nextTick()
    await Promise.resolve()
  }
}

describe('StripeCardElement（Stripe Elements 真实卡控件）', () => {
  beforeEach(() => {
    __resetStripeLoaderForTests()
    loadStripeMock.mockReset()
    vi.stubEnv('VITE_STRIPE_PUBLISHABLE_KEY', '')
  })

  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('不再自绘卡号/CVC：页面零原生 input，卡控件由 Stripe Element mount 到容器', async () => {
    const card = makeCardElement()
    loadStripeMock.mockResolvedValue(makeStripe(card))
    const m = mountCard()

    await settle()

    // 关键安全断言：本地不再有卡号/有效期/CVC 输入框（旧实现是 4 个 a-input）
    expect(m.root.querySelectorAll('input').length).toBe(0)
    expect(m.root.querySelectorAll('textarea').length).toBe(0)
    // 卡控件真实挂载：mount 只调用一次，且指向组件内容器
    expect(card.mount).toHaveBeenCalledTimes(1)
    const mountEl = m.root.querySelector('.stripe-card-mount')
    expect(mountEl).toBeTruthy()
    expect(mountEl.dataset.stripeCardMounted).toBe('1')
    expect(m.root.textContent).toContain('Stripe 安全托管')
    m.cleanup()
  })

  it('createPaymentMethod：透传 card 与 billing_details，返回 Stripe 结果', async () => {
    const card = makeCardElement()
    const stripe = makeStripe(card)
    stripe.createPaymentMethod.mockResolvedValue({ paymentMethod: { id: 'pm_123' } })
    loadStripeMock.mockResolvedValue(stripe)
    const m = mountCard()
    await settle()

    const result = await m.holder.value.createPaymentMethod({ name: '张三', phone: '13800138000' })

    expect(result.paymentMethod.id).toBe('pm_123')
    expect(stripe.createPaymentMethod).toHaveBeenCalledTimes(1)
    expect(stripe.createPaymentMethod).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'card',
        card,
        billing_details: { name: '张三', phone: '13800138000' }
      })
    )
    m.cleanup()
  })

  it('createPaymentMethod 失败分支：卡被拒时原样返回 error，不抛异常掩盖真实原因', async () => {
    const card = makeCardElement()
    const stripe = makeStripe(card)
    stripe.createPaymentMethod.mockResolvedValue({
      error: { type: 'card_error', code: 'card_declined', message: 'Your card was declined.' }
    })
    loadStripeMock.mockResolvedValue(stripe)
    const m = mountCard()
    await settle()

    const result = await m.holder.value.createPaymentMethod({ name: '李四' })

    expect(result.error.code).toBe('card_declined')
    expect(result.error.message).toBe('Your card was declined.')
    m.cleanup()
  })

  it('卸载时调用 card.unmount（避免 iframe/监听器泄漏）', async () => {
    const card = makeCardElement()
    loadStripeMock.mockResolvedValue(makeStripe(card))
    const m = mountCard()
    await settle()

    m.cleanup()

    expect(card.unmount).toHaveBeenCalledTimes(1)
  })

  it('key 缺失时不自动初始化：显示引导文案，ensureCardElement 抛可行动配置错误', async () => {
    const m = mountCard({ publishableKey: '' })
    await settle()

    expect(loadStripeMock).not.toHaveBeenCalled()
    expect(m.root.textContent).toContain('点击「立即支付」')
    await expect(m.holder.value.ensureCardElement()).rejects.toThrow(/公钥未配置/)
    m.cleanup()
  })

  it('Stripe.js 加载失败：展示失败原因并允许重试（错误不缓存）', async () => {
    loadStripeMock
      .mockRejectedValueOnce(new Error('Stripe.js 资源被网络策略拦截'))
      .mockResolvedValueOnce(makeStripe(makeCardElement()))
    const m = mountCard()
    await settle()

    expect(m.root.textContent).toContain('Stripe.js 资源被网络策略拦截')
    // 重试：同一实例再次 ensureCardElement 会重新 loadStripe（loader 失败不缓存）
    const card = await m.holder.value.ensureCardElement()
    expect(card).toBeTruthy()
    expect(loadStripeMock).toHaveBeenCalledTimes(2)
    m.cleanup()
  })

  it('卡控件 change 事件透传 complete/brand，供父组件置灰「确认支付」', async () => {
    const card = makeCardElement()
    loadStripeMock.mockResolvedValue(makeStripe(card))
    const changes = []
    const holder = ref(null)
    const root = document.createElement('div')
    document.body.appendChild(root)
    const app = createApp({
      render: () => h(StripeCardElement, { publishableKey: 'pk_test_component', ref: holder, onChange: (e) => changes.push(e) })
    })
    app.mount(root)
    await settle()

    card.__onChange({ complete: true, brand: 'visa', error: undefined })

    expect(changes.at(-1)).toEqual({ complete: true, brand: 'visa', error: null })
    app.unmount()
    root.remove()
  })
})
