import { describe, it, expect } from 'vitest'
import {
  MSG_KIND, messageKind, isOrderNo, primaryLink, secondaryLink, cleanContent
} from './messageLink'

const ORDER_NO = '20261107072056914277'

function msg(over = {}) {
  return { type: 'order', title: '订单已完成', content: `订单 ${ORDER_NO} 已确认收货，交易完成。`, relatedNo: ORDER_NO, ...over }
}

describe('messageKind 业务归类', () => {
  it('订单类消息识别为 order', () => {
    expect(messageKind(msg())).toBe(MSG_KIND.ORDER)
  })

  it('退款/售后关键词识别为 refund（含商户初审等中间态）', () => {
    expect(messageKind(msg({ title: '退款成功' }))).toBe(MSG_KIND.REFUND)
    expect(messageKind(msg({ title: '商户退款初审已通过' }))).toBe(MSG_KIND.REFUND)
    expect(messageKind(msg({ title: '退货退款已入库' }))).toBe(MSG_KIND.REFUND)
  })

  it('物流关键词识别为 logistics', () => {
    expect(messageKind(msg({ title: '商家已发货' }))).toBe(MSG_KIND.LOGISTICS)
    expect(messageKind(msg({ title: '运单已签收' }))).toBe(MSG_KIND.LOGISTICS)
  })

  it('非订单类型一律 other（促销/系统/价格不参与订单跳转）', () => {
    // 促销/系统/价格各自归类，走自己的落地页，不再当成"无跳转的 other"
    expect(messageKind({ type: 'promotion', title: '退款活动', relatedNo: ORDER_NO })).toBe(MSG_KIND.PROMOTION)
    expect(messageKind({ type: 'system', title: '发货通知' })).toBe(MSG_KIND.SYSTEM)
    expect(messageKind({ type: 'price', title: '价格预警' })).toBe(MSG_KIND.PRICE)
    expect(messageKind({ type: 'unknown', title: '未知类型' })).toBe(MSG_KIND.OTHER)
    expect(messageKind(null)).toBe(MSG_KIND.OTHER)
  })
})

describe('isOrderNo 订单号校验', () => {
  it('接受真实订单号形态', () => {
    expect(isOrderNo(ORDER_NO)).toBe(true)
    expect(isOrderNo('  ' + ORDER_NO + '  ')).toBe(true)
  })

  it('拒绝空值、过短、含非法字符的值', () => {
    expect(isOrderNo(null)).toBe(false)
    expect(isOrderNo('')).toBe(false)
    expect(isOrderNo('123')).toBe(false)
    expect(isOrderNo('RMA-2026/001')).toBe(false)
    expect(isOrderNo('../etc/passwd')).toBe(false)
  })
})

describe('primaryLink 二级页面', () => {
  it('订单消息指向订单详情', () => {
    expect(primaryLink(msg())).toEqual({ path: `/orders/${ORDER_NO}`, label: '查看订单详情' })
  })

  it('退款/物流同样先给订单详情入口', () => {
    expect(primaryLink(msg({ title: '退款成功' })).path).toBe(`/orders/${ORDER_NO}`)
    expect(primaryLink(msg({ title: '商家已发货' })).path).toBe(`/orders/${ORDER_NO}`)
  })

  it('缺订单号或非订单类型返回 null（不拼坏链）', () => {
    expect(primaryLink(msg({ relatedNo: '' }))).toBeNull()
    expect(primaryLink(msg({ relatedNo: 'abc' }))).toBeNull()
    expect(primaryLink(null)).toBeNull()
  })

  it('订单号做 URL 编码，避免特殊字符注入路径', () => {
    const weird = 'ABCDEFGH'
    expect(primaryLink(msg({ relatedNo: weird })).path).toBe(`/orders/${encodeURIComponent(weird)}`)
  })
})

describe('secondaryLink 三级页面', () => {
  it('退款消息给出退款进度落点', () => {
    expect(secondaryLink(msg({ title: '退款成功' })))
      .toEqual({ path: `/orders/${ORDER_NO}?tab=refund`, label: '查看退款进度' })
  })

  it('物流消息给出物流轨迹落点', () => {
    expect(secondaryLink(msg({ title: '商家已发货' })))
      .toEqual({ path: `/orders/${ORDER_NO}?tab=logistics`, label: '查看物流轨迹' })
  })

  it('普通订单完成消息没有三级入口', () => {
    expect(secondaryLink(msg())).toBeNull()
  })

  it('无法解析订单号时二级三级都为空', () => {
    expect(secondaryLink(msg({ relatedNo: '' }))).toBeNull()
  })
})

describe('cleanContent 正文去噪', () => {
  it('去掉与关联单号重复的订单号，避免正文与 meta 重复', () => {
    expect(cleanContent(msg())).toBe('订单 已确认收货，交易完成。')
  })

  it('没有 relatedNo 时原样返回', () => {
    expect(cleanContent({ content: '系统维护通知' })).toBe('系统维护通知')
  })

  it('空值安全', () => {
    expect(cleanContent(null)).toBe('')
    expect(cleanContent({})).toBe('')
  })
})

describe('非订单通知的二三级落地页', () => {
  const welcome = {
    type: 'system',
    title: '欢迎使用 LANLinkshopping',
    content: '平台已为你开通采购账号，可前往个人中心完善资料。',
    relatedNo: ''
  }

  it('系统「完善资料」引导到账户设置，并给地址管理作为三级入口', () => {
    expect(primaryLink(welcome)).toEqual({ path: '/me/settings', label: '前往账户设置' })
    expect(secondaryLink(welcome)).toEqual({ path: '/me/address', label: '管理收货地址' })
  })

  it('系统安全类通知引导到登录记录', () => {
    const security = { type: 'system', title: '账号安全提醒', content: '检测到异地登录，请检查密码。' }
    expect(primaryLink(security).path).toBe('/me/settings')
    expect(secondaryLink(security)).toEqual({ path: '/me/login-log', label: '查看登录记录' })
  })

  it('普通系统通知回个人中心，无三级入口', () => {
    const plain = { type: 'system', title: '系统维护通知', content: '平台将于今晚维护。' }
    expect(primaryLink(plain)).toEqual({ path: '/me', label: '进入个人中心' })
    expect(secondaryLink(plain)).toBeNull()
  })

  it('促销通知：活动中心（二级）→ 领券中心（三级）', () => {
    const promo = { type: 'promotion', title: '双十一大促开启', content: '全场满减，先领券再下单。' }
    expect(primaryLink(promo)).toEqual({ path: '/activity', label: '查看活动中心' })
    expect(secondaryLink(promo)).toEqual({ path: '/coupons', label: '去领券中心' })
  })

  it('价格通知：价格情报（二级）→ 积分明细（三级）', () => {
    const price = { type: 'price', title: '螺纹钢价格预警', content: '你关注的商品降价 3%。' }
    expect(primaryLink(price)).toEqual({ path: '/price-monitor', label: '查看价格情报' })
    expect(secondaryLink(price)).toEqual({ path: '/membership/points', label: '查看积分明细' })
  })

  it('非订单通知不需要 relatedNo 也能给出入口', () => {
    expect(primaryLink({ type: 'system', title: 'x', content: 'y', relatedNo: '' })).not.toBeNull()
  })
})
