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
    expect(isCsrfProtectedMutation({ method: 'post', url: '/product/publish' })).toBe(false)
  })

  it('leaves anonymous public reporting endpoints outside CSRF (no token for anonymous sessions)', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/ab/expose' })).toBe(false)
    expect(isCsrfProtectedMutation({ method: 'post', url: '/ab/convert' })).toBe(false)
  })
})
