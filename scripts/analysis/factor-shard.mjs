/* 五维因子分析分片跑批器（P1 算法链路的批量生产工具）
 * 用法：node scripts/analysis/factor-shard.mjs --shard 0 --total 6 [--base http://127.0.0.1:8080/api] [--out out/factors]
 * 行为：
 *   1. 分页拉取全部在售商品（/product/page）
 *   2. 按 index % total === shard 取本分片
 *   3. 断点续跑：out/factors/shard-{i}.jsonl 里已 ok 的 prodId 跳过
 *   4. 并发池（6）逐个调 /price-monitor/factors/{prodId}，逐行追加 JSONL
 *   5. 末行输出统计 JSON：{"shard":i,"ok":x,"fail":y,"failIds":[...]}
 */
const args = process.argv.slice(2)
const get = (name, dflt) => {
  const i = args.indexOf('--' + name)
  return i >= 0 ? args[i + 1] : dflt
}
const SHARD = Number(get('shard', '0'))
const TOTAL = Number(get('total', '1'))
const BASE = get('base', 'http://127.0.0.1:8080/api')
const OUT_DIR = get('out', 'out/factors')
const CONC = Number(get('conc', '6'))
const fs = await import('node:fs')
const path = await import('node:path')

fs.mkdirSync(OUT_DIR, { recursive: true })
const shardFile = path.join(OUT_DIR, `shard-${SHARD}.jsonl`)

async function jfetch(url, timeoutMs = 30000) {
  const r = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) })
  return r.json()
}

// 1. 拉全部在售商品
const all = []
for (let current = 1; ; current++) {
  const j = await jfetch(`${BASE}/product/page?current=${current}&size=100`)
  if (j?.code !== 200) throw new Error('product/page failed: ' + JSON.stringify(j).slice(0, 200))
  for (const rec of j.data?.records || []) all.push({ prodId: rec.prodId, title: rec.title })
  if (current * 100 >= Number(j.data?.total || 0)) break
}
const mine = all.filter((_, idx) => idx % TOTAL === SHARD)

// 2. 断点续跑
const doneIds = new Set()
if (fs.existsSync(shardFile)) {
  for (const line of fs.readFileSync(shardFile, 'utf8').split('\n')) {
    if (!line.trim()) continue
    try { const rec = JSON.parse(line); if (rec.ok) doneIds.add(rec.prodId) } catch {}
  }
}
const todo = mine.filter(p => !doneIds.has(p.prodId))
console.error(`[shard ${SHARD}] products=${mine.length} done=${doneIds.size} todo=${todo.length}`)

// 3. 并发池
let cursor = 0
let ok = doneIds.size
const failIds = []
async function worker() {
  while (cursor < todo.length) {
    const item = todo[cursor++]
    const t0 = Date.now()
    let rec
    try {
      const j = await jfetch(`${BASE}/price-monitor/factors/${item.prodId}?lang=zh`)
      const d = j?.data
      const dims = Array.isArray(d?.factors) ? d.factors.map(x => (x && (x.key || x.dimension)) || '') : Object.keys(d?.factors || [])
      const hasFive = ['sentiment', 'supply', 'material', 'international', 'politics'].every(k => dims.includes(k))
      rec = { prodId: item.prodId, title: item.title, ok: j?.code === 200 && !!d && hasFive, ms: Date.now() - t0, code: j?.code, data: ok2(d) ? d : undefined, error: j?.code === 200 ? undefined : (j?.message || 'bad shape') }
      if (!rec.ok) { rec.error = rec.error || 'missing dimensions: ' + dims.join(',') }
    } catch (e) {
      rec = { prodId: item.prodId, title: item.title, ok: false, ms: Date.now() - t0, error: String(e.cause?.message || e.message).slice(0, 160) }
    }
    if (rec.ok) ok++
    else failIds.push(rec.prodId)
    fs.appendFileSync(shardFile, JSON.stringify(rec) + '\n')
    process.stdout.write(`[shard ${SHARD}] ${rec.ok ? 'ok' : 'FAIL'} ${rec.prodId} ${rec.ms}ms\n`)
  }
}
function ok2(d) { return !!d && typeof d === 'object' }
await Promise.all(Array.from({ length: Math.min(CONC, todo.length || 1) }, worker))

// 4. 统计（末行 JSON，供工作流解析）
console.log(JSON.stringify({ shard: SHARD, ok, fail: failIds.length, failIds: failIds.slice(0, 50) }))
if (failIds.length > 0) process.exitCode = 1
