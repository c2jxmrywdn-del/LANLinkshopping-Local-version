import { afterEach, describe, expect, it } from 'vitest'
import { resolveStorageUrl } from './storageUrl'

const ORIGINAL_BASE = import.meta.env.VITE_STORAGE_PUBLIC_BASE

afterEach(() => {
  import.meta.env.VITE_STORAGE_PUBLIC_BASE = ORIGINAL_BASE
})

describe('resolveStorageUrl', () => {
  it('为 /products/ 相对路径拼接 VITE_STORAGE_PUBLIC_BASE 公开读取基址', () => {
    import.meta.env.VITE_STORAGE_PUBLIC_BASE = 'http://localhost:9000/products'
    expect(resolveStorageUrl('/products/1001.webp')).toBe('http://localhost:9000/products/1001.webp')
    expect(resolveStorageUrl('/products/ind/2001.webp')).toBe('http://localhost:9000/products/ind/2001.webp')
  })

  it('基址末尾带斜杠时不产生双斜杠', () => {
    import.meta.env.VITE_STORAGE_PUBLIC_BASE = 'http://localhost:9000/products/'
    expect(resolveStorageUrl('/products/1001.webp')).toBe('http://localhost:9000/products/1001.webp')
  })

  it('未配置 VITE_STORAGE_PUBLIC_BASE 时原样返回（回退 Vite public）', () => {
    // 与 || '' 兜底等价的空值形态：build 期未注入时运行时为 undefined，测试经 process.env
    // 代理只能赋字符串（undefined 会被强转成 'undefined'），故用空串覆盖同一 falsy 分支
    import.meta.env.VITE_STORAGE_PUBLIC_BASE = ''
    expect(resolveStorageUrl('/products/1001.webp')).toBe('/products/1001.webp')
  })

  it('绝对 URL 原样透传（本存储域与外域 CDN）', () => {
    expect(resolveStorageUrl('http://localhost:9000/products/1001.webp'))
      .toBe('http://localhost:9000/products/1001.webp')
    expect(resolveStorageUrl('https://cdn.example.com/p/x.webp?v=2#top'))
      .toBe('https://cdn.example.com/p/x.webp?v=2#top')
    expect(resolveStorageUrl('HTTPS://CDN.EXAMPLE.COM/P/X.WEBP'))
      .toBe('HTTPS://CDN.EXAMPLE.COM/P/X.WEBP')
  })

  it('/uploads/ 相对路径复用 normalizeUploadUrl 语义补 /api 前缀', () => {
    expect(resolveStorageUrl('/uploads/license.jpg')).toBe('/api/uploads/license.jpg')
    expect(resolveStorageUrl('/api/uploads/license.jpg')).toBe('/api/uploads/license.jpg')
  })

  it('其他相对路径与 data: 前端静态资源原样返回', () => {
    expect(resolveStorageUrl('/img/hero.webp')).toBe('/img/hero.webp')
    expect(resolveStorageUrl('data:image/svg+xml;utf8,abc')).toBe('data:image/svg+xml;utf8,abc')
    expect(resolveStorageUrl('products/1001.webp')).toBe('products/1001.webp')
  })

  it('空值与非字符串原样返回，保留组件 v-if/占位语义', () => {
    expect(resolveStorageUrl(null)).toBeNull()
    expect(resolveStorageUrl(undefined)).toBeUndefined()
    expect(resolveStorageUrl('')).toBe('')
    expect(resolveStorageUrl(123)).toBe(123)
  })

  it('幂等：解析结果再次解析不变（组件内与调用方双重兜底安全）', () => {
    import.meta.env.VITE_STORAGE_PUBLIC_BASE = 'http://localhost:9000/products'
    const once = resolveStorageUrl('/products/1001.webp')
    expect(resolveStorageUrl(once)).toBe(once)
    expect(resolveStorageUrl(resolveStorageUrl('/uploads/a.jpg'))).toBe('/api/uploads/a.jpg')
  })
})
