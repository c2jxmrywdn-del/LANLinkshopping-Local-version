/**
 * Vite 开发代理的入口校验（纯函数，供 vite.config.js 与单测共用）。
 *
 * 背景：开发代理原先无条件把 Origin 改写成 127.0.0.1:5173，并静态注入客服代理密钥，
 * 使任意站点/局域网客户端都能借本机 5173 拿到客服媒体预签名，绕过后端来源白名单；
 * 同时 `/api` 泛代理把后端 `/actuator/prometheus` 也透传出去。
 *
 * 这里只做判定，不做转发；返回 null 表示放行。
 */

const LOOPBACK_REMOTES = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1'])

const DEFAULT_ALLOWED_ORIGINS = ['http://localhost:5173', 'http://127.0.0.1:5173']

/** 归一化 socket remoteAddress（IPv4-mapped IPv6 视作 IPv4 回环） */
export function isLoopbackAddress(rawAddress) {
  if (!rawAddress) return false
  if (LOOPBACK_REMOTES.has(rawAddress)) return true
  return LOOPBACK_REMOTES.has(rawAddress.replace(/^::ffff:/, ''))
}

/** 解析来源白名单：环境变量优先，空值回落到本地默认两条 */
export function parseAllowedOrigins(raw) {
  const list = String(raw || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
  return list.length ? list : [...DEFAULT_ALLOWED_ORIGINS]
}

/**
 * 判定一次代理请求是否允许转发。
 *
 * @param {{url?: string, headers?: Record<string,string>, socket?: {remoteAddress?: string}}} req
 * @param {{requireAllowedOrigin?: boolean, allowedOrigins?: string[]}} options
 * @returns {{status: number, message: string}|null}
 */
export function proxyViolation(req, options = {}) {
  const { requireAllowedOrigin = false, allowedOrigins = DEFAULT_ALLOWED_ORIGINS } = options
  const path = String((req && req.url) || '').split('?')[0]

  // 内部工具面与后端指标绝不暴露给浏览器
  if (path.startsWith('/api/internal/') || path === '/api/actuator/prometheus') {
    return { status: 404, message: 'not found' }
  }

  const remote = (req && req.socket && req.socket.remoteAddress) || ''
  if (!isLoopbackAddress(remote)) {
    return { status: 403, message: 'forbidden: dev proxy only accepts loopback clients' }
  }

  if (requireAllowedOrigin) {
    const origin = String((req.headers && req.headers.origin) || '')
    // 同源 GET/预检可能不带 Origin；带了就必须在白名单内
    if (origin && !allowedOrigins.includes(origin)) {
      return { status: 403, message: 'forbidden: origin not allowed' }
    }
  }
  return null
}
