import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./request', () => {
  const request = { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }
  return { default: request, fetchCsrfToken: vi.fn() }
})

import request from './request'
import { logisticsApi } from './index'

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
})
