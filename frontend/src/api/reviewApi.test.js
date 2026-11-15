import { describe, it, expect, vi, beforeEach } from 'vitest'

// 拦截 axios 封装层：只断言 reviewApi 新端点的 URL 与参数形态，不发真实请求。
// mock 工厂内不能引用顶层变量（vi.mock 会被提升），句柄挂在全局容器上
const calls = { get: [], post: [] }
vi.mock('./request', () => ({
  default: {
    get: (...a) => { calls.get.push(a); return Promise.resolve({}) },
    post: (...a) => { calls.post.push(a); return Promise.resolve({}) }
  }
}))

import { reviewApi } from './index'

beforeEach(() => { calls.get.length = 0; calls.post.length = 0 })

describe('reviewApi 订单评价端点（t_order_review 新链路）', () => {
  it('submitOrderReview 走 POST /order/{no}/order-review（登录+CSRF 前缀）', async () => {
    await reviewApi.submitOrderReview('ON-9', { orderItemId: 10, rating: 5 })
    expect(calls.post).toContainEqual(['/order/ON-9/order-review', { orderItemId: 10, rating: 5 }])
  })

  it('orderReviewedItems 走 GET /order/{no}/order-reviewed-items', async () => {
    await reviewApi.orderReviewedItems('ON-9')
    expect(calls.get).toContainEqual(['/order/ON-9/order-reviewed-items'])
  })

  it('orderList/orderSummary 挂公开前缀 /product/detail/{id}/order-reviews*', async () => {
    await reviewApi.orderList(500, { page: 1, size: 10 })
    expect(calls.get).toContainEqual(['/product/detail/500/order-reviews', { params: { page: 1, size: 10 } }])
    await reviewApi.orderSummary(500)
    expect(calls.get).toContainEqual(['/product/detail/500/order-reviews/summary'])
  })

  it('merchantOrderReviews 走 GET /merchant/order-reviews（merId 由登录态推导，不带客户端参数）', async () => {
    await reviewApi.merchantOrderReviews({ page: 2, size: 5 })
    expect(calls.get).toContainEqual(['/merchant/order-reviews', { params: { page: 2, size: 5 } }])
  })

  it('旧行级链路四端点保持原样（并存不迁移）', async () => {
    await reviewApi.list(500, { page: 1 })
    expect(calls.get).toContainEqual(['/product/detail/500/reviews', { params: { page: 1 } }])
    await reviewApi.summary(500)
    expect(calls.get).toContainEqual(['/product/detail/500/reviews/summary'])
    await reviewApi.submit('ON-1', { itemId: 10, rating: 4 })
    expect(calls.post).toContainEqual(['/order/ON-1/review', { itemId: 10, rating: 4 }])
    await reviewApi.reviewedItems('ON-1')
    expect(calls.get).toContainEqual(['/order/ON-1/reviewed-items'])
  })
})
