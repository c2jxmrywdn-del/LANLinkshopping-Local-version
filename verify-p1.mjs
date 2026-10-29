/* P1 三件套 E2E：A/B 分流与统计 + 广告 eCPM/pacing + RFM 分层 */
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
  const cr = await fetch(API + '/user/csrf-token', { headers: { Cookie: base } })
  const csrf = (await cr.json())?.data?.token || ''
  const authed = { 'Content-Type': 'application/json', Cookie: base, 'X-CSRF-TOKEN': csrf }

  // ===== 1. A/B 平台 =====
  const up = await fetch(API + '/admin/ab/experiments', {
    method: 'POST', headers: authed,
    body: JSON.stringify({ expKey: 'e2e_rec_rank', name: '推荐排序对比', status: 'running',
      trafficPct: 100, variants: ['control', 'treatment'], metric: 'ctr' })
  }).then(r => r.json()).catch(() => null)
  ok(up?.code === 200, 'A/B 实验创建/更新', 'code=' + up?.code)

  // 同用户两次分流恒定同变体
  const a1 = await fetch(API + '/ab/assign?exp=e2e_rec_rank', { headers: { Cookie: base } }).then(r => r.json())
  const a2 = await fetch(API + '/ab/assign?exp=e2e_rec_rank', { headers: { Cookie: base } }).then(r => r.json())
  ok(a1?.data?.variant && a1.data.variant === a2.data.variant, '同用户恒定同变体',
    `variant=${a1?.data?.variant} bucketBy=${a1?.data?.bucketBy}`)
  const variant = a1?.data?.variant

  // 匿名（无 session id）→ null
  const anon = await fetch(API + '/ab/assign?exp=e2e_rec_rank').then(r => r.json())
  ok(anon?.data?.variant === null, '无身份匿名返回默认分支', 'variant=' + anon?.data?.variant)

  // 曝光 + 转化日志
  await fetch(API + '/ab/expose', { method: 'POST', headers: authed,
    body: JSON.stringify({ expKey: 'e2e_rec_rank', variant }) })
  await fetch(API + '/ab/expose', { method: 'POST', headers: authed,
    body: JSON.stringify({ expKey: 'e2e_rec_rank', variant }) }) // 同日去重
  await fetch(API + '/ab/convert', { method: 'POST', headers: authed,
    body: JSON.stringify({ expKey: 'e2e_rec_rank', variant, value: 128.5 }) })
  // 造第二变体数据（两个变体才有对照检验）
  sql("INSERT INTO t_ab_log(exp_key,variant,user_id,event_type,value) VALUES('e2e_rec_rank','treatment',5,'expose',NULL),('e2e_rec_rank','treatment',5,'convert',99.0)")
  const stat = await fetch(API + '/ab/result?exp=e2e_rec_rank', { headers: { Cookie: base } }).then(r => r.json())
  const v0 = (stat?.data?.variants || [])[0] || {}
  ok(v0.exposes === 1, '曝光同日去重生效', `exposes=${v0.exposes}`)
  ok(v0.converts === 1 && v0.gmv === 128.5, '转化与金额入统计',
    `converts=${v0.converts} gmv=${v0.gmv}`)
  ok(stat?.data?.test && stat.data.test.pValue !== undefined, '两变体检验输出（p 值/样本提示）',
    `p=${stat?.data?.test?.pValue} lift=${stat?.data?.test?.relativeLift}% hint=${(stat?.data?.test?.sampleHint || '').slice(0, 14)}`)

  // 暂停后分流回默认
  await fetch(API + '/admin/ab/experiments/e2e_rec_rank/status', {
    method: 'POST', headers: authed, body: JSON.stringify({ status: 'paused' }) })
  await sleep(31_000) // 分流缓存 30s
  const paused = await fetch(API + '/ab/assign?exp=e2e_rec_rank', { headers: { Cookie: base } }).then(r => r.json())
  ok(paused?.data?.variant === null, '暂停后全量回默认分支', 'variant=' + paused?.data?.variant)
  sql("DELETE FROM t_ab_log WHERE exp_key='e2e_rec_rank'; DELETE FROM t_ab_experiment WHERE exp_key='e2e_rec_rank'")

  // ===== 2. 广告 eCPM+pacing =====
  const slots = await fetch(API + '/ad/slots?limit=3').then(r => r.json()).catch(() => null)
  ok(slots !== null && Array.isArray(slots?.data || slots), '广告位接口照常（eCPM 排序路径）',
    `n=${(slots?.data || slots || []).length}`)
  // 校验 DB 中 e2e 无脏数据 & 计费链路字段未动
  const cols = sql("SHOW COLUMNS FROM t_ad_campaign LIKE 'cpc_price'")
  ok(cols.includes('cpc_price'), '计费链路字段未动（cpc_price 仍在）')

  // ===== 3. RFM =====
  const rb = await fetch(API + '/admin/crm/rfm/rebuild', { method: 'POST', headers: authed })
    .then(r => r.json()).catch(() => null)
  ok(rb?.code === 200, 'RFM 手动重算触发', 'code=' + rb?.code)
  const sum = await fetch(API + '/admin/crm/rfm/summary', { headers: { Cookie: base } }).then(r => r.json()).catch(() => null)
  ok(sum?.code === 200 && (sum?.data || []).length > 0, '分层概览非空（八层矩阵）',
    JSON.stringify(sum?.data || []).slice(0, 110))
  const users = await fetch(API + '/admin/crm/rfm/users?size=5', { headers: { Cookie: base } }).then(r => r.json()).catch(() => null)
  const row0 = (users?.data?.records || [])[0] || {}
  ok(row0.layer && row0.r_score != null, '分层用户列表含分值与层级',
    `layer=${row0.layer} R=${row0.r_score} F=${row0.f_score} M=${row0.m_score}`)

  console.log(failed === 0 ? '\n=== P1 E2E ALL PASS ===' : `\n=== ${failed} CHECK(S) FAILED ===`)
  process.exit(failed === 0 ? 0 : 1)
})().catch(e => { console.error('crashed:', e.message); process.exit(1) })
