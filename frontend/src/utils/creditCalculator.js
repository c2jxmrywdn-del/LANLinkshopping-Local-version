/**
 * 工业互联网平台企业账期利率与还款试算引擎
 *
 * 严格对齐后端 CreditTermService.calculateInterestTrial 计算口径与金融监管红线：
 * 1. 账期严格限定在 7 ~ 15 天（敏捷短融 / 周转备货 / 标准赊销）；
 * 2. 默认执行 3 天免息宽限期，计息天数 = max(0, 账期 - 免息期)；
 * 3. 罚息利率按执行基准日利率的 1.5 倍计算；
 * 4. 金额计算严格四舍五入保留 2 位小数，单利年化 APR = 日息 * 360。
 */

export const MIN_TERM_DAYS = 7
export const MAX_TERM_DAYS = 15
export const DEFAULT_DAILY_RATE = 0.0003 // 0.03%/天 -> APR 10.80%
export const DEFAULT_GRACE_DAYS = 3
export const STANDARD_TIERS = [7, 10, 15]

/**
 * 校验并规范化账期天数（7 ~ 15 天）
 * @param {number} days
 * @returns {number}
 */
export function clampTermDays(days) {
  const d = Math.round(Number(days) || MIN_TERM_DAYS)
  return Math.max(MIN_TERM_DAYS, Math.min(MAX_TERM_DAYS, d))
}

/**
 * 精确金额舍入（保留两位小数）
 * @param {number} val
 * @returns {number}
 */
export function roundCurrency(val) {
  if (typeof val !== 'number' || isNaN(val)) return 0
  return Number(Math.round(val * 100 + Number.EPSILON) / 100)
}

/**
 * 计算账期还款与利息试算核心函数
 * @param {Object} params
 * @param {number} params.amount 融资本金
 * @param {number} [params.termDays=7] 账期期限（7 ~ 15 天）
 * @param {number} [params.dailyRate=0.0003] 执行基准日利率（0 ~ 0.001）
 * @param {number} [params.graceDays=3] 免息宽限期天数（>= 0）
 * @param {number} [params.overdueDays=0] 模拟逾期天数（>= 0）
 * @returns {Object} 试算结果明细与多档位对比
 */
export function calculateCreditTrial({
  amount = 0,
  termDays = MIN_TERM_DAYS,
  dailyRate = DEFAULT_DAILY_RATE,
  graceDays = DEFAULT_GRACE_DAYS,
  overdueDays = 0
} = {}) {
  const principal = Math.max(0, Number(amount) || 0)
  const days = clampTermDays(termDays)
  const rate = Math.max(0, Math.min(0.001, Number(dailyRate) || DEFAULT_DAILY_RATE))
  const grace = Math.max(0, Math.round(Number(graceDays) || 0))
  const overdue = Math.max(0, Math.round(Number(overdueDays) || 0))

  // 1. 正常计息天数（扣除免息宽限期）
  const interestBearingDays = Math.max(0, days - grace)
  const normalInterest = roundCurrency(principal * rate * interestBearingDays)

  // 2. 逾期罚息（1.5 倍基准日息）
  const overdueRate = rate * 1.5
  const penaltyInterest = roundCurrency(principal * overdueRate * overdue)

  // 3. 汇总金额
  const totalInterest = roundCurrency(normalInterest + penaltyInterest)
  const totalRepayment = roundCurrency(principal + totalInterest)
  const dailyCost = roundCurrency(principal * rate)
  const apr = Number((rate * 360).toFixed(4))
  const aprPercent = (apr * 100).toFixed(2) + '%'

  // 4. 标准三档位对比矩阵（7天 / 10天 / 15天）
  const tierComparisons = STANDARD_TIERS.map(td => {
    const ibDays = Math.max(0, td - grace)
    const ni = roundCurrency(principal * rate * ibDays)
    return {
      termDays: td,
      interestBearingDays: ibDays,
      normalInterest: ni,
      totalRepayment: roundCurrency(principal + ni),
      label: td === 7 ? '7天 (敏捷短融)' : td === 10 ? '10天 (周转备货)' : '15天 (标准赊销)'
    }
  })

  return {
    principal,
    termDays: days,
    dailyRate: rate,
    dailyRatePercent: (rate * 100).toFixed(4) + '%',
    apr,
    aprPercent,
    graceDays: grace,
    interestBearingDays,
    normalInterest,
    overdueDays: overdue,
    overdueRate,
    penaltyInterest,
    totalInterest,
    totalRepayment,
    dailyCost,
    tierComparisons
  }
}
