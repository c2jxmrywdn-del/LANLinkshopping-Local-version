import request, { fetchCsrfToken } from './request'
import { hasCookieConsent } from '../utils/cookieConsent'

// The public assistant stays knowledge-base-only. Dialogue storage is token-scoped and redacted server-side.
export const supportApi = {
  async ask(question, visitorToken, persistConversation = false) {
    const unavailable = '客服暂时无法响应，请稍后重试。'
    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), 10000)
    try {
      const response = await fetch('/api/support/ask', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' }, cache: 'no-store',
        body: JSON.stringify({ action: 'ask', question, visitorToken, persistConversation }),
        signal: controller.signal
      })
      if (!response.ok) return { answer: unavailable, unavailable: true, visitorToken }
      const body = await response.json().catch(() => null)
      const answer = body?.data?.answer || body?.message
      if (typeof answer !== 'string' || !answer) return { answer: unavailable, unavailable: true, visitorToken }
      return {
        answer,
        unavailable: false,
        visitorToken: body?.data?.visitorToken || visitorToken,
        lastMessageId: Number(body?.data?.lastMessageId || 0)
      }
    } catch {
      return { answer: unavailable, unavailable: true, visitorToken }
    } finally {
      clearTimeout(timeoutId)
    }
  },
  async history(visitorToken, afterMessageId = 0) {
    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), 5000)
    try {
      const response = await fetch('/api/support/ask', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' }, cache: 'no-store',
        body: JSON.stringify({ action: 'history', visitorToken, afterMessageId }),
        signal: controller.signal
      })
      if (!response.ok) return { messages: [], visitorToken, lastMessageId: afterMessageId }
      const body = await response.json().catch(() => null)
      return {
        messages: Array.isArray(body?.data?.messages) ? body.data.messages : [],
        visitorToken: body?.data?.visitorToken || visitorToken,
        lastMessageId: Number(body?.data?.lastMessageId || afterMessageId)
      }
    } catch {
      return { messages: [], visitorToken, lastMessageId: afterMessageId }
    } finally {
      clearTimeout(timeoutId)
    }
  }
}

export const authApi = {
  register: (d) => request.post('/auth/register', d),
  login: (d) => request.post('/auth/login', d),
  // 两步验证登录：登录返回 require2fa 后，用一次性票据 + 动态码完成登录
  login2fa: (d) => request.post('/auth/login/2fa', d),
  logout: () => request.post('/auth/logout'),
  me: () => request.get('/auth/me')
}

export const homeApi = {
  industries: () => request.get('/home/industries'),
  hot: (limit = 8) => request.get('/home/hot', { params: { limit } }),
  categories: (indId) => request.get('/category/list', { params: { indId } })
}

export const productApi = {
  page: (params) => request.get('/product/page', { params }),
  detail: (id) => request.get(`/product/detail/${id}`),
  publish: (d) => request.post('/product/publish', d),
  // 商户端：我的商品列表（含审核状态）/ 商品主图上传
  myList: (params) => request.get('/product/my/list', { params }),
  uploadImage: (formData) => request.post('/product/image', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  }),
  // 管理后台专用
  adminList: (params) => request.get('/product/admin/list', { params }),
  adminStatus: (prodId, status) => request.post(`/product/admin/status/${prodId}`, null, { params: { status } }),
  adminReview: (prodId, reviewStatus, reason) => request.post(`/product/admin/review/${prodId}`, null, { params: { reviewStatus, reason } })
}

export const cartApi = {
  add: (d) => request.post('/cart/add', d),
  list: () => request.get('/cart/list'),
  updateQty: (cartId, quantity) => request.post(`/cart/quantity/${cartId}`, null, { params: { quantity } }),
  setChecked: (cartId, checked) => request.post(`/cart/checked/${cartId}`, null, { params: { checked } }),
  remove: (cartId) => request.delete(`/cart/${cartId}`)
}

export const orderApi = {
  checkout: (d) => request.post('/order/checkout', d),
  my: () => request.get('/order/my'),
  detail: (orderNo) => request.get(`/order/detail/${orderNo}`),
  pay: (orderNo) => request.post(`/order/pay/${orderNo}`),
  cancel: (orderNo) => request.post(`/order/cancel/${orderNo}`),
  // 管理后台：订单分页（交易管理，可按支付状态过滤）
  adminList: (params) => request.get('/order/admin/list', { params })
}

// ===== 支付系统（多渠道：wallet 钱包 / mock 模拟 / wechat / alipay） =====
export const paymentApi = {
  // 发起支付：{ orderNo, channel } → wallet 同步完成；mock 返回模拟令牌；wechat 返回 codeUrl；alipay 返回 form
  create: (orderNo, channel) => request.post('/payment/create', { orderNo, channel }),
  // mock 模式下"模拟支付成功"
  mockConfirm: (orderNo) => request.post('/payment/mock-confirm', null, { params: { orderNo } }),
  // 支付状态查询（未支付时对真实渠道主动对账）
  query: (orderNo) => request.get(`/payment/query/${orderNo}`),
  // 我的交易记录
  my: () => request.get('/payment/my'),
  // 发起退款（仅平台运营）
  refund: (orderNo, amount, reason) => request.post('/payment/refund', { orderNo, amount, reason })
}

// ===== 钱包 =====
export const walletApi = {
  my: () => request.get('/wallet/my'),
  recharge: (amount, remark) => request.post('/wallet/recharge', { amount, remark })
}

// ===== 企业账期 =====
export const creditTermApi = {
  overview: () => request.get('/credit-term/overview'),
  apply: (d) => request.post('/credit-term/apply', d),
  bills: () => request.get('/credit-term/bills'),
  detail: (id) => request.get(`/credit-term/bills/${id}`),
  repayments: (id) => request.get(`/credit-term/bills/${id}/repayments`),
  repay: (id, amount, method = 'wallet') => request.post(`/credit-term/bills/${id}/repay`, { amount, method }),
  adminAccounts: (status) => request.get('/credit-term/admin/accounts', { params: { status } }),
  review: (id, d) => request.post(`/credit-term/admin/accounts/${id}/review`, d)
}

// ===== 账号中心（/user/**，非 GET 请求自动携带 CSRF 令牌，见 request.js） =====
export const userApi = {
  // 个人资料
  getProfile: () => request.get('/user/profile'),
  putProfile: (patch) => request.put('/user/profile', patch),
  // 头像上传（FormData，支持 onUploadProgress 进度回调）
  uploadAvatar: (file, onProgress) => {
    const fd = new FormData()
    fd.append('file', file)
    return request.post('/user/avatar', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (e) => { if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100)) }
    })
  },
  // 联系方式换绑（邮箱：SMTP 配置时验证码发往邮箱不回显；未配置时回退 devCode 演示模式）
  sendPhoneCode: (phone) => request.post('/user/phone-code', { phone }),
  bindPhone: (phone, code) => request.post('/user/phone-bind', { phone, code }),
  sendEmailCode: (email) => request.post('/user/email-code', { email }),
  bindEmail: (email, code) => request.post('/user/email-bind', { email, code }),
  // 账户安全
  changePassword: (oldPassword, newPassword) => request.post('/user/password', { oldPassword, newPassword }),
  getSecurity: () => request.get('/user/security'),
  totpSetup: () => request.post('/user/2fa/setup'),
  totpEnable: (code) => request.post('/user/2fa/enable', { code }),
  totpDisable: (code) => request.post('/user/2fa/disable', { code }),
  // 系统设置
  getSettings: () => request.get('/user/settings'),
  putSettings: (patch) => request.put('/user/settings', patch),
  resetSettings: () => request.post('/user/settings/reset'),
  // 第三方授权
  thirdAuthList: () => request.get('/user/third-auth'),
  revokeThirdAuth: (id) => request.delete(`/user/third-auth/${id}`),
  // 审计：客户端侧事件上报（如 cache.clear）
  audit: (action, detail) => request.post('/user/audit', { action, detail }),
  // 消息通知中心
  messagePage: (params) => request.get('/user/message/page', { params }),
  messageUnread: () => request.get('/user/message/unread'),
  messageRead: (id) => request.post(`/user/message/read/${id}`),
  messageReadAll: () => request.post('/user/message/read-all'),
  // 收货地址簿
  addressList: () => request.get('/user/address'),
  addressAdd: (d) => request.post('/user/address', d),
  addressUpdate: (id, d) => request.put(`/user/address/${id}`, d),
  addressDelete: (id) => request.delete(`/user/address/${id}`),
  addressSetDefault: (id) => request.post(`/user/address/${id}/default`),
  // 登录安全记录
  loginLogPage: (params) => request.get('/user/login-log', { params })
}

export const merchantApi = {
  apply: (d) => request.post('/merchant/apply', d),
  my: () => request.get('/merchant/my'),
  // 入驻申请材料上传（申请阶段，仅需登录）：kind=license 工商执照(JPG/PNG) | taxProof 纳税记录(JPG/PNG/PDF)，≤10MB
  applyUpload: (kind, file, onProgress) => {
    const fd = new FormData()
    fd.append('file', file)
    return request.post('/merchant/apply-upload', fd, {
      params: { kind },
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (e) => { if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100)) }
    })
  },
  // 营业执照上传（JPG/PNG，返回 { url }）
  uploadLicense: (formData) => request.post('/merchant/license', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  }),
  // 税务缴纳证明上传（PDF/JPG/PNG，返回 { url }，可多次调用）
  uploadTaxProof: (formData) => request.post('/merchant/tax-proof', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  }),
  // 税务登记号查询近 3 个月缴纳记录（返回 { records, totalAmount }）
  taxQuery: (taxRegNo) => request.get('/merchant/tax-query', { params: { taxRegNo } }),
  // 管理后台专用
  adminList: (reviewStatus) => request.get('/merchant/admin/list', { params: { reviewStatus } }),
  adminDetail: (merId) => request.get(`/merchant/admin/detail/${merId}`),
  adminReview: (merId, reviewStatus, reason) => request.post(`/merchant/admin/review/${merId}`, null, { params: { reviewStatus, reason } })
}

// ===== 商户端：流量管理（经营数据统计） =====
export const trafficApi = {
  overview: () => request.get('/merchant/traffic/overview'),
  trend: (days = 30) => request.get('/merchant/traffic/trend', { params: { days } }),
  channels: (days = 30) => request.get('/merchant/traffic/channels', { params: { days } }),
  products: (days = 30) => request.get('/merchant/traffic/products', { params: { days } }),
  sources: (days = 30) => request.get('/merchant/traffic/sources', { params: { days } }),
  conversion: (days = 30) => request.get('/merchant/traffic/conversion', { params: { days } }),
  diagnosis: (days = 30) => request.get('/merchant/traffic/diagnosis', { params: { days } }),
  track: (event) => request.post('/merchant/traffic/events', event),
  trackPage: (event) => {
    // 页面行为分析属于可选 Cookie 范畴；用户未同意时不创建分析会话或上报行为。
    if (!hasCookieConsent('analytics')) return Promise.resolve({ skipped: true })
    const key='ll_traffic_session'
    let sid=sessionStorage.getItem(key)
    if(!sid){
      sid=crypto.randomUUID?.() || ('ll-' + Date.now() + '-' + Math.random().toString(36).slice(2))
      sessionStorage.setItem(key,sid)
    }
    return request.post('/merchant/traffic/events',{
      ...event,
      sessionId:sid,
      deviceType:/Mobi|Android/i.test(navigator.userAgent)?'mobile':'pc',
      pageUrl:location.pathname
    })
  }
}

// ===== 管理后台审计（/admin/**，AuthInterceptor 已限定 admin 角色） =====
export const adminApi = {
  auditPage: (params) => request.get('/admin/audit/page', { params }),
  auditExport: (params) => request.get('/admin/audit/export', { params, responseType: 'blob' }),
  supportLearningPreview: (payload) => request.post('/admin/support-learning/preview', payload),
  communicationOverview: () => request.get('/admin/customer-communications/overview'),
  communicationConversations: (params) => request.get('/admin/customer-communications/conversations', { params }),
  communicationConversation: (id) => request.get('/admin/customer-communications/conversations/' + encodeURIComponent(id)),
  communicationReply: (id, payload) => request.post('/admin/customer-communications/conversations/' + encodeURIComponent(id) + '/messages', payload),
  communicationSetStatus: (id, status) => request.put('/admin/customer-communications/conversations/' + encodeURIComponent(id) + '/status', { status }),
  communicationSetPriority: (id, priority) => request.put('/admin/customer-communications/conversations/' + encodeURIComponent(id) + '/priority', { priority }),
  communicationAssign: (id) => request.post('/admin/customer-communications/conversations/' + encodeURIComponent(id) + '/assign'),
  communicationUnassign: (id) => request.delete('/admin/customer-communications/conversations/' + encodeURIComponent(id) + '/assign'),
  communicationCreateLearningCandidate: (id, payload) => request.post('/admin/customer-communications/conversations/' + encodeURIComponent(id) + '/learn', payload),
  communicationLearningCandidates: (params) => request.get('/admin/customer-communications/learning-candidates', { params }),
  communicationLearningCandidate: (id) => request.get('/admin/customer-communications/learning-candidates/' + encodeURIComponent(id)),
  communicationReviewLearningCandidate: (id, payload) => request.post('/admin/customer-communications/learning-candidates/' + encodeURIComponent(id) + '/review', payload)
}

// ===== 营销中台：活动系统 / 促销系统 / 会员系统 =====
export const activityApi = {
  list: () => request.get('/activity/list'),
  join: (id) => request.post(`/activity/${id}/join`),
  my: () => request.get('/activity/my')
}

export const promotionApi = {
  list: () => request.get('/promotion/list')
}

export const membershipApi = {
  my: () => request.get('/membership/my'),
  // 积分抵现试算：返回 { points 实际抵扣积分, amount 抵扣金额, maxAmount 本单上限, balance 积分余额 }
  redeemQuote: (points, orderAmount) => request.get('/membership/redeem-quote', { params: { points, orderAmount } })
}
