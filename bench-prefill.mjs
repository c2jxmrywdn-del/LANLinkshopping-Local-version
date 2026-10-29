import { readFileSync } from 'node:fs'
import { execSync } from 'node:child_process'
const R = 'http://127.0.0.1:18123/v1/chat/completions'
const MODEL = 'qwen2.5-7b'
const pw = readFileSync('D:/桌面/LANLinkShopping/Shopping/.env', 'utf8').split('\n').find(l => l.startsWith('REDIS_PASSWORD=')).slice(15).trim()
let redisSamples = []
let redisTimer = null
const rping = () => { const t = Date.now(); try { execSync(`docker exec shopping-redis-1 redis-cli -a ${pw} --no-auth-warning PING`, { stdio: 'ignore' }); return Date.now() - t } catch (e) { return 999 } }
function startMonitor() { redisSamples = []; redisTimer = setInterval(() => redisSamples.push(rping()), 2000) }
function stopMonitor() { clearInterval(redisTimer); const max = Math.max(...redisSamples); const avg = (redisSamples.reduce((a, b) => a + b, 0) / redisSamples.length).toFixed(1); return `Redis avg=${avg}ms max=${max}ms` }

// 40 段互不重复的行业资料（真实预填负载，BPE 无法压缩）
const TOPICS = [
  ['螺纹钢', 'HRB400E 规格 12-25mm，现货 5000 吨，上海仓自提'],
  ['水泥', 'P.O42.5 散装水泥，周转库存 8000 吨，苏南厂家直供'],
  ['电解铜', '99.95% A 级电解铜 200 吨，保税仓仓单交易'],
  ['铝合金型材', '6061-T6 工业型材，定制开模周期 15 天'],
  ['PPR 管材', 'DN20-DN110 全规格，Hy 抗菌系列 5000 米'],
  ['变压器', 'SCB13 干式变压器 1250kVA，两台现期货'],
  ['液压油', 'L-HM46 抗磨液压油 200 桶，长城品牌'],
  ['工字钢', 'Q235B 20# 工字钢 300 吨，日照港齐货'],
  [' electrolytic zinc', '0# 锌锭 100 吨，上期所仓单'],
  ['聚乙烯', 'LLDPE 7042 颗粒 500 吨，煤化工源头价']
]
function buildDiverse(targetChars) {
  let out = ''
  let i = 0
  while (out.length < targetChars) {
    const [name, desc] = TOPICS[i % TOPICS.length]
    const day = (i % 28) + 1
    out += `第${i + 1}期市场快报（${day}日）：${name}方面，${desc}；近期到港量与齐货节奏有所变化，下游按需补库，贸易商报价随行就市，资金面保持稳健。\n`
    i++
  }
  return out
}

async function gen(label, opts = {}) {
  const { maxTokens = 8, promptChars = 0, system = null } = opts
  const filler = promptChars ? buildDiverse(promptChars) : ''
  const content = (filler ? filler + '\n\n' : '') + '基于以上资料，用一句话判断未来两周价格方向。'
  const t = Date.now()
  const messages = system ? [{ role: 'system', content: system }, { role: 'user', content }] : [{ role: 'user', content }]
  const resp = await fetch(R, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ model: MODEL, messages, stream: true, max_tokens: maxTokens }) })
  if (!resp.ok) return { label, error: 'http ' + resp.status + ' ' + (await resp.text()).slice(0, 120) }
  const reader = resp.body.getReader()
  const dec = new TextDecoder()
  let first = 0, n = 0
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    for (const line of dec.decode(value).split('\n')) {
      if (!line.startsWith('data:')) continue
      const p = line.slice(5).trim()
      if (p === '[DONE]') continue
      try { if (JSON.parse(p).choices?.[0]?.delta?.content !== undefined) { if (!first) first = Date.now() - t; n++ } } catch (e) {}
    }
  }
  return { label, ttft: first, tokens: n, total: Date.now() - t }
}

async function main() {
  const mode = process.argv[2] || 'run'
  console.log('=== [' + mode + '] 真实预填（不重复 4000 字）===')
  startMonitor()
  // 预填：4000 字不重复资料 → TTFT 即预填耗时（max_tokens=1 消除生成干扰）
  const p4000 = await gen('prefill-4000ch-diverse', { promptChars: 4000, maxTokens: 1 })
  console.log(JSON.stringify(p4000))
  const p8000 = await gen('prefill-8000ch-diverse', { promptChars: 8000, maxTokens: 1 })
  console.log(JSON.stringify(p8000))
  // 生成速度（短 prompt 200 token）
  const g = await gen('gen-200tok', { maxTokens: 220 })
  console.log(JSON.stringify(g))
  console.log('[STABILITY]', stopMonitor())
}
main()
