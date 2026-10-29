import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  isInternalNews,
  newsLinkLabel,
  newsLinkUrl,
  openNewsLink,
  safeHttpUrl
} from './newsLink'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('safeHttpUrl', () => {
  it('放行 http / https 绝对地址并去掉首尾空白', () => {
    expect(safeHttpUrl('https://www.dcement.com/')).toBe('https://www.dcement.com/')
    expect(safeHttpUrl('http://www.csteelnews.com/news/1.html')).toBe('http://www.csteelnews.com/news/1.html')
    expect(safeHttpUrl('  https://www.gov.cn/  ')).toBe('https://www.gov.cn/')
    expect(safeHttpUrl('HTTPS://WWW.GOV.CN/')).toBe('HTTPS://WWW.GOV.CN/')
  })

  it('拒绝 javascript: / data: 等危险伪协议', () => {
    expect(safeHttpUrl('javascript:alert(1)')).toBe('')
    expect(safeHttpUrl('JavaScript:alert(document.cookie)')).toBe('')
    expect(safeHttpUrl('data:text/html,<script>alert(1)</script>')).toBe('')
    expect(safeHttpUrl('vbscript:msgbox(1)')).toBe('')
  })

  it('拒绝站内相对路径、协议相对地址与空白', () => {
    expect(safeHttpUrl('/price-monitor')).toBe('')
    expect(safeHttpUrl('//evil.example.com/a')).toBe('')
    expect(safeHttpUrl('price-monitor')).toBe('')
    expect(safeHttpUrl('   ')).toBe('')
    expect(safeHttpUrl('https://exa mple.com/')).toBe('')
    expect(safeHttpUrl('http://')).toBe('')
  })

  it('非字符串 / 空值安全返回空串', () => {
    expect(safeHttpUrl(null)).toBe('')
    expect(safeHttpUrl(undefined)).toBe('')
    expect(safeHttpUrl(123)).toBe('')
    expect(safeHttpUrl({ url: 'https://www.gov.cn/' })).toBe('')
  })
})

describe('isInternalNews', () => {
  it('linkType=internal 视为站内资讯', () => {
    expect(isInternalNews({ linkType: 'internal', url: '/price-monitor' })).toBe(true)
    expect(isInternalNews({ linkType: 'INTERNAL' })).toBe(true)
    expect(isInternalNews({ linkType: 'internal' })).toBe(true)
  })

  it('url 以 / 开头的相对路径视为站内资讯（兼容缺 linkType 的历史数据）', () => {
    expect(isInternalNews({ url: '/price-monitor' })).toBe(true)
    expect(isInternalNews({ url: '/product/12' })).toBe(true)
  })

  it('外链、协议相对地址、空值与异常入参均不是站内资讯', () => {
    expect(isInternalNews({ linkType: 'article', url: 'https://www.gov.cn/' })).toBe(false)
    expect(isInternalNews({ url: '//evil.example.com/a' })).toBe(false)
    expect(isInternalNews({ url: '' })).toBe(false)
    expect(isInternalNews({})).toBe(false)
    expect(isInternalNews(null)).toBe(false)
    expect(isInternalNews(undefined)).toBe(false)
    expect(isInternalNews('internal')).toBe(false)
  })
})

describe('newsLinkUrl', () => {
  it('原文链接优先返回', () => {
    expect(newsLinkUrl({ linkType: 'article', url: 'https://www.gov.cn/a.html' }))
      .toBe('https://www.gov.cn/a.html')
  })

  it('原文缺失时回退来源站点首页（source 类型仍以 url 为准）', () => {
    expect(newsLinkUrl({ linkType: 'source', url: 'https://www.dcement.com/', sourceUrl: 'https://www.dcement.com/' }))
      .toBe('https://www.dcement.com/')
  })

  it('url 非法（javascript:/相对路径/空白）时回退 searchUrl', () => {
    const search = 'https://www.bing.com/news/search?q=%E6%B0%B4%E6%B3%A5'
    expect(newsLinkUrl({ linkType: 'search', url: '', searchUrl: search })).toBe(search)
    expect(newsLinkUrl({ linkType: 'search', url: 'javascript:alert(1)', searchUrl: search })).toBe(search)
    expect(newsLinkUrl({ linkType: 'search', url: '   ', searchUrl: search })).toBe(search)
  })

  it('站内资讯返回站内相对路径', () => {
    expect(newsLinkUrl({ linkType: 'internal', url: '/price-monitor' })).toBe('/price-monitor')
    expect(newsLinkUrl({ url: '/product/12' })).toBe('/product/12')
  })

  it('任何链接都缺失时返回空串，不抛错', () => {
    expect(newsLinkUrl({ title: '没有链接' })).toBe('')
    expect(newsLinkUrl({ url: '', searchUrl: 'javascript:alert(1)' })).toBe('')
    expect(newsLinkUrl(null)).toBe('')
    expect(newsLinkUrl(undefined)).toBe('')
    expect(newsLinkUrl('https://www.gov.cn/')).toBe('')
  })
})

describe('openNewsLink', () => {
  it('外链新开页签且带 noopener', () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const router = { push: vi.fn() }
    const item = { linkType: 'article', url: 'https://www.gov.cn/a.html' }

    expect(openNewsLink(item, router)).toBe(true)
    expect(open).toHaveBeenCalledTimes(1)
    expect(open).toHaveBeenCalledWith('https://www.gov.cn/a.html', '_blank', 'noopener')
    expect(router.push).not.toHaveBeenCalled()
  })

  it('站内资讯交给 router.push，不打开新页签', () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const router = { push: vi.fn() }
    const item = { linkType: 'internal', url: '/price-monitor' }

    expect(openNewsLink(item, router)).toBe(true)
    expect(router.push).toHaveBeenCalledTimes(1)
    expect(router.push).toHaveBeenCalledWith('/price-monitor')
    expect(open).not.toHaveBeenCalled()
  })

  it('非法/缺失链接时 no-op', () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const router = { push: vi.fn() }

    expect(openNewsLink({ url: 'javascript:alert(1)' }, router)).toBe(false)
    expect(openNewsLink({ title: '无链接' }, router)).toBe(false)
    expect(openNewsLink(null, router)).toBe(false)
    expect(open).not.toHaveBeenCalled()
    expect(router.push).not.toHaveBeenCalled()
  })
})

describe('newsLinkLabel', () => {
  it('四种 linkType 的中文文案', () => {
    expect(newsLinkLabel({ linkType: 'article' }, false)).toBe('原文')
    expect(newsLinkLabel({ linkType: 'source' }, false)).toBe('来源站点')
    expect(newsLinkLabel({ linkType: 'search' }, false)).toBe('检索原文')
    expect(newsLinkLabel({ linkType: 'internal' }, false)).toBe('站内')
  })

  it('四种 linkType 的英文文案', () => {
    expect(newsLinkLabel({ linkType: 'article' }, true)).toBe('Original')
    expect(newsLinkLabel({ linkType: 'source' }, true)).toBe('Source site')
    expect(newsLinkLabel({ linkType: 'search' }, true)).toBe('Find original')
    expect(newsLinkLabel({ linkType: 'internal' }, true)).toBe('On-site')
  })

  it('缺少 linkType 时按 url 形态推断，完全无链接则返回空串', () => {
    expect(newsLinkLabel({ url: '/price-monitor' }, false)).toBe('站内')
    expect(newsLinkLabel({ url: 'https://www.gov.cn/' }, true)).toBe('Original')
    expect(newsLinkLabel({ title: '无链接' }, false)).toBe('')
    expect(newsLinkLabel(null, false)).toBe('')
  })
})
