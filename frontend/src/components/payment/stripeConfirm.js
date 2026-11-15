/**
 * Stripe 卡支付确认的共享封装（OrderDetail / MyOrders 复用；Checkout 为独立弹窗实现）。
 *
 * 安全边界：卡号/CVC 只在 Stripe Elements iframe 内采集，这里只做
 * clientSecret + 卡控件 → confirmCardPayment（含 3DS）→ 结果判定；
 * 任何配置缺失/卡被拒都必须抛出可行动错误，调用方不得把"预授权已建立"当成功。
 */
import { loadStripeOnce } from '../../utils/stripeLoader'

/** 标记为本模块抛出的可展示错误：调用方据此决定是否再弹一次（避免与 request.js 拦截器重复提示） */
function stripeError(message) {
  const err = new Error(message)
  err.__llStripe = true
  return err
}

/**
 * 校验后端 /payment/create 返回的 payInfo 必备字段。
 * @returns {{clientSecret:string, publishableKey:string}}
 */
export function validateStripePayInfo(payInfo) {
  const clientSecret = String(payInfo?.clientSecret || '').trim()
  const publishableKey = String(payInfo?.publishableKey || '').trim()
  if (!clientSecret) {
    throw stripeError('Stripe 预下单未返回 clientSecret：请确认后端已配置 STRIPE_SECRET_KEY，且 LANLINK_PAYMENT_MOCK=false')
  }
  if (!publishableKey) {
    throw stripeError('Stripe 支付公钥未配置：请在后端 .env 填写 VITE_STRIPE_PUBLISHABLE_KEY（或 STRIPE_PUBLISHABLE_KEY）后重启后端')
  }
  return { clientSecret, publishableKey }
}

/**
 * 用已挂载的 StripeCardElement 完成 PaymentIntent 确认（自动处理 3DS / next_action）。
 * @param {{ensureCardElement:() => Promise<object>}} cardComponent StripeCardElement 组件实例（通过 ref 获取）
 * @param {{clientSecret?:string, publishableKey?:string}} payInfo 后端 payInfo
 * @param {{name?:string, phone?:string, email?:string}} [billingDetails]
 * @returns {Promise<{status:string, paymentIntent:object|null}>}
 * @throws {Error} 配置缺失 / 卡被拒 / 3DS 失败（message 可直接展示给用户）
 */
export async function confirmStripeCardPayment(cardComponent, payInfo, billingDetails = {}) {
  if (!cardComponent || typeof cardComponent.ensureCardElement !== 'function') {
    throw stripeError('Stripe 卡控件未就绪，请关闭收银台后重新打开')
  }
  const { clientSecret, publishableKey } = validateStripePayInfo(payInfo)
  const stripe = await loadStripeOnce(publishableKey)
  const card = await cardComponent.ensureCardElement()
  const { error, paymentIntent } = await stripe.confirmCardPayment(clientSecret, {
    payment_method: {
      card,
      billing_details: {
        name: billingDetails.name || undefined,
        phone: billingDetails.phone || undefined,
        email: billingDetails.email || undefined
      }
    }
  })
  if (error) {
    // card_error（被拒/余额不足/3DS 失败）等：原样抛出 Stripe 的可读 message
    throw stripeError(error.message || '银行卡支付失败，请更换卡或稍后重试')
  }
  return { status: paymentIntent?.status || 'unknown', paymentIntent: paymentIntent || null }
}

/**
 * 统一结果判定：只有 succeeded 才能提示"支付成功"。
 * @param {string} status
 * @returns {{ok:boolean, processing:boolean, message:string}}
 */
export function describeStripePaymentResult(status) {
  if (status === 'succeeded') return { ok: true, processing: false, message: 'Stripe 支付成功' }
  if (status === 'processing') {
    return { ok: false, processing: true, message: '银行正在处理支付，请稍后在「我的订单」查看结果' }
  }
  return {
    ok: false,
    processing: false,
    message: `支付未完成（状态：${status || 'unknown'}），请稍后在我的订单确认或重新支付`
  }
}
