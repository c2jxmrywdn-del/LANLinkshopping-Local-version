/* LANLink 本地 AI 向量知识库 / 双模型 / 外部资讯 / 意图理解 端到端回归
 * 用法：node verify-ai-features.mjs   （需 backend:8080 / ai-gateway:8090 / Redis / llama.cpp router:8123 已启动）
 * 前置：admin 账号 13800000000/123456（dev 演示数据）
 */
const API = 'http://127.0.0.1:8080/api'
const GW = 'http://127.0.0.1:8090'
let cookie = ''
let failed = 0
const results = []

function assert(cond, label, extra) {
  const ok = !!cond
  if (!ok) failed++
  results.push({ ok, label, extra })
  console.log(`[${ok ? 'PASS' : 'FAIL'}] ${label}${extra !== undefined ? ' | ' + extra : ''}`)
}

async function login(phone = '13800000000') {
  const r = await fetch(API + '/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone, password: '123456' })
  })
  const setCookies = r.headers.getSetCookie ? r.headers.getSetCookie() : []
  cookie = setCookies.map(c => c.split(';')[0]).join('; ')
  const body = await r.json().catch(() => null)
  return body
}

async function api(method, path, body) {
  const res = await fetch(API + path, {
    method,
    headers: { 'Content-Type': 'application/json', Cookie: cookie },
    body: body ? JSON.stringify(body) : undefined
  })
  const json = await res.json().catch(() => null)
  return json
}

async function gateway(path, options = {}) {
  const res = await fetch(GW + path, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) }
  })
  const text = await res.text()
  try { return { status: res.status, json: JSON.parse(text), text } }
  catch (e) { return { status: res.status, json: null, text } }
}

function fmtMs(ms) { return Math.round(ms) + 'ms' }

async function main() {
  console.log('=== 1. AI 网关健康 & 双模型 ===')
  const health = await gateway('/health')
  assert(health.status === 200 && health.json?.status === 'UP', '网关 /health UP',
    JSON.stringify(health.json?.components?.llm || {}).slice(0, 160))
  const models = await gateway('/v1/models')
  const ids = (models.json?.data || []).map(m => m.id)
  assert(ids.includes('qwen2.5-7b'), '/v1/models 含文本模型 qwen2.5-7b', ids.join(','))
  assert(ids.includes('qwen2.5-vl'), '/v1/models 含视觉模型 qwen2.5-vl', ids.join(','))

  console.log('\n=== 2. Redis 向量知识库检索（网关） ===')
  const kb = await gateway('/kb/search?q=' + encodeURIComponent('企业账期怎么申请') + '&top_k=3')
  const kbHits = kb.json?.results || []
  assert(kb.status === 200 && kbHits.length > 0, '网关 /kb/search 有向量命中', `hits=${kbHits.length} backend=${kb.json?.kb_backend || kb.json?.backend || '?'}`)
  if (kbHits[0]) {
    assert(!!(kbHits[0].title || kbHits[0].content), '命中项含 title/content', JSON.stringify(kbHits[0]).slice(0, 160))
  }
  const embed = await gateway('/v1/embeddings', {
    method: 'POST',
    body: JSON.stringify({ model: 'nomic-embed-text', input: ['水泥价格上行', '螺纹钢库存下降'] })
  })
  const embedDim = embed.json?.data?.[0]?.embedding?.length || 0
  assert(embed.status === 200 && embedDim >= 256, '/v1/embeddings 返回真实嵌入', `dim=${embedDim}`)

  console.log('\n=== 3. 本地 7B 文本模型（网关会话） ===')
  const t0 = Date.now()
  const chat = await gateway('/v1/chat/completions', {
    method: 'POST',
    body: JSON.stringify({
      model: 'qwen2.5-7b',
      messages: [{ role: 'user', content: '用一句话说明：水泥价格上涨对建筑行业采购成本的影响。' }],
      max_tokens: 80,
      temperature: 0.2
    })
  })
  const chatText = chat.json?.choices?.[0]?.message?.content || ''
  assert(chat.status === 200 && chatText.length > 0, 'qwen2.5-7b 聊天返回',
    `${fmtMs(Date.now() - t0)} model=${chat.json?.model} text=${chatText.slice(0, 40)}`)
  assert(chat.json?.lanlink?.backend?.includes('llamacpp') || chat.json?.lanlink?.backend?.includes('gateway'),
    '聊天标注本地后端', chat.json?.lanlink?.backend)

  console.log('\n=== 4. 管理端向量库统计 ===')
  const loginRes = await login()
  assert(loginRes?.code === 200, 'admin 登录', loginRes?.message)
  const stats = await api('GET', '/admin/kb/stats')
  const st = stats?.data || {}
  assert(stats?.code === 200 && Number(st.totalDocs) > 0, '向量库文档数', `totalDocs=${st.totalDocs}`)
  assert(!!st.indexMode, '统计含 indexMode', `indexMode=${st.indexMode}`)
  assert(!!st.embedCache && st.embedCache.hit !== undefined, '统计含 embedCache', JSON.stringify(st.embedCache || {}).slice(0, 120))
  assert(!!st.search && st.search.count !== undefined, '统计含 search 耗时', JSON.stringify(st.search || {}).slice(0, 120))
  if (st.models) console.log('  models =', JSON.stringify(st.models).slice(0, 240))

  console.log('\n=== 5. 外部资讯自动抓取（RSS → 市场资讯 → 向量库） ===')
  const beforeNews = await api('GET', '/price-monitor/news?limit=50')
  const beforeCount = (beforeNews?.data || []).length
  const fetchRes = await api('POST', '/admin/kb/fetch-external?limit=12')
  const fr = fetchRes?.data || {}
  assert(fetchRes?.code === 200, '抓取接口返回 200', fetchRes?.message)
  console.log('  fetch stats =', JSON.stringify({ fetched: fr.fetched, inserted: fr.inserted, skipped: fr.skipped, ingested: fr.ingested, elapsedMs: fr.elapsedMs }).slice(0, 240))
  assert(Number(fr.fetched) > 0, '本轮抓取到外部条目', `fetched=${fr.fetched}`)
  const fetch2 = await api('POST', '/admin/kb/fetch-external?limit=12')
  const fr2 = fetch2?.data || {}
  console.log('  fetch#2 stats =', JSON.stringify({ fetched: fr2.fetched, inserted: fr2.inserted, skipped: fr2.skipped }).slice(0, 200))
  assert(fetch2?.code === 200 && Number(fr2.skipped) > 0, '二次抓取命中标题去重（skipped>0）', `skipped=${fr2.skipped}`)
  const afterNews = await api('GET', '/price-monitor/news?limit=60')
  const rows = afterNews?.data || []
  const realArticles = rows.filter(n => n.linkType === 'article' && /^https?:\/\//.test(n.url || ''))
  assert(realArticles.length > 0, '市场资讯出现真实原文链接（外部抓取）', `article=${realArticles.length}/${rows.length}`)
  if (realArticles[0]) console.log('  sample =', realArticles[0].source, '|', realArticles[0].title.slice(0, 40), '|', realArticles[0].url.slice(0, 70))
  const titles = rows.map(n => (n.title || '').trim())
  const dup = titles.length - new Set(titles).size
  assert(dup === 0, '市场资讯标题无重复（唯一键去重生效）', `rows=${rows.length} dup=${dup}`)
  assert(rows.length >= beforeCount, '资讯总量未减少', `${beforeCount} -> ${rows.length}`)

  console.log('\n=== 6. 意图理解（规则 → 向量 → 本地 7B） ===')
  const cases = [
    ['我要退款，买的钢材规格不对', 'refund_aftersale'],
    ['帮我看看我的订单什么时候发货', 'cart_order'],
    ['怎么申请企业账期额度', 'wallet_credit']
  ]
  for (const [question, expect] of cases) {
    const res = await api('POST', '/support/intent/analyze', { question, recentTurns: [] })
    const d = res?.data || {}
    assert(res?.code === 200 && !!d.intent, `意图分析返回（${question.slice(0, 12)}…）`,
      `intent=${d.intent} source=${d.source} conf=${d.confidence}`)
    if (expect) assert(d.intent === expect, `意图命中 ${expect}`, `actual=${d.intent}`)
  }
  const intentEntries = await api('GET', '/admin/kb/entries?source=intent&limit=50')
  assert((intentEntries?.data || []).length > 0, '意图示例已写入向量库', `n=${(intentEntries?.data || []).length}`)

  console.log('\n=== 7. 本地 7B / VL 模型 RAG 路由 ===')
  const askText = await api('POST', '/admin/kb/ask', { question: '企业账期怎么申请？', topK: 4, model: 'text' })
  const at = askText?.data || {}
  assert(askText?.code === 200 && at.answer, 'RAG(text) 生成回答', `routed=${at.routed} model=${at.model} hits=${at.hits} ${at.elapsedMs}ms`)
  const askAuto = await api('POST', '/admin/kb/ask', { question: '会员等级折扣规则是什么？', topK: 4, model: 'auto' })
  const aa = askAuto?.data || {}
  assert(askAuto?.code === 200 && aa.answer, 'RAG(auto) 生成回答', `routed=${aa.routed || aa.model} hits=${aa.hits}`)

  console.log('\n=== 8. 视觉模型 qwen2.5-VL（网关 /v1/vision） ===')
  try {
    const fs = await import('node:fs')
    const imgPath = new URL('./.verify-out/vl-test-image.png', import.meta.url)
    const b64 = fs.readFileSync(imgPath).toString('base64')
    const v0 = Date.now()
    const vision = await gateway('/v1/vision', {
      method: 'POST',
      body: JSON.stringify({ model: 'qwen2.5-vl', prompt: '请读出图片中的全部文字与数字，并一句话总结。', image_base64: b64, max_tokens: 220 })
    })
    const vText = vision.json?.choices?.[0]?.message?.content || ''
    assert(vision.status === 200 && vText.length > 0 && /3850|螺纹钢|水泥/.test(vText), 'VL 视觉读图返回', `${fmtMs(Date.now() - v0)} text=${vText.slice(0, 60)}`)
  } catch (e) {
    assert(false, 'VL 视觉读图', 'skipped: ' + e.message)
  }

  console.log(`\n=== ${failed === 0 ? 'ALL AI FEATURE CHECKS PASSED' : failed + ' CHECK(S) FAILED'} ===`)
  process.exit(failed === 0 ? 0 : 1)
}

main().catch(e => { console.error('crashed:', e); process.exit(1) })
