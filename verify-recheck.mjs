/* 复检收尾验证：游客 401 silent 吸收 + trend 公开 + PermInterceptor 类级回归 */
const B = 'http://127.0.0.1:8080/api'
let cookie = ''
let failed = 0
function assert(cond, label, extra) {
  console.log(`[${cond ? 'PASS' : 'FAIL'}] ${label}${extra ? ' | ' + extra : ''}`)
  if (!cond) failed++
}
async function call(method, path, body, who) {
  const res = await fetch(B + path, { method, headers: { 'Content-Type': 'application/json', Cookie: cookie }, body: body ? JSON.stringify(body) : undefined })
  let json = null
  try { json = await res.json() } catch (e) {}
  return { status: res.status, json }
}
async function login(phone) {
  const r = await fetch(B + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ phone, password: '123456' }) })
  cookie = (r.headers.getSetCookie?.() || []).map(c => c.split(';')[0]).join('; ')
}

async function main() {
  // 1. 游客：productAlerts 401（前端以 silent 吸收，不再弹错/跳登录——本次回归的核心）
  const anon = await call('GET', '/price-monitor/product/1/alerts')
  assert(anon.status === 401, '匿名 productAlerts 返回 401(前端 silent 吸收)', `status=${anon.status}`)
  const trend = await call('GET', '/price-monitor/trend/1?days=30')
  assert(trend.json?.code === 200 && trend.json.data?.series?.length, '游客 trend 公开正常', `points=${trend.json?.data?.series?.length}`)

  // 2. PermInterceptor 类级回退回归：管理员访问 /admin/price-monitor/** 正常
  await login('13800000000')
  const anom = await call('GET', '/admin/price-monitor/anomalies?days=30')
  assert(anom.json?.code === 200, '管理员异动排行(类级注解回退后)', `rows=${anom.json?.data?.length}`)

  // 3. 买家被类级 admin:all 拦截
  await login('13900000001')
  const denied = await call('GET', '/admin/price-monitor/benchmarks')
  assert(denied.json?.code === 403 || denied.status === 403, '买家访问 admin 端点被拒', `code=${denied.json?.code}`)

  console.log(failed === 0 ? '\n=== RECHECK E2E ALL PASSED ===' : `\n=== ${failed} FAILED ===`)
  process.exit(failed === 0 ? 0 : 1)
}
main().catch(e => { console.error('crashed:', e.message); process.exit(1) })
