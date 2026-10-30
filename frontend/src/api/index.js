import request, { fetchCsrfToken } from './request'
import { hasCookieConsent } from '../utils/cookieConsent'

// The public assistant stays knowledge-base-only. Dialogue storage is token-scoped and redacted server-side.
export const supportApi = {
  async ask(question, visitorToken, persistConversation = false) {
    const unavailable = '您好！客服暂时无法响应，请稍后重试。'
    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), 25000)
    try {
      const response = await fetch('/api/support/ask', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' }, cache: 'no-store',
        body: JSON.stringify({ action: 'ask', question, visitorToken, persistConversation }),
        signal: controller.signal
      })
      const body = await response.json().catch(() => null)
      // 优先展示后端返回的真实文案（如知识库范围说明），仅在后端无应答时才用通用兜底提示
      const serverAnswer = body?.data?.answer || body?.message
      const answer = (typeof serverAnswer === 'string' && serverAnswer) ? serverAnswer : unavailable
      if (!response.ok) return { answer, unavailable: true, visitorToken }
      if (answer === unavailable) return { answer, unavailable: true, visitorToken }
      return {
        answer,
        unavailable: false,
        source: body?.data?.source,
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

// 人工客服工单：用户在「设置 → 人工客服」提交留言并查看回复；运营在工作台处理。
export const supportTicketApi = {
  // 用户侧
  submit: (payload) => request.post('/support-ticket/submit', payload),
  my: () => request.get('/support-ticket/my'),
  detail: (id) => request.get(`/support-ticket/${encodeURIComponent(id)}`),
  // 运营侧
  adminList: (params) => request.get('/admin/support-ticket/list', { params }),
  adminReply: (id, reply) => request.post(`/admin/support-ticket/${encodeURIComponent(id)}/reply`, { reply }),
  adminSetStatus: (id, status) => request.post(`/admin/support-ticket/${encodeURIComponent(id)}/status`, { status })
}

// 向量知识库管理（运营后台）：统计 / 重灌 / RAG 测试台 / 逐条维护
export const kbApi = {
  stats: () => request.get('/admin/kb/stats'),
  // 向量库重灌/外部抓取/RAG 测试台：涉及 bge-m3 嵌入 + 本地 7B 推理，放宽 axios 超时，
  // 避免默认 15s 超时被误判为"系统繁忙"而弹出全屏排队遮罩。
  ingest: (source) => request.post('/admin/kb/ingest', null, { params: { source }, timeout: 180000 }),
  ask: (payload) => request.post('/admin/kb/ask', payload, { timeout: 180000 }),
  // 外部资讯自动抓取（RSS → 市场资讯 → 向量库）；limit 控制单次抓取条数
  fetchExternal: (limit = 30) => request.post('/admin/kb/fetch-external', null, { params: { limit }, timeout: 180000 }),
  entries: (params) => request.get('/admin/kb/entries', { params }),
  addEntry: (payload) => request.post('/admin/kb/entry', payload),
  deleteEntry: (id) => request.delete(`/admin/kb/entry/${encodeURIComponent(id)}`)
}

export const authApi = {
  register: (d) => request.post('/auth/register', d),
  login: (d) => request.post('/auth/login', d),
  // 两步验证登录：登录返回 require2fa 后，用一次性票据 + 动态码完成登录
  login2fa: (d) => request.post('/auth/login/2fa', d),
  logout: () => request.post('/auth/logout'),
  me: () => request.get('/auth/me')
}

// ===== 社交登录（微信 / 支付宝 / Apple / Google，公开接口）=====
export const socialApi = {
  // 渠道可用性：未配置也返回，前端据此渲染品牌入口并区分「已配置 / 未配置 / 沙箱可用」
  providers: () => request.get('/auth/social/providers'),
  // 取平台授权地址（未配置时后端返回明确错误，不伪造登录）
  authorize: (provider, state) => request.get(`/auth/social/authorize/${provider}`, { params: { state } }),
  // 授权回调：平台回跳带回 code/state，前端交给后端换取身份并建立会话
  callback: (provider, code, state) => request.post(`/auth/social/callback/${provider}`, { code, state }),
  // 已登录用户绑定第三方账号（账号设置 → 第三方授权）
  bind: (provider, code, state) => request.post(`/auth/social/bind/${provider}`, { code, state }),
  // 沙箱模拟登录（用于在开发/评审/无外网密钥环境下体验 Google / Apple 登录）
  sandboxLogin: (payload) => request.post('/auth/social/sandbox-login', payload),
  // 获取当前用户已绑定的第三方账号列表
  myBindings: () => request.get('/auth/social/my-bindings'),
  // 解绑指定渠道第三方账号
  unbind: (provider) => request.post(`/auth/social/unbind/${provider}`)
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
  // 商户端：商品详情回显 / 编辑（内容变更将重新送审）/ 自主上下架
  myDetail: (prodId) => request.get(`/product/my/detail/${prodId}`),
  myUpdate: (prodId, d) => request.post(`/product/my/update/${prodId}`, d),
  myStatus: (prodId, status) => request.post(`/product/my/status/${prodId}`, null, { params: { status } }),
  uploadImage: (formData) => request.post('/product/image', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  }),
  // 管理后台专用
  adminList: (params) => request.get('/product/admin/list', { params }),
  adminStats: () => request.get('/product/admin/stats'),
  adminDetail: (prodId) => request.get(`/product/admin/detail/${prodId}`),
  adminBatchReview: (payload) => request.post('/product/admin/batch-review', payload),
  adminAuditLogs: (prodId) => request.get(`/product/admin/audit-logs/${prodId}`),
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
  // 买家确认收货（已发货 → 已完成，联动签收在途运单）
  confirmReceipt: (orderNo) => request.post(`/order/confirm/${orderNo}`),
  cancel: (orderNo) => request.post(`/order/cancel/${orderNo}`),
  // 退款申请闭环：用户提交申请（进入平台审核队列）/ 平台驳回申请（仅运营）
  applyRefund: (orderNo, reason) => request.post(`/order/refund/apply/${orderNo}`, { reason }),
  adminRejectRefund: (orderNo, reason) => request.post(`/order/admin/refund/reject/${orderNo}`, { reason }),
  // 管理后台：订单分页（交易管理，可按支付状态过滤）
  adminList: (params) => request.get('/order/admin/list', { params }),
  // 商户端：包含本商户商品的订单（明细只暴露本商户部分）
  merchantList: (params) => request.get('/order/merchant/list', { params }),
  merchantDetail: (orderNo) => request.get(`/order/merchant/detail/${orderNo}`)
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
  // 单条流水详情（按 ID 直查，不受"最近 50 条"窗口限制）
  logDetail: (id) => request.get(`/wallet/log/${id}`),
  recharge: (amount, remark) => request.post('/wallet/recharge', { amount, remark })
}

// ===== 企业账期 =====
export const creditTermApi = {
  overview: () => request.get('/credit-term/overview'),
  apply: (d) => request.post('/credit-term/apply', d),
  bills: () => request.get('/credit-term/bills'),
  detail: (id) => request.get(`/credit-term/bills/${id}`),
  repayments: (id) => request.get(`/credit-term/bills/${id}/repayments`),
  repay: (id, amount, method = 'wallet', referenceNo = '') => request.post(`/credit-term/bills/${id}/repay`, { amount, method, referenceNo }),
  adjustments: () => request.get('/credit-term/adjustments'),
  applyAdjust: (d) => request.post('/credit-term/adjust', d),
  withdrawAdjust: (id) => request.post(`/credit-term/adjustments/${id}/withdraw`),
  adminAccounts: (status) => request.get('/credit-term/admin/accounts', { params: { status } }),
  review: (id, d) => request.post(`/credit-term/admin/accounts/${id}/review`, d),
  adminAdjustments: (status) => request.get('/credit-term/admin/adjustments', { params: { status } }),
  reviewAdjust: (id, d) => request.post(`/credit-term/admin/adjustments/${id}/review`, d),
  adminRepayments: (status) => request.get('/credit-term/admin/repayments', { params: { status } }),
  confirmRepayment: (id, d) => request.post(`/credit-term/admin/repayments/${id}/confirm`, d)
}

// ===== 物流模块（运单/轨迹/服务商/模板/告警/售后/结算） =====
export const logisticsApi = {
  // 商家发货（创建运单）
  ship: (d) => request.post('/logistics/ship', d),
  // 状态流转（确认签收等）
  transit: (waybillId, status, note) => request.post(`/logistics/transit/${waybillId}`, null, { params: { status, note } }),
  // 运单分页
  waybillList: (params) => request.get('/logistics/waybill/list', { params }),
  // 运单轨迹
  traces: (waybillId) => request.get(`/logistics/trace/${waybillId}`),
  // 商户端发货资料：可用物流服务商 / 运费模板（仅启用项）
  providers: () => request.get('/logistics/providers'),
  templates: (providerId) => request.get('/logistics/templates', { params: { providerId } }),
  // 轨迹同步（联调/模拟服务商推送）
  syncTrace: (waybillId, nodes) => request.post(`/logistics/trace/sync/${waybillId}`, nodes),
  // ===== 运营后台（/admin/logistics） =====
  adminProviders: () => request.get('/admin/logistics/provider/list'),
  saveProvider: (d) => request.post('/admin/logistics/provider/save', d),
  adminTemplates: (providerId) => request.get('/admin/logistics/template/list', { params: { providerId } }),
  saveTemplate: (d) => request.post('/admin/logistics/template/save', d),
  alerts: (params) => request.get('/admin/logistics/alert/list', { params }),
  handleAlert: (alertId, note) => request.post(`/admin/logistics/alert/handle/${alertId}`, null, { params: { note } }),
  aftersales: (params) => request.get('/admin/logistics/aftersale/list', { params }),
  createAftersale: (d) => request.post('/admin/logistics/aftersale/create', d),
  processAftersale: (aftersaleId, action, note) => request.post(`/admin/logistics/aftersale/process/${aftersaleId}`, null, { params: { action, note } }),
  settlements: (params) => request.get('/admin/logistics/settlement/list', { params }),
  settlementItems: (settlementId) => request.get(`/admin/logistics/settlement/items/${settlementId}`),
  generateSettlement: (params) => request.post('/admin/logistics/settlement/generate', null, { params }),
  reviewSettlement: (settlementId, action, note) => request.post(`/admin/logistics/settlement/review/${settlementId}`, null, { params: { action, note } })
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
  // 已入驻商户：月度完税记录上报（带所属期，后端写入文件名以精确归属）
  submitTaxProof: (file, period, onProgress) => {
    const fd = new FormData()
    fd.append('file', file)
    return request.post('/merchant/tax-proof', fd, {
      params: { period },
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (e) => { if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100)) }
    })
  },
  // 税务登记号查询近 3 个月缴纳记录（返回 { records, totalAmount }）
  taxQuery: (taxRegNo) => request.get('/merchant/tax-query', { params: { taxRegNo } }),
  // 管理后台专用
  adminList: (reviewStatus) => request.get('/merchant/admin/list', { params: { reviewStatus } }),
  adminStats: () => request.get('/merchant/admin/stats'),
  adminDetail: (merId) => request.get(`/merchant/admin/detail/${merId}`),
  adminSensitive: (merId) => request.get(`/merchant/admin/sensitive/${merId}`),
  adminBatchReview: (payload) => request.post('/merchant/admin/batch-review', payload),
  adminAuditLogs: (merId) => request.get(`/merchant/admin/audit-logs/${merId}`),
  adminReview: (merId, reviewStatus, reason) => request.post(`/merchant/admin/review/${merId}`, null, { params: { reviewStatus, reason } })
}

// ===== 直播带货（广场/直播间/商户控制台/管理端治理；后端 /live/** 与 /admin/live/**） =====
export const liveApi = {
  // ---- 公开：观众端（白名单 /live/rooms、/live/room/**、/live/room-by-product/**） ----
  // 房间列表：status 0 预告 / 1 直播中 / 2 已结束，不传返回全部
  rooms: (status, config) => request.get('/live/rooms', { params: (status === undefined || status === null || status === '') ? {} : { status }, ...config }),
  // 房间详情（含 pinnedProduct 讲解中商品）
  room: (roomId, config) => request.get(`/live/room/${roomId}`, config),
  // 挂载商品专柜（LiveProduct[]，含 livePrice/liveStock/clickCount）
  products: (roomId) => request.get(`/live/room/${roomId}/products`),
  roomProducts: (roomId) => request.get(`/live/room/${roomId}/products`),
  // 弹幕（升序，默认最近 50 条）；config 可传 { silent: true }
  comments: (roomId, limit = 50, config) => request.get(`/live/room/${roomId}/comments`, { params: { limit }, ...config }),
  // 发送弹幕（可匿名，昵称降级；msgType: chat | buy | system | gift）
  comment: (roomId, content, msgType = 'chat', config) => request.post(`/live/room/${roomId}/comment`, { content, msgType }, config),
  // 点赞：返回累计点赞数（后端从 body.count 读取，count query 兼容保留）
  like: (roomId, count = 1, config) => request.post(`/live/room/${roomId}/like`, { count }, { params: { count }, ...config }),
  // 进出直播间：join 返回当前在线数；leave 无返回（游客触发请传 { silent: true }）
  join: (roomId, config) => request.post(`/live/room/${roomId}/join`, null, config),
  leave: (roomId, config) => request.post(`/live/room/${roomId}/leave`, null, config),
  // 讲解商品点击埋点：返回含最新 livePrice 的 LiveProduct
  productClick: (roomId, prodId, config) => request.post(`/live/room/${roomId}/product/${prodId}/click`, null, config),
  trackProductClick: (roomId, prodId) => request.post(`/live/room/${roomId}/product/${prodId}/click`),
  // 商品直播中徽章：返回 LiveRoom | null（游客可访问，建议传 { silent: true } 吸收 401）
  roomByProduct: (prodId, config) => request.get(`/live/room-by-product/${prodId}`, config),
  // ---- 商户控制台（merchant:manage）----
  merchantCurrent: () => request.get('/live/merchant/current'),
  myRoom: () => request.get('/live/merchant/current'),
  merchantCreate: (d) => request.post('/live/merchant/create', d),
  createRoom: (d) => request.post('/live/merchant/create', d),
  // 置顶讲解商品 / 开关播（后端均从 body 读取参数）
  merchantPin: (roomId, prodId) => request.post(`/live/merchant/${roomId}/pin-product`, { prodId }),
  pinProduct: (roomId, prodId) => request.post(`/live/merchant/${roomId}/pin-product`, { prodId }),
  merchantStatus: (roomId, status) => request.post(`/live/merchant/${roomId}/status`, { status }),
  updateStatus: (roomId, status) => request.post(`/live/merchant/${roomId}/status`, { status }),
  // 复盘统计：{ room, products[], totalOrders, totalGmv, commentCount }
  merchantStats: (roomId, config) => request.get(`/live/merchant/${roomId}/stats`, config),
  stats: (roomId) => request.get(`/live/merchant/${roomId}/stats`),
  // ---- 管理端（admin）----
  adminRooms: (status) => request.get('/admin/live/rooms', { params: (status === undefined || status === null || status === '') ? {} : { status } }),
  adminForceClose: (roomId) => request.post(`/admin/live/${roomId}/force-close`),
  // 平台审核：approved=true 通过（商户方可开播）/ false 驳回（下线并回显原因，站内通知商户）
  review: (roomId, approved, reason) => request.post(`/admin/live/${roomId}/review`, { approved, reason }),
  adminStats: (roomId) => request.get(`/admin/live/${roomId}/stats`)
}

// ===== 买家↔商家 联系商家对话（sender_role 由服务端按登录身份强制写入） =====
export const chatApi = {
  // 买家侧
  start: (merId, prodId) => request.post('/chat/session', { merId, prodId }),
  sessions: () => request.get('/chat/sessions'),
  unread: () => request.get('/chat/unread'),
  messages: (sessionId, afterId = 0) => request.get(`/chat/${sessionId}/messages`, { params: { afterId } }),
  // 发消息：body 透传两种形态——文本 { content }（传字符串时包装兼容旧签名）与
  // 媒体 { msgType: 'image'|'file'|'voice', mediaUrl, mediaName?, mediaSize?, voiceSeconds? }
  send: (sessionId, payload) => request.post(`/chat/${sessionId}/messages`, typeof payload === 'string' ? { content: payload } : payload),
  // 聊天媒体上传（kind=image/file/voice，返回 { url, name, size }）：
  // FormData + onUploadProgress 进度回调；大文件放宽到 120s（默认 15s 超时会掐断上传）
  upload: (file, kind, onProgress) => {
    const fd = new FormData()
    fd.append('file', file)
    fd.append('kind', kind)
    return request.post('/chat/media', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000,
      onUploadProgress: (e) => { if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100)) }
    })
  },
  // 商户端（merchant:manage）
  merchant: {
    sessions: (page = 1, size = 20) => request.get('/merchant/center/chat/sessions', { params: { page, size } }),
    unread: () => request.get('/merchant/center/chat/unread'),
    messages: (sessionId, afterId = 0) => request.get(`/merchant/center/chat/${sessionId}/messages`, { params: { afterId } }),
    send: (sessionId, payload) => request.post(`/merchant/center/chat/${sessionId}/messages`, typeof payload === 'string' ? { content: payload } : payload),
    upload: (file, kind, onProgress) => {
      const fd = new FormData()
      fd.append('file', file)
      fd.append('kind', kind)
      return request.post('/merchant/center/chat/media', fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: 120000,
        onUploadProgress: (e) => { if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100)) }
      })
    }
  }
}
// ===== 商户中心：工作台汇总 / 资金结算 / 店铺资料 =====
export const merchantCenterApi = {
  summary: () => request.get('/merchant/center/summary'),
  finance: () => request.get('/merchant/center/finance'),
  updateContact: (d) => request.put('/merchant/center/contact', d)
}

// ===== 广告流量推广（商户购买流量 → 钱包冻结 → 点击计费 → 结算退回） =====
export const adApi = {
  // 商户端：创建/支付/暂停/恢复/终止/列表/总览
  create: (d) => request.post('/ad/campaigns', d),
  pay: (campaignId) => request.post(`/ad/campaigns/${campaignId}/pay`),
  pause: (campaignId) => request.post(`/ad/campaigns/${campaignId}/pause`),
  resume: (campaignId) => request.post(`/ad/campaigns/${campaignId}/resume`),
  terminate: (campaignId) => request.post(`/ad/campaigns/${campaignId}/terminate`),
  my: () => request.get('/ad/campaigns/my'),
  stats: () => request.get('/ad/campaigns/stats'),
  // 商城侧：广告位商品（公开）
  slots: (limit = 3) => request.get('/ad/slots', { params: { limit } }),
  // 曝光/点击上报（公开；click 按 CPC 计费）
  track: (campaignId, type) => request.post('/ad/track', { campaignId, type })
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
  track: (event) => request.post('/merchant/traffic/events', event, { silent: true }),
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
    }, { silent: true })
  }
}

// ===== 商品推荐（公开浏览；个性化仅在 analytics 授权下生效，否则回退热度榜） =====
export const recommendApi = {
  home: (limit = 8) => request.get('/recommendations/home', { params: { limit }, silent: true }),
  similar: (prodId, limit = 6) => request.get(`/recommendations/similar/${prodId}`, { params: { limit }, silent: true })
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
  communicationReviewLearningCandidate: (id, payload) => request.post('/admin/customer-communications/learning-candidates/' + encodeURIComponent(id) + '/review', payload),
  communicationCandidateAiSuggest: (id) => request.post('/admin/customer-communications/learning-candidates/' + encodeURIComponent(id) + '/ai-suggest'),
  outboundModelConfigList: () => request.get('/admin/outbound-model-config'),
  outboundModelConfigUpsert: (payload) => request.post('/admin/outbound-model-config', payload),
  outboundModelConfigToggle: (key, on) => request.post('/admin/outbound-model-config/toggle', { key, on }),
  outboundModelConfigTest: (payload) => request.post('/admin/outbound-model-config/test', payload),
  outboundModelInvoke: (payload) => request.post('/admin/outbound-model-config/invoke', payload),
  outboundModelAiSuggest: (question) => request.post('/admin/outbound-model-config/ai-suggest', { question }),
  aiApiKeysList: () => request.get('/admin/ai-api-keys'),
  aiApiKeyCreate: (payload) => request.post('/admin/ai-api-keys', payload),
  aiApiKeyDisable: (id) => request.post(`/admin/ai-api-keys/${encodeURIComponent(id)}/disable`),
  supportIntentStats: () => request.get('/support/intent/stats'),
  supportIntentAnalyze: (payload) => request.post('/support/intent/analyze', payload),
  // AI 备用链路状态（SupportController /support/fallback/status，admin:all）
  fallbackStatus: () => request.post('/support/fallback/status'),
  // 动态系统配置（AdminConfigController；set/refresh 为 query 参数而非 body）
  configList: () => request.get('/admin/config/list'),
  configSet: (cfgKey, cfgValue) => request.post('/admin/config/set', null, { params: { cfgKey, cfgValue } }),
  configRefresh: () => request.post('/admin/config/refresh')
}

// ===== 营销中台：统一活动系统（领券/促销/激励/商户报名） =====
export const campaignApi = {
  // 用户端
  list: (tab) => request.get('/campaign/list', { params: tab && tab !== 'all' ? { tab } : {} }),
  detail: (id) => request.get(`/campaign/detail/${id}`),
  claim: (id) => request.post(`/campaign/${id}/claim`),
  join: (id) => request.post(`/campaign/${id}/join`),
  myCoupons: () => request.get('/campaign/my/coupons'),
  myParticipations: () => request.get('/campaign/my/participations'),
  // 结算可用券试算（金额/行业由服务端按购物车重算）
  quoteCoupons: (cartIds) => request.post('/campaign/coupons/quote', { cartIds }),
  // 商户端（报名平台活动 + 激励领奖）
  merchantList: () => request.get('/campaign/merchant/list'),
  merchantJoin: (id) => request.post(`/campaign/merchant/${id}/join`),
  merchantClaimReward: (id) => request.post(`/campaign/merchant/${id}/claim-reward`),
  // 平台管理端（上架/下架/CRUD）
  adminPage: (params) => request.get('/admin/campaign/page', { params }),
  adminDetail: (id) => request.get(`/admin/campaign/${id}`),
  adminCreate: (data) => request.post('/admin/campaign', data),
  adminUpdate: (id, data) => request.put(`/admin/campaign/${id}`, data),
  adminToggle: (id, status) => request.post(`/admin/campaign/${id}/status`, null, { params: { status } }),
  adminDelete: (id) => request.delete(`/admin/campaign/${id}`)
}

export const membershipApi = {
  my: () => request.get('/membership/my'),
  // 单条积分流水详情（按 logId 直查，不受"最近 20 条"窗口限制）
  pointLog: (logId) => request.get(`/membership/points/log/${logId}`),
  // 积分抵现试算：返回 { points 实际抵扣积分, amount 抵扣金额, maxAmount 本单上限, balance 积分余额 }
  redeemQuote: (points, orderAmount) => request.get('/membership/redeem-quote', { params: { points, orderAmount } })
}

// ===== 会员订阅（订阅计划 / 购买 / 我的订阅 / 取消） =====
export const subscriptionApi = {
  // 订阅计划列表：[{planId, planName, planCode, priceCents, durationDays, autoRenew, discountLabel, sort}]
  plans: () => request.get('/subscription/plans'),
  // 购买订阅：{planId} → {subId, planId, startTime, endTime, status}
  purchase: (planId) => request.post('/subscription/purchase', { planId }),
  // 我的当前订阅：{subId, planId, startTime, endTime, status, autoRenew} | null
  my: () => request.get('/subscription/my'),
  // 取消订阅（到期后不再自动续费）
  cancel: () => request.post('/subscription/cancel')
}

// ===== 关于我们 · 加入我们（求职 / 渠道合作） =====
export const joinApi = {
  // 提交申请：{applyType:'job'|'partner', position, companyName, contactName, phone, email, city, experience, message}
  apply: (payload) => request.post('/join/apply', payload),
  // 我的申请（含状态、审核备注）
  my: () => request.get('/join/my'),
  // 运营端列表（可按类型/状态筛选）
  adminList: (params) => request.get('/join/admin/list', { params }),
  // 运营端审核：{approved, remark}
  review: (id, payload) => request.post(`/join/admin/review/${id}`, payload)
}

// ===== 价格智能监控 / 价格情报台 =====
export const priceMonitorApi = {
  // 价格走势 + 日K + 区间高低价（days 7-90）
  trend: (prodId, days = 30) => request.get(`/price-monitor/trend/${prodId}`, { params: { days } }),
  // 首页每日行情：有调价留痕的在售商品
  marketProducts: (limit = 12) => request.get('/price-monitor/market/products', { params: { limit } }),
  // 五维价格因子看板（按商品计算：市场情绪/上游供应链/基础原材料/国际化/政治政策）
  // lang=zh|en 让后端本地化 summary / systematicAnalysis.conclusion 等文案
  factors: (prodId, lang = 'zh') => request.get(`/price-monitor/factors/${prodId}`, { params: { lang } }),
  // 规则引擎调价建议
  analysis: (prodId) => request.get(`/price-monitor/analysis/${prodId}`),
  // AI 实时分析（本地 7B 链路含模型冷加载/切换，需放宽超时；未配置外部模型时后端回退规则引擎，source=RULE；lang=en 返回英文规则文案）
  aiAnalysis: (prodId, lang = 'zh') => request.get(`/price-monitor/ai-analysis/${prodId}`, { params: { lang }, timeout: 180000 }),
  // 因果问答：{ prodId, question, lang }（本地模型推理可能 10-30s，放宽超时）
  ask: (prodId, question, lang = 'zh') => request.post('/price-monitor/ask', { prodId, question, lang }, { timeout: 180000 }),
  // 市场资讯
  news: (category, limit = 20) => request.get('/price-monitor/news', { params: { category: category || undefined, limit } }),
  // 商户价格总览
  merchantOverview: () => request.get('/price-monitor/merchant/overview'),
  // 管理端
  adminAnomalies: (days = 30, limit = 50) => request.get('/admin/price-monitor/anomalies', { params: { days, limit } }),
  adminBenchmarks: () => request.get('/admin/price-monitor/benchmarks'),
  // 预警订阅
  myAlerts: () => request.get('/price-monitor/my/alerts'),
  subscribe: (payload) => request.post('/price-monitor/alerts', payload),
  cancelAlert: (id) => request.delete(`/price-monitor/alerts/${id}`),
  // 商品详情回显当前用户订阅（后端 GET /price-monitor/product/{id}/alerts）
  productAlerts: (prodId, config) => request.get(`/price-monitor/product/${prodId}/alerts`, config),
  // 管理端手动刷新行业基准（POST /admin/price-monitor/refresh，返回行业数）
  refresh: () => request.post('/admin/price-monitor/refresh')
}

// ===== 设计工具对接中台（微服务 lanlink-design-service，经网关 /api/design/**）=====
// 统一适配器模型：tool 取值 figma / canva / mock；token 经 X-Design-Token 头携带 OAuth 令牌
export const designApi = {
  // 适配器能力矩阵
  tools: () => request.get('/design/tools'),
  // OAuth 2.0 授权
  authUrl: (tool, state) => request.get(`/design/auth/${tool}/url`, { params: { state } }),
  exchangeToken: (tool, code) => request.post(`/design/auth/${tool}/token`, { code }),
  refreshToken: (tool, refreshToken) => request.post(`/design/auth/${tool}/refresh`, { refreshToken }),
  // 设计文件 / 组件 / 令牌 / 预览 / 增量同步（≤3s）
  designFile: (tool, fileKey, token) => request.get(`/design/${tool}/files/${fileKey}`, { headers: { 'X-Design-Token': token } }),
  components: (tool, fileKey, token, componentIds = []) => request.post(`/design/${tool}/files/${fileKey}/components`, { componentIds }, { headers: { 'X-Design-Token': token } }),
  tokens: (tool, fileKey, token) => request.get(`/design/${tool}/files/${fileKey}/tokens`, { headers: { 'X-Design-Token': token } }),
  preview: (tool, fileKey, token, nodeId) => request.get(`/design/${tool}/files/${fileKey}/preview`, { params: { nodeId }, headers: { 'X-Design-Token': token } }),
  sync: (tool, fileKey, token, sinceVersion) => request.get(`/design/${tool}/files/${fileKey}/sync`, { params: { sinceVersion }, headers: { 'X-Design-Token': token } }),
  // Canva 模板库 / 编辑器嵌入 / 成果回传（闭环）
  templates: (tool, token, query = {}) => request.post(`/design/${tool}/templates`, query, { headers: { 'X-Design-Token': token } }),
  embed: (tool, token, designId) => request.post(`/design/${tool}/embed`, { designId }, { headers: { 'X-Design-Token': token } }),
  callback: (tool, token, designId, exportUrl) => request.post(`/design/${tool}/callback`, { designId, exportUrl }, { headers: { 'X-Design-Token': token } }),
  // 前端作品资源库（21st.dev 风格精选）：多维组合筛选 + 关键词检索
  gallerySearch: (query = {}) => request.post('/design/gallery/search', query),
  galleryDetail: (caseId) => request.get(`/design/gallery/${caseId}`),
  galleryFacets: () => request.get('/design/gallery/facets'),
  // 多格式代码导出（REACT/VUE/HTML/CSS）+ 设计令牌自动映射
  exportCode: (tool, fileKey, componentId, token, format) => request.post('/design/code/export', { tool, fileKey, componentId, token, format })
}

// ===== Vercel Workflow 服务（商品批量导入等长任务）=====
// 独立 Next.js 应用（仓库内 workflow/ 子目录），基址由 VITE_WORKFLOW_URL 配置。
// 该服务用浏览器原生 fetch 调用，不复用 Spring Boot 的 axios request（不同域、无 Cookie）。
const WORKFLOW_BASE = import.meta.env.VITE_WORKFLOW_URL || 'http://localhost:3000'
export const workflowApi = {
  // 启动商品批量导入工作流：payload = { format: 'json' | 'csv', data: 数组 或 CSV 文本 }
  startProductImport: async (payload) => {
    const res = await fetch(`${WORKFLOW_BASE}/api/import`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    return res.json()
  },
  // 查询工作流运行状态：{ runId, status, result?, error? }
  importStatus: async (runId) => {
    const res = await fetch(`${WORKFLOW_BASE}/api/import/${encodeURIComponent(runId)}`)
    return res.json()
  },
}
