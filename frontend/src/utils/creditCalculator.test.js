import { describe, expect, it } from 'vitest'
import {
  calculateCreditTrial,
  clampTermDays,
  DEFAULT_DAILY_RATE,
  DEFAULT_GRACE_DAYS,
  roundCurrency
} from './creditCalculator'

describe('creditCalculator 核心算力单元测试', () => {
  it('clampTermDays 严格将天数约束在 7 ~ 15 天之内', () => {
    expect(clampTermDays(1)).toBe(7)
    expect(clampTermDays(6)).toBe(7)
    expect(clampTermDays(7)).toBe(7)
    expect(clampTermDays(10)).toBe(10)
    expect(clampTermDays(15)).toBe(15)
    expect(clampTermDays(30)).toBe(15)
    expect(clampTermDays(null)).toBe(7)
    expect(clampTermDays('12')).toBe(12)
  })

  it('roundCurrency 正确精确保留两位小数并处理浮点误差', () => {
    expect(roundCurrency(10.005)).toBe(10.01)
    expect(roundCurrency(10.004)).toBe(10)
    expect(roundCurrency(0)).toBe(0)
    expect(roundCurrency(NaN)).toBe(0)
  })

  it('calculateCreditTrial 基础计算：50000 元，7 天，3 天免息期', () => {
    // 计息天数 = 7 - 3 = 4 天
    // normalInterest = 50000 * 0.0003 * 4 = 60.00
    // penaltyInterest = 0
    // totalRepayment = 50060.00
    // dailyCost = 50000 * 0.0003 = 15.00
    // apr = 0.0003 * 360 = 0.108 (10.80%)
    const res = calculateCreditTrial({
      amount: 50000,
      termDays: 7,
      dailyRate: DEFAULT_DAILY_RATE,
      graceDays: DEFAULT_GRACE_DAYS,
      overdueDays: 0
    })

    expect(res.principal).toBe(50000)
    expect(res.termDays).toBe(7)
    expect(res.interestBearingDays).toBe(4)
    expect(res.normalInterest).toBe(60)
    expect(res.penaltyInterest).toBe(0)
    expect(res.totalInterest).toBe(60)
    expect(res.totalRepayment).toBe(50060)
    expect(res.dailyCost).toBe(15)
    expect(res.aprPercent).toBe('10.80%')
  })

  it('calculateCreditTrial 包含逾期罚息模拟（1.5 倍基准日息）', () => {
    // 100000 元，10 天，3 天免息期，逾期 5 天
    // normalInterest = 100000 * 0.0003 * (10 - 3) = 210.00
    // penaltyRate = 0.0003 * 1.5 = 0.00045
    // penaltyInterest = 100000 * 0.00045 * 5 = 225.00
    // totalInterest = 210 + 225 = 435.00
    // totalRepayment = 100435.00
    const res = calculateCreditTrial({
      amount: 100000,
      termDays: 10,
      dailyRate: 0.0003,
      graceDays: 3,
      overdueDays: 5
    })

    expect(res.interestBearingDays).toBe(7)
    expect(res.normalInterest).toBe(210)
    expect(res.penaltyInterest).toBe(225)
    expect(res.totalInterest).toBe(435)
    expect(res.totalRepayment).toBe(100435)
  })

  it('calculateCreditTrial 正确生成 7/10/15 天标准三档位对比矩阵', () => {
    const res = calculateCreditTrial({
      amount: 50000,
      termDays: 7
    })

    expect(res.tierComparisons).toHaveLength(3)
    const [tier7, tier10, tier15] = res.tierComparisons

    expect(tier7.termDays).toBe(7)
    expect(tier7.normalInterest).toBe(60) // (7 - 3) * 15 = 60

    expect(tier10.termDays).toBe(10)
    expect(tier10.normalInterest).toBe(105) // (10 - 3) * 15 = 105

    expect(tier15.termDays).toBe(15)
    expect(tier15.normalInterest).toBe(180) // (15 - 3) * 15 = 180
  })

  it('边界异常值防护：0金额或负金额处理', () => {
    const res = calculateCreditTrial({
      amount: -100,
      termDays: 3, // 会自动 clamp 到 7
      overdueDays: -5 // 会取 0
    })

    expect(res.principal).toBe(0)
    expect(res.termDays).toBe(7)
    expect(res.totalRepayment).toBe(0)
    expect(res.normalInterest).toBe(0)
  })
})
