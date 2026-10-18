/**
 * 二维码随机生成与渲染工具
 *
 * 基于项目既有依赖 qrcode@1.5.4（见 components/account/TotpSetup.vue 的用法），
 * 不引入任何新依赖、不访问外部服务。
 *
 * 提供两块能力：
 *  1) 随机生成器：每次调用产出全新的一次性令牌（优先 crypto.getRandomValues），
 *     二维码内容随刷新而变化，避免"重放固定码"。
 *  2) 渲染封装：统一品牌配色 / 纠错等级 / 静默区，输出到 canvas 或 dataURL。
 *
 * 纯逻辑函数与渲染分离：randomToken / buildLoginQrPayload / buildPayQrPayload
 * 不触碰 DOM，可在 jsdom 之外直接单测。
 */
import QRCode from 'qrcode'

/** 统一的二维码渲染参数：深色走品牌墨色，留 1 模块静默区 */
export const QR_RENDER_DEFAULTS = {
  errorCorrectionLevel: 'M',
  margin: 1,
  width: 220,
  color: { dark: '#13233A', light: '#FFFFFF' }
}

const TOKEN_CHARS = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'

/**
 * 生成随机一次性令牌。
 * @param {number} len 令牌长度
 * @returns {string}
 */
export function randomToken(len = 24) {
  const size = Math.max(1, Math.floor(len))
  const out = new Array(size)
  const c = typeof globalThis !== 'undefined' ? globalThis.crypto : undefined
  if (c && typeof c.getRandomValues === 'function') {
    const buf = new Uint8Array(size)
    c.getRandomValues(buf)
    for (let i = 0; i < size; i++) out[i] = TOKEN_CHARS[buf[i] % TOKEN_CHARS.length]
  } else {
    for (let i = 0; i < size; i++) out[i] = TOKEN_CHARS[Math.floor(Math.random() * TOKEN_CHARS.length)]
  }
  return out.join('')
}

/**
 * 扫码登录 payload：本站地址 + 一次性令牌 + 时间戳，手机扫码后会打开本站并带上本次令牌。
 * @param {string} appName 应用名（写入查询参数便于识别来源）
 * @param {string} [origin] 站点 origin，缺省取 location.origin
 * @param {() => number} [now] 时间源，便于单测注入
 */
export function buildLoginQrPayload(appName = 'LANLinkshopping', origin, now = () => Date.now()) {
  const base = origin || (typeof location !== 'undefined' ? location.origin : '')
  const token = randomToken(28)
  return `${base}/login?llqr=1&app=${encodeURIComponent(appName)}&t=${token}&ts=${now()}`
}

/**
 * 扫码支付 payload：优先使用渠道返回的 codeUrl（微信 Native），
 * 渠道未配置时退化为可扫的订单演示码，便于本地演示完整交互。
 */
export function buildPayQrPayload({ codeUrl, orderNo, amount } = {}) {
  if (codeUrl) return String(codeUrl)
  const no = encodeURIComponent(orderNo || '')
  const amt = amount === undefined || amount === null ? '' : encodeURIComponent(String(amount))
  return `LANLINK://pay?orderNo=${no}&amount=${amt}&t=${randomToken(16)}`
}

/**
 * 把文本渲染到指定 canvas（浏览器环境）。
 * @param {HTMLCanvasElement} canvas
 * @param {string} text
 * @param {object} [opts] 覆盖 QR_RENDER_DEFAULTS 的参数
 */
export async function renderQrToCanvas(canvas, text, opts = {}) {
  if (!canvas || !text) return null
  return QRCode.toCanvas(canvas, String(text), { ...QR_RENDER_DEFAULTS, ...opts })
}

/**
 * 把文本渲染为 dataURL（可用于 <img> 或下载）。
 * @param {string} text
 * @param {object} [opts]
 */
export function renderQrToDataUrl(text, opts = {}) {
  if (!text) return Promise.resolve('')
  return QRCode.toDataURL(String(text), { ...QR_RENDER_DEFAULTS, ...opts })
}
