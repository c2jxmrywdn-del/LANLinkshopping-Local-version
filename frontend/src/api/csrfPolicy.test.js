import { describe, expect, it } from 'vitest'
import { isCsrfProtectedMutation } from './csrfPolicy'

describe('CSRF request policy', () => {
  it('protects writes to user/account endpoints', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/user/email-code' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'put', url: '/user/profile?source=settings' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'delete', url: '/user/address/10' })).toBe(true)
  })

  it('protects administrator marketing email writes', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/admin/marketing-email/send' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/admin/marketing-email' })).toBe(true)
  })

  it('protects material upload and bind writes, leaves relay download alone', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/attachment/upload' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/attachment/bind' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'delete', url: '/attachment/AT20261104A1/content' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/attachment/AT20261104A1/content' })).toBe(false)
  })

  it('does not add CSRF requirements to reads or preflight requests', () => {
    for (const method of ['get', 'head', 'options']) {
      expect(isCsrfProtectedMutation({ method, url: '/user/profile' })).toBe(false)
      expect(isCsrfProtectedMutation({ method, url: '/admin/marketing-email/send' })).toBe(false)
    }
  })

  it('covers all admin writes under /admin/** (A11: outbound-model-config / kb ingest / ab admin)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/admin/audit/export' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/admin/outbound-model-config' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/admin/kb/ingest' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/admin/ab/1' })).toBe(true)
  })

  it('covers merchant traffic events and price alert subscriptions', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/merchant/traffic/events' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/price-monitor/alerts' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'delete', url: '/price-monitor/alerts/12' })).toBe(true)
  })

  it('protects product image upload (P1-5, mirrors backend WebConfig /product/image)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/product/image' })).toBe(true)
    // 最小面：/product 域其余公开浏览端点（page/detail）不受影响
    expect(isCsrfProtectedMutation({ method: 'get', url: '/product/page' })).toBe(false)
  })

  it('protects product publish and merchant product edits (P0-2 长尾)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/product/publish' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/product/my/update/123' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/product/my/status/123' })).toBe(true)
    // 公开浏览不受影响
    expect(isCsrfProtectedMutation({ method: 'get', url: '/product/detail/123' })).toBe(false)
  })

  it('protects the whole merchant domain including center/refund/chat (P0-2 长尾)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/merchant/apply' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/merchant/apply-upload' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/merchant/license' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/merchant/tax-proof' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'put', url: '/merchant/center/contact' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'put', url: '/merchant/center/bank-account' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/merchant/center/order/refund/approve/ORD1' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/merchant/center/chat/S1/messages' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/merchant/center/chat/media' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/merchant/rfq/RFQ1/quote' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/merchant/marketing-image/tasks' })).toBe(true)
    // GET 浏览不受影响
    expect(isCsrfProtectedMutation({ method: 'get', url: '/merchant/center/summary' })).toBe(false)
  })

  it('protects cart writes (P0-2 长尾)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/cart/add' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/cart/quantity/12' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/cart/checked/12' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'delete', url: '/cart/12' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/cart/list' })).toBe(false)
  })

  it('protects campaign claim/join and coupon writes, leaves public browsing alone (P0-2 长尾)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/campaign/9/claim' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/campaign/9/join' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/campaign/coupons/batch-claim' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/campaign/coupons/quote' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/campaign/merchant/9/claim-reward' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/campaign/list' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/campaign/detail/9' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/campaign/for-product/9' })).toBe(false)
  })

  it('protects membership checkin, subscription purchase/cancel, task claim (P0-2 长尾)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/membership/checkin' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/membership/checkin/status' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/subscription/purchase' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/subscription/cancel' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/subscription/plans' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/task/claim' })).toBe(true)
  })

  it('protects credit-term apply/repay/adjust/settlement writes (P0-2 长尾)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/credit-term/apply' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/credit-term/bills/1/repay' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/credit-term/adjust' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/credit-term/adjustments/1/withdraw' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/credit-term/calculator/trial' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/credit-term/settlement/1/pay' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/credit-term/admin/repayments/1/confirm' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/credit-term/overview' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/credit-term/bills/1' })).toBe(false)
  })

  it('protects join application and ad campaign merchant writes, leaves anonymous ad track alone (P0-2 长尾)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/join/apply' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/ad/campaigns' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/ad/campaigns/1/pay' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/ad/campaigns/1/terminate' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/ad/track' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/ad/slots' })).toBe(false)
  })

  it('protects chat writes and merchant live-console writes, leaves anonymous live interactions alone (P0-2 长尾)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/chat/session' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/chat/1/messages' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/chat/media' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/live/merchant/create' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/live/merchant/1/pin-product' })).toBe(true)
    // 匿名直播间互动豁免（点赞/弹幕/进出场/点击）
    expect(isCsrfProtectedMutation({ method: 'post', url: '/live/room/1/like' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/live/room/1/comment' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/live/room/1/join' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/live/rooms' })).toBe(false)
  })

  it('protects logistics merchant writes and watchdog, leaves carrier callbacks alone (P0-2 长尾)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/logistics/ship' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/logistics/ship/split' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/logistics/pod/upload' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/logistics/pod/audit/1' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/logistics/transit/1' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/logistics/trace/sync/1' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/logistics/recommend-carriers' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/logistics/watchdog/run-inspection' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/logistics/watchdog/expedite/1' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/logistics/trace/1' })).toBe(false)
    // 渠道回调豁免（承运商服务器推送，无用户会话）
    expect(isCsrfProtectedMutation({ method: 'post', url: '/logistics/callback/kuaidi100/token' })).toBe(false)
  })

  it('protects support ticket submit, intent analyze and fallback status (P0-2 长尾)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/support-ticket/submit' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/support/intent/analyze' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/support/fallback/status' })).toBe(true)
    // 可信代理密钥通道豁免（前端 fetch 直调不走 axios）
    expect(isCsrfProtectedMutation({ method: 'post', url: '/support/ask' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/support/ask-stream' })).toBe(false)
  })

  it('protects logout and social bind/unbind, leaves anonymous auth entries alone (P0-2 长尾)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/auth/logout' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/auth/social/bind/google' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/auth/social/unbind/wechat' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/auth/login' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/auth/register' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/auth/social/callback/google' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/auth/social/sandbox-login' })).toBe(false)
  })

  it('protects user habits report and avatar upload (P0-2 长尾)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/user/habits/report' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/user/avatar' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/user/message/read-all' })).toBe(true)
  })

  it('protects price-monitor ask and alert writes (P0-2 长尾)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/price-monitor/ask' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/price-monitor/alerts' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'delete', url: '/price-monitor/alerts/12' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/price-monitor/trend/12' })).toBe(false)
  })

  it('protects RFQ publish writes (P1-9, mirrors backend WebConfig /rfq/**)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/rfq' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/rfq/accept' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/rfq/quote' })).toBe(true)
    // 读接口不要求 CSRF
    expect(isCsrfProtectedMutation({ method: 'get', url: '/rfq/my' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/rfq/RFQ20261109000001' })).toBe(false)
  })

  it('protects merchant RFQ quote writes (P1-9 S4, mirrors backend /merchant/rfq/**)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/merchant/rfq/RFQ20261109000001/quote' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/merchant/rfq/open' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/merchant/rfq/RFQ20261109000001/quote' })).toBe(false)
  })
  it('protects merchant SKU writes (mirrors backend /product/my/sku/**)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/product/my/sku/100/save' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/product/my/sku/100' })).toBe(false)
  })

  it('protects invite link rotation (mirrors backend /invite/**)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/invite/link/rotate' })).toBe(true)
    // 读接口（取链接/统计）不要求 CSRF
    expect(isCsrfProtectedMutation({ method: 'get', url: '/invite/link' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/invite/stats' })).toBe(false)
  })

  it('protects admin settlement writes (mirrors backend /admin/settlement/**)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/admin/settlement/generate' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/admin/settlement/1/approve' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/admin/settlement/list' })).toBe(false)
  })

  it('leaves anonymous public reporting endpoints outside CSRF (no token for anonymous sessions)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/ab/expose' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/ab/convert' })).toBe(false)
  })

  it('protects merchant exit writes and admin merchant-exit writes (mirrors backend /merchant/exit/** and /admin/merchant-exit/**)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/merchant/exit/request' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/merchant/exit/withdraw' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/admin/merchant-exit/1/approve' })).toBe(true)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/admin/merchant-exit/run-notice' })).toBe(true)
    // 读接口不要求 CSRF
    expect(isCsrfProtectedMutation({ method: 'get', url: '/merchant/exit/gate' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'get', url: '/admin/merchant-exit/list' })).toBe(false)
  })
})
