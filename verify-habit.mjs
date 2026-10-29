/* 用户习惯画像后端链路 E2E：鉴权/CSRF/上报/查询/清除 */
const API = 'http://127.0.0.1:8080/api'
let failed = 0
const ok = (cond, label, extra) => {
  if (!cond) failed++
  console.log('[' + (cond ? 'PASS' : 'FAIL') + '] ' + label + (extra ? ' | ' + extra : ''))
}
const payload = {
  v: 1, visits: 5,
  searchTerms: { '螺纹钢': 3, '水泥': 1 },
  categories: { '建材': 4 },
  recentProducts: [{ id: 1, title: '演示商品', ts: Date.now() }],
  updatedAt: new Date().toISOString()
}

;(async () => {
  const l = await fetch(API + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone: '13800000000', password: '123456' })
  })
  const base = (l.headers.getSetCookie ? l.headers.getSetCookie() : []).map(x => x.split(';')[0]).join('; ')

  const csrfRes = await fetch(API + '/user/csrf-token', { headers: { Cookie: base } })
  const csrfBody = await csrfRes.json().catch(() => null)
  const extra = (csrfRes.headers.getSetCookie ? csrfRes.headers.getSetCookie() : []).map(c => c.split(';')[0]).join('; ')
  const cookie = [base, extra].filter(Boolean).join('; ')
  const token = String(csrfBody?.data?.token || '')

  const anon = await fetch(API + '/user/habits/report', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ payload })
  }).then(r => r.json()).catch(() => null)
  ok(anon && anon.code === 401, '未登录上报返回 401', 'code=' + (anon && anon.code))

  const rep = await fetch(API + '/user/habits/report', {
    method: 'POST', headers: {
      'Content-Type': 'application/json', Cookie: cookie, 'X-CSRF-TOKEN': token
    },
    body: JSON.stringify({ payload })
  }).then(r => r.json()).catch(() => null)
  ok(rep && rep.code === 200 && rep.data && rep.data.synced, '登录上报 upsert（CSRF 令牌通过）',
    'code=' + (rep && rep.code) + ' chars=' + (rep && rep.data && rep.data.chars))

  const get = await fetch(API + '/user/habits', { headers: { Cookie: cookie } }).then(r => r.json()).catch(() => null)
  ok(get && get.code === 200 && get.data && get.data.exists && get.data.payload &&
    get.data.payload.visits === 5, '服务端副本可查（跨设备续用）',
    'visits=' + (get && get.data && get.data.payload && get.data.payload.visits))

  const noCsrf = await fetch(API + '/user/habits/report', {
    method: 'POST', headers: { 'Content-Type': 'application/json', Cookie: cookie },
    body: JSON.stringify({ payload })
  }).then(r => r.json()).catch(() => null)
  ok(noCsrf && noCsrf.code === 403, '无 CSRF 令牌的写操作被拦截', 'code=' + (noCsrf && noCsrf.code))

  const del = await fetch(API + '/user/habits', {
    method: 'DELETE', headers: { Cookie: cookie, 'X-CSRF-TOKEN': token }
  }).then(r => r.json()).catch(() => null)
  ok(del && del.code === 200, '删除权行使（服务端清除）', 'code=' + (del && del.code))

  const get2 = await fetch(API + '/user/habits', { headers: { Cookie: cookie } }).then(r => r.json()).catch(() => null)
  ok(get2 && get2.code === 200 && get2.data && get2.data.exists === false, '清除后副本为空',
    'exists=' + (get2 && get2.data && get2.data.exists))

  console.log(failed === 0 ? '=== HABIT E2E ALL PASS ===' : failed + ' FAILED')
  process.exit(failed === 0 ? 0 : 1)
})()
