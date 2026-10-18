import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import { locale, t, setLocale, switchLocale, initI18nDom } from './index'

// 注入确定性 localStorage 桩：不依赖 jsdom 内置存储实现差异
function installStorage(store = {}) {
  const mem = store
  globalThis.localStorage = {
    getItem: (k) => (k in mem ? mem[k] : null),
    setItem: (k, v) => { mem[k] = String(v) },
    removeItem: (k) => { delete mem[k] },
    clear: () => { for (const k of Object.keys(mem)) delete mem[k] }
  }
  return mem
}

describe('i18n 中英文转换组件', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    installStorage()
    locale.value = 'zh-CN'
    document.documentElement.removeAttribute('data-locale')
    document.documentElement.lang = 'zh-CN'
  })
  afterEach(() => {
    document.body.innerHTML = ''
  })

  // ===== 正常场景 =====
  it('正常：切换到英文后 locale 与 DOM 标记同步变化', () => {
    setLocale('en-US')
    expect(locale.value).toBe('en-US')
    expect(document.documentElement.getAttribute('data-locale')).toBe('en-US')
    expect(document.documentElement.lang).toBe('en-US')
    setLocale('zh-CN')
    expect(locale.value).toBe('zh-CN')
    expect(document.documentElement.getAttribute('data-locale')).toBe('zh-CN')
  })

  it('正常：switchLocale 与 setLocale 等价（组件调用入口）', () => {
    switchLocale('en-US')
    expect(locale.value).toBe('en-US')
    switchLocale('zh-CN')
    expect(locale.value).toBe('zh-CN')
  })

  it('正常：DOM 文本节点被翻译成英文，切回中文可还原', () => {
    document.body.innerHTML = '<div id="x">加入购物车</div>'
    setLocale('en-US')
    expect(document.getElementById('x').textContent).toBe('Add to Cart')
    setLocale('zh-CN')
    expect(document.getElementById('x').textContent).toBe('加入购物车')
  })

  it('正常：属性 placeholder/title/aria-label 一并翻译', () => {
    document.body.innerHTML = '<input id="i" placeholder="搜索" title="搜索"><button aria-label="搜索">x</button>'
    setLocale('en-US')
    expect(document.getElementById('i').getAttribute('placeholder')).toBe('Search')
    expect(document.getElementById('i').getAttribute('title')).toBe('Search')
    expect(document.querySelector('button').getAttribute('aria-label')).toBe('Search')
    setLocale('zh-CN')
    expect(document.getElementById('i').getAttribute('placeholder')).toBe('搜索')
  })

  it('回归：en-US 下属性回写幂等，不会发生 MutationObserver 自触发死循环（页面无响应）', async () => {
    document.body.innerHTML = '<input id="r" placeholder="搜索">'
    setLocale('en-US')
    const el = document.getElementById('r')
    expect(el.getAttribute('placeholder')).toBe('Search')

    const orig = Element.prototype.setAttribute
    let calls = 0
    Element.prototype.setAttribute = function (n, v) { calls++; return orig.call(this, n, v) }
    try {
      // 把属性改回中文源：观察者应翻译一次成英文后收敛；
      // 若 attributes 分支缺少等值判断，setAttribute 会不断产生新的变更记录而无限回写
      el.setAttribute('placeholder', '搜索')
      await new Promise((r) => setTimeout(r, 40))
      expect(calls).toBeLessThanOrEqual(3) // 上限保险：仅允许 1 次译文回写 + 极少量框架写入
      expect(el.getAttribute('placeholder')).toBe('Search')
    } finally {
      Element.prototype.setAttribute = orig
    }
  })

  // ===== 参数传入/插值（边界） =====
  it('边界：t() 支持 {n} 插值', () => {
    setLocale('zh-CN')
    expect(t('acct.profile.bioHelp', { n: 12 })).toBe('剩余可输入 12 字')
    setLocale('en-US')
    expect(t('acct.profile.bioHelp', { n: 12 })).toContain('12 characters')
  })

  it('边界：插值参数缺失时保留占位符而非崩溃', () => {
    setLocale('zh-CN')
    const out = t('acct.profile.bioHelp')
    expect(out).toContain('{n}')
  })

  it('边界：插值参数为 0/空串不崩溃', () => {
    setLocale('zh-CN')
    expect(() => t('acct.profile.bioHelp', { n: 0 })).not.toThrow()
    expect(t('acct.profile.bioHelp', { n: 0 })).toContain('0')
    expect(() => t('acct.profile.bioHelp', { n: '' })).not.toThrow()
  })

  // ===== 异常场景 =====
  it('异常：非法语言回退 zh-CN，不抛错', () => {
    setLocale('xx-YY')
    expect(locale.value).toBe('zh-CN')
    setLocale(null)
    expect(locale.value).toBe('zh-CN')
    setLocale(undefined)
    expect(locale.value).toBe('zh-CN')
  })

  it('异常：未知 key 回退 key 本身', () => {
    expect(t('does.not.exist.at.all')).toBe('does.not.exist.at.all')
  })

  it('异常：英文缺翻译时回退中文而非英文 key', () => {
    setLocale('en-US')
    // zh 有、en 结构缺的 key（如某新增项）应回退中文
    expect(t('acct.title')).toBe('Account Center') // 两边都有
    expect(t('acct.loadFail')).toBe('Failed to load')
    // 不存在的走 key 回退
    expect(t('no.such')).toBe('no.such')
  })

  it('异常：t() 传 null/undefined params 不崩溃', () => {
    expect(() => t('acct.title', null)).not.toThrow()
    expect(() => t('acct.title', undefined)).not.toThrow()
    expect(t('acct.title', null)).toBe('账号中心')
  })

  // ===== 持久化 =====
  it('正常：locale 持久化到 localStorage，initI18nDom 可恢复', () => {
    setLocale('en-US')
    expect(localStorage.getItem('ll_locale')).toBe('en-US')
    locale.value = 'zh-CN'
    initI18nDom()
    expect(locale.value).toBe('en-US') // 恢复持久化值
  })

  it('异常：localStorage 被污染（非法值）时 initI18nDom 不崩溃且保持中文', () => {
    localStorage.setItem('ll_locale', 'garbage-value')
    locale.value = 'zh-CN'
    expect(() => initI18nDom()).not.toThrow()
    expect(locale.value).toBe('zh-CN')
  })

  it('异常：localStorage 完全不可用（隐私模式）时 initI18nDom 不崩溃（回归：启动即崩 bug）', () => {
    // 模拟隐私模式：getItem 直接抛异常
    globalThis.localStorage = {
      getItem: () => { throw new Error('SecurityError: localStorage disabled') },
      setItem: () => { throw new Error('SecurityError') },
      removeItem: () => { throw new Error('SecurityError') },
      clear: () => { throw new Error('SecurityError') }
    }
    locale.value = 'zh-CN'
    expect(() => initI18nDom()).not.toThrow()
    expect(locale.value).toBe('zh-CN')
    // setLocale 写入也不应崩溃
    expect(() => setLocale('en-US')).not.toThrow()
    expect(locale.value).toBe('en-US')
  })
})
