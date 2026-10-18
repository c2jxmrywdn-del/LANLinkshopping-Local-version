import { describe, expect, it } from 'vitest'
import { QR_RENDER_DEFAULTS, buildLoginQrPayload, buildPayQrPayload, randomToken } from './qrCode'

// 说明：二维码的 canvas 渲染（qrcode.toCanvas / toDataURL）依赖真实浏览器 canvas，
// jsdom 未实现 getContext，故此处只覆盖不触碰 DOM 的纯逻辑：
// 随机令牌、payload 组装（随机生成器）与渲染默认参数。

describe('二维码随机生成器（utils/qrCode）', () => {
  it('正常：randomToken 产出指定长度、URL 安全字符，且两次不同', () => {
    const a = randomToken(28)
    const b = randomToken(28)
    expect(a).toHaveLength(28)
    expect(a).toMatch(/^[A-Za-z0-9]+$/)
    expect(a).not.toBe(b)
  })

  it('边界：randomToken 长度参数被规范化（≤0 至少产出 1 位）', () => {
    expect(randomToken(0)).toHaveLength(1)
    expect(randomToken(-5)).toHaveLength(1)
    expect(randomToken(1)).toHaveLength(1)
  })

  it('正常：buildLoginQrPayload 携带 app/一次性令牌/时间戳，且令牌每次不同', () => {
    const p1 = buildLoginQrPayload('LANLinkshopping', 'https://shop.example.com')
    const p2 = buildLoginQrPayload('LANLinkshopping', 'https://shop.example.com')
    expect(p1.startsWith('https://shop.example.com/login?llqr=1')).toBe(true)
    expect(p1).toContain('app=LANLinkshopping')
    const t1 = new URL(p1).searchParams.get('t')
    const t2 = new URL(p2).searchParams.get('t')
    expect(t1).toHaveLength(28)
    expect(t1).not.toBe(t2)
  })

  it('正常：buildLoginQrPayload 的 ts 走注入时间源（可测）', () => {
    const p = buildLoginQrPayload('App', 'https://a.test', () => 1700000000000)
    expect(new URL(p).searchParams.get('ts')).toBe('1700000000000')
  })

  it('正常：buildPayQrPayload 有 codeUrl 时原样返回（微信 Native 优先）', () => {
    const codeUrl = 'weixin://wxpay/bizpayurl?pr=abc123'
    expect(buildPayQrPayload({ codeUrl, orderNo: 'O1', amount: 100 })).toBe(codeUrl)
  })

  it('边界：无 codeUrl（本地未配置渠道）时退化为订单演示码，带随机令牌', () => {
    const a = buildPayQrPayload({ orderNo: 'O1', amount: 199.5 })
    const b = buildPayQrPayload({ orderNo: 'O1', amount: 199.5 })
    expect(a.startsWith('LANLINK://pay?')).toBe(true)
    expect(a).toContain('orderNo=O1')
    expect(a).toContain('amount=199.5')
    expect(a).not.toBe(b)
  })

  it('异常：buildPayQrPayload 空参不崩溃', () => {
    expect(() => buildPayQrPayload()).not.toThrow()
    expect(buildPayQrPayload()).toContain('LANLINK://pay?')
  })

  it('渲染默认参数：品牌墨色前景 + 1 模块静默区', () => {
    expect(QR_RENDER_DEFAULTS.color.dark).toBe('#13233A')
    expect(QR_RENDER_DEFAULTS.color.light).toBe('#FFFFFF')
    expect(QR_RENDER_DEFAULTS.margin).toBe(1)
  })
})
