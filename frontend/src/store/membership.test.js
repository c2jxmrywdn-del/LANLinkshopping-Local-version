import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

// 拦截 API 层：不发起真实请求（先例 store/settings.test.js）
vi.mock('../api', () => ({
  membershipApi: { my: vi.fn() },
  subscriptionApi: { my: vi.fn(), plans: vi.fn(), purchase: vi.fn(), cancel: vi.fn() }
}))
// 登录态开关：公共页在 fetchMe 完成前 logged=false
let logged = false
vi.mock('./user', () => ({
  useUserStore: () => ({ get logged() { return logged } })
}))

import { useMembershipStore } from './membership'
import { membershipApi, subscriptionApi } from '../api'

describe('membership store（双轨口径聚合）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    logged = true // 默认登录态（ensure 才会发请求）；访客场景在用例内单独置 false
    vi.clearAllMocks()
  })

  it('有真实折扣：discountRate 读 level.discountRate，hasDiscount=true', async () => {
    membershipApi.my.mockResolvedValue({
      level: { levelId: 3, levelName: '黄金会员', discountRate: 0.97 },
      rule: { vipEarnMultiplier: 2 },
      subscription: { subId: 9, status: 'active' }
    })
    const store = useMembershipStore()
    await store.ensure()
    expect(store.discountRate).toBe(0.97)
    expect(store.hasDiscount).toBe(true)
    expect(store.hasActiveSub).toBe(true)
    expect(store.vipEarnMultiplier).toBe(2)
    expect(store.checked).toBe(true)
    expect(subscriptionApi.my).not.toHaveBeenCalled() // /membership/my 已带 subscription，无需兜底
  })

  it('接口失败优雅回退 1.0：hasDiscount=false（不展示对比价）', async () => {
    membershipApi.my.mockRejectedValue(new Error('网络错误'))
    subscriptionApi.my.mockRejectedValue(new Error('网络错误'))
    const store = useMembershipStore()
    await store.ensure()
    expect(store.discountRate).toBe(1)
    expect(store.hasDiscount).toBe(false)
    expect(store.hasActiveSub).toBe(false)
    expect(store.checked).toBe(true)
  })

  it('普通会员 1.000：有数据但无折扣，同样不展示对比价', async () => {
    membershipApi.my.mockResolvedValue({ level: { levelId: 1, levelName: '普通会员', discountRate: 1.0 } })
    const store = useMembershipStore()
    await store.ensure()
    expect(store.discountRate).toBe(1)
    expect(store.hasDiscount).toBe(false)
  })

  it('异常数据（rate>1 / NaN）按不可信回退 1.0', async () => {
    membershipApi.my.mockResolvedValue({ level: { discountRate: 1.5 } })
    const store = useMembershipStore()
    await store.ensure()
    expect(store.discountRate).toBe(1)
  })

  it.each([
    ['active', true], ['ACTIVE', true], ['1', true], [1, true], ['valid', true], ['', true], ['未知状态', true],
    ['cancelled', false], ['expired', false], ['3', false], [0, false], ['已取消', false]
  ])('hasActiveSub 状态归一：%s → %s', async (status, expected) => {
    membershipApi.my.mockResolvedValue({ level: { discountRate: 0.95 }, subscription: { status } })
    const store = useMembershipStore()
    await store.ensure()
    expect(store.hasActiveSub).toBe(expected)
  })

  it('ensure 幂等：并发/重复调用只发一次请求', async () => {
    membershipApi.my.mockImplementation(() => new Promise(r => setTimeout(() => r({ level: { discountRate: 0.9 } }), 5)))
    const store = useMembershipStore()
    await Promise.all([store.ensure(), store.ensure(), store.ensure()])
    await store.ensure()
    expect(membershipApi.my).toHaveBeenCalledTimes(1)
  })

  it('未登录跳过请求；登录态恢复后自动补拉', async () => {
    logged = false
    membershipApi.my.mockResolvedValue({ level: { discountRate: 0.93 } })
    const store = useMembershipStore()
    await store.ensure()
    expect(membershipApi.my).not.toHaveBeenCalled()
    logged = true
    await store.ensure()
    expect(membershipApi.my).toHaveBeenCalledTimes(1)
    expect(store.hasDiscount).toBe(true)
  })

  it('setSub 本地同步订阅态', async () => {
    membershipApi.my.mockResolvedValue({ level: { discountRate: 1 } })
    const store = useMembershipStore()
    await store.ensure()
    store.setSub({ status: 'active', planId: 4 })
    expect(store.hasActiveSub).toBe(true)
    store.setSub({ status: 'cancelled' })
    expect(store.hasActiveSub).toBe(false)
  })
})
