/**
 * src/utils/seo.js 单元测试（P1-19 按路由 TDK 注入，CSR 方案）：
 * 覆盖 title / meta[name=description|keywords] / meta[property=og:*] 的写入、
 * {param} 占位与函数求值、路由离开恢复全局兜底、i18n 语言标题委托。
 * index.html 是兜底真源；测试用等价的最小 head 结构模拟其基线，
 * 每个用例通过 vi.resetModules() 重建模块内状态（baseline/current/localeTitle）。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'

const BASELINE_HEAD = '<title>BASE TITLE</title>' +
  '<meta name="description" content="BASE DESC" />' +
  '<meta name="keywords" content="BASE KW" />' +
  '<meta property="og:title" content="BASE OG TITLE" />' +
  '<meta property="og:description" content="BASE OG DESC" />' +
  '<meta property="og:url" content="https://lanlink.cc.cd/" />' +
  '<meta property="og:image" content="https://lanlink.cc.cd/logo-256.png" />' +
  '<meta property="og:type" content="website" />'

function contentOf(key) {
  const el = key.startsWith('og:')
    ? document.head.querySelector(`meta[property="${key}"]`)
    : document.head.querySelector(`meta[name="${key}"]`)
  return el ? el.getAttribute('content') : null
}

beforeEach(() => {
  document.head.innerHTML = BASELINE_HEAD
  document.title = 'BASE TITLE'
  vi.resetModules()
})

const loadSeo = () => import('../utils/seo')

describe('setRouteSeo 注入', () => {
  it('写入 document.title 与 description/keywords/og', async () => {
    const seo = await loadSeo()
    const resolved = seo.setRouteSeo({
      title: '测试页 · LANLinkshopping',
      description: '测试页描述',
      keywords: 'a,b,c',
      og: { 'og:title': '测试页 OG' }
    }, { fullPath: '/mall' })
    expect(document.title).toBe('测试页 · LANLinkshopping')
    expect(contentOf('description')).toBe('测试页描述')
    expect(contentOf('keywords')).toBe('a,b,c')
    expect(contentOf('og:title')).toBe('测试页 OG')
    expect(contentOf('og:description')).toBe('测试页描述')
    expect(resolved.title).toBe('测试页 · LANLinkshopping')
    expect(resolved.og['og:title']).toBe('测试页 OG')
  })

  it('{param} 占位从 route.params 取值', async () => {
    const seo = await loadSeo()
    seo.setRouteSeo({ title: '商品详情 {id} · LANLinkshopping' }, { params: { id: '42' }, fullPath: '/product/42' })
    expect(document.title).toBe('商品详情 42 · LANLinkshopping')
  })

  it('函数字段可用 route 求值，抛错时回退空串（不写该标签）', async () => {
    const seo = await loadSeo()
    seo.setRouteSeo({
      title: r => `商品 ${r.params.id}`,
      description: () => { throw new Error('no data') }
    }, { params: { id: '9' } })
    expect(document.title).toBe('商品 9')
    expect(contentOf('description')).toBe('BASE DESC')
    expect(contentOf('og:description')).toBe('BASE OG DESC')
  })

  it('og:url 默认取当前路由 fullPath', async () => {
    const seo = await loadSeo()
    seo.setRouteSeo({ title: 'T' }, { fullPath: '/venue' })
    expect(contentOf('og:url')).toBe(window.location.origin + '/venue')
  })
})

describe('恢复全局兜底', () => {
  it('resetRouteSeo 还原基线 title 与全部受管 meta', async () => {
    const seo = await loadSeo()
    seo.setRouteSeo({
      title: '临时标题', description: '临时描述', keywords: '临时词',
      og: { 'og:title': '临时OG', 'og:image': 'https://cdn.example.com/x.png' }
    })
    seo.resetRouteSeo()
    expect(document.title).toBe('BASE TITLE')
    expect(contentOf('description')).toBe('BASE DESC')
    expect(contentOf('keywords')).toBe('BASE KW')
    expect(contentOf('og:title')).toBe('BASE OG TITLE')
    expect(contentOf('og:description')).toBe('BASE OG DESC')
    expect(contentOf('og:url')).toBe('https://lanlink.cc.cd/')
    expect(contentOf('og:image')).toBe('https://lanlink.cc.cd/logo-256.png')
  })

  it('基线中不存在的标签：注入时创建，恢复时移除', async () => {
    document.head.innerHTML = '<title>BASE TITLE</title><meta name="description" content="BASE DESC" />'
    const seo = await loadSeo()
    seo.setRouteSeo({ title: 'T', description: 'D', keywords: 'K' })
    expect(contentOf('keywords')).toBe('K')
    seo.resetRouteSeo()
    expect(contentOf('keywords')).toBeNull()
    expect(contentOf('description')).toBe('BASE DESC')
    expect(document.title).toBe('BASE TITLE')
  })

  it('连续注入两个路由不残留上一路由文案', async () => {
    const seo = await loadSeo()
    seo.setRouteSeo({ title: 'A 页标题', description: 'A 描述', keywords: 'A词' })
    seo.setRouteSeo({ title: 'B 页标题', description: 'B 描述', keywords: 'B词' })
    expect(document.title).toBe('B 页标题')
    expect(contentOf('description')).toBe('B 描述')
    expect(contentOf('keywords')).toBe('B词')
  })
})

describe('applyRouteSeo（路由 meta 驱动）', () => {
  it('meta.seo 存在时注入', async () => {
    const seo = await loadSeo()
    seo.applyRouteSeo({ meta: { seo: { title: '活动中心', description: '活动描述' } }, fullPath: '/activity' })
    expect(document.title).toBe('活动中心')
    expect(contentOf('description')).toBe('活动描述')
    expect(contentOf('og:url')).toBe(window.location.origin + '/activity')
  })

  it('meta.seo 缺失（私域路由）时恢复兜底', async () => {
    const seo = await loadSeo()
    seo.applyRouteSeo({ meta: { seo: { title: 'A', description: 'D' } } })
    expect(document.title).toBe('A')
    seo.applyRouteSeo({ meta: {}, fullPath: '/me' })
    expect(document.title).toBe('BASE TITLE')
    expect(contentOf('description')).toBe('BASE DESC')
    expect(seo.getRouteSeo()).toBeNull()
  })

  it('非法入参不抛错', async () => {
    const seo = await loadSeo()
    expect(seo.setRouteSeo(null)).toBeNull()
    expect(seo.applyRouteSeo(null)).toBeNull()
    expect(document.title).toBe('BASE TITLE')
  })
})

describe('i18n 语言标题委托', () => {
  it('无路由级 SEO 时设置语言级标题', async () => {
    const seo = await loadSeo()
    seo.applyLocaleTitle('LANLinkshopping · Connected Commerce')
    expect(document.title).toBe('LANLinkshopping · Connected Commerce')
  })

  it('路由级 SEO 生效期间切换语言保持路由标题，离开后回落语言标题', async () => {
    const seo = await loadSeo()
    seo.setRouteSeo({ title: '路由标题', description: 'D' })
    seo.applyLocaleTitle('EN TITLE')
    expect(document.title).toBe('路由标题')
    seo.resetRouteSeo()
    expect(document.title).toBe('EN TITLE')
  })

  it('空串与非字符串上报不覆盖已登记的语言标题', async () => {
    const seo = await loadSeo()
    seo.applyLocaleTitle('ZH TITLE')
    seo.applyLocaleTitle('')
    seo.applyLocaleTitle(null)
    expect(document.title).toBe('ZH TITLE')
  })
})

describe('toAbsoluteUrl（og:image 用）', () => {
  it('相对路径补 origin，绝对 URL 透传，空值返回空串', async () => {
    const seo = await loadSeo()
    expect(seo.toAbsoluteUrl('/products/a.webp')).toBe(window.location.origin + '/products/a.webp')
    expect(seo.toAbsoluteUrl('https://cdn.example.com/x.png')).toBe('https://cdn.example.com/x.png')
    expect(seo.toAbsoluteUrl('')).toBe('')
    expect(seo.toAbsoluteUrl(null)).toBe('')
  })
})
