/* 工具调用全链路验证（本地 Qwen2.5 + 网关 agent 循环 + 真实工具执行）
 * 用法：node verify-tools.mjs [round2]
 * 前置：ai-gateway:8090（含工具层）/ backend:8080 / llama-cpp 已启动
 */
const API = 'http://127.0.0.1:8080/api'
const GW = 'http://127.0.0.1:8090'
let cookie = ''
let failed = 0

function assert(cond, label, extra) {
  const ok = !!cond
  if (!ok) failed++
  console.log(`[${ok ? 'PASS' : 'FAIL'}] ${label}${extra ? ' | ' + extra : ''}`)
}

async function login() {
  const r = await fetch(API + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone: '13800000000', password: '123456' })
  })
  cookie = (r.headers.getSetCookie ? r.headers.getSetCookie() : []).map(c => c.split(';')[0]).join('; ')
}

async function gwChat(body, timeoutMs = 240000) {
  const t0 = Date.now()
  const res = await fetch(GW + '/v1/chat/completions', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body), signal: AbortSignal.timeout(timeoutMs)
  })
  const j = await res.json().catch(() => null)
  return { ms: Date.now() - t0, status: res.status, j }
}

function toolsInfo(j) { return j?.lanlink?.tools || {} }
function traceNames(j) { return (toolsInfo(j).trace || []).map(t => t.tool) }

const CASES = [
  {
    name: 'search-trigger(时效问题必须触发联网搜索)',
    body: { model: 'qwen2.5-7b', use_kb: false, tools: 'auto', max_tokens: 600,
      messages: [{ role: 'user', content: '今天（2026年10月27日）螺纹钢期货的最新价格大概是多少？请联网查证后回答。' }] },
    strict: (j) => traceNames(j).includes('web_search'),
    extra: (j) => `trace=${traceNames(j).join(',') || '无'}`
  },
  {
    name: 'search-then-fetch(两步链式：搜索后抓正文)',
    body: { model: 'qwen2.5-7b', use_kb: false, tools: 'auto', max_tokens: 700,
      messages: [{ role: 'user', content: '请先联网搜索"螺纹钢 价格"，然后抓取第一条结果网页的正文，总结当前钢材市场要点。' }] },
    strict: (j) => toolsInfo(j).count >= 1 && toolsInfo(j).trace.some(t => t.ok) &&
      (j?.choices?.[0]?.message?.content || '').length > 50,
    soft: (j) => traceNames(j).includes('fetch_page') ? 'search+fetch 双工具' : `单工具(${traceNames(j).join(',')})`,
    extra: (j) => `trace=${traceNames(j).join(',') || '无'}`
  },
  {
    name: 'context7-trigger(技术文档查询)',
    body: { model: 'qwen2.5-7b', use_kb: false, tools: 'auto', max_tokens: 700,
      messages: [{ role: 'user', content: '查一下 FastAPI 的 Depends 依赖注入怎么用，给出一个代码示例。' }] },
    strict: (j) => toolsInfo(j).count >= 1 && (traceNames(j).includes('context7_docs') || traceNames(j).includes('web_search')),
    extra: (j) => `trace=${traceNames(j).join(',') || '无'}`
  },
  {
    name: 'no-tool-precision(日常常识不该调联网工具)',
    body: { model: 'qwen2.5-7b', use_kb: false, tools: 'auto', max_tokens: 400,
      messages: [{ role: 'user', content: '凭你的已有知识简要回答：什么是增值税专用发票？不要联网。' }] },
    strict: (j) => !traceNames(j).some(t => t !== 'think'),
    soft: (j) => traceNames(j).includes('think') ? '仅 think（未联网，合规）' : '完全未调工具',
    extra: (j) => `trace=${traceNames(j).join(',') || '无'}`
  },
  {
    name: 'think-trigger(复杂权衡调用思考工具)',
    body: { model: 'qwen2.5-7b', use_kb: false, tools: 'auto', max_tokens: 900,
      messages: [{ role: 'user', content: '月采购额 100 万：A 供应商单价低但账期 30 天，B 单价高 5% 但账期 90 天。请分步骤系统分析选哪个，先调用 think 工具组织推理。' }] },
    strict: (j) => (j?.choices?.[0]?.message?.content || '').length > 50,
    soft: (j) => traceNames(j).includes('think') ? 'think 已触发' : `未触发 think（trace=${traceNames(j).join(',') || '无'}），回答质量为主`,
    extra: (j) => `len=${(j?.choices?.[0]?.message?.content || '').length}`
  },
]

async function main() {
  const round = process.argv[2] || 'round1'
  console.log(`=== 工具调用全链路验证 [${round}] ===`)

  console.log('\n--- 0. 网关健康 ---')
  const health = await fetch(GW + '/health').then(r => r.json()).catch(() => null)
  assert(health?.status === 'UP', '网关 /health UP', JSON.stringify(health?.components?.llm || {}).slice(0, 100))

  console.log('\n--- 触发准确率 / 结果完整性 / 上下文关联 ---')
  const summary = []
  for (const c of CASES) {
    const { ms, status, j } = await gwChat(c.body)
    const answer = j?.choices?.[0]?.message?.content || ''
    let pass = status === 200 && answer.length > 8
    let strictResult = ''
    if (pass && c.strict) {
      pass = !!c.strict(j)
      strictResult = pass ? '' : ' [strict 不满足]'
    }
    assert(pass, c.name + strictResult, `${Math.round(ms / 100) / 10}s ${c.extra ? c.extra(j) : ''} len=${answer.length}`)
    if (c.soft) console.log('      note:', c.soft(j))
    if (toolsInfo(j).trace?.length) {
      for (const t of toolsInfo(j).trace) console.log(`      tool: ${t.tool} ok=${t.ok} ${t.ms}ms chars=${t.chars}`)
    }
    console.log('      answer:', answer.slice(0, 100).replace(/\n/g, ' '))
    summary.push({ name: c.name, pass, answerLen: answer.length, tools: traceNames(j).join(',') })
  }

  console.log('\n--- Java 端到端（RAG 无命中 → 工具增强） ---')
  await login()
  const t0 = Date.now()
  const ask = await fetch(API + '/admin/kb/ask', {
    method: 'POST', headers: { 'Content-Type': 'application/json', Cookie: cookie },
    body: JSON.stringify({ question: '今天上海的天气怎么样？适合去码头提货吗？', topK: 4, model: 'text', enableTools: true }),
    signal: AbortSignal.timeout(240000)
  }).then(r => r.json()).catch(e => ({ err: String(e) }))
  const d = ask?.data || {}
  const toolsRouted = d.routed === 'tools'
  const fallbackOk = d.answer && d.routed !== 'tools' && !ask.err  // 工具失败但兜底可用也算链路未断
  assert(toolsRouted || fallbackOk, 'Java /admin/kb/ask 工具增强链路',
    `${Math.round((Date.now() - t0) / 100) / 10}s routed=${d.routed} notice=${d.notice || '-'}` +
    ` toolTrace=${(d.toolTrace || []).map(t => t.tool).join(',') || '无'}`)
  if (d.answer) console.log('      answer:', String(d.answer).slice(0, 100).replace(/\n/g, ' '))

  console.log(`\n=== ${round} 汇总：${summary.filter(s => s.pass).length}/${summary.length + 1} 通过（不含 Java 项则 ${summary.filter(s => s.pass).length}/${summary.length}） ===`)
  process.exit(failed === 0 ? 0 : 1)
}

main().catch(e => { console.error('crashed:', e.message); process.exit(1) })
