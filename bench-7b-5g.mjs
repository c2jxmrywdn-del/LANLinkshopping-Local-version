import { readFileSync } from 'node:fs'
import { execSync } from 'node:child_process'
const pw = readFileSync('D:/桌面/LANLinkShopping/Shopping/.env', 'utf8').split('\n').find(l => l.startsWith('REDIS_PASSWORD=')).slice(15).trim()
let redisSamples = []
let redisTimer = null
const rping = () => { const t = Date.now(); try { execSync(`docker exec shopping-redis-1 redis-cli -a ${pw} --no-auth-warning PING`, { stdio: 'ignore' }); return Date.now() - t } catch (e) { return 999 } }
function startMonitor() { redisSamples = []; redisTimer = setInterval(() => redisSamples.push(rping()), 2000) }
function stopMonitor() { clearInterval(redisTimer); const max = Math.max(...redisSamples); const avg = (redisSamples.reduce((a, b) => a + b, 0) / redisSamples.length).toFixed(1); return `Redis avg=${avg}ms max=${max}ms (${redisSamples.length} samples)` }

async function chat7b(label, { numCtx = 8192, prompt = '用 100 字介绍 B2B 电商平台的核心功能。', expectTokens = 120 } = {}) {
  const t = Date.now()
  const resp = await fetch('http://127.0.0.1:11434/api/chat', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: 'qwen2.5:7b', messages: [{ role: 'user', content: prompt }], stream: false, keep_alive: '10m', options: { num_ctx: numCtx, temperature: 0.2 } })
  })
  const j = await resp.json().catch(() => null)
  const ms = Date.now() - t
  if (!j) return { label, error: 'http ' + resp.status, ms }
  const evalTok = j.eval_count || 0
  const evalSec = (j.eval_duration || 0) / 1e9
  const promptTok = j.prompt_eval_count || 0
  const promptSec = (j.prompt_eval_duration || 0) / 1e9
  return {
    label, ms,
    loadOrFirst: j.prompt_eval_count === undefined ? '-' : Math.round(promptSec * 1000) + 'ms/' + promptTok + 'tok',
    tps: evalSec > 0 ? (evalTok / evalSec).toFixed(1) : '-',
    evalTok, promptTok, promptTps: promptSec > 0 ? Math.round(promptTok / promptSec) : '-',
    text: (j.message?.content || '').slice(0, 60)
  }
}

async function main() {
  console.log('=== [ollama 5G 限] qwen2.5:7b 性能实测 ===')
  startMonitor()
  // 0. 确认模型在
  const list = execSyncSafe()
  function execSyncSafe() { try { return execSyncSync() } catch (e) { return '' } }
  function execSyncSync() { return '' }

  // 载入 + 首次推理（含 4.7GB 模型载入时间，上限 10 分钟）
  console.log('[load+first] 开始（含模型载入，可能数分钟）……')
  const first = await chat7b('first-load num_ctx8192', { numCtx: 8192 })
  console.log(JSON.stringify(first))

  // 稳态 tokens/s：8192
  const b8 = await chat7b('steady num_ctx8192', { numCtx: 8192 })
  console.log(JSON.stringify(b8))

  // KV 上限加强档：16384
  const b16 = await chat7b('steady num_ctx16384', { numCtx: 16384 })
  console.log(JSON.stringify(b16))

  // 长输入预填（4000 字业务资料）
  const filler = '平台支持企业账期、对公转账与批量报价、增值税专票与 30-60 天信用账期。'.repeat(100)
  const bLong = await chat7b('long-input 16384', { numCtx: 16384, prompt: filler + '\n请总结平台支持的支付方式。' })
  console.log(JSON.stringify(bLong))

  // VRAM/内存观测
  const ps = execSyncWrap('docker exec lanlink-ollama ollama ps 2>&1')
  console.log('[ollama ps]', ps.trim().split('\n').slice(-1)[0])
  const mem = execSyncWrap('docker stats lanlink-ollama --no-stream --format "{{.MemUsage}} {{.MemPerc}}" 2>&1')
  console.log('[container mem]', mem.trim())

  console.log('[STABILITY]', stopMonitor())

  // 清理：卸载 7B（恢复互斥策略，只保留嵌入模型）
  execSyncWrap('docker exec lanlink-ollama ollama stop qwen2.5:7b 2>&1')
  console.log('[cleanup] 7B 已卸载')

  function execSyncWrap(c) { try { return execSync(c, { encoding: 'utf8', maxBuffer: 1024 * 1024 }) } catch (e) { return 'ERR' } }
}
function execSyncSync() { return '' }
main()
