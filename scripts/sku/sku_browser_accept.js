#!/usr/bin/env node
/**
 * One-shot browser acceptance capture for the SKU foundation slice (Task 5).
 *
 * Opens the live merchant product-edit page with an isolated Chrome profile,
 * logs in as the demo merchant through the real login form, drives the SkuEditor
 * (add dimension -> add values -> combo matrix -> price/stock -> save), verifies
 * the tree survives a reload, then switches the locale to en-US and scans the
 * SkuEditor subtree for CJK characters.
 *
 * Writes reports/sku-editor-screenshot.png (zh-CN) and
 * reports/sku-editor-en-screenshot.png (en-US), plus
 * reports/sku-editor-browser-evidence.json.
 *
 * Credentials come from the environment (SKU_PROBE_PHONE / SKU_PROBE_PASSWORD)
 * and are never printed.
 */
const fs = require('fs')
const path = require('path')
const puppeteer = require('puppeteer-core')

const BASE = process.env.SKU_PROBE_BASE || 'http://127.0.0.1:5173'
const PHONE = process.env.SKU_PROBE_PHONE
const PASSWORD = process.env.SKU_PROBE_PASSWORD
const PROD_ID = process.env.SKU_PROBE_PROD || '1'
const OUT_DIR = process.env.SKU_PROBE_OUT || path.resolve(__dirname, '../../reports')
const CHROME = process.env.SKU_PROBE_CHROME ||
  'C:/Program Files/Google/Chrome/Application/chrome.exe'

const CJK = /[\u4e00-\u9fff]/

const evidence = {
  probe: 'sku-browser-acceptance',
  generated_at: new Date().toISOString(),
  base: BASE,
  prod_id: PROD_ID,
  steps: [],
  cjk_scan: {},
  screenshots: {},
}

function step(name, ok, detail) {
  evidence.steps.push({ name, ok: !!ok, detail })
  console.log(`[${ok ? 'PASS' : 'FAIL'}] ${name}`)
  if (!ok) console.log('       ' + JSON.stringify(detail).slice(0, 700))
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function setInputValue(page, handle, value) {
  await handle.evaluate((el, v) => {
    const proto = Object.getPrototypeOf(el)
    const desc = Object.getOwnPropertyDescriptor(proto, 'value')
    desc.set.call(el, v)
    el.dispatchEvent(new Event('input', { bubbles: true }))
    el.dispatchEvent(new Event('change', { bubbles: true }))
  }, value)
}

;(async () => {
  if (!PHONE || !PASSWORD) {
    console.error('SKU_PROBE_PHONE / SKU_PROBE_PASSWORD are required')
    process.exit(2)
  }
  fs.mkdirSync(OUT_DIR, { recursive: true })

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--window-size=1440,1600'],
    defaultViewport: { width: 1440, height: 1600 },
  })
  let exitCode = 1
  try {
    const page = await browser.newPage()
    const consoleErrors = []
    page.on('console', (m) => {
      if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 200))
    })

    // ---- login through the real form ----
    await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 60000 })
    await page.waitForSelector('input', { timeout: 20000 })
    await sleep(600)
    const demoBtn = await page.evaluateHandle(() => [...document.querySelectorAll('button')]
      .find((b) => b.textContent.includes('13700000002')))
    const demoOk = await demoBtn.evaluate((b) => !!b)
    step('login page shows the demo merchant shortcut button', demoOk, { found: demoOk })

    const submitLogin = async () => {
      await page.evaluate(() => {
        const b = [...document.querySelectorAll('button')]
          .find((x) => x.textContent.replace(/\s/g, '') === '登录')
        if (b) b.click()
      })
    }

    let loggedIn = false
    for (let attempt = 1; attempt <= 4 && !loggedIn; attempt += 1) {
      if (demoOk) {
        await demoBtn.click()
        await sleep(400)
      } else {
        const inputs = await page.$$('input')
        await setInputValue(page, inputs[0], PHONE)
        await setInputValue(page, inputs[1], PASSWORD)
      }
      const phoneValue = await page.evaluate(() => {
        const el = [...document.querySelectorAll('input')]
          .find((i) => (i.placeholder || '').includes('手机号'))
        return el ? el.value : ''
      })
      evidence.login_attempts = (evidence.login_attempts || []).concat([{ attempt, phoneValue }])
      await submitLogin()
      try {
        await page.waitForFunction(() => !!localStorage.getItem('ll_user'),
          { timeout: 15000 })
        loggedIn = true
      } catch (e) {
        const errText = await page.evaluate(() => document.body.innerText.slice(0, 400))
        evidence.login_error = errText
        // /auth/login is throttled at 8 req / 120 s: wait out the window and retry
        if (/频繁|rate|稍后/.test(errText)) {
          console.log('[WAIT] login throttled, waiting for the next window')
          await sleep(65000)
        } else {
          await sleep(1500)
        }
      }
    }
    step('demo merchant logged in (ll_user persisted)', loggedIn,
      { attempts: (evidence.login_attempts || []).length, error: evidence.login_error })
    if (!loggedIn) throw new Error('login did not persist ll_user')

    // ---- open the product edit page ----
    const url = `${BASE}/merchant/center/products/${PROD_ID}/edit`
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 60000 })
    await page.waitForSelector('.sku-editor', { timeout: 30000 })
    step('SkuEditor rendered on the merchant product-edit page', true, { url })

    // ---- drive the editor: 2 dims x 2 values ----
    const editor = await page.$('.sku-editor')
    const clickIn = async (text) => {
      await editor.evaluate((root, t) => {
        const b = [...root.querySelectorAll('button')]
          .find((x) => x.textContent.replace(/\s/g, '').includes(t))
        if (b) b.click()
      }, text)
      await sleep(200)
    }

    // clear any pre-existing tree so the run starts from a known state
    const dimCount = await editor.evaluate((r) => r.querySelectorAll('.spec-dim').length)
    for (let i = 0; i < dimCount; i += 1) {
      await editor.evaluate((r) => {
        const del = [...r.querySelectorAll('.spec-dim button')]
          .find((b) => b.textContent.trim() === '删除')
        if (del) del.click()
      })
      await sleep(150)
    }

    await clickIn('添加维度')
    await clickIn('添加维度')

    const fillDim = async (index, name, values) => {
      const dims = await editor.$$('.spec-dim')
      const dim = dims[index]
      const inputs = await dim.$$('input')
      await setInputValue(page, inputs[0], name)
      for (let i = 0; i < values.length; i += 1) {
        const current = await dim.$$('input')
        await setInputValue(page, current[current.length - 1], values[i])
        await dim.evaluate((d) => {
          const b = [...d.querySelectorAll('button')]
            .find((x) => x.textContent.trim() === '添加值')
          if (b) b.click()
        })
        await sleep(150)
      }
    }
    await fillDim(0, '颜色', ['红', '蓝'])
    await fillDim(1, '尺寸', ['S', 'L'])
    await sleep(400)

    const matrix = await editor.evaluate((r) => ({
      dims: [...r.querySelectorAll('.spec-dim')].map((d) =>
        [...d.querySelectorAll('.ant-tag')].map((t) => t.textContent.trim())),
      rows: [...r.querySelectorAll('tbody tr')].map((row) =>
        row.innerText.replace(/\s+/g, ' ').trim()),
      hasPriceInput: !!r.querySelector('tbody tr .ant-input-number'),
      hasSaveButton: [...r.querySelectorAll('button')]
        .some((b) => b.textContent.trim() === '保存规格'),
    }))
    evidence.matrix = matrix
    step('adding 2 dimensions x 2 values generates a 4-row combo matrix',
      matrix.dims.length === 2 && matrix.rows.length === 4, matrix)
    step('combo rows render price/stock inputs and a save button',
      matrix.hasPriceInput && matrix.hasSaveButton, matrix)

    // ---- fill price / stock on every row ----
    await editor.evaluate((r) => {
      const setVal = (el, v) => {
        const d = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value')
        d.set.call(el, v)
        el.dispatchEvent(new Event('input', { bubbles: true }))
        el.dispatchEvent(new Event('change', { bubbles: true }))
        el.dispatchEvent(new Event('blur', { bubbles: true }))
      }
      ;[...r.querySelectorAll('tbody tr')].forEach((row) => {
        const ins = [...row.querySelectorAll('input')]
        if (ins.length >= 2) {
          setVal(ins[0], '9.90')
          setVal(ins[1], '10')
        }
      })
    })
    await sleep(400)
    const filled = await editor.evaluate((r) =>
      [...r.querySelectorAll('tbody tr')].map((row) =>
        [...row.querySelectorAll('input')].map((i) => i.value)))
    evidence.filled = filled

    // ---- screenshot zh-CN ----
    await page.evaluate(() => {
      const el = document.querySelector('.sku-editor')
      if (el) el.scrollIntoView({ block: 'start', behavior: 'instant' })
    })
    await sleep(500)
    const zhPath = path.join(OUT_DIR, 'sku-editor-screenshot.png')
    await editor.screenshot({ path: zhPath })
    evidence.screenshots.zh = zhPath
    step('captured the zh-CN SkuEditor screenshot', fs.existsSync(zhPath), { path: zhPath })

    // ---- save through the UI ----
    await editor.evaluate((r) => {
      const b = [...r.querySelectorAll('button')]
        .find((x) => x.textContent.trim() === '保存规格')
      if (b) b.click()
    })
    await sleep(2500)
    const afterSave = await editor.evaluate((r) => ({
      rows: [...r.querySelectorAll('tbody tr')].length,
      values: [...r.querySelectorAll('tbody tr')].map((row) =>
        [...row.querySelectorAll('input')].map((i) => i.value)),
    }))
    evidence.after_save = afterSave
    step('UI save returned a 4-row tree with price 9.90 / stock 10',
      afterSave.rows === 4 && afterSave.values.every((v) => v[0] === '9.90' && v[1] === '10'),
      afterSave)

    // ---- reload and confirm persistence ----
    await page.reload({ waitUntil: 'networkidle2', timeout: 60000 })
    await page.waitForSelector('.sku-editor', { timeout: 30000 })
    await sleep(1500)
    const afterReload = await page.$eval('.sku-editor', (r) => ({
      dims: [...r.querySelectorAll('.spec-dim')].map((d) => ({
        name: (d.querySelector('input') || {}).value || '',
        values: [...d.querySelectorAll('.ant-tag')].map((t) => t.textContent.trim()),
      })),
      rows: [...r.querySelectorAll('tbody tr')].map((row) =>
        row.innerText.replace(/\s+/g, ' ').trim()),
    }))
    evidence.after_reload = afterReload
    step('reload restores 2 dimensions and 4 combo rows from the API',
      afterReload.dims.length === 2 && afterReload.rows.length === 4, afterReload)

    // ---- switch to en-US and scan the SkuEditor for CJK ----
    await page.evaluate(() => {
      try { localStorage.setItem('ll_locale', 'en-US') } catch (e) { /* ignore */ }
    })
    await page.reload({ waitUntil: 'networkidle2', timeout: 60000 })
    await page.waitForSelector('.sku-editor', { timeout: 30000 })
    await sleep(2500)
    const enScan = await page.$eval('.sku-editor', (r) => {
      const cjk = /[\u4e00-\u9fff]/
      const texts = []
      const walker = document.createTreeWalker(r, NodeFilter.SHOW_TEXT)
      let n = walker.nextNode()
      while (n) {
        const t = (n.nodeValue || '').trim()
        if (t) texts.push(t)
        n = walker.nextNode()
      }
      const attrs = [...r.querySelectorAll('[placeholder],[title],[aria-label]')]
        .flatMap((el) => ['placeholder', 'title', 'aria-label']
          .map((a) => el.getAttribute(a)).filter(Boolean))
      const offending = [...texts, ...attrs].filter((t) => cjk.test(t))
      return {
        locale: document.documentElement.getAttribute('data-locale'),
        lang: document.documentElement.lang,
        sampleTexts: texts.slice(0, 40),
        offending,
        dimTags: [...r.querySelectorAll('.ant-tag')].map((t) => t.textContent.trim()),
        dimNameValues: [...r.querySelectorAll('.spec-dim input')].map((i) => i.value),
      }
    })
    evidence.cjk_scan = enScan
    // The dimension name / value the operator typed (颜色 / 尺寸 / 红 / 蓝 / S / L) and
    // the specText the server composes out of them (颜色:红;尺寸:S) are DATA, not UI
    // copy, so they are excluded from the CJK scan. A string counts as data when it
    // can be rebuilt purely from those typed tokens joined by ':' and ';'.
    const dataTokens = new Set(
      enScan.dimTags.concat(enScan.dimNameValues).filter(Boolean))
    const isDataString = (s) => {
      if (dataTokens.has(s)) return true
      const parts = s.split(/[:;]/).map((p) => p.trim()).filter(Boolean)
      return parts.length > 0 && parts.every((p) => dataTokens.has(p))
    }
    const uiOffending = enScan.offending.filter((t) => !isDataString(t))
    evidence.cjk_scan.data_tokens = [...dataTokens]
    evidence.cjk_scan.ui_offending = uiOffending
    step('locale switched to en-US', enScan.locale === 'en-US', {
      locale: enScan.locale, lang: enScan.lang })
    step('SkuEditor UI copy contains ZERO CJK in en-US (user-typed spec data excluded)',
      uiOffending.length === 0,
      { ui_offending: uiOffending, all_offending: enScan.offending,
        sample: enScan.sampleTexts })

    const enPath = path.join(OUT_DIR, 'sku-editor-en-screenshot.png')
    await page.$eval('.sku-editor', (el) => {
      el.scrollIntoView({ block: 'start', behavior: 'instant' })
    })
    await sleep(400)
    const enEditor = await page.$('.sku-editor')
    await enEditor.screenshot({ path: enPath })
    evidence.screenshots.en = enPath
    step('captured the en-US SkuEditor screenshot', fs.existsSync(enPath), { path: enPath })

    evidence.console_errors = consoleErrors
    step('no uncaught console errors during the acceptance run',
      consoleErrors.filter((e) => !/favicon|ResizeObserver/i.test(e)).length === 0,
      { console_errors: consoleErrors.slice(0, 8) })

    exitCode = evidence.steps.every((s) => s.ok) ? 0 : 1
  } catch (e) {
    evidence.error = String(e && e.stack ? e.stack : e).slice(0, 1500)
    console.error('browser acceptance failed: ' + String(e).slice(0, 400))
    exitCode = 1
  } finally {
    evidence.exit_code = exitCode
    fs.writeFileSync(path.join(OUT_DIR, 'sku-editor-browser-evidence.json'),
      JSON.stringify(evidence, null, 2), 'utf8')
    await browser.close()
  }
  console.log(`\n${evidence.steps.filter((s) => s.ok).length}/${evidence.steps.length} steps passed, exit=${exitCode}`)
  process.exit(exitCode)
})()
