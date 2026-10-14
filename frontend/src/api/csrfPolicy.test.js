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

  it('does not add CSRF requirements to reads or preflight requests', () => {
    for (const method of ['get', 'head', 'options']) {
      expect(isCsrfProtectedMutation({ method, url: '/user/profile' })).toBe(false)
      expect(isCsrfProtectedMutation({ method, url: '/admin/marketing-email/send' })).toBe(false)
    }
  })

  it('leaves unrelated admin writes outside the marketing email CSRF mapping', () => {
    expect(isCsrfProtectedMutation({ method: 'post', url: '/admin/audit/export' })).toBe(false)
  })
})
