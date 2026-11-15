import { describe, it, expect } from 'vitest'
import {
  REFUND_REVIEWABLE_STATUSES,
  isPlatformReviewable,
  resolveRefundReviewTarget,
  buildReviewPayload,
  explainRefundReviewError,
  approvalActionText,
  refundStatusText
} from './refundReview'

/** 模拟 request.js 对业务码错误抛出的 Error（HTTP 200 + body.code=403/409，只剩 message） */
function businessError(message) {
  return new Error(message)
}

/** 模拟真实 HTTP 403/409（权限拦截 / 网关） */
function httpError(status, message) {
  const err = new Error(message || 'Request failed')
  err.response = { status, data: { code: status, message } }
  return err
}

describe('refundReview（P0-C 管理端退款复核纯逻辑）', () => {
  it('可复核状态白名单与后端一致：仅 merchant_approved/shipping_back/received/partial', () => {
    expect(REFUND_REVIEWABLE_STATUSES).toEqual(['merchant_approved', 'shipping_back', 'received', 'partial'])
    expect(isPlatformReviewable({ refundStatus: 'merchant_approved' })).toBe(true)
    expect(isPlatformReviewable({ refundStatus: 'partial' })).toBe(true)
    // applied（待商户初审）/rejected/none 不允许平台复核
    expect(isPlatformReviewable({ refundStatus: 'applied' })).toBe(false)
    expect(isPlatformReviewable({ refundStatus: 'rejected' })).toBe(false)
    expect(isPlatformReviewable({ refundStatus: 'none' })).toBe(false)
    expect(isPlatformReviewable(null)).toBe(false)
  })

  it('链路判定：有 currentRmaNo 走 RMA 复核，否则走订单复核', () => {
    expect(resolveRefundReviewTarget({ orderNo: 'O1', currentRmaNo: 'RMA1' })).toEqual({
      scope: 'rma', orderNo: 'O1', rmaNo: 'RMA1', id: 'RMA1'
    })
    expect(resolveRefundReviewTarget({ orderNo: 'O2', currentRmaNo: null })).toEqual({
      scope: 'order', orderNo: 'O2', rmaNo: null, id: 'O2'
    })
  })

  it('请求体：通过写 remark、驳回写 reason；金额为空表示全额复核（不传 0）', () => {
    expect(buildReviewPayload({ action: 'approve', amount: 100, remark: '核实无误' }))
      .toEqual({ amount: 100, remark: '核实无误' })
    expect(buildReviewPayload({ action: 'reject', remark: '已超售后期' }))
      .toEqual({ reason: '已超售后期' })
    expect(buildReviewPayload({ action: 'approve', amount: '', remark: '  ' })).toEqual({})
    expect(buildReviewPayload({ action: 'approve', amount: -5 })).toEqual({})
  })

  describe('403/409 → 可行动提示（不显示通用网络错误）', () => {
    it('409：复核凭证已使用 → 提示刷新重新复核（不重复出款）', () => {
      const msg = explainRefundReviewError(
        businessError('平台复核凭证已使用，禁止重复出款；如需再次退款请重新复核'))
      expect(msg).toContain('凭证已被使用')
      expect(msg).toContain('刷新')
      expect(msg).not.toContain('网络异常')
    })

    it('409：并发使用凭证 → 提示"本次不会重复出款"', () => {
      const msg = explainRefundReviewError(businessError('平台复核凭证已被并发使用，禁止重复出款'))
      expect(msg).toContain('不会重复出款')
    })

    it('403：退款已被驳回 → 指向"工单申诉 + 重开复核"，而不是让运营硬退', () => {
      const msg = explainRefundReviewError(
        businessError('退款申请已被驳回，禁止出款；如有异议请走售后工单申诉，由平台重开复核'))
      expect(msg).toContain('驳回')
      expect(msg).toContain('申诉')
      expect(msg).toContain('重开复核')
    })

    it('403：缺少平台复核凭证 → 提示"先提交平台复核"而非直接出款', () => {
      const msg = explainRefundReviewError(
        businessError('缺少平台复核通过记录，禁止出款；请先提交平台复核'))
      expect(msg).toContain('平台复核凭证')
    })

    it('403：未完成商户初审 → 提示先完成商户初审/验货', () => {
      const msg = explainRefundReviewError(
        businessError('当前退款状态不可平台复核（applied）：须先完成商户初审'))
      expect(msg).toContain('商户初审')
    })

    it('HTTP 403（权限拦截）→ 明确提示平台运营账号 + 不要走旧一步式入口', () => {
      const msg = explainRefundReviewError(httpError(403, '没有权限执行该操作'))
      expect(msg).toContain('403')
      expect(msg).toContain('平台运营')
      expect(msg).toContain('旧的一步式退款入口')
    })

    it('HTTP 409（网关/状态冲突）→ 提示刷新后重试', () => {
      const msg = explainRefundReviewError(httpError(409, 'Conflict'))
      expect(msg).toContain('409')
      expect(msg).toContain('刷新')
    })

    it('超时：提示先确认是否已出款再重试（避免重复出款）', () => {
      const timeout = new Error('请求超时')
      timeout.code = 'ECONNABORTED'
      const msg = explainRefundReviewError(timeout)
      expect(msg).toContain('超时')
      expect(msg).toContain('避免重复出款')
    })

    it('无服务端文案时给出兜底提示；有具体文案时原样透出', () => {
      expect(explainRefundReviewError(new Error(''))).toContain('未返回原因')
      expect(explainRefundReviewError(businessError('渠道退款未受理，本次复核已回滚，请稍后重新复核')))
        .toContain('渠道退款未受理')
    })
  })

  it('审批历史文案：MERCHANT/PLATFORM × APPROVE/REJECT/REOPEN + 金额/备注', () => {
    expect(approvalActionText({
      stage: 'MERCHANT', action: 'APPROVE', amount: 88, remark: '商户同意'
    })).toBe('商户通过 · 金额 ¥88.00 · 商户同意')
    expect(approvalActionText({ stage: 'PLATFORM', action: 'REJECT', remark: '超时' }))
      .toBe('平台驳回 · 超时')
    expect(approvalActionText({ stage: 'PLATFORM', action: 'REOPEN' })).toBe('平台申诉重开')
  })

  it('状态文案覆盖平台复核新增状态（避免管理端显示"无退款"误导）', () => {
    expect(refundStatusText('merchant_approved')).toContain('平台复核')
    expect(refundStatusText('rejected')).toContain('驳回')
    expect(refundStatusText('none')).toBe('无退款')
  })
})
