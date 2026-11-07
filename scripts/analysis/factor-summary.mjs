/* 五维因子跑批汇总 + 完整性门禁
 * 用法：node scripts/analysis/factor-summary.mjs [--out out/factors] [--base http://127.0.0.1:8080/api]
 * 输出 out/factors/summary.json；stdout 末行 {"complete":bool,...}；complete=false 时退出码 1。
 */
const args = process.argv.slice(2)
const get = (name, dflt) => {
  const i = args.indexOf('--' + name)
  return i >= 0 ? args[i + 1] : dflt
}
const BASE = get('base', 'http://127.0.0.1:8080/api')
const OUT_DIR = get('out', 'out/factors')
const fs = await import('node:fs')
const path = await import('node:path')

const FIVE = ['sentiment', 'supply', 'material', 'international', 'politics']

async function jfetch(url) {
  const r = await fetch(url, { signal: AbortSignal.timeout(30000) })
  return r.json()
}

// 全量在售商品清单（完整性基准）
const all = []
for (let current = 1; ; current++) {
  const j = await jfetch(`${BASE}/product/page?current=${current}&size=100`)
  if (j?.code !== 200) throw new Error('product/page failed')
  for (const rec of j.data?.records || []) all.push({ prodId: rec.prodId, title: rec.title })
  if (current * 100 >= Number(j.data?.total || 0)) break
}
const byId = new Map(all.map(p => [p.prodId, p]))

// 读全部分片
const profiles = new Map()
const fails = new Map()
for (const f of fs.readdirSync(OUT_DIR)) {
  if (!/^shard-\d+\.jsonl$/.test(f)) continue
  for (const line of fs.readFileSync(path.join(OUT_DIR, f), 'utf8').split('\n')) {
    if (!line.trim()) continue
    try {
      const rec = JSON.parse(line)
      if (rec.ok) profiles.set(rec.prodId, rec)
      else fails.set(rec.prodId, rec.error || 'failed')
    } catch {}
  }
}

// 完整性：每个在售商品都有 ok 画像，且五维齐全
const missing = []
const missingDims = []
let dominantDist = {}
let msTotal = 0
for (const p of all) {
  const rec = profiles.get(p.prodId)
  if (!rec) { missing.push(p.prodId); continue }
  msTotal += rec.ms || 0
  const dims = Array.isArray(rec.data?.factors) ? rec.data.factors.map(x => (x && (x.key || x.dimension)) || '') : Object.keys(rec.data?.factors || {})
  for (const k of FIVE) if (!dims.includes(k)) missingDims.push(p.prodId + ':' + k)
  const d = rec.data?.dominant
  if (d) dominantDist[d] = (dominantDist[d] || 0) + 1
}
const complete = missing.length === 0 && missingDims.length === 0

const summary = {
  complete,
  totalProducts: all.length,
  okProfiles: profiles.size,
  failed: [...fails.entries()].map(([prodId, error]) => ({ prodId, error })),
  missing,
  missingDims: missingDims.slice(0, 50),
  avgLatencyMs: profiles.size ? Math.round(msTotal / profiles.size) : null,
  dominantDist,
  generatedAt: new Date().toISOString(),
}
fs.writeFileSync(path.join(OUT_DIR, 'summary.json'), JSON.stringify(summary, null, 2))
console.log(JSON.stringify({ complete: summary.complete, ok: summary.okProfiles, total: summary.totalProducts, missing: summary.missing.length, badDims: summary.missingDims.length }))
if (!complete) {
  console.error('[summary] INCOMPLETE: missing=' + missing.length + ' badDims=' + missingDims.length)
  process.exitCode = 1
}
