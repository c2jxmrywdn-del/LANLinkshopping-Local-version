import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// vi.mock 工厂被提升到 import 之前，用 vi.hoisted 承载 mock 引用，避免 TDZ。
const { loadStripeMock } = vi.hoisted(() => ({ loadStripeMock: vi.fn() }))
vi.mock('@stripe/stripe-js', () => ({ loadStripe: loadStripeMock }))

/**
 * 每个用例重新 import 模块：loader 有模块级单例缓存，不复位会串用例。
 */
async function freshLoader() {
  vi.resetModules()
  return import('./stripeLoader.js')
}

describe('stripeLoader（Stripe.js 单例 + key 校验）', () => {
  beforeEach(() => {
    loadStripeMock.mockReset()
    vi.stubEnv('VITE_STRIPE_PUBLISHABLE_KEY', '')
  })

  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('publishable key 缺失：抛 StripeConfigError（可行动文案），且不加载 Stripe.js', async () => {
    const mod = await freshLoader()
    let caught = null
    try {
      mod.loadStripeOnce()
    } catch (e) {
      caught = e
    }
    expect(caught).toBeTruthy()
    expect(caught.name).toBe('StripeConfigError')
    // 文案必须给出"填哪个 env"，而不是笼统的支付失败
    expect(caught.message).toContain('VITE_STRIPE_PUBLISHABLE_KEY')
    expect(caught.message).toContain('STRIPE_PUBLISHABLE_KEY')
    expect(loadStripeMock).not.toHaveBeenCalled()
  })

  it('显式 key（后端 payInfo.publishableKey）优先于构建期 env', async () => {
    vi.stubEnv('VITE_STRIPE_PUBLISHABLE_KEY', 'pk_env_should_not_win')
    loadStripeMock.mockResolvedValue({ id: 'stripe-from-explicit' })
    const mod = await freshLoader()

    const stripe = await mod.loadStripeOnce('pk_explicit_backend')

    expect(loadStripeMock).toHaveBeenCalledTimes(1)
    expect(loadStripeMock).toHaveBeenCalledWith('pk_explicit_backend')
    expect(stripe).toEqual({ id: 'stripe-from-explicit' })
  })

  it('显式 key 缺失时回退到构建期 env（frontend/.env 可选兜底）', async () => {
    vi.stubEnv('VITE_STRIPE_PUBLISHABLE_KEY', 'pk_env_fallback')
    loadStripeMock.mockResolvedValue({ id: 'stripe-from-env' })
    const mod = await freshLoader()

    const stripe = await mod.loadStripeOnce()

    expect(loadStripeMock).toHaveBeenCalledWith('pk_env_fallback')
    expect(stripe).toEqual({ id: 'stripe-from-env' })
  })

  it('同一 key 单例缓存：并发调用只加载一次且返回同一个 Promise', async () => {
    loadStripeMock.mockResolvedValue({ id: 'stripe-singleton' })
    const mod = await freshLoader()

    const p1 = mod.loadStripeOnce('pk_same_key')
    const p2 = mod.loadStripeOnce('pk_same_key')

    expect(p1).toBe(p2) // 同一 Promise，Stripe.js 只注入一次
    expect(loadStripeMock).toHaveBeenCalledTimes(1)
    await expect(p1).resolves.toEqual({ id: 'stripe-singleton' })
    expect(mod.__stripeLoaderStateForTests()).toEqual({ key: 'pk_same_key', hasPromise: true })
  })

  it('key 变化时重新加载（测试/沙箱切换）', async () => {
    loadStripeMock.mockResolvedValue({ id: 'stripe' })
    const mod = await freshLoader()

    await mod.loadStripeOnce('pk_first')
    await mod.loadStripeOnce('pk_second')

    expect(loadStripeMock).toHaveBeenCalledTimes(2)
    expect(loadStripeMock).toHaveBeenNthCalledWith(1, 'pk_first')
    expect(loadStripeMock).toHaveBeenNthCalledWith(2, 'pk_second')
  })

  it('加载失败不缓存：清空单例，允许用户重试', async () => {
    loadStripeMock
      .mockRejectedValueOnce(new Error('Stripe.js 资源被网络策略拦截'))
      .mockResolvedValueOnce({ id: 'stripe-on-retry' })
    const mod = await freshLoader()

    await expect(mod.loadStripeOnce('pk_test')).rejects.toThrow('Stripe.js 资源被网络策略拦截')
    expect(mod.__stripeLoaderStateForTests()).toEqual({ key: '', hasPromise: false })

    await expect(mod.loadStripeOnce('pk_test')).resolves.toEqual({ id: 'stripe-on-retry' })
    expect(loadStripeMock).toHaveBeenCalledTimes(2)
  })

  it('getStripeElements：同 key 复用同一 Stripe 实例与 Elements 分组（弹窗反复开关不重建）', async () => {
    const elementsGroup = { create: vi.fn() }
    const stripe = { id: 'stripe-with-elements', elements: vi.fn(() => elementsGroup) }
    loadStripeMock.mockResolvedValue(stripe)
    const mod = await freshLoader()

    const first = await mod.getStripeElements('pk_shared_elements')
    const second = await mod.getStripeElements('pk_shared_elements')

    expect(first.stripe).toBe(second.stripe)
    expect(first.elements).toBe(second.elements)
    expect(stripe.elements).toHaveBeenCalledTimes(1)
    expect(loadStripeMock).toHaveBeenCalledTimes(1)
  })

  it('resolvePublishableKey 对空白字符串按缺失处理', async () => {
    const mod = await freshLoader()
    expect(() => mod.resolvePublishableKey('   ')).toThrow(/公钥未配置/)
    expect(mod.resolvePublishableKey('  pk_trim  ')).toBe('pk_trim')
  })
})
