// Headless CDP audit: open the app, capture failed requests, console errors and toasts.
// Usage: node scripts/audit/cdp-audit.mjs [url] [waitMs]
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const WebSocket = require('../../frontend/node_modules/ws')

const URL_TO_OPEN = process.argv[2] || 'http://127.0.0.1:5173/'
const WAIT_MS = Number(process.argv[3] || 9000)
const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const profile = path.join(process.cwd(), '.verify-out', 'cdp-profile')
fs.mkdirSync(profile, { recursive: true })

const chrome = spawn(CHROME, [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--remote-debugging-port=9333', `--user-data-dir=${profile}`,
  '--window-size=1440,900', 'about:blank'
], { stdio: 'ignore' })

const sleep = (ms) => new Promise(r => setTimeout(r, ms))

async function getWsUrl() {
  for (let i = 0; i < 40; i++) {
    try {
      const r = await fetch('http://127.0.0.1:9333/json/version')
      const j = await r.json()
      if (j.webSocketDebuggerUrl) return j.webSocketDebuggerUrl
    } catch {}
    await sleep(250)
  }
  throw new Error('chrome devtools not ready')
}

const wsUrl = await getWsUrl()
const ws = new WebSocket(wsUrl)
let id = 0
const pending = new Map()
const events = []
function send(method, params = {}, sessionId) {
  const msgId = ++id
  ws.send(JSON.stringify({ id: msgId, method, params, sessionId }))
  return new Promise((resolve) => pending.set(msgId, resolve))
}
ws.on('message', (raw) => {
  const msg = JSON.parse(raw.toString())
  if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg.result || msg.error); pending.delete(msg.id) }
  else if (msg.method) events.push(msg)
})
await new Promise(r => ws.on('open', r))

const { targetId } = await send('Target.createTarget', { url: 'about:blank' })
const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true })
await send('Page.enable', {}, sessionId)
await send('Network.enable', {}, sessionId)
await send('Runtime.enable', {}, sessionId)
await send('Log.enable', {}, sessionId)

const responses = []
const consoleMsgs = []
const exceptions = []
ws.on('message', (raw) => {
  const msg = JSON.parse(raw.toString())
  if (msg.sessionId !== sessionId) return
  if (msg.method === 'Network.responseReceived') {
    const r = msg.params.response
    responses.push({ url: r.url, status: r.status, type: msg.params.type })
  }
  if (msg.method === 'Network.loadingFailed') {
    responses.push({ url: msg.params.requestId, status: 'FAILED:' + msg.params.errorText, type: msg.params.type })
  }
  if (msg.method === 'Runtime.consoleAPICalled') {
    const text = (msg.params.args || []).map(a => a.value ?? a.description ?? '').join(' ')
    consoleMsgs.push({ type: msg.params.type, text })
  }
  if (msg.method === 'Runtime.exceptionThrown') {
    exceptions.push(msg.params.exceptionDetails?.exception?.description || msg.params.exceptionDetails?.text)
  }
  if (msg.method === 'Log.entryAdded') {
    consoleMsgs.push({ type: msg.params.entry.level, text: msg.params.entry.text })
  }
})

await send('Page.navigate', { url: URL_TO_OPEN }, sessionId)
await sleep(WAIT_MS)

// Capture visible toast text
const toastRes = await send('Runtime.evaluate', {
  expression: `Array.from(document.querySelectorAll('.ant-message-notice-content, .ant-message-custom-content, [class*=message]')).map(e=>e.innerText).filter(Boolean).slice(0,10).join(' | ')`,
  returnByValue: true
}, sessionId)
const titleRes = await send('Runtime.evaluate', { expression: 'document.title', returnByValue: true }, sessionId)
const htmlRes = await send('Runtime.evaluate', { expression: 'document.querySelector("#app")?.innerHTML?.length || 0', returnByValue: true }, sessionId)

const shot = await send('Page.captureScreenshot', { format: 'png' }, sessionId)
if (shot?.data) fs.writeFileSync(path.join(process.cwd(), '.verify-out', 'cdp-audit.png'), Buffer.from(shot.data, 'base64'))

const bad = responses.filter(r => typeof r.status === 'number' ? r.status >= 400 : true)
console.log('URL:', URL_TO_OPEN)
console.log('TITLE:', titleRes?.result?.value)
console.log('APP_HTML_LEN:', htmlRes?.result?.value)
console.log('TOASTS:', toastRes?.result?.value || '(none)')
console.log('--- FAILED/NON-2XX REQUESTS ---')
for (const r of bad.slice(0, 40)) console.log(`${r.status} ${r.type} ${r.url}`)
if (!bad.length) console.log('(none)')
console.log('--- CONSOLE (error/warn) ---')
for (const m of consoleMsgs.filter(m => ['error', 'warning', 'warn'].includes(m.type)).slice(0, 25)) console.log(`[${m.type}] ${m.text.slice(0, 300)}`)
if (!consoleMsgs.filter(m => ['error', 'warning', 'warn'].includes(m.type)).length) console.log('(none)')
console.log('--- EXCEPTIONS ---')
for (const e of exceptions.slice(0, 15)) console.log(String(e).slice(0, 400))
if (!exceptions.length) console.log('(none)')

ws.close()
chrome.kill()
process.exit(0)
