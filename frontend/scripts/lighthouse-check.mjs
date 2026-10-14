// Lighthouse 验收评测（生产 preview + 登录态 cookie）
import lighthouse from 'lighthouse'
import * as chromeLauncher from 'chrome-launcher'
import { writeFileSync } from 'node:fs'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const SESSION = process.env.LH_SESSION // JSESSIONID 可选

const flags = {
  onlyCategories: ['performance', 'accessibility', 'best-practices'],
  output: 'json',
  chromeFlags: ['--headless=new', '--no-sandbox', '--disable-gpu', '--no-first-run'],
  preset: 'desktop',
  throttlingMethod: 'provided', // 真实网络，不做移动端模拟节流
  formFactor: 'desktop',
  screenEmulation: { mobile: false, width: 1440, height: 900 }
}

async function run(url) {
  const chrome = await chromeLauncher.launch({ chromePath: CHROME, chromeFlags: flags.chromeFlags })
  try {
    const opts = { ...flags, port: chrome.port }
    if (url.includes('/me') && SESSION) opts.extraHeaders = { Cookie: `JSESSIONID=${SESSION}` }
    const result = await lighthouse(url, opts)
    const cat = result.lhr.categories
    const get = (id) => result.lhr.audits[id]
    console.log(`\n===== ${url} =====`)
    console.log(`performance=${Math.round(cat.performance.score * 100)} accessibility=${Math.round(cat.accessibility.score * 100)} best-practices=${Math.round(cat['best-practices'].score * 100)}`)
    console.log(`FCP=${get('first-contentful-paint').displayValue} LCP=${get('largest-contentful-paint').displayValue} SI=${get('speed-index').displayValue} TBT=${get('total-blocking-time').displayValue}`)
    const axFails = cat.accessibility.auditRefs.map((r) => ({ id: r.id, w: r.weight })).filter((r) => r.w > 0)
      .map((r) => ({ ...r, a: get(r.id) })).filter((r) => r.a.score != null && r.a.score < 1)
    if (axFails.length) {
      console.log('AX 未通过项:')
      for (const f of axFails) {
        const items = f.a.details?.items || []
        const first = items[0]
        const sel = first?.node?.selector ? ` [${first.node.selector}]` : ''
        console.log(`  - ${f.a.title}${sel}`)
      }
    } else console.log('AX 全部通过')
    const name = url.replace('http://localhost:4173', '').replace(/[/:]/g, '_') || '_'
    writeFileSync(`${process.env.TEMP}\\lh_final_${name}.json`, JSON.stringify(result.lhr))
    return result.lhr
  } finally {
    await chrome.kill()
  }
}

await run('http://localhost:4173/')
await run('http://localhost:4173/login')
if (SESSION) await run('http://localhost:4173/me')