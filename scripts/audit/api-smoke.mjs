// Backend endpoint smoke sweep: parse Spring GET mappings without path variables and probe them.
// Usage: node scripts/audit/api-smoke.mjs [--guest-only]
import fs from 'node:fs'
import path from 'node:path'

const ROOT = process.cwd()
const JAVA_ROOT = path.join(ROOT, 'backend', 'src', 'main', 'java')
const BASE = 'http://127.0.0.1:8080/api'

function walk(dir, out = []) {
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name)
    const st = fs.statSync(p)
    if (st.isDirectory()) walk(p, out)
    else if (name.endsWith('.java')) out.push(p)
  }
  return out
}

function collectMappings() {
  const paths = []
  for (const file of walk(JAVA_ROOT)) {
    const src = fs.readFileSync(file, 'utf8')
    if (!src.includes('@RestController') && !src.includes('@Controller')) continue
    const classMapping = (src.match(/@RequestMapping\("([^"]+)"\)/) || [])[1] || ''
    const re = /@GetMapping\((?:value\s*=\s*)?"([^"]+)"\)/g
    let m
    while ((m = re.exec(src))) {
      const sub = m[1].split('?')[0] // 去掉注解里的 query 模板（如 ?lang={lang}）
      if (sub.includes('{')) continue // skip path-variable endpoints
      const full = (classMapping + (sub.startsWith('/') ? sub : '/' + sub)).replace(/\/+/g, '/')
      paths.push({ path: full, file: path.relative(ROOT, file) })
    }
  }
  return paths
}

async function login() {
  const res = await fetch(BASE + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone: '13800000000', password: '123456' })
  })
  const cookies = res.headers.getSetCookie().map(x => x.split(';')[0]).join('; ')
  return { status: res.status, cookies }
}

const mappings = collectMappings()
const auth = await login()
console.log(`parsed ${mappings.length} GET endpoints (no path vars); admin login ${auth.status}`)

const results = []
for (const { path: p, file } of mappings) {
  const url = BASE + p
  try {
    const ctl = new AbortController()
    const timer = setTimeout(() => ctl.abort(), 20000)
    const r = await fetch(url, { headers: auth.cookies ? { Cookie: auth.cookies } : {}, signal: ctl.signal })
    clearTimeout(timer)
    let body = null
    const text = await r.text()
    try { body = JSON.parse(text) } catch {}
    results.push({
      path: p, status: r.status, code: body?.code ?? null,
      message: String(body?.message || '').slice(0, 60), ms: 0, file, len: text.length
    })
  } catch (e) {
    results.push({ path: p, status: 0, code: null, message: 'ERR ' + e.message, file, len: 0 })
  }
}

const bad = results.filter(r => r.status >= 400 || (r.code !== null && r.code !== 200) || r.status === 0)
console.log(`\n=== FAILURES (${bad.length}/${results.length}) ===`)
for (const r of bad.sort((a, b) => a.path.localeCompare(b.path))) {
  console.log(`${r.status || 'ERR'} code=${r.code} ${r.path} :: ${r.message} [${r.file}]`)
}
console.log('\n=== OK count:', results.length - bad.length, '===')
