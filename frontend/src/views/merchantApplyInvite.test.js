import { describe, expect, it } from 'vitest'
import { readInvitePrefill } from './merchantApplyInvite'
import router from '../router/index.js'

describe('readInvitePrefill 商家入驻邀约预填', () => {
  it('有 invite：trim + 大写归一后切到邀约并预填码', () => {
    expect(readInvitePrefill({ invite: '  inv-2026abc  ' })).toEqual({
      joinType: '邀约',
      joinCode: 'INV-2026ABC'
    })
  })

  it('无 invite、空串或纯空白时返回 null', () => {
    expect(readInvitePrefill({})).toBeNull()
    expect(readInvitePrefill(undefined)).toBeNull()
    expect(readInvitePrefill({ invite: '' })).toBeNull()
    expect(readInvitePrefill({ invite: '   ' })).toBeNull()
  })

  it('数组 query 取首个非空值并归一化', () => {
    expect(readInvitePrefill({ invite: ['', ' inv-array-1 '] })).toEqual({
      joinType: '邀约',
      joinCode: 'INV-ARRAY-1'
    })
    expect(readInvitePrefill({ invite: [] })).toBeNull()
  })
})

describe('merchant 入驻路由兼容旧落地链接', () => {
  it('/merchant-apply 解析到 merchant 且保留 invite query 与 auth meta', () => {
    const resolved = router.resolve('/merchant-apply?invite=INV2026ABC')
    expect(resolved.name).toBe('merchant')
    expect(resolved.query.invite).toBe('INV2026ABC')
    expect(resolved.meta.auth).toBe(true)
  })
})
