// 商品图片批量抓取管道：品名 → Bing 真图搜索 → turl CDN 下载 → 魔数校验 → md5 查重 → 落盘
// 用法:
//   node scripts/img/product-img-crawler.mjs --limit 6          # 小样试跑（前 6 个未完成商品）
//   node scripts/img/product-img-crawler.mjs --all              # 全量（断点续跑：跳过已完成）
//   node scripts/img/product-img-crawler.mjs --ids 12,34        # 指定商品强制重抓
//   node scripts/img/product-img-crawler.mjs --ids 12 --queries-file out/imgreplace/alt.json
// 输出: frontend/public/products/<prodId>.<jpg|png|webp> + out/imgreplace/progress.jsonl 台账
//       结束时 stdout 最后一行输出汇总 JSON {"ok","fail","failIds","reused","total"}
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'

const ROOT = 'D:/桌面/LANLinkShopping/Shopping'
const OUT_DIR = path.join(ROOT, 'out/imgreplace')
const PUB_DIR = path.join(ROOT, 'frontend/public/products')
const PRODS_TSV = path.join(OUT_DIR, 'prods.tsv')
const CATS_TSV = path.join(OUT_DIR, 'cats.tsv')
const PROGRESS = path.join(OUT_DIR, 'progress.jsonl')
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

// ── 参数
const argVal = (name) => {
  const i = process.argv.indexOf(name)
  return i > 0 ? process.argv[i + 1] : undefined
}
const limit = Number(argVal('--limit') ?? 0)
const all = process.argv.includes('--all')
const idsArg = argVal('--ids')
const queriesFile = argVal('--queries-file')
const concurrency = Math.max(1, Number(argVal('--concurrency') ?? 6))
const queryOverrides = queriesFile ? JSON.parse(fs.readFileSync(queriesFile, 'utf8')) : {}
// 禁用哈希清单（--avoid-json）：{"hashes":["md5",...]}，命中即跳过，用于"不重样"修复轮
const avoidFile = argVal('--avoid-json')
const avoidSet = new Set(avoidFile ? ((JSON.parse(fs.readFileSync(avoidFile, 'utf8')).hashes) ?? []) : [])

// ── 数据
const parseTsv = (p) => fs.readFileSync(p, 'utf8').trim().split(/\r?\n/)
  .map(l => l.split('\t'))
const products = parseTsv(PRODS_TSV).map(([id, title, catId]) => ({ id: Number(id), title, catId: Number(catId) }))
const catName = new Map(parseTsv(CATS_TSV).map(([id, name]) => [Number(id), name]))

// ── 台账（断点续跑）
const doneIds = new Set()
if (fs.existsSync(PROGRESS)) {
  for (const l of fs.readFileSync(PROGRESS, 'utf8').split(/\r?\n/)) {
    if (!l.trim()) continue
    try { const r = JSON.parse(l); if (r.ok) doneIds.add(r.id) } catch { /* 跳过坏行 */ }
  }
}
const seenHash = new Map() // md5 -> prodId 首次使用者
if (fs.existsSync(PROGRESS)) {
  for (const l of fs.readFileSync(PROGRESS, 'utf8').split(/\r?\n/)) {
    if (!l.trim()) continue
    try { const r = JSON.parse(l); if (r.ok && r.hash) seenHash.set(r.hash, r.id) } catch { /* 跳过 */ }
  }
}

// ── 品名清洗：去掉规格碎片（Φ/×/mm/吨/定尺/现货/括号补语），保留品类与牌号关键词
const cleanTitle = (title) => {
  const stripped = title.replace(/（[^）]*）/g, ' ').replace(/\([^)]*\)/g, ' ')
  const tokens = stripped.split(/[\s,，、/|]+/).filter(Boolean).filter(t => {
    if (/定尺|现货|批发|厂家|厂家直销|供应|热销|包邮/.test(t)) return false
    if (/[Φφ]|×|x\d/i.test(t) && /\d/.test(t)) return false
    if (/^\d+(\.\d+)?(mm|cm|m|吨|kg|g|米|支|卷|张|桶|箱)$/i.test(t)) return false
    if (/^\d+(\.\d+)?$/.test(t)) return false
    return true
  })
  return tokens.join(' ').trim()
}
const buildQueries = (p) => {
  const core = cleanTitle(p.title)
  const cn = catName.get(p.catId) ?? ''
  const list = []
  if (queryOverrides[p.id]) list.push(queryOverrides[p.id])
  if (core) list.push(core)
  const coreHead = core.split(' ').slice(0, 2).join(' ')
  if (cn && coreHead && coreHead !== core) list.push(`${cn} ${coreHead}`.trim())
  if (cn) list.push(cn)
  return [...new Set(list.filter(Boolean))].slice(0, 4)
}

// ── Bing 图片搜索：解析 m="{...}"（HTML 转义 JSON）→ [{murl,turl,purl}]
const unescapeHtml = (s) => s
  .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<')
  .replace(/&gt;/g, '>').replace(/&amp;/g, '&')
async function searchBing(q, first = 1) {
  const url = `https://www.bing.com/images/search?q=${encodeURIComponent(q)}&form=HDRSC2&first=${first}&count=35`
  const res = await fetch(url, { headers: { 'user-agent': UA, 'accept-language': 'zh-CN,zh;q=0.9', accept: 'text/html' }, signal: AbortSignal.timeout(15000) })
  if (!res.ok) throw new Error(`search HTTP ${res.status}`)
  const html = await res.text()
  const out = []
  for (const m of html.matchAll(/m="([^"]+)"/g)) {
    try {
      const j = JSON.parse(unescapeHtml(m[1]))
      if (j.turl && j.murl) out.push({ murl: j.murl, turl: j.turl, purl: j.purl ?? '' })
    } catch { /* 非图片元数据块，跳过 */ }
  }
  return out
}

// ── 下载 + 魔数校验
const magicExt = (buf) => {
  if (buf.length < 12) return null
  if (buf[0] === 0xFF && buf[1] === 0xD8 && buf[2] === 0xFF) return 'jpg'
  if (buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4E && buf[3] === 0x47) return 'png'
  if (buf.slice(0, 4).toString('latin1') === 'RIFF' && buf.slice(8, 12).toString('latin1') === 'WEBP') return 'webp'
  return null
}
async function downloadImage(url) {
  const res = await fetch(url, { headers: { 'user-agent': UA, accept: 'image/*,*/*;q=0.8' }, signal: AbortSignal.timeout(15000) })
  if (!res.ok) throw new Error(`image HTTP ${res.status}`)
  const buf = Buffer.from(await res.arrayBuffer())
  const ext = magicExt(buf)
  if (!ext) throw new Error(`bad magic (${buf.length}B)`)
  if (buf.length < 6000) throw new Error(`too small ${buf.length}B`)
  return { buf, ext }
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms))
const appendProgress = (row) => fs.appendFileSync(PROGRESS, JSON.stringify(row) + '\n')

// ── 单商品流程：逐查询变体×逐结果页级联尝试，只接受"从未被其他商品用过且不在禁用清单"的图（不重样）
async function processOne(p) {
  const queries = buildQueries(p)
  const tried = []
  for (const q of queries) {
    for (const first of [1, 36]) {
      let candidates = []
      try { candidates = await searchBing(q, first) } catch (e) { tried.push(`${q}#p${first} → search ${e.message}`); await sleep(400); continue }
      await sleep(120 + Math.floor(Math.random() * 230))
      for (const cand of candidates) {
        try {
          const { buf, ext } = await downloadImage(cand.turl)
          const hash = crypto.createHash('md5').update(buf).digest('hex')
          if (seenHash.has(hash) || avoidSet.has(hash)) continue // 已被占用或列入禁用清单 → 跳过
          seenHash.set(hash, p.id)
          return { ...writeFile(p, buf, ext), q, murl: cand.murl, purl: cand.purl, hash, reused: false }
        } catch (e) { tried.push(`${q}#p${first} → ${e.message}`) }
      }
    }
  }
  return { ok: false, error: `no unique candidate (${tried.length} attempts): ${tried.slice(0, 4).join(' | ')}` }
}
function writeFile(p, buf, ext) {
  const file = path.join(PUB_DIR, `${p.id}.${ext}`)
  fs.writeFileSync(file, buf)
  return { ok: true, ext, bytes: buf.length, file: `products/${p.id}.${ext}` }
}

// ── 目标集合
let targets = products
if (idsArg) {
  const wanted = new Set(idsArg.split(',').map(Number))
  targets = products.filter(p => wanted.has(p.id))
} else if (!all) {
  targets = targets.filter(p => !doneIds.has(p.id)).slice(0, limit || 6)
} else {
  targets = targets.filter(p => !doneIds.has(p.id))
}
console.error(`targets: ${targets.length} (done ${doneIds.size}/${products.length}) concurrency=${concurrency}`)

// ── 并发池
let idx = 0
const results = []
async function worker() {
  while (idx < targets.length) {
    const p = targets[idx++]
    const r = await processOne(p)
    results.push({ id: p.id, title: p.title, ...r })
    appendProgress({ id: p.id, ...r })
    console.error(`#${p.id} ${r.ok ? `OK ${r.ext} ${(r.bytes / 1024).toFixed(0)}KB${r.reused ? ' [复用]' : ''} q="${r.q}"` : `FAIL ${r.error}`}`)
  }
}
await Promise.all(Array.from({ length: concurrency }, worker))

// ── 汇总
const okRows = results.filter(r => r.ok)
const summary = {
  ok: okRows.length + doneIds.size,
  fail: results.filter(r => !r.ok).length,
  failIds: results.filter(r => !r.ok).map(r => r.id),
  reused: okRows.filter(r => r.reused).length,
  total: products.length,
}
// 全量完成时聚合 credits2.json（来源审计清单）
if (all || (!limit && !idsArg)) {
  const ledger = fs.readFileSync(PROGRESS, 'utf8').split(/\r?\n/).filter(Boolean)
    .map(l => { try { return JSON.parse(l) } catch { return null } }).filter(Boolean)
  const best = new Map()
  for (const r of ledger) if (r.ok) best.set(r.id, r)
  const credits = products.filter(p => best.has(p.id)).map(p => {
    const r = best.get(p.id)
    return { prodId: p.id, title: p.title, query: r.q ?? '', file: r.file, originalUrl: r.murl ?? null, sourcePage: r.purl ?? null, reused: !!r.reused, bytes: r.bytes }
  })
  fs.writeFileSync(path.join(OUT_DIR, 'credits2.json'), JSON.stringify(credits, null, 2))
}
console.log(JSON.stringify(summary))
