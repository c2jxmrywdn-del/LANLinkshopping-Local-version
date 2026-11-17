import axios from 'axios'
import { message } from 'ant-design-vue'
import router from '../router'
import { hideBusyLoading } from '../utils/busy'
import { isCsrfProtectedMutation } from './csrfPolicy'

/**
 * 会话失效统一收口：清 Pinia 登录态 + 清 localStorage 快照。
 *
 * <p>为什么必须在拦截器里清快照：路由守卫在 store 为空时会回落到 `ll_user` 快照，
 * 只跳登录页而不清快照的话，下一次导航到受保护页会被旧快照再次放行，
 * 页面接口随即全部 401、再被踢回登录页——形成反复跳转的循环。
 *
 * <p>动态 import 避免 request.js ↔ store/user.js 的循环依赖
 * （user store 依赖 api/index.js，后者依赖本文件）。
 */
function clearSession() {
  try {
    localStorage.removeItem('ll_user')
  } catch (e) { /* 隐私模式下 localStorage 可能抛错，忽略 */ }
  import('../store/user').then(({ useUserStore }) => {
    try {
      const store = useUserStore()
      store.user = null
      store.chatUnread = 0
    } catch (e) { /* pinia 未激活时忽略，快照已清即可 */ }
  }).catch(() => { /* store 加载失败不影响登出语义 */ })
}

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

/**
 * 错误提示去重：同一文案 5 秒内只弹一次。
 * 首屏并发预加载（行业/热门/分类等）同时失败时，原来会叠出 3-4 条一模一样的 toast，
 * 看起来像"服务崩了"；去重后只保留一条，且不阻塞后续操作。
 */
const toastLastAt = new Map()
/**
 * @param {string} text 提示文案
 * @param {'warning'|'error'} level 级别
 * @param {string} [key] 合并键：同键的提示由 antd 复用同一条 toast（不会堆叠）
 */
function notify(text, level = 'warning', key) {
  const msg = String(text || '').trim()
  if (!msg) return
  const now = Date.now()
  if (now - (toastLastAt.get(msg) || 0) < 5000) return
  toastLastAt.set(msg, now)
  if (toastLastAt.size > 50) {
    for (const [k, at] of toastLastAt) if (now - at > 60_000) toastLastAt.delete(k)
  }
  const opts = key ? { key } : undefined
  if (level === 'error') message.error(msg, opts)
  else message.warning(msg, opts)
}

/** 主动拉取并缓存 CSRF 令牌 */
export async function fetchCsrfToken() {
  // silent：令牌获取失败（如匿名会话 401）不弹"请先登录"、不跳登录页——
  // 由发起原请求的调用按其自身 silent 语义处理；遥测类静默通道依赖此约束。
  const data = await request.get('/user/csrf-token', { silent: true })
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
        // silent 请求（埋点/遥测/错误上报）拥塞时不打扰用户。
        if (!res.config.silent) notify(body.message || '系统繁忙，请稍后重试', 'warning', 'll-api-error')
        /*
         * 必须把业务码挂到 error 上。
         *
         * 后端有些端点用 `HTTP 200 + body.code=503` 表达"依赖服务不可用"
         * （例如多模态 RAG 的 /multimodal/*，因为它是可选增强能力）。
         * 如果只 reject 一个 Error，调用方拿到的是纯字符串消息，
         * 无法区分"真的系统繁忙"和"该功能未启用"，只能统一弹通用提示。
         * 挂 bizCode 后，页面可以据此展示"改用文本知识库"这类可行动引导。
         */
        const busyErr = new Error(body.message || '系统繁忙')
        busyErr.bizCode = Number(body.code)
        busyErr.bizBody = body
        return Promise.reject(busyErr)
      }
      // CSRF 令牌失效：换新后重放一次；覆盖账号中心与管理员营销邮件接口
      if (isCsrfFailure(body.code, body) && !res.config._retried && isCsrfProtectedMutation(res.config)) {
        return retryWithCsrf(res.config)
      }
      // /auth/me 仅用于静默探测登录态：401 时不弹提示、不强制跳登录
      const isMe = (res.config.url || '').includes('/auth/me')
      // 埋点/遥测类请求（config.silent）失败不打扰用户：不弹错、不跳登录
      if (!isMe && !res.config.silent) {
        notify(body.message || '请求失败', body.code === 401 ? 'warning' : 'error')
        if (body.code === 401) {
          // 先清登录态再跳转：否则守卫会拿旧快照再次放行，形成 401 循环
          clearSession()
          // 带上当前路径：登录成功后回到触发 401 的页面，而不是一律回首页
          const current = router.currentRoute.value
          const redirect = current && current.fullPath && current.path !== '/login' ? current.fullPath : undefined
          router.push({ path: '/login', query: redirect ? { redirect } : {} })
        }
      }
      const bizErr = new Error(body.message)
      bizErr.bizCode = Number(body.code)
      bizErr.bizBody = body
      return Promise.reject(bizErr)
    }
    hideBusyLoading(180)
    return body.data
  },
  (err) => {
    const resp = err.response
    // 统一把业务码/HTTP 状态挂到 error 上，供调用方按码分流（见上方 2xx 分支的说明）
    if (err.bizCode === undefined) {
      const bodyCode = resp && resp.data && typeof resp.data === 'object' ? resp.data.code : undefined
      err.bizCode = bodyCode !== undefined ? Number(bodyCode) : (resp ? Number(resp.status) : 0)
    }
    // 局部超时/网络中断不再弹全屏"系统繁忙"遮罩：AI 推理本身可能较慢，超时只提示即可，
    // 只有服务端明确 429/502/503/504（真拥塞）才显示排队遮罩，避免误伤正常长请求。
    const isCongested = !!(resp && [429, 502, 503, 504].includes(Number(resp.status)))
    if (isCongested) {
      if (!err.config?.silent) notify('系统繁忙，请稍后重试', 'warning', 'll-api-error')
      return Promise.reject(err)
    }
    if (err.code === 'ECONNABORTED' || /timeout/i.test(err.message || '')) {
      if (!err.config?.silent) notify('请求超时，请稍后重试', 'warning', 'll-api-timeout')
      return Promise.reject(err)
    }
    if (resp && err.config && isCsrfFailure(resp.status, resp.data) && !err.config._retried && isCsrfProtectedMutation(err.config)) {
      return retryWithCsrf(err.config)
    }
    // 会话过期（后端拦截器现返回真实 HTTP 401，与业务体 code=401 同语义）：
    // 与上方 2xx 分支的 401 处理保持一致——静默探测/显式 silent 不打扰，其余带路径跳登录
    const http401 = resp && Number(resp.status) === 401
    if (http401 && !err.config?.silent && !(err.config?.url || '').includes('/auth/me')) {
      clearSession()
      const current = router.currentRoute.value
      const redirect = current && current.fullPath && current.path !== '/login' ? current.fullPath : undefined
      router.push({ path: '/login', query: redirect ? { redirect } : {} })
    }
    if (!err.config?.silent) {
      // 统一按状态码给出可行动的信息：原来所有 4xx/5xx 一律"网络异常"，
      // 413（文件过大）、403（无权限）这类本来能自助处理的错误被掩盖。
      const status = resp ? Number(resp.status) : 0
      const serverMsg = resp && resp.data && typeof resp.data === 'object' && typeof resp.data.message === 'string'
        ? resp.data.message : ''
      const byStatus = status === 413 ? '文件过大，请压缩后重试'
        : status === 403 ? '没有权限执行该操作'
          : status === 404 ? '请求的资源不存在'
            : status >= 500 ? '服务异常，请稍后重试'
              : '网络异常，请稍后重试'
      // 固定 key：并发失败合并为一条，避免首屏多个请求各弹一次"服务异常"
      notify(serverMsg || byStatus, status >= 500 ? 'error' : 'warning', 'll-api-error')
    }
    return Promise.reject(err)
  }
)

export default request
