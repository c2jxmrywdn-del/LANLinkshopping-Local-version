import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./request', () => {
  const request = { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }
  return { default: request, fetchCsrfToken: vi.fn() }
})

import request from './request'
import { getInviteLink, getInviteStats, rotateInviteLink } from './invite'

describe('invite API 契约', () => {
  beforeEach(() => vi.clearAllMocks())

  it('三个端点路径与 request.js 实例一致（baseURL 已含 /api）', async () => {
    await getInviteLink()
    expect(request.get).toHaveBeenLastCalledWith('/invite/link')

    await getInviteStats()
    expect(request.get).toHaveBeenLastCalledWith('/invite/stats')

    await rotateInviteLink()
    expect(request.post).toHaveBeenLastCalledWith('/invite/link/rotate', null, undefined)
  })

  it('rotate 的 maxUses 走 query 而不是 body（后端是 @RequestParam）', async () => {
    await rotateInviteLink(5)
    expect(request.post).toHaveBeenLastCalledWith('/invite/link/rotate', null, { params: { maxUses: 5 } })

    const [, body] = request.post.mock.calls[0]
    expect(body).toBeNull()
  })

  it('maxUses=0 原样传 0（后端回落到配置默认/不限）', async () => {
    await rotateInviteLink(0)
    expect(request.post).toHaveBeenLastCalledWith('/invite/link/rotate', null, { params: { maxUses: 0 } })
  })

  it('未传/空值/非数字时不带 maxUses 参数', async () => {
    await rotateInviteLink(undefined)
    expect(request.post).toHaveBeenLastCalledWith('/invite/link/rotate', null, undefined)

    await rotateInviteLink(null)
    expect(request.post).toHaveBeenLastCalledWith('/invite/link/rotate', null, undefined)

    // Number('') === 0，必须显式排除空串，否则会被误传成"0 次"
    await rotateInviteLink('')
    expect(request.post).toHaveBeenLastCalledWith('/invite/link/rotate', null, undefined)

    await rotateInviteLink('abc')
    expect(request.post).toHaveBeenLastCalledWith('/invite/link/rotate', null, undefined)
  })
})
