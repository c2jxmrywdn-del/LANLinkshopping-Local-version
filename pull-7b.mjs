import { readFileSync } from 'node:fs'
import { execSync } from 'node:child_process'
const pw = readFileSync('D:/桌面/LANLinkShopping/Shopping/.env', 'utf8').split('\n').find(l => l.startsWith('REDIS_PASSWORD=')).slice(15).trim()
console.log('=== pull qwen2.5:7b ===')
try {
  const out = execSync('docker exec lanlink-ollama ollama pull qwen2.5:7b 2>&1', { encoding: 'utf8', maxBuffer: 20 * 1024 * 1024, timeout: 15 * 60 * 1000 })
  console.log(out.split('\n').filter(Boolean).slice(-2).join('\n'))
} catch (e) { console.log('PULL-ERR', (e.stdout || '').slice(-200)) }
console.log(execSync('docker exec lanlink-ollama ollama list 2>&1', { encoding: 'utf8' }))
