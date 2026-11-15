/**
 * Stripe.js 单例加载器（前端唯一入口，禁止各页面直接 import { loadStripe }）。
 *
 * 为什么要单例：Stripe.js 官方要求整页只注入一次 https://js.stripe.com/v3；
 * 每次 loadStripe(key) 都会新建一个 <script> 并重复下载/执行，同页多个支付入口
 * （结算页、我的订单、订单详情、订阅中心）并发调用会重复加载并互相覆盖 window.Stripe。
 *
 * publishable key 的权威来源是后端：`POST /payment/create` 在 channel=stripe 时返回
 * `payInfo.publishableKey`（后端从 VITE_STRIPE_PUBLISHABLE_KEY / STRIPE_PUBLISHABLE_KEY 读取，
 * 见 application-payment.yml）。前端构建期 env 只作为可选兜底（frontend/.env），
 * 缺失时必须在调用处给出可行动错误，绝不能静默降级成"假支付"。
 */
import { loadStripe } from '@stripe/stripe-js'

/** publishable key 缺失/非法时抛出的可识别错误（调用方据此给配置指引，而不是"支付失败"） */
export class StripeConfigError extends Error {
  constructor(message) {
    super(message)
    this.name = 'StripeConfigError'
  }
}

const KEY_MISSING_MESSAGE =
  'Stripe 支付公钥未配置：请在后端 .env 填写 VITE_STRIPE_PUBLISHABLE_KEY（或 STRIPE_PUBLISHABLE_KEY），' +
  '重启后端后重试；或确认 /payment/create 返回了 payInfo.publishableKey'

let cachedKey = ''
let cachedPromise = null
let cachedElementsKey = ''
let cachedElements = null

/**
 * 解析 publishable key：显式入参（后端返回）优先，其次构建期 env。
 * @param {string} [explicitKey]
 * @returns {string}
 * @throws {StripeConfigError} 两处都没有时
 */
export function resolvePublishableKey(explicitKey) {
  const explicit = typeof explicitKey === 'string' ? explicitKey.trim() : ''
  if (explicit) return explicit
  let fromEnv = ''
  try {
    fromEnv = String(import.meta.env?.VITE_STRIPE_PUBLISHABLE_KEY || '').trim()
  } catch (e) {
    // 非 Vite 运行时（如 node 脚本）没有 import.meta.env：按缺失处理
    fromEnv = ''
  }
  if (fromEnv) return fromEnv
  throw new StripeConfigError(KEY_MISSING_MESSAGE)
}

/**
 * 按 key 缓存加载结果：同一 key 复用同一个 Promise（并发调用只加载一次），
 * key 变化时重新加载（测试/沙箱切换场景）。
 * 失败不缓存：loadStripe 拒绝（网络被拦截/公钥非法）后清空缓存，允许用户重试。
 * @param {string} [explicitKey]
 * @returns {Promise<import('@stripe/stripe-js').Stripe | null>}
 */
export function loadStripeOnce(explicitKey) {
  const key = resolvePublishableKey(explicitKey)
  if (cachedPromise && cachedKey === key) return cachedPromise
  cachedKey = key
  cachedPromise = Promise.resolve(loadStripe(key)).catch((error) => {
    if (cachedKey === key) {
      cachedKey = ''
      cachedPromise = null
    }
    throw error
  })
  return cachedPromise
}

/**
 * 取「Stripe 实例 + Elements 分组」，两者都按 key 单例缓存。
 * Stripe 的建议是整页只创建一次 Elements 分组（弹窗反复开关时不要每次重建分组）；
 * 卡元素本身仍由调用方在卸载时 unmount，可安全重建。
 * @param {string} [explicitKey]
 * @returns {Promise<{stripe: import('@stripe/stripe-js').Stripe, elements: object}>}
 */
export async function getStripeElements(explicitKey) {
  const key = resolvePublishableKey(explicitKey)
  const stripe = await loadStripeOnce(key)
  if (!stripe) throw new Error('Stripe.js 加载失败（返回空实例）')
  if (!cachedElements || cachedElementsKey !== key) {
    cachedElements = stripe.elements()
    cachedElementsKey = key
  }
  return { stripe, elements: cachedElements }
}

/** 仅测试使用：清空单例缓存，保证用例间互不影响 */
export function __resetStripeLoaderForTests() {
  cachedKey = ''
  cachedPromise = null
  cachedElementsKey = ''
  cachedElements = null
}

/** 仅测试使用：读取当前缓存状态（断言"同 key 只加载一次"） */
export function __stripeLoaderStateForTests() {
  return { key: cachedKey, hasPromise: cachedPromise !== null }
}
