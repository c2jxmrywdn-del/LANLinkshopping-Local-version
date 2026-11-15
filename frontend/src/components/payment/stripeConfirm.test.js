import { describe, it, expect, vi, beforeEach } from 'vitest'

const { loadStripeMock } = vi.hoisted(() => ({ loadStripeMock: vi.fn() }))
vi.mock('@stripe/stripe-js', () => ({ loadStripe: loadStripeMock }))

import {
  validateStripePayInfo,
  confirmStripeCardPayment,
  describeStripePaymentResult
} from './stripeConfirm'
import { __resetStripeLoaderForTests } from '../../utils/stripeLoader'

/** 伪卡控件（StripeCardElement 暴露 ensureCardElement） */
function cardComponent() {
  return {
    ensureCardElement: vi.fn(async () => ({ id: 'card-element' }))
  }
}

function stripeWith(result) {
  return {
    confirmCardPayment: vi.fn(async () => result)
  }
}

describe('stripeConfirm（商品订单 Stripe 真实确认链共享封装）', () => {
  beforeEach(() => {
    __resetStripeLoaderForTests()
    loadStripeMock.mockReset()
    vi.stubEnv('VITE_STRIPE_PUBLISHABLE_KEY', '')
  })

  it('payInfo 缺 clientSecret / publishableKey：抛可行动配置错误（不发起支付）', () => {
    expect(() => validateStripePayInfo({ publishableKey: 'pk_test' }))
      .toThrow(/STRIPE_SECRET_KEY/)
    expect(() => validateStripePayInfo({ clientSecret: 'pi_secret' }))
      .toThrow(/VITE_STRIPE_PUBLISHABLE_KEY/)
    expect(validateStripePayInfo({ clientSecret: 'pi_secret', publishableKey: 'pk_test' }))
      .toEqual({ clientSecret: 'pi_secret', publishableKey: 'pk_test' })
  })

  it('确认成功：透传 clientSecret 与卡控件，返回 succeeded 状态', async () => {
    const stripe = stripeWith({ error: undefined, paymentIntent: { id: 'pi_1', status: 'succeeded' } })
    loadStripeMock.mockResolvedValue(stripe)
    const comp = cardComponent()

    const res = await confirmStripeCardPayment(comp, {
      clientSecret: 'pi_1_secret', publishableKey: 'pk_test_123'
    }, { name: '张三', phone: '13800138000' })

    expect(loadStripeMock).toHaveBeenCalledWith('pk_test_123')
    expect(comp.ensureCardElement).toHaveBeenCalledTimes(1)
    expect(stripe.confirmCardPayment).toHaveBeenCalledWith('pi_1_secret', {
      payment_method: {
        card: { id: 'card-element' },
        billing_details: { name: '张三', phone: '13800138000', email: undefined }
      }
    })
    expect(res.status).toBe('succeeded')
  })

  it('卡被拒（Stripe 返回 error）：抛出带 __llStripe 标记的可展示错误，不返回成功', async () => {
    const stripe = stripeWith({ error: { type: 'card_error', code: 'card_declined', message: 'Your card was declined.' } })
    loadStripeMock.mockResolvedValue(stripe)

    let caught = null
    try {
      await confirmStripeCardPayment(cardComponent(), {
        clientSecret: 'pi_1_secret', publishableKey: 'pk_test_123'
      })
    } catch (e) {
      caught = e
    }
    expect(caught).toBeTruthy()
    expect(caught.message).toBe('Your card was declined.')
    expect(caught.__llStripe).toBe(true)
  })

  it('卡控件未就绪/未挂载：抛可行动错误（避免 undefined.ensureCardElement）', async () => {
    await expect(confirmStripeCardPayment(null, { clientSecret: 'x', publishableKey: 'pk' }))
      .rejects.toThrow(/卡控件未就绪/)
  })

  it('结果判定：仅 succeeded 算成功；processing 提示处理中；其余不提示成功', () => {
    expect(describeStripePaymentResult('succeeded')).toMatchObject({ ok: true, processing: false })
    const processing = describeStripePaymentResult('processing')
    expect(processing.ok).toBe(false)
    expect(processing.processing).toBe(true)
    expect(processing.message).toContain('正在处理')
    const failed = describeStripePaymentResult('requires_action')
    expect(failed.ok).toBe(false)
    expect(failed.message).toContain('支付未完成')
  })
})
