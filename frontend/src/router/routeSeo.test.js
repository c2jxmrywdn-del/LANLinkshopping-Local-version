/**
 * 路由 meta.seo 抽查（P1-19 按路由 TDK 注入）：
 * ① 拍板覆盖清单内的路由（含任务/会员/账期/价格监控/直播/技术服务等登录态页）必须带 meta.seo；
 * ② 私域路由（/me /merchant /admin /cart /checkout 家族与工作台）不得注入，保持全局兜底；
 * ③ 动态路由（商品/活动/直播间/积分明细/账单）用 {param} 模板兜底，真实名称由页面数据回调覆盖；
 * ④ 守卫：TDK 文案不得出现具体金额（不虚构商品/价格数据）。
 */
import { describe, it, expect } from 'vitest'
import router from './index'

function findRoute(name, records = router.options.routes) {
  for (const r of records) {
    if (r.name === name) return r
    if (r.children) {
      const hit = findRoute(name, r.children)
      if (hit) return hit
    }
  }
  return null
}

const SEO_REQUIRED = [
  'home', 'mall', 'product',
  'activity', 'activity-detail', 'activity-rules',
  'coupon-center', 'task-center', 'campaign-venue',
  'live-square', 'live-room',
  'membership', 'membership-orders', 'membership-levels', 'membership-points', 'membership-point-detail', 'membership-benefits',
  'credit-term', 'credit-term-apply', 'credit-term-adjust', 'credit-term-bills', 'credit-term-bill-detail',
  'price-monitor',
  'about',
  'tech', 'tech-exchange', 'tech-experts', 'tech-demands', 'tech-achievements', 'tech-appraisal', 'tech-hosting', 'tech-pilot', 'tech-open-license', 'tech-policies'
]

const SEO_FORBIDDEN = [
  'me', 'account-messages', 'account-addresses', 'account-settings',
  'cart', 'checkout', 'orders', 'order-detail',
  'rfq', 'wallet',
  'merchant', 'merchant-center', 'merchant-center-products',
  'admin-dash', 'admin-merchants', 'admin-settlement',
  'activity-participation', 'activity-my',
  'tech-workspace', 'tech-contracts', 'tech-org-tasks',
  'login', 'register', 'not-found'
]

describe('路由 meta.seo 抽查', () => {
  it('覆盖清单内的路由都带完整 meta.seo', () => {
    for (const name of SEO_REQUIRED) {
      const r = findRoute(name)
      expect(r, `路由 ${name} 应存在`).toBeTruthy()
      const seo = r.meta && r.meta.seo
      expect(seo, `路由 ${name} 应有 meta.seo`).toBeTruthy()
      expect(typeof seo.title, `${name}.seo.title 应为字符串`).toBe('string')
      expect(seo.title.length, `${name}.seo.title 非空`).toBeGreaterThan(0)
      expect(seo.description.length, `${name}.seo.description 非空`).toBeGreaterThan(10)
      expect(seo.keywords.length, `${name}.seo.keywords 非空`).toBeGreaterThan(0)
    }
  })

  it('私域路由不得注入 meta.seo（保持全局兜底）', () => {
    for (const name of SEO_FORBIDDEN) {
      const r = findRoute(name)
      expect(r, `路由 ${name} 应存在`).toBeTruthy()
      expect(r.meta && r.meta.seo, `${name} 不应有 meta.seo`).toBeFalsy()
    }
  })

  it('动态路由用 {param} 模板兜底（账单明细的记录 id 无检索价值，标题保持泛化）', () => {
    expect(findRoute('product').meta.seo.title).toContain('{id}')
    expect(findRoute('activity-detail').meta.seo.title).toContain('{id}')
    expect(findRoute('live-room').meta.seo.title).toContain('{roomId}')
    expect(findRoute('membership-point-detail').meta.seo.title).toContain('{id}')
  })

  it('全部 meta.seo 文案不得出现具体金额（不虚构价格数据）', () => {
    const collect = (records, out) => {
      for (const r of records) {
        if (r.meta && r.meta.seo) out.push(r.meta.seo)
        if (r.children) collect(r.children, out)
      }
      return out
    }
    const seos = collect(router.options.routes, [])
    expect(seos.length, '注入 seo 的路由数量').toBeGreaterThanOrEqual(SEO_REQUIRED.length)
    for (const s of seos) {
      expect(s.title, `标题不得含具体金额：${s.title}`).not.toMatch(/[¥￥]\s*\d/)
      expect(s.description, `描述不得含具体金额：${s.description}`).not.toMatch(/[¥￥]\s*\d/)
    }
  })
})
