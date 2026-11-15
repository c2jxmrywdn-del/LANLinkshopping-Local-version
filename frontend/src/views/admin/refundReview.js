/**
 * P0-C 管理端退款平台复核：纯逻辑与错误文案映射（与 AdminPayments.vue 同目录，便于单测）。
 *
 * 背景：退款出款已收口到「平台复核」这一唯一运营入口——
 *   POST /admin/refund/review/order/{orderNo}/approve|reject（链A 普通订单）
 *   POST /admin/refund/review/rma/{rmaNo}/approve|reject|reopen（链B RMA 售后单）
 * 旧的一步式 `POST /payment/refund` 在无复核凭证/凭证已用时会返回 403/409；
 * 因此前端必须把 403/409 翻译成"走平台复核 / 刷新重新复核"的可行动提示，而不是"网络异常"。
 */

/** 可提交平台复核的订单退款状态（与后端 OrderRmaService.PLATFORM_REVIEWABLE_ORDER_STATUS 同源）。 */
export const REFUND_REVIEWABLE_STATUSES = ['merchant_approved', 'shipping_back', 'received', 'partial']

/** 退款状态展示文案（管理端列表/弹窗共用，避免各页面口径漂移）。 */
export const REFUND_STATUS_TEXT = {
  none: '无退款',
  applied: '退款申请中 · 待商户初审',
  merchant_approved: '商户初审通过 · 待平台复核',
  shipping_back: '买家已寄回 · 待验货',
  received: '仓库已收货 · 待验货',
  dispute: '验货异常 · 协商中',
  rejected: '已驳回',
  processing: '退款处理中',
  partial: '已部分退款',
  success: '已全额退款'
}

export function refundStatusText(status) {
  return REFUND_STATUS_TEXT[String(status || 'none')] || String(status || '—')
}

/** 当前订单退款是否处于「可平台复核」状态（此时才展示复核按钮）。 */
export function isPlatformReviewable(record) {
  return !!record && REFUND_REVIEWABLE_STATUSES.includes(String(record.refundStatus || ''))
}

/**
 * 决定本次复核走哪条链：
 * - 订单存在 currentRmaNo（当前活跃 RMA 单号）→ 链B，必须走 RMA 复核（否则售后单状态与审批链不闭环）；
 * - 否则 → 链A 普通订单复核。
 * @returns {{scope:'order'|'rma', orderNo:string, rmaNo:string|null, id:string}}
 */
export function resolveRefundReviewTarget(record) {
  const orderNo = String(record?.orderNo || '')
  const rmaNo = record?.currentRmaNo ? String(record.currentRmaNo) : null
  return rmaNo
    ? { scope: 'rma', orderNo, rmaNo, id: rmaNo }
    : { scope: 'order', orderNo, rmaNo: null, id: orderNo }
}

/**
 * 组装复核请求体（完全匹配后端 RefundReviewRequest { amount, remark, reason }）：
 * - amount 为空表示"按剩余可退金额全额复核"（服务端默认），因此不传 0；
 * - 通过用 remark 写复核意见；驳回用 reason 写驳回原因（展示给买家）。
 */
export function buildReviewPayload({ action = 'approve', amount = null, remark = '' } = {}) {
  const payload = {}
  const amt = amount === '' || amount === null || amount === undefined ? null : Number(amount)
  if (amt !== null && Number.isFinite(amt) && amt > 0) payload.amount = amt
  const text = String(remark || '').trim()
  if (text) {
    if (action === 'reject') payload.reason = text
    else payload.remark = text
  }
  return payload
}

/** 从 axios 错误 / request.js 业务错误中取服务端文案（request.js 业务码错误只保留 message）。 */
export function extractServerMessage(error) {
  const data = error?.response?.data
  if (data && typeof data === 'object' && typeof data.message === 'string' && data.message.trim()) {
    return data.message.trim()
  }
  const msg = error?.message
  return typeof msg === 'string' ? msg.trim() : ''
}

/**
 * 403/409 → 可行动提示。
 * 注意：后端业务异常走 GlobalExceptionHandler 返回 HTTP 200 + body.code（403/409），
 * request.js 只把 body.message 包成 Error 抛出，因此这里以**服务端文案关键词**为主判据，
 * 以真实 HTTP status 为兜底（权限拦截/网关场景）。
 * @param {unknown} error
 * @param {'approve'|'reject'|'reopen'} action
 * @returns {string} 给运营看的中文提示
 */
export function explainRefundReviewError(error, action = 'approve') {
  const msg = extractServerMessage(error)
  const status = Number(error?.response?.status || 0)
  const has = (s) => msg.includes(s)

  // 409：复核凭证已被消费 / 并发
  if (has('凭证已使用') || has('凭证已被并发使用') || has('凭证已作废') || has('重复出款')) {
    return '平台复核凭证已被使用（可能已出款或被并发处理），本次不会重复出款。请刷新列表后重新发起复核。'
  }
  // 403：驳回终态 / 缺凭证 / 金额不符
  if (has('已被驳回')) {
    return '该退款已被驳回，不能直接出款；如买家有异议，请让买家提交售后工单申诉，再由平台「重开复核」。'
  }
  if (has('缺少平台复核') || has('请先提交平台复核')) {
    return '缺少平台复核凭证，禁止直接出款。请按「商户初审 → 平台复核通过」两步操作（出款由复核通过自动触发）。'
  }
  if (has('商户初审')) {
    return '该退款尚未完成商户初审，暂不能平台复核；请先由商户完成初审（或完成退货验货）后再复核。'
  }
  if (has('金额') && (has('不一致') || has('超出'))) {
    return '复核金额与可退余额/已锁定金额不一致，请刷新订单后按最新可退余额重新复核。'
  }
  // 409：状态变化
  if (has('状态已变化') || has('已被其他运营处理') || has('不可平台复核') || has('不可平台驳回')) {
    return '退款状态已变化或当前状态不可复核，请刷新列表后重试（不要重复点击出款）。'
  }
  if (has('已退款') || has('已结案')) {
    return '该退款已结案/已全额退款，无需再次复核。'
  }
  // 其它出款守卫拒绝：保留服务端原始原因（比通用文案具体），并提示正确流程
  if (has('禁止出款') || has('不可出款')) {
    return '服务端出款守卫已拦截本次操作：' + msg + '。请按「商户初审 → 平台复核」流程操作，不要绕过复核直接出款。'
  }
  // HTTP 状态兜底
  if (status === 403) {
    return '无权操作或状态不允许（403）：请确认使用平台运营账号，且该退款已完成商户初审；不要再使用旧的一步式退款入口。'
  }
  if (status === 409) {
    return '退款状态冲突（409）：该退款可能已被处理或状态已变化，请刷新列表后重试。'
  }
  // 网络/服务异常
  if (error?.code === 'ECONNABORTED' || /timeout/i.test(msg)) {
    return '请求超时：退款可能已受理，请先刷新列表/查看审批历史，确认未出款后再重试，避免重复出款。'
  }
  if (!msg) {
    return action === 'approve'
      ? '平台复核失败（未返回原因），请刷新列表确认退款状态后再试。'
      : '操作失败（未返回原因），请刷新列表后重试。'
  }
  // 服务端已有可读业务文案：原样透出（它比通用文案更具体）
  return msg
}

/** 审批历史单行展示文案（双人留痕：MERCHANT 初审 + PLATFORM 复核/驳回/重开）。 */
export function approvalActionText(approval) {
  const stage = approval?.stage === 'MERCHANT' ? '商户' : approval?.stage === 'PLATFORM' ? '平台' : approval?.stage || '—'
  const action = approval?.action === 'APPROVE' ? '通过'
    : approval?.action === 'REJECT' ? '驳回'
      : approval?.action === 'REOPEN' ? '申诉重开' : approval?.action || '—'
  const amount = approval?.amount === null || approval?.amount === undefined ? '' : ` · 金额 ¥${Number(approval.amount).toFixed(2)}`
  const note = approval?.remark ? ` · ${approval.remark}` : ''
  return `${stage}${action}${amount}${note}`
}
