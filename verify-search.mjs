/* P1-2 语义搜索 E2E：向量灌库 → 分词 LIKE / 语义 KNN / RRF 融合 / 降级 */
const API = 'http://127.0.0.1:8080/api'
let failed = 0
const ok = (c, label, extra) => {
  if (!c) failed++
  console.log(`[${c ? 'PASS' : 'FAIL'}] ${label}${extra ? ' | ' + extra : ''}`)
}
const sleep = (ms) => new Promise(r => setTimeout(r, ms))
const { execSync } = await import('node:child_process')
const sql = (stmt) => execSync(
  `docker exec shopping-mysql-1 mysql -uroot -plocal-validation-password lanlink_shopping -e "${stmt.replace(/"/g, '\\"')}"`,
  { stdio: ['pipe', 'pipe', 'pipe'], encoding: 'utf8' })

;(async () => {
  for (let i = 0; i < 40; i++) {
    try { const j = await fetch(API + '/actuator/health').then(r => r.json()); if (j.status === 'UP') { console.log('backend up'); break } } catch {}
    await sleep(4000)
  }
  const l = await fetch(API + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone: '13800000000', password: '123456' })
  })
  const base = (l.headers.getSetCookie ? l.headers.getSetCookie() : []).map(x => x.split(';')[0]).join('; ')

  // 1. 手动触发向量灌库（15 商品，bge-m3 批量，秒级）
  const t0 = Date.now()
  const re = await fetch(API + '/product/admin/search/reindex', { method: 'POST', headers: { Cookie: base } })
    .then(r => r.json()).catch(() => null)
  ok(re?.code === 200 && re?.data?.indexed > 0, '向量灌库', `indexed=${re?.data?.indexed} skipped=${re?.data?.skipped} ${Math.round((Date.now() - t0) / 100) / 10}s`)

  // 2. 词面搜索回归：现有关键词仍命中（关键词走 API 取——cmd 读 mysql 中文会变 ????）
  const first = await fetch(API + '/product/page?current=1&size=1').then(r => r.json()).catch(() => null)
  const prod0 = (first?.data?.records || [])[0] || {}
  const detail = prod0.prodId ? await fetch(API + '/product/detail/' + prod0.prodId).then(r => r.json()).catch(() => null) : null
  const catName = detail?.data?.catName || ''
  const kw = String(prod0.title || '').slice(0, 4)
  ok(!!kw && !!catName, '测试词就绪（API 取，UTF-8 干净）', `kw=${kw} cat=${catName}`)
  const r1 = await fetch(API + `/product/page?current=1&size=6&keyword=${encodeURIComponent(kw)}`).then(r => r.json()).catch(() => null)
  ok(r1?.code === 200 && (r1?.data?.records || []).length > 0, '词面搜索回归（jieba 多词 LIKE）',
    `kw=${kw} n=${(r1?.data?.records || []).length}`)

  // 3. 语义样例：类目名搜索（标题大多不含类目全称——向量路补足词面盲区）
  ok(!!catName, '类目名就绪', catName)
  const r2 = await fetch(API + `/product/page?current=1&size=6&keyword=${encodeURIComponent(catName)}`).then(r => r.json()).catch(() => null)
  ok(r2?.code === 200 && (r2?.data?.records || []).length > 0, '类目词搜索命中（向量+词面双路）',
    `cat=${catName} n=${(r2?.data?.records || []).length}`)

  // 4. 分页与 total 语义正确
  ok(r2?.data?.total >= (r2?.data?.records || []).length, '分页 total 语义', `total=${r2?.data?.total}`)

  // 5. 无结果词不 500（全空回退）
  const r3 = await fetch(API + '/product/page?current=1&size=6&keyword=' + encodeURIComponent('zzqx9k9 零命中词')).then(r => r.json()).catch(() => null)
  ok(r3?.code === 200, '零命中词安全回退', `code=${r3?.code} n=${(r3?.data?.records || []).length}`)

  console.log(failed === 0 ? '\n=== SEARCH E2E ALL PASS ===' : `\n=== ${failed} CHECK(S) FAILED ===`)
  process.exit(failed === 0 ? 0 : 1)
})().catch(e => { console.error('crashed:', e.message); process.exit(1) })
