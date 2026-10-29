const R = 'http://127.0.0.1:18123/v1/chat/completions'
const MODEL = 'qwen2.5-7b'
let redisSamples = []
let redisTimer = null
import { readFileSync } from 'node:fs'
import { execSync } from 'node:child_process'
const pw = readFileSync('D:/桌面/LANLinkShopping/Shopping/.env', 'utf8').split('\n').find(l => l.startsWith('REDIS_PASSWORD=')).slice(15).trim()
const rping = () => { const t = Date.now(); try { execSync(`docker exec shopping-redis-1 redis-cli -a ${pw} --no-auth-warning PING`, { stdio: 'ignore' }); return Date.now() - t } catch (e) { return 999 } }
function startMonitor() { redisSamples = []; redisTimer = setInterval(() => redisSamples.push(rping()), 2000) }
function stopMonitor() { clearInterval(redisTimer); const max = Math.max(...redisSamples); const avg = (redisSamples.reduce((a, b) => a + b, 0) / redisSamples.length).toFixed(1); return `Redis avg=${avg}ms max=${max}ms (${redisSamples.length} samples)` }

async function gen(label, opts = {}) {
  const { maxTokens = 220, promptLen = 0 } = opts || {}
  const filler = promptLen ? '平台支持企业账期、对公转账与批量报价、增值税专票与 30-60 天信用账期。'.repeat(Math.ceil(promptLen / 40)) : ''
  const content = (filler ? filler + '\n' : '') + '请写一段约 200 字的 B2B 平台介绍。'
  const t = Date.now()
  const resp = await fetch(R, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ model: MODEL, messages: [{ role: 'user', content }], stream: true, max_tokens: maxTokens }) })
  if (!resp.ok) return { label, error: 'http ' + resp.status }
  const reader = resp.body.getReader()
  const dec = new TextDecoder()
  let first = 0, last = 0, n = 0
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    for (const line of dec.decode(value).split('\n')) {
      if (!line.startsWith('data:')) continue
      const payload = line.slice(5).trim()
      if (payload === '[DONE]') continue
      try {
        const j = JSON.parse(payload)
        const tok = j.choices?.[0]?.delta?.content
        if (tok !== undefined && tok !== null) { if (!first) first = Date.now() - t; n++ }
      } catch (e) {}
    }
  }
  last = Date.now() - t
  return { label, first, total: last, tokens: n, tps: n > 1 ? ((n - 1) / ((last - first) / 1000)).toFixed(1) : '-' }
}

async function main() {
  const mode = process.argv[2] || 'baseline'
  console.log('=== llama.cpp 直连基线/对比 [' + mode + '] ===')
  startMonitor()
  // 1. 生成速度（200 token）
  const g1 = await gen('gen-200tok')
  console.log(JSON.stringify(g1))
  // 2. 预填速度（4000 字符长输入 → TTFT≈prefill）
  const p1 = await gen('prefill-4000ch', { promptLen: 4000, maxTokens: 8 })
  console.log(JSON.stringify(p1))
  // 3. 并发 2 路 200 token
  const t = Date.now()
  const [c1, c2] = await Promise.all([gen('conc-1'), gen('conc-2')])
  console.log(JSON.stringify(c1)); console.log(JSON.stringify(c2))
  console.log('并发总耗时', Date.now() - t, 'ms')
  console.log('[STABILITY]', stopMonitor())
}
main()
