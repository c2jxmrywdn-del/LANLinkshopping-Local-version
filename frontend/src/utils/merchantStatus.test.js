import { describe, it, expect } from 'vitest'
import {
  ORDER_STATUS, PAY_STATUS, REFUND_STATUS, REVIEW_STATUS, WAYBILL_STATUS,
  orderStepIndex, isPaid, fmtMoney
} from './merchantStatus'

describe('merchantStatus 状态字典', () => {
  it('订单状态覆盖后端全部取值且文案与买家侧口径一致', () => {
    expect(Object.keys(ORDER_STATUS).sort()).toEqual(['0', '1', '2', '3', '4'])
    expect(ORDER_STATUS[0].text).toBe('待发货')
    expect(ORDER_STATUS[2].text).toBe('已完成')
    expect(ORDER_STATUS[4].text).toBe('已退款')
  })

  it('支付状态 0/1/2 齐全', () => {
    expect(Object.keys(PAY_STATUS).sort()).toEqual(['0', '1', '2'])
  })

  it('退款状态覆盖后端枚举', () => {
    expect(Object.keys(REFUND_STATUS).sort())
      .toEqual(['applied', 'none', 'processing', 'rejected', 'success'])
  })

  it('商品审核状态覆盖 0/1/2', () => {
    expect(Object.keys(REVIEW_STATUS).sort()).toEqual(['0', '1', '2'])
  })

  it('运单状态与后端状态机取值一致', () => {
    expect(Object.keys(WAYBILL_STATUS).sort()).toEqual([
      'cancelled', 'delivered', 'exception', 'in_transit',
      'lost', 'out_for_delivery', 'pending_ship', 'picked_up', 'returned'
    ])
  })
})

describe('orderStepIndex', () => {
  it('未付款为第 0 步', () => {
    expect(orderStepIndex({ payStatus: 0, orderStatus: 0 })).toBe(0)
  })
  it('已付款待发货为第 1 步', () => {
    expect(orderStepIndex({ payStatus: 1, orderStatus: 0 })).toBe(1)
  })
  it('已发货为第 2 步', () => {
    expect(orderStepIndex({ payStatus: 1, orderStatus: 1 })).toBe(2)
  })
  it('已完成为第 3 步', () => {
    expect(orderStepIndex({ payStatus: 1, orderStatus: 2 })).toBe(3)
  })
  it('空对象兜底', () => {
    expect(orderStepIndex(null)).toBe(0)
  })
})

describe('isPaid', () => {
  it('账期单视为已付款（进入履约）', () => {
    expect(isPaid({ payStatus: 0, payType: 'term' })).toBe(true)
  })
  it('普通单按 payStatus 判断', () => {
    expect(isPaid({ payStatus: 1, payType: 'mock' })).toBe(true)
    expect(isPaid({ payStatus: 0, payType: 'mock' })).toBe(false)
    expect(isPaid(null)).toBe(false)
  })
})

describe('fmtMoney', () => {
  it('千分位 + 两位小数', () => {
    expect(fmtMoney(3600)).toBe('3,600.00')
    expect(fmtMoney('12.5')).toBe('12.50')
  })
  it('空值按 0 处理', () => {
    expect(fmtMoney(null)).toBe('0.00')
    expect(fmtMoney(undefined)).toBe('0.00')
  })
})
