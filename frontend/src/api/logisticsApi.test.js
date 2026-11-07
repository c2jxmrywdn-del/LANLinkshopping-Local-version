import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./request', () => {
  const request = { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }
  return { default: request, fetchCsrfToken: vi.fn() }
})

import request from './request'
import { logisticsApi, trackingApi } from './index'

describe('logisticsApi 商户端与运营后台接口隔离', () => {
  beforeEach(() => vi.clearAllMocks())

  it('商户端 providers/templates 指向 /logistics，不再被后台同名方法覆盖', async () => {
    await logisticsApi.providers()
    expect(request.get).toHaveBeenLastCalledWith('/logistics/providers')

    await logisticsApi.templates(8)
    expect(request.get).toHaveBeenLastCalledWith('/logistics/templates', { params: { providerId: 8 } })
  })

  it('运营后台使用独立的 adminProviders/adminTemplates', async () => {
    await logisticsApi.adminProviders()
    expect(request.get).toHaveBeenLastCalledWith('/admin/logistics/provider/list')

    await logisticsApi.adminTemplates(8)
    expect(request.get).toHaveBeenLastCalledWith('/admin/logistics/template/list', { params: { providerId: 8 } })
  })

  it('两组方法为不同函数引用，避免对象字面量同名覆盖', () => {
    expect(logisticsApi.providers).not.toBe(logisticsApi.adminProviders)
    expect(logisticsApi.templates).not.toBe(logisticsApi.adminTemplates)
  })

  it('工业多包裹拆单 shipSplit 与全景聚合 orderWaybills 端点路径正确', async () => {
    const splitReq = { orderNo: 'ORD123', items: [{ orderItemId: 1, quantity: 2 }] }
    await logisticsApi.shipSplit(splitReq)
    expect(request.post).toHaveBeenLastCalledWith('/logistics/ship/split', splitReq)

    await logisticsApi.orderWaybills('ORD123')
    expect(request.get).toHaveBeenLastCalledWith('/logistics/order-waybills/ORD123')
  })
})

describe('trackingApi 轨迹时间轴/地图与客服异常件看板', () => {
  beforeEach(() => vi.clearAllMocks())

  it('traces 消费既有 GET /logistics/trace/{waybillId} 契约', async () => {
    await trackingApi.traces(42)
    expect(request.get).toHaveBeenLastCalledWith('/logistics/trace/42')
  })

  it('exceptions 指向 /admin/logistics/exception 且 silent（页面自管 404 占位提示）', async () => {
    await trackingApi.exceptions()
    expect(request.get).toHaveBeenLastCalledWith('/admin/logistics/exception', { silent: true })
  })

  it('createTicket 用 waybillId 拼接转工单端点并对编号做 URI 转义', async () => {
    await trackingApi.createTicket(77)
    expect(request.post).toHaveBeenLastCalledWith('/admin/logistics/exception/77/ticket', null, { silent: true })

    await trackingApi.createTicket('LL2026/01')
    expect(request.post).toHaveBeenLastCalledWith('/admin/logistics/exception/LL2026%2F01/ticket', null, { silent: true })
  })
})
