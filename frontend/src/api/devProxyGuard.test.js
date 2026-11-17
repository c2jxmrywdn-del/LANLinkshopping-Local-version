import { describe, expect, it } from 'vitest'
import { isLoopbackAddress, parseAllowedOrigins, proxyViolation } from './devProxyGuard.js'

const loopback = { remoteAddress: '127.0.0.1' }

describe('isLoopbackAddress', () => {
  it('识别 IPv4 / IPv6 / IPv4-mapped 回环', () => {
    expect(isLoopbackAddress('127.0.0.1')).toBe(true)
    expect(isLoopbackAddress('::1')).toBe(true)
    expect(isLoopbackAddress('::ffff:127.0.0.1')).toBe(true)
  })

  it('拒绝局域网与空地址', () => {
    expect(isLoopbackAddress('192.168.1.20')).toBe(false)
    expect(isLoopbackAddress('')).toBe(false)
    expect(isLoopbackAddress(undefined)).toBe(false)
  })
})

describe('parseAllowedOrigins', () => {
  it('按逗号切分并去空白', () => {
    expect(parseAllowedOrigins('http://a:1 , http://b:2')).toEqual(['http://a:1', 'http://b:2'])
  })

  it('空值回落到本地默认两条', () => {
    expect(parseAllowedOrigins('')).toEqual(['http://localhost:5173', 'http://127.0.0.1:5173'])
    expect(parseAllowedOrigins(undefined)).toHaveLength(2)
  })
})

describe('proxyViolation', () => {
  it('放行本机同源请求', () => {
    expect(proxyViolation({ url: '/api/product/list', headers: {}, socket: loopback })).toBeNull()
  })

  it('内部端点返回 404', () => {
    const result = proxyViolation({ url: '/api/internal/ai/tools/search', headers: {}, socket: loopback })
    expect(result).toEqual({ status: 404, message: 'not found' })
  })

  it('后端指标返回 404（带查询串同样命中）', () => {
    const result = proxyViolation({ url: '/api/actuator/prometheus?x=1', headers: {}, socket: loopback })
    expect(result.status).toBe(404)
  })

  it('非回环来源 403', () => {
    const result = proxyViolation({
      url: '/api/support/media/presign',
      headers: { origin: 'http://127.0.0.1:5173' },
      socket: { remoteAddress: '192.168.1.20' }
    })
    expect(result.status).toBe(403)
    expect(result.message).toContain('loopback')
  })

  it('客服链路拒绝非白名单 Origin', () => {
    const result = proxyViolation(
      { url: '/api/support/media/presign', headers: { origin: 'http://evil.example' }, socket: loopback },
      { requireAllowedOrigin: true }
    )
    expect(result).toEqual({ status: 403, message: 'forbidden: origin not allowed' })
  })

  it('客服链路接受白名单 Origin 与无 Origin 的同源请求', () => {
    const allowed = { requireAllowedOrigin: true, allowedOrigins: ['http://127.0.0.1:5173'] }
    expect(
      proxyViolation({ url: '/api/support/ask', headers: { origin: 'http://127.0.0.1:5173' }, socket: loopback }, allowed)
    ).toBeNull()
    expect(proxyViolation({ url: '/api/support/ask', headers: {}, socket: loopback }, allowed)).toBeNull()
  })

  it('通用 /api 代理不校验 Origin（登录等跨域预检依赖后端 CORS）', () => {
    expect(
      proxyViolation({ url: '/api/auth/login', headers: { origin: 'http://localhost:5173' }, socket: loopback })
    ).toBeNull()
  })
})
