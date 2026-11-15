/**
 * P1-19 SEO 基线守卫：public/sitemap.xml 与 public/robots.txt 必须与 src/router/index.js 保持一致。
 * 覆盖面：
 * ① sitemap 每条 <loc> 能被 router 解析到真实路由（不得落到 not-found），且不含私域前缀；
 * ② router 公开静态路由（无 auth/admin、非动态参数、非 redirect）除白名单外必须全部进入 sitemap（防漏收录）；
 * ③ sitemap 路径不得命中 robots.txt 任何 Disallow 前缀（防「收录即被封禁」自相矛盾）；
 * ④ robots.txt 结构校验（Sitemap 指向、私域 Disallow 清单齐全）。
 * 依据路由清单：tasks/membership/credit-term/price-monitor 等在 router 中 meta.auth=true，均属私域。
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import router from '../router'

const here = dirname(fileURLToPath(import.meta.url))
const publicDir = resolve(here, '../../public')
const SITE_HOST = 'https://lanlink.cc.cd'

const xml = readFileSync(resolve(publicDir, 'sitemap.xml'), 'utf8')
const robots = readFileSync(resolve(publicDir, 'robots.txt'), 'utf8')

const locPaths = [...xml.matchAll(/<loc>https:\/\/[^<]+?(\/[^<]*)<\/loc>/g)]
  .map(m => (m[1].replace(/\/$/, '') || '/'))

// robots Disallow 前缀（与 robots.txt 人工清单保持同源；新增私域时两处同步改）
const DISALLOW_PREFIXES = ['/admin', '/merchant', '/me', '/cart', '/checkout', '/orders', '/wallet', '/rfq', '/chat', '/tasks', '/membership', '/credit-term', '/price-monitor', '/activity/my', '/tech/workspace', '/tech/contracts', '/tech/org-tasks', '/api']
// sitemap 语义上的私域（比 robots 多覆盖 /activity/:id/participation 动态段）
function isPrivatePath(p) {
  if (DISALLOW_PREFIXES.some(d => p === d || p.startsWith(d + '/'))) return true
  if (/^\/activity\/[^/]+\/participation/.test(p)) return true
  return false
}
function blockedByRobots(p) {
  return DISALLOW_PREFIXES.some(d => p.startsWith(d))
}

function joinPath(base, seg) {
  if (!seg) return base
  if (base.endsWith('/')) return base + seg
  return base + '/' + seg
}
// 深遍历 route records 还原完整路径；记录继承的私有标记（父子 meta 合并语义）
function walk(records, base = '', inheritedPrivate = false) {
  const out = []
  for (const r of records) {
    const full = r.path.startsWith('/') ? r.path : joinPath(base, r.path)
    const priv = inheritedPrivate || Boolean(r.meta?.auth) || Boolean(r.meta?.admin)
    if (r.children) {
      out.push(...walk(r.children, full, priv))
    } else if (r.redirect) {
      // 兼容跳转不参与收录判断
    } else {
      out.push({ path: full, priv, dynamic: r.path.includes(':') })
    }
  }
  return out
}
const allRoutes = walk(router.options.routes)
// 工具页不进 sitemap（登录/注册非索引内容页，属收录最佳实践排除项，非私域）
const SITEMAP_EXCLUDED = ['/login', '/register']
const publicStatic = allRoutes
  .filter(r => !r.priv && !r.dynamic && r.path !== '/' && !SITEMAP_EXCLUDED.includes(r.path))
  .map(r => r.path)

describe('P1-19 sitemap × router 一致性', () => {
  it('sitemap <loc> 全部解析到真实路由，且不落在私域', () => {
    expect(locPaths.length).toBeGreaterThan(0)
    for (const p of locPaths) {
      const matched = router.resolve(p)
      expect(matched.name, `sitemap 路径 ${p} 应命中真实路由`).not.toBe('not-found')
      expect(matched.matched.length, `${p} 应命中路由记录`).toBeGreaterThan(0)
      expect(isPrivatePath(p), `${p} 属私域，禁止入 sitemap`).toBe(false)
    }
  })

  it('router 公开静态路由无遗漏进入 sitemap（新增公开页未收录时此测试失败）', () => {
    for (const p of publicStatic) {
      expect(locPaths, `公开路由 ${p} 未收录进 sitemap.xml`).toContain(p)
    }
    // 动态详情段不强制收录：/product/:id、/activity/:id、/live/:roomId 依赖运行时数据（拍板⑦-12）
  })

  it('sitemap 与 robots 不自相矛盾：收录页不得命中任何 Disallow 前缀', () => {
    for (const p of locPaths) {
      expect(blockedByRobots(p), `sitemap 收录的 ${p} 被 robots.txt Disallow 封禁`).toBe(false)
    }
  })

  it('sitemap URL 统一为生产域名与 ISO 日期', () => {
    for (const loc of [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map(m => m[1])) {
      expect(loc.startsWith(SITE_HOST + '/'), `loc 应以 ${SITE_HOST}/ 开头：${loc}`).toBe(true)
    }
    for (const lastmod of [...xml.matchAll(/<lastmod>([^<]+)<\/lastmod>/g)].map(m => m[1])) {
      expect(lastmod, `lastmod 应为 YYYY-MM-DD：${lastmod}`).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    }
    expect(locPaths, 'sitemap 不收录登录/注册工具页').not.toContain('/login')
    expect(locPaths).not.toContain('/register')
  })

  it('robots.txt 声明 sitemap 且私域 Disallow 清单齐全', () => {
    expect(robots).toMatch(new RegExp('^Sitemap: ' + SITE_HOST.replace(/\./g, '\\.') + '/sitemap\\.xml$', 'm'))
    for (const d of DISALLOW_PREFIXES) {
      expect(robots).toContain('Disallow: ' + d)
    }
  })
})
