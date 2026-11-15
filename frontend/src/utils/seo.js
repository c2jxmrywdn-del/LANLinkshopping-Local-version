/**
 * 按路由注入 TDK（title / description / keywords）与 Open Graph。
 *
 * 形态约定（拍板：云上 docker compose + CSR，不做 SSR / 预渲染）：
 * - index.html 保留全局兜底 TDK + og（P1-19 基线），本模块只在运行期做差异化覆盖；
 * - 静态 HTML 对爬虫仍是全局兜底，路由级 TDK 由 CSR 执行后生效；
 * - 私域路由（/me /merchant /admin /cart /checkout 等）不注入，走全局兜底。
 *
 * 基线真源：index.html。首次写入前把 DOM 现状抓成快照，还原时按快照回写或删除，
 * 本模块不复制一份兜底文案常量，避免与 index.html 漂移。
 *
 * 语言协作：i18n 切换语言时原本直接改写 document.title，会把路由级标题打回全局兜底。
 * 这里把标题所有权收口到本模块：i18n 调用 applyLocaleTitle() 上报语言级标题；
 * 路由级 TDK 生效期间保持路由标题，路由离开后回落到语言级标题。
 */

const SITE_NAME = 'LANLinkshopping'
const DEFAULT_OG_IMAGE = 'https://lanlink.cc.cd/logo-256.png'

// 站点级 og 属性（og:site_name / og:locale）不参与路由差异化，由 index.html 常驻
const MANAGED_OG = ['og:title', 'og:description', 'og:url', 'og:image', 'og:type']
const MANAGED_META = ['description', 'keywords', ...MANAGED_OG]

let baseline = null // 首次写入前的 DOM 快照
let localeTitle = null // i18n 上报的语言级标题（全局兜底态使用）
let current = null // 当前生效的路由级 SEO

function doc() {
  return typeof document !== 'undefined' ? document : null
}

function findMeta(key) {
  const d = doc()
  if (!d) return null
  const selector = key.startsWith('og:') ? `meta[property="${key}"]` : `meta[name="${key}"]`
  return d.head.querySelector(selector)
}

function ensureMeta(key) {
  const d = doc()
  if (!d) return null
  let el = findMeta(key)
  if (!el) {
    el = d.createElement('meta')
    if (key.startsWith('og:')) el.setAttribute('property', key)
    else el.setAttribute('name', key)
    d.head.appendChild(el)
  }
  return el
}

function setMetaContent(key, content) {
  const el = ensureMeta(key)
  if (el) el.setAttribute('content', content)
}

function setTitle(title) {
  const d = doc()
  if (d && title) d.title = title
}

/** 首次写入前抓取 DOM 现状，作为还原目标。 */
function captureBaseline() {
  if (baseline) return baseline
  const d = doc()
  const snapshot = { title: d ? d.title : '', metas: {} }
  for (const key of MANAGED_META) {
    const el = findMeta(key)
    snapshot.metas[key] = { existed: !!el, content: el ? el.getAttribute('content') || '' : '' }
  }
  baseline = snapshot
  return baseline
}

/** 把单个标签还原到基线：基线里没有该标签则移除本模块创建的副本。 */
function restoreMeta(key) {
  const saved = baseline && baseline.metas[key]
  const el = findMeta(key)
  if (!saved || !saved.existed) {
    if (el) el.remove()
    return
  }
  if (el) el.setAttribute('content', saved.content)
  else setMetaContent(key, saved.content)
}

/** 整体回到全局兜底（index.html 基线 + i18n 语言级标题）。 */
function restoreAll() {
  captureBaseline()
  for (const key of MANAGED_META) restoreMeta(key)
  setTitle(localeTitle || baseline.title)
}

/**
 * 字段求值：支持函数形式（接收 route）与 {param} 占位符（取 route.params）。
 * 函数抛错视为取不到值，交由调用方走模板兜底。
 */
function resolveField(value, route) {
  if (typeof value === 'function') {
    try {
      value = value(route || {})
    } catch (e) {
      return ''
    }
  }
  if (typeof value !== 'string') return ''
  const params = (route && route.params) || {}
  const filled = value.replace(/\{(\w+)\}/g, (m, key) => {
    const v = params[key]
    return v === undefined || v === null ? '' : String(v)
  })
  return filled.replace(/\s{2,}/g, ' ').trim()
}

/** 当前页面绝对地址：优先用路由 fullPath，保证与跳转目标一致。 */
function resolveUrl(route) {
  const w = typeof window !== 'undefined' ? window : null
  const origin = w && w.location ? w.location.origin : ''
  const path = route && (route.fullPath || route.path)
  if (!path) {
    if (!w || !w.location) return ''
    return w.location.origin + w.location.pathname + (w.location.search || '')
  }
  return origin ? origin + path : path
}

/**
 * 注入路由级 TDK 与 og。
 * @param {{title?:string|Function,description?:string|Function,keywords?:string|Function,og?:object}} seo
 * @param {object} [route] 路由对象，提供 params / fullPath
 * @returns {object|null} 实际生效的 TDK（便于测试与调试）
 */
export function setRouteSeo(seo, route) {
  if (!seo || typeof seo !== 'object') return null
  // 先回到全局兜底，避免上一个路由的文案泄漏到下一个路由
  restoreAll()
  const src = seo.og && typeof seo.og === 'object' ? seo.og : {}
  const resolved = {
    title: resolveField(seo.title, route),
    description: resolveField(seo.description, route),
    keywords: resolveField(seo.keywords, route),
    og: {}
  }
  for (const key of MANAGED_OG) {
    const v = resolveField(src[key], route)
    if (v) resolved.og[key] = v
  }
  current = resolved
  if (resolved.title) setTitle(resolved.title)
  if (resolved.description) setMetaContent('description', resolved.description)
  if (resolved.keywords) setMetaContent('keywords', resolved.keywords)
  // og:title/og:description 缺值时保留基线内容，不用空串覆盖；og:url/og:image/og:type 有明确默认值必写
  const ogTitle = resolved.og['og:title'] || resolved.title || ''
  if (ogTitle) setMetaContent('og:title', ogTitle)
  const ogDesc = resolved.og['og:description'] || resolved.description || ''
  if (ogDesc) setMetaContent('og:description', ogDesc)
  setMetaContent('og:url', resolved.og['og:url'] || resolveUrl(route))
  setMetaContent('og:image', resolved.og['og:image'] || DEFAULT_OG_IMAGE)
  setMetaContent('og:type', resolved.og['og:type'] || 'website')
  return resolved
}

/** 撤销路由级 TDK，恢复全局兜底（私域路由与无 seo 的路由走这里）。 */
export function resetRouteSeo() {
  current = null
  restoreAll()
}

/**
 * i18n 切换语言时上报语言级标题。
 * 路由级 TDK 生效期间保持路由标题，否则回落到语言级标题。
 */
export function applyLocaleTitle(title) {
  if (typeof title === 'string' && title) localeTitle = title
  if (current) {
    if (current.title) setTitle(current.title)
    return
  }
  if (localeTitle) setTitle(localeTitle)
}

/** 当前生效的路由级 TDK；处于全局兜底态时返回 null。 */
export function getRouteSeo() {
  return current
}

/**
 * 按路由 meta.seo 注入。meta.seo 缺失时回落到全局兜底。
 * @param {object} route vue-router 的路由对象
 */
export function applyRouteSeo(route) {
  const seo = route && route.meta ? route.meta.seo : null
  if (!seo) {
    resetRouteSeo()
    return null
  }
  return setRouteSeo(seo, route)
}

/** 供 og:image 使用：把存储层相对路径补成绝对地址。 */
export function toAbsoluteUrl(url) {
  if (typeof url !== 'string' || !url) return ''
  if (/^https?:\/\//i.test(url)) return url
  const w = typeof window !== 'undefined' ? window : null
  const origin = w && w.location ? w.location.origin : ''
  if (!origin) return url
  return url.startsWith('/') ? origin + url : origin + '/' + url
}

export { SITE_NAME, DEFAULT_OG_IMAGE }
