import axios from 'axios'
import { message } from 'ant-design-vue'
import router from '../router'
import { hideBusyLoading } from '../utils/busy'
import { isCsrfProtectedMutation } from './csrfPolicy'

// Production Vercel builds use a same-origin /api rewrite to the Railway backend.
// This keeps session cookies first-party and avoids browser third-party-cookie blocking.
const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15000,
  withCredentials: true
})

// CSRF 令牌缓存（模块级，刷新页面后重新获取）
let csrfToken = null
let csrfTokenRequest = null

/** 主动拉取并缓存 CSRF 令牌 */
export async function fetchCsrfToken() {
  const data = await request.get('/user/csrf-token')
  csrfToken = data.token
  return csrfToken
}

// 首个并发写请求共用一次令牌获取，避免同时请求造成重复会话初始化。
async function ensureCsrfToken() {
  if (csrfToken) return csrfToken
  if (!csrfTokenRequest) {
    csrfTokenRequest = fetchCsrfToken().finally(() => {
      csrfTokenRequest = null
    })
  }
  return csrfTokenRequest
}

// 是否为 CSRF 失效响应：403 且 message 含 CSRF
function isCsrfFailure(status, body) {
  return status === 403 && /csrf/i.test((body && body.message) || '')
}

// 换新令牌并重放一次原请求（config._retried 防循环）
async function retryWithCsrf(config) {
  await fetchCsrfToken()
  config._retried = true
  config.headers = config.headers || {}
  config.headers['X-CSRF-TOKEN'] = csrfToken
  return request(config)
}

request.interceptors.request.use(async (config) => {
  if (isCsrfProtectedMutation(config)) {
    await ensureCsrfToken()
    config.headers = config.headers || {}
    config.headers['X-CSRF-TOKEN'] = csrfToken
  }
  return config
})

request.interceptors.response.use(
  (res) => {
    // 二进制响应（如 CSV 导出）不按业务 JSON 解析
    if (res.config.responseType === 'blob') return res.data
    const body = res.data
    if (body && body.code !== 200) {
      if ([429, 502, 503, 504].includes(Number(body.code))) {
        // 不再弹全屏"系统正在繁忙处理"遮罩：一次失败就遮住整页 3-5 秒体验很差；
        // 改为轻量 toast，用户可继续操作/重试（本地 AI 请求已单独放宽超时）。
        message.warning(body.message || '系统繁忙，请稍后重试')
        return Promise.reject(new Error(body.message || '系统繁忙'))
      }
      // CSRF 令牌失效：换新后重放一次；覆盖账号中心与管理员营销邮件接口
      if (isCsrfFailure(body.code, body) && !res.config._retried && isCsrfProtectedMutation(res.config)) {
        return retryWithCsrf(res.config)
      }
      // /auth/me 仅用于静默探测登录态：401 时不弹提示、不强制跳登录
      const isMe = (res.config.url || '').includes('/auth/me')
      // 埋点/遥测类请求（config.silent）失败不打扰用户：不弹错、不跳登录
      if (!isMe && !res.config.silent) {
        message.error(body.message || '请求失败')
        if (body.code === 401) {
          router.push('/login')
        }
      }
      return Promise.reject(new Error(body.message))
    }
    hideBusyLoading(180)
    return body.data
  },
  (err) => {
    const resp = err.response
    // 局部超时/网络中断不再弹全屏"系统繁忙"遮罩：AI 推理本身可能较慢，超时只提示即可，
    // 只有服务端明确 429/502/503/504（真拥塞）才显示排队遮罩，避免误伤正常长请求。
    const isCongested = !!(resp && [429, 502, 503, 504].includes(Number(resp.status)))
    if (isCongested) {
      message.warning('系统繁忙，请稍后重试')
      return Promise.reject(err)
    }
    if (err.code === 'ECONNABORTED' || /timeout/i.test(err.message || '')) {
      if (!err.config?.silent) message.warning('请求超时，请稍后重试')
      return Promise.reject(err)
    }
    if (resp && err.config && isCsrfFailure(resp.status, resp.data) && !err.config._retried && isCsrfProtectedMutation(err.config)) {
      return retryWithCsrf(err.config)
    }
    if (!err.config?.silent) {
      message.warning('网络异常，请稍后重试')
    }
    return Promise.reject(err)
  }
)

export default request
