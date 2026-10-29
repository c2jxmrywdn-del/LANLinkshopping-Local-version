/* 价格监控回归 E2E：审计修复后全链路 */
const B = 'http://127.0.0.1:8080/api'
let cookie = ''
let failed = 0
function assert(cond, label, extra) {
  console.log(`[${cond ? 'PASS' : 'FAIL'}] ${label}${extra ? ' | ' + extra : ''}`)
  if (!cond) failed++
}
async function login(phone) {
  const r = await fetch(B + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ phone, password: '123456' }) })
  cookie = (r.headers.getSetCookie?.() || []).map(c => c.split(';')[0]).join('; ')
}
async function call(method, path, body) {
  const res = await fetch(B + path, { method, headers: { 'Content-Type': 'application/json', Cookie: cookie }, body: body ? JSON.stringify(body) : undefined })
  let json = null
  try { json = await res.json() } catch (e) {}
  return json
}

async function main() {
  // 匿名公开接口
  const trend = await call('GET', '/price-monitor/trend/1?days=30')
  assert(trend?.code === 200, '匿名走势(白名单)', `points=${trend?.data?.series?.length}`)
  const factors = await call('GET', '/price-monitor/factors/1')
  assert(factors?.code === 200, '五维因子(公开)', `n=${factors?.data?.factors?.length}`)

  await login('13900000001')
  const dupSub1 = await call('POST', '/price-monitor/alerts', { prodId: 2, conditionType: 'BELOW', threshold: 100 })
  const dupSub2 = await call('POST', '/price-monitor/alerts', { prodId: 2, conditionType: 'BELOW', threshold: 100 })
  assert(dupSub1?.code === 200 && dupSub2?.code !== 200, '订阅+并发语义去重', `2nd=${dupSub2?.message}`)
  const pa = await call('GET', '/price-monitor/product/2/alerts')
  assert(pa?.code === 200 && pa.data.length >= 1, '商品订阅回显端点', `n=${pa?.data?.length}`)
  const denied = await call('GET', '/price-monitor/merchant/overview')
  assert(denied?.code !== 200, '买家访问商户端被拒', `code=${denied?.code}`)

  await login('13700000002')
  const ov = await call('GET', '/price-monitor/merchant/overview')
  assert(ov?.code === 200 && ov.data.length > 0, '商户总览(聚合查询)', `rows=${ov?.data?.length}`)

  await login('13800000000')
  const rf = await call('POST', '/admin/price-monitor/refresh')
  assert(rf?.code === 200 && Number(rf.data) >= 0, '手动刷新基准(幂等:当日已存在则0新建)', `inds=${rf?.data}`)
  const bench = await call('GET', '/admin/price-monitor/benchmarks')
  assert(bench?.code === 200 && bench.data.length >= 4, '基准总览', `inds=${bench?.data?.length}`)
  // PermInterceptor 类级回退：admin 端点正常（admin:all 通过）
  const anom = await call('GET', '/admin/price-monitor/anomalies?days=30')
  assert(anom?.code === 200, '异动排行(类级注解回归)', `rows=${anom?.data?.length}`)

  console.log(failed === 0 ? '\n=== REGRESSION E2E ALL PASSED ===' : `\n=== ${failed} FAILED ===`)
  process.exit(failed === 0 ? 0 : 1)
}
main().catch(e => { console.error('crashed:', e.message); process.exit(1) })
