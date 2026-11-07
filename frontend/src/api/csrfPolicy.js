// Keep this policy aligned with backend WebConfig.CsrfInterceptor mappings.
// A11：后端已把 CSRF 扩到 /admin/** 整体、商户流量事件上报与价格预警订阅——此处同步镜像，
// 保证 axios 非 GET 请求自动携带 X-CSRF-TOKEN（缺失时 request.js 会拉取令牌并重放）。
// 匿名公开上报（/ab/expose、/ab/convert）不纳入：匿名会话取不到 /user/csrf-token。
const CSRF_PROTECTED_PATHS = [
  /^\/user(?:\/|$)/,
  /^\/admin(?:\/|$)/,
  /^\/tech(?:\/|$)/,
  /^\/attachment(?:\/|$)/,
  /^\/merchant\/traffic\/events$/,
  /^\/price-monitor\/alerts(?:\/|$)/,
  // 商品主图上传（P1-5）：与后端 WebConfig CsrfInterceptor 的 /product/image 登记同步
  /^\/product\/image$/,
  // AI 标题润色（ai-polish）：与后端 WebConfig CsrfInterceptor 的 /product/ai/** 登记同步
  /^\/product\/ai\//,
  // 资金与交易写路径：订单/钱包/支付建单与退款。渠道回调（/payment/notify/**）不在列，
  // 那些由支付宝/微信服务器发起，没有用户会话。
  /^\/order(?:\/|$)/,
  /^\/wallet(?:\/|$)/,
  /^\/payment\/(?:create|mock-confirm|refund)$/
]

export function isCsrfProtectedMutation(config = {}) {
  const method = String(config.method || 'get').toLowerCase()
  if (['get', 'head', 'options'].includes(method)) return false

  // Axios normally provides relative endpoint paths; strip a query string defensively.
  const url = String(config.url || '').split('?')[0]
  return CSRF_PROTECTED_PATHS.some((pattern) => pattern.test(url))
}
