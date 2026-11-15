import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createApp, h, ref, nextTick } from 'vue'

/**
 * Express Checkout（Apple Pay / Google Pay）挂载契约。
 *
 * 缺陷形态（修复前）：钱包选项被伪装成卡表单，页面上还写着"由 Express Checkout 引擎驱动、
 * 支持 Touch ID / Face ID"，但全仓没有任何钱包实现，属于虚假功能承诺。
 *
 * 修复后：只在 Stripe 报告该设备有可用钱包时才渲染按钮；没有 clientSecret 时不挂载；
 * 不支持时给出"请改用银行卡"的可行动说明。
 *
 * 测试方式与 StripeCardElement.test.js 一致：用 createApp 直接挂载，不依赖
 * @vue/test-utils（该依赖不在 package.json 里）。
 */
const { getStripeElementsMock } = vi.hoisted(() => ({ getStripeElementsMock: vi.fn() }))
vi.mock('../../utils/stripeLoader', () => ({ getStripeElements: getStripeElementsMock }))

import StripeExpressCheckout from './StripeExpressCheckout.vue'

/** 伪 Express Element：记录 mount/destroy/on，并暴露事件处理器供用例触发 */
function makeExpress() {
  const handlers = {}
  const express = {
    mount: vi.fn(),
    destroy: vi.fn(),
    on: vi.fn((evt, cb) => { handlers[evt] = cb }),
    __handlers: handlers
  }
  return express
}

let app
let host
let elementsCreate
let stripeConfirm

beforeEach(() => {
  host = document.createElement('div')
  document.body.appendChild(host)
  elementsCreate = vi.fn()
  stripeConfirm = vi.fn()
  getStripeElementsMock.mockResolvedValue({
    stripe: { confirmPayment: stripeConfirm },
    elements: { create: elementsCreate }
  })
})

afterEach(() => {
  if (app) app.unmount()
  app = null
  if (host && host.parentNode) host.parentNode.removeChild(host)
  vi.clearAllMocks()
})

function mountExpress(props, on = {}) {
  app = createApp({
    render: () => h(StripeExpressCheckout, { ...props, ...on })
  })
  app.mount(host)
  return app
}

describe('StripeExpressCheckout', () => {
  it('没有 clientSecret 时不创建 Express 元素（金额未知不能挂钱包按钮）', async () => {
    mountExpress({ publishableKey: 'pk_test_x', clientSecret: '', channel: 'apple_pay' })
    await nextTick()
    await Promise.resolve()
    expect(elementsCreate).not.toHaveBeenCalled()
  })

  it('有 clientSecret 时按渠道限定 paymentMethodTypes', async () => {
    const express = makeExpress()
    elementsCreate.mockReturnValue(express)

    mountExpress({ publishableKey: 'pk_test_x', clientSecret: 'pi_x_secret_y', channel: 'apple_pay' })
    await nextTick()
    await Promise.resolve()
    await nextTick()

    expect(elementsCreate).toHaveBeenCalledWith('expressCheckout', expect.objectContaining({
      paymentMethodTypes: ['apple_pay']
    }))
  })

  it('设备无可用钱包时触发 unsupported，并提示改用银行卡', async () => {
    const express = makeExpress()
    elementsCreate.mockReturnValue(express)
    const unsupported = vi.fn()

    mountExpress(
      { publishableKey: 'pk_test_x', clientSecret: 'pi_x_secret_y', channel: 'google_pay' },
      { onUnsupported: unsupported }
    )
    await nextTick()
    await Promise.resolve()

    // 模拟 Stripe 报告"该设备没有可用钱包"
    express.__handlers.ready({ availablePaymentMethods: null })
    await nextTick()

    expect(unsupported).toHaveBeenCalledWith('google_pay')
    expect(host.textContent).toContain('Google Pay 当前不可用')
  })

  it('钱包可用时触发 ready', async () => {
    const express = makeExpress()
    elementsCreate.mockReturnValue(express)
    const ready = vi.fn()

    mountExpress(
      { publishableKey: 'pk_test_x', clientSecret: 'pi_x_secret_y', channel: 'apple_pay' },
      { onReady: ready }
    )
    await nextTick()
    await Promise.resolve()

    express.__handlers.ready({ availablePaymentMethods: { applePay: true } })
    await nextTick()

    expect(ready).toHaveBeenCalledWith('apple_pay')
  })

  it('confirm 时用 clientSecret 调 confirmPayment，成功后回传状态', async () => {
    const express = makeExpress()
    elementsCreate.mockReturnValue(express)
    stripeConfirm.mockResolvedValue({ error: null, paymentIntent: { status: 'succeeded' } })
    const succeeded = vi.fn()

    mountExpress(
      { publishableKey: 'pk_test_x', clientSecret: 'pi_x_secret_y', channel: 'apple_pay' },
      { onSucceeded: succeeded }
    )
    await nextTick()
    await Promise.resolve()

    await express.__handlers.confirm()
    await nextTick()

    expect(stripeConfirm).toHaveBeenCalledWith(expect.objectContaining({
      clientSecret: 'pi_x_secret_y',
      redirect: 'if_required'
    }))
    expect(succeeded).toHaveBeenCalledWith('succeeded')
  })

  it('用户取消钱包弹窗不弹错误（failed 传空串）', async () => {
    const express = makeExpress()
    elementsCreate.mockReturnValue(express)
    const failed = vi.fn()

    mountExpress(
      { publishableKey: 'pk_test_x', clientSecret: 'pi_x_secret_y', channel: 'apple_pay' },
      { onFailed: failed }
    )
    await nextTick()
    await Promise.resolve()

    express.__handlers.cancel()
    await nextTick()

    expect(failed).toHaveBeenCalledWith('')
  })
})
