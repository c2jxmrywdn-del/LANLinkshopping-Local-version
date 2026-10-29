/**
 * 资讯来源链接工具（「每日资讯 / 价格情报台资讯」导向信息源头的统一出口）。
 *
 * 后端 `/price-monitor/news` 与 `/price-monitor/factors/{prodId}` 的 newsRefs 会返回：
 *   { url, sourceUrl, searchUrl, linkType }
 * linkType 语义：
 *   - article  ：直接是资讯原文（外链，新开页签）
 *   - source   ：原文缺失，退到来源站点官网/频道首页（外链）
 *   - search   ：无原文也无官网，退到必应资讯检索（外链）
 *   - internal ：平台自产资讯，url 为站内相对路径（如 /price-monitor，走 router.push）
 *
 * 后端字段可能缺失（并行开发 / 历史数据），因此这里全部做纯函数 + 防御性降级：
 * 拿不到合法链接时返回 '' 或 no-op，绝不抛错。组件只依赖本文件判断跳转方式。
 */

/** 仅放行 http/https 绝对地址；其余（空、javascript:、相对路径、含空白等）返回 ''。 */
export function safeHttpUrl(url) {
  if (typeof url !== 'string') return ''
  const trimmed = url.trim()
  if (!trimmed || /\s/.test(trimmed)) return ''
  if (!/^https?:\/\//i.test(trimmed)) return ''
  try {
    // 交给 URL 解析器兜底校验（http:// 这类无主机地址会被拒绝）
    const parsed = new URL(trimmed)
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? trimmed : ''
  } catch (e) {
    return ''
  }
}

/** 是否站内链接：linkType === 'internal'，或 url 是以 '/' 开头的站内相对路径。 */
export function isInternalNews(item) {
  if (!item || typeof item !== 'object') return false
  if (String(item.linkType || '').toLowerCase() === 'internal') return true
  const url = typeof item.url === 'string' ? item.url.trim() : ''
  // '//host' 是协议相对外链，不能当站内路径（router.push 会被当成外部域名）
  return url.startsWith('/') && !url.startsWith('//')
}

/**
 * 资讯最终跳转地址：
 *   - 站内资讯返回站内相对路径（如 /price-monitor）；
 *   - 外链优先 url（原文）→ searchUrl（检索兜底）→ ''。
 */
export function newsLinkUrl(item) {
  if (!item || typeof item !== 'object') return ''
  const url = typeof item.url === 'string' ? item.url.trim() : ''
  if (isInternalNews(item)) {
    return url.startsWith('/') && !url.startsWith('//') ? url : ''
  }
  const httpUrl = safeHttpUrl(url)
  if (httpUrl) return httpUrl
  return safeHttpUrl(item.searchUrl)
}

/** 打开资讯链接：站内 router.push，外链新开页签；非法链接 no-op。返回是否已处理。 */
export function openNewsLink(item, router) {
  const url = newsLinkUrl(item)
  if (!url) return false
  if (isInternalNews(item)) {
    if (router && typeof router.push === 'function') {
      router.push(url)
      return true
    }
    return false
  }
  if (typeof window !== 'undefined' && typeof window.open === 'function') {
    window.open(url, '_blank', 'noopener')
    return true
  }
  return false
}

/** 跳转方式徽标文案：原文 / 来源站点 / 检索原文 / 站内（中英双语）。 */
export function newsLinkLabel(item, isEn) {
  if (!item || typeof item !== 'object') return ''
  const rawType = String(item.linkType || '').toLowerCase()
  const type = rawType || (isInternalNews(item) ? 'internal' : (newsLinkUrl(item) ? 'article' : ''))
  const labels = {
    article: { zh: '原文', en: 'Original' },
    source: { zh: '来源站点', en: 'Source site' },
    search: { zh: '检索原文', en: 'Find original' },
    internal: { zh: '站内', en: 'On-site' }
  }
  const label = labels[type]
  return label ? label[isEn ? 'en' : 'zh'] : ''
}
