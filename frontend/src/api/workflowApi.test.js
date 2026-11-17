import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./request', () => {
  const request = { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }
  return { default: request, fetchCsrfToken: vi.fn() }
})

describe('workflowApi 导入鉴权头（审计 P1-4）', () => {
  let originalFetch

  beforeEach(() => {
    originalFetch = globalThis.fetch
    globalThis.fetch = vi.fn().mockResolvedValue({ json: async () => ({ ok: true }) })
    vi.resetModules()
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
    delete import.meta.env.VITE_WORKFLOW_IMPORT_TOKEN
  })

  it('配置 VITE_WORKFLOW_IMPORT_TOKEN 时，启动与查询都带 X-Import-Token', async () => {
    import.meta.env.VITE_WORKFLOW_IMPORT_TOKEN = 'unit-token-0123456789abcdef'
    const { workflowApi } = await import('./index')

    await workflowApi.startProductImport({ format: 'json', data: [] })
    await workflowApi.importStatus('run-1')

    const [startCall, statusCall] = globalThis.fetch.mock.calls
    expect(startCall[1].headers['X-Import-Token']).toBe('unit-token-0123456789abcdef')
    expect(statusCall[1].headers['X-Import-Token']).toBe('unit-token-0123456789abcdef')
  })

  it('未配置 token 时不伪造该头（由服务端 fail-closed 返回 503/401）', async () => {
    delete import.meta.env.VITE_WORKFLOW_IMPORT_TOKEN
    const { workflowApi } = await import('./index')

    await workflowApi.importStatus('run-2')

    const [statusCall] = globalThis.fetch.mock.calls
    expect(statusCall[1].headers).not.toHaveProperty('X-Import-Token')
  })
})
