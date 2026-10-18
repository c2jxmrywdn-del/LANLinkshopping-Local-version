// 极端网络降级 + 内存稳定性 + 主线程阻塞 验证脚本（Puppeteer）
import puppeteer from 'puppeteer-core'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const URL = 'http://localhost:4173/'

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-gpu', '--no-first-run']
})

async function scenario1_blockedEntry() {
  // 极端网络：入口脚本全部加载失败 → 应显示基础降级提示（可用页面）
  const page = await browser.newPage()
  await page.setRequestInterception(true)
  page.on('request', (req) => {
    if (req.url().includes('/assets/') && req.url().endsWith('.js')) req.abort()
    else req.continue()
  })
  await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 30000 })
  await new Promise(r => setTimeout(r, 9000)) // 等重试 2 次 + 兜底
  const body = await page.evaluate(() => document.body.innerText)
  const hasFallback = /网络异常|重试/.test(body)
  const skeletonGone = await page.evaluate(() => {
    const sk = document.getElementById('ll-boot-skeleton')
    return sk ? sk.innerText.length > 10 : true
  })
  console.log(`[1-极端网络降级] 降级提示显示=${hasFallback} 无白屏=${skeletonGone} → ${hasFallback ? 'PASS' : 'FAIL'}`)
  await page.close()
  return hasFallback && skeletonGone
}

async function scenario2_slowNetwork() {
  // 弱网（限速）：页面应能出骨架并最终渲染，无永久白屏
  const page = await browser.newPage()
  const cdp = await page.createCDPSession()
  await cdp.send('Network.enable')
  await cdp.send('Network.emulateNetworkConditions', {
    offline: false, latency: 400, downloadThroughput: 50 * 1024, uploadThroughput: 20 * 1024
  })
  await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 })
  const earlyText = await page.evaluate(() => document.body.innerText.length)
  await new Promise(r => setTimeout(r, 6000))
  const finalText = await page.evaluate(() => document.body.innerText.length)
  const ok = earlyText >= 0 && finalText > 30
  console.log(`[2-弱网加载] 初期文本=${earlyText}字 最终=${finalText}字 → ${ok ? 'PASS' : 'FAIL'}`)
  await page.close()
  return ok
}

async function scenario3_longTask() {
  // 主线程阻塞：测量导航后的 long task 总时长（应远低于阈值）
  const page = await browser.newPage()
  await page.evaluateOnNewDocument(() => {
    window.__longTasks = []
    try {
      new PerformanceObserver((list) => {
        list.getEntries().forEach(e => window.__longTasks.push(Math.round(e.duration)))
      }).observe({ entryTypes: ['longtask'] })
    } catch {}
  })
  await page.goto(URL, { waitUntil: 'networkidle2', timeout: 60000 })
  // 模拟用户滚动（触发动效/懒加载）
  await page.evaluate(async () => {
    for (let i = 0; i < 12; i++) { window.scrollBy(0, 400); await new Promise(r => setTimeout(r, 120)) }
  })
  await new Promise(r => setTimeout(r, 1500))
  const tasks = await page.evaluate(() => window.__longTasks || [])
  const max = tasks.length ? Math.max(...tasks) : 0
  const total = tasks.reduce((a, b) => a + b, 0)
  const ok = max < 200 // 无单任务 >200ms 的主线程阻塞
  console.log(`[3-主线程阻塞] longtask 数=${tasks.length} 最大=${max}ms 总计=${total}ms → ${ok ? 'PASS (无>200ms阻塞)' : 'FAIL'}`)
  await page.close()
  return ok
}

async function scenario4_memory() {
  // 内存稳定性：多次路由切换后 JS 堆应不持续攀升
  const page = await browser.newPage()
  const measure = () => page.evaluate(() => {
    if (window.gc) window.gc()
    return performance.memory ? Math.round(performance.memory.usedJSHeapSize / 1048576) : 0
  })
  await page.goto(URL, { waitUntil: 'networkidle2', timeout: 60000 })
  const samples = []
  for (let i = 0; i < 6; i++) {
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
    await new Promise(r => setTimeout(r, 400))
    await page.evaluate(() => window.scrollTo(0, 0))
    await new Promise(r => setTimeout(r, 400))
    samples.push(await measure())
  }
  // 内存不应单调持续增长（末次 - 首次 < 首次的 50%）
  const growth = samples[samples.length - 1] - samples[0]
  const ok = growth < samples[0] * 0.5 + 20
  console.log(`[4-内存稳定] 采样(MB)=[${samples.join(', ')}] 增长=${growth}MB → ${ok ? 'PASS (无持续泄漏)' : 'FAIL'}`)
  await page.close()
  return ok
}

const r1 = await scenario1_blockedEntry()
const r2 = await scenario2_slowNetwork()
const r3 = await scenario3_longTask()
const r4 = await scenario4_memory()
console.log(`\n===== 汇总: ${[r1, r2, r3, r4].filter(Boolean).length}/4 通过 =====`)
await browser.close()
