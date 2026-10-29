/* 三算法综合 E2E：推荐（共现/热度/降级）+ 个性化推流 + 风控名单
 * 用法：node verify-algo.mjs   （需 backend:8080 已启动）
 */
import { execSync } from 'node:child_process'
const API = 'http://127.0.0.1:8080/api'
let failed = 0
const ok = (c, label, extra) => {
  if (!c) failed++
  console.log(`[${c ? 'PASS' : 'FAIL'}] ${label}${extra ? ' | ' + extra : ''}`)
}
const sleep = (ms) => new Promise(r => setTimeout(r, ms))

function sql(stmt) {
  return execSync(`docker exec shopping-mysql-1 mysql -uroot -plocal-validation-password lanlink_shopping -e "${stmt.replace(/"/g, '\\"')}"`,
    { stdio: ['pipe', 'pipe', 'pipe'] }).toString()
}

;(async () => {
  for (let i = 0; i < 40; i++) {
    try {
      const j = await fetch(API + '/actuator/health').then(r => r.json())
      if (j.status === 'UP') { console.log('backend up'); break }
    } catch {}
    await sleep(4000)
  }

  // ===== 种子：两个 session 各浏览 商品1+2 / 商品1+3（制造 1↔2、1↔3 共现） =====
  const S = 'e2e-seed-' + Date.now()
  const ev = (sid, pid, type) =>
    `INSERT INTO t_traffic_event(merchant_id,product_id,user_id,session_id,event_type,source_type,event_time) VALUES(1,${pid},NULL,'${sid}','${type}','direct',NOW())`
  sql(ev(S + 'a', 1, 'VIEW_PRODUCT') + '; ' + ev(S + 'a', 2, 'CLICK_PRODUCT') + '; ' +
      ev(S + 'b', 1, 'VIEW_PRODUCT') + '; ' + ev(S + 'b', 3, 'ADD_CART'))

  // ===== 手动物化（与 RecommendMaterializeJob 相同 SQL） =====
  sql('DELETE FROM t_prod_cooccur')
  sql("INSERT INTO t_prod_cooccur(prod_a, prod_b, cnt, score) SELECT pairs.a_id, pairs.b_id, SUM(pairs.w), 0 FROM ( SELECT t1.product_id a_id, t2.product_id b_id, CASE t1.event_type WHEN 'PAY' THEN 5 WHEN 'ADD_CART' THEN 3 WHEN 'CLICK_PRODUCT' THEN 2 ELSE 1 END w FROM t_traffic_event t1 JOIN t_traffic_event t2 ON t1.session_id=t2.session_id AND t1.product_id<>t2.product_id WHERE t1.event_time>=DATE_SUB(NOW(),INTERVAL 30 DAY) AND t2.event_time>=DATE_SUB(NOW(),INTERVAL 30 DAY) AND t1.product_id IS NOT NULL AND t2.product_id IS NOT NULL AND t1.event_type IN ('VIEW_PRODUCT','CLICK_PRODUCT','ADD_CART','PAY') AND t2.event_type IN ('VIEW_PRODUCT','CLICK_PRODUCT','ADD_CART','PAY')) pairs GROUP BY pairs.a_id, pairs.b_id ON DUPLICATE KEY UPDATE cnt=VALUES(cnt)")
  sql("UPDATE t_prod_cooccur c JOIN (SELECT prod_a, SUM(cnt) ta FROM t_prod_cooccur GROUP BY prod_a) ta ON c.prod_a=ta.prod_a JOIN (SELECT prod_b, SUM(cnt) tb FROM t_prod_cooccur GROUP BY prod_b) tb ON c.prod_b=tb.prod_b SET c.score = ROUND(c.cnt / SQRT(ta.ta * tb.tb), 6)")
  sql('DELETE FROM t_prod_hot_stat')
  sql("INSERT INTO t_prod_hot_stat(prod_id, ind_id, cat_id, view_cnt, cart_cnt, pay_cnt, hot_score, stat_date) SELECT p.prod_id, p.ind_id, p.cat_id, COALESCE(SUM(e.event_type='VIEW_PRODUCT'),0), COALESCE(SUM(e.event_type='ADD_CART'),0), COALESCE(SUM(e.event_type='PAY'),0), 0, CURDATE() FROM t_product p LEFT JOIN t_traffic_event e ON e.product_id=p.prod_id AND e.event_time>=DATE_SUB(NOW(),INTERVAL 30 DAY) AND e.event_type IN ('VIEW_PRODUCT','ADD_CART','PAY') WHERE p.status=1 AND p.review_status=1 AND p.deleted=0 GROUP BY p.prod_id, p.ind_id, p.cat_id")
  sql("UPDATE t_prod_hot_stat h JOIN t_product p ON p.prod_id=h.prod_id SET h.hot_score = ROUND( ((h.pay_cnt + 0.02*p.sales) + 1.9208) / ((h.pay_cnt + 0.02*p.sales) + (h.view_cnt + 0.02*p.sales) + 3.8416) - 1.96 * SQRT( ((h.pay_cnt + 0.02*p.sales) * (h.view_cnt + 0.02*p.sales)) / GREATEST((h.pay_cnt + 0.02*p.sales) + (h.view_cnt + 0.02*p.sales) + 3.8416, 0.0001) ) / (1 + 3.8416 / GREATEST((h.pay_cnt + 0.02*p.sales) + (h.view_cnt + 0.02*p.sales) + 3.8416, 0.0001)) , 6)")
  sql("UPDATE t_prod_hot_stat h JOIN (SELECT prod_id, TIMESTAMPDIFF(DAY, MIN(create_time), NOW()) age FROM t_product GROUP BY prod_id) a ON h.prod_id=a.prod_id SET h.hot_score = ROUND(h.hot_score / POWER(a.age + 2, 0.4), 6)")
  const cooccur = sql('SELECT COUNT(*) c FROM t_prod_cooccur')
  console.log('cooccur rows:', cooccur.trim().split('\n').pop())

  // ===== 登录 =====
  const l = await fetch(API + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone: '13800000000', password: '123456' })
  })
  const base = (l.headers.getSetCookie ? l.headers.getSetCookie() : []).map(x => x.split(';')[0]).join('; ')
  const csrfRes = await fetch(API + '/user/csrf-token', { headers: { Cookie: base } })
  const csrf = (await csrfRes.json())?.data?.token || ''
  const csrfCk = (csrfRes.headers.getSetCookie ? csrfRes.headers.getSetCookie() : []).map(c => c.split(';')[0]).join('; ')
  const cookieAnon = ''
  const cookieAuth = [base, csrfCk].filter(Boolean).join('; ')
  const authHeaders = (withConsent) => ({
    'Content-Type': 'application/json',
    Cookie: withConsent ? cookieAuth + '; ll_analytics=1' : cookieAuth
  })

  // ===== 1. 看了又看：商品1 → 共现 2/3 =====
  const sim = await fetch(API + '/recommendations/similar/1?limit=6', { headers: { Cookie: cookieAnon } })
    .then(r => r.json()).catch(() => null)
  const simIds = (sim?.data?.items || []).map(p => p.prodId)
  ok(sim?.code === 200 && simIds.length > 0 && simIds.some(id => id === 2 || id === 3),
    '看了又看命中共现商品（2/3）', 'ids=' + simIds.join(','))

  // ===== 2. 首页推荐：匿名 → 热度降级 =====
  const homeAnon = await fetch(API + '/recommendations/home?limit=6').then(r => r.json()).catch(() => null)
  ok(homeAnon?.code === 200 && homeAnon?.data?.personalized === false && (homeAnon?.data?.items || []).length > 0,
    '匿名首页推荐降级热度', `personalized=${homeAnon?.data?.personalized} n=${(homeAnon?.data?.items || []).length}`)

  // ===== 3. 登录 + 画像上报（consent=1 落库）+ ll_analytics=1 → 个性化 =====
    // 类目名必须走 API（UTF-8）：cmd 管道读 mysql 中文会变 ????，画像键将无法匹配
  const loginForCat = await fetch(API + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ phone: '13800000000', password: '123456' }) })
  const baseForCat = (loginForCat.headers.getSetCookie ? loginForCat.headers.getSetCookie() : []).map(x => x.split(';')[0]).join('; ')
  const catName = (await fetch(API + '/product/detail/2').then(r => r.json()))?.data?.catName
  const payload = { v: 1, visits: 3, searchTerms: {}, categories: { [catName]: 6 }, recentProducts: [{ id: 2, title: 'x', ts: Date.now() }], updatedAt: new Date().toISOString() }
  const rep = await fetch(API + '/user/habits/report', {
    method: 'POST', headers: { ...authHeaders(true), 'X-CSRF-TOKEN': csrf },
    body: JSON.stringify({ payload })
  }).then(r => r.json()).catch(() => null)
  ok(rep?.code === 200 && rep?.data?.synced, '画像上报（consent=1 落库）', 'code=' + rep?.code)

  const homePers = await fetch(API + '/recommendations/home?limit=6', { headers: authHeaders(true) })
    .then(r => r.json()).catch(() => null)
  ok(homePers?.code === 200 && homePers?.data?.personalized === true && (homePers?.data?.items || []).length > 0,
    '首页推荐个性化（双信号门控通过）', `personalized=${homePers?.data?.personalized} reason=${homePers?.data?.reason}`)

  // ===== 4. 个性化推流：/product/page personalization=true =====
  const pp = await fetch(API + '/product/page?current=1&size=4&personalization=true', { headers: authHeaders(true) })
    .then(r => r.json()).catch(() => null)
  ok(pp?.code === 200 && pp?.data?.personalized === true && (pp?.data?.records || []).length > 0,
    '推流个性化生效', `n=${(pp?.data?.records || []).length}`)
  // 无授权 Cookie → 降级
  const ppNo = await fetch(API + '/product/page?current=1&size=4&personalization=true', { headers: { Cookie: base } })
    .then(r => r.json()).catch(() => null)
  ok(ppNo?.code === 200 && ppNo?.data?.personalized === undefined, '无 analytics Cookie 自动降级自然排序',
    `personalized=${ppNo?.data?.personalized}`)

  // ===== 5. 撤回闭环：删除画像 → 推荐降级 =====
  await fetch(API + '/user/habits', { method: 'DELETE', headers: { ...authHeaders(true), 'X-CSRF-TOKEN': csrf } })
  const homeAfter = await fetch(API + '/recommendations/home?limit=6', { headers: authHeaders(true) })
    .then(r => r.json()).catch(() => null)
  ok(homeAfter?.data?.personalized === false, '撤回画像后推荐降级', `personalized=${homeAfter?.data?.personalized}`)

  // ===== 6. 风控：种子事件 → 名单 → 误报复核（先清历史 E2E 事件防跨轮污染） =====
  sql("DELETE FROM t_user_risk_event WHERE user_id=4 AND detail LIKE 'E2E%'; DELETE FROM t_user_risk_summary WHERE user_id=4")
  sql("INSERT INTO t_user_risk_event(user_id,rule_code,score,detail) VALUES(4,'R01',30,'E2E 高频取消'),(4,'R04',30,'E2E 重复退款')")
  sql("INSERT INTO t_user_risk_summary(user_id,total_score,level,hit_count) VALUES(4,60,'WATCH',2) ON DUPLICATE KEY UPDATE total_score=60,level='WATCH',hit_count=2")
  const risk = await fetch(API + '/admin/risk/users?level=WATCH', { headers: { Cookie: base } })
    .then(r => r.json()).catch(() => null)
  const hit = (risk?.data?.records || []).find(r => Number(r.userId) === 4)
  ok(hit && hit.level === 'WATCH', '风险名单出现 WATCH 用户', JSON.stringify(risk?.data?.records || []).slice(0, 120))

  const evList = await fetch(API + '/admin/risk/users/4/events', { headers: { Cookie: base } })
    .then(r => r.json()).catch(() => null)
  const ev1 = (evList?.data || []).find(e => e.reviewedStatus === null)
  ok(!!ev1, '命中明细可查', 'events=' + (evList?.data || []).length)
  if (ev1) {
    const rv = await fetch(API + '/admin/risk/users/4/review', {
      method: 'POST', headers: { 'Content-Type': 'application/json', Cookie: base },
      body: JSON.stringify({ eventId: ev1.id, reviewedStatus: 0 })
    }).then(r => r.json()).catch(() => null)
    ok(rv?.code === 200 && rv?.data?.totalScore === 30 && rv?.data?.level === 'NORMAL',
      '误报复核即时重算（60→30，降 NORMAL）', JSON.stringify(rv?.data || {}))
    // 清理：E2E 事件与汇总（含跨轮残留）
    sql("DELETE FROM t_user_risk_event WHERE user_id=4 AND detail LIKE 'E2E%'; DELETE FROM t_user_risk_summary WHERE user_id=4")
  }

  // ===== 清理种子流量（保留物化表供线上冷启动前观察） =====
  sql(`DELETE FROM t_traffic_event WHERE session_id LIKE '${S}%'`)

  console.log(failed === 0 ? '\n=== ALGO E2E ALL PASS ===' : `\n=== ${failed} CHECK(S) FAILED ===`)
  process.exit(failed === 0 ? 0 : 1)
})().catch(e => { console.error('crashed:', e.message); process.exit(1) })
