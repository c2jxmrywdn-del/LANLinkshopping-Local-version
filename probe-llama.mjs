import { execSync } from 'node:child_process'
const out = execSync('docker logs lanlink-llama-cpp --since 15m 2>&1', { encoding: 'utf8', maxBuffer: 20 * 1024 * 1024 })
const lines = out.split('\n')
// 启动参数与预填耗时
console.log('=== spawn/args ===')
lines.filter(l => /llama-server|ubatch|parallel|threads|model-draft|listening|preset/i.test(l)).slice(0, 12).forEach(l => console.log(l.slice(0, 220)))
console.log('=== prompt eval（最近） ===')
lines.filter(l => /prompt eval time|eval time/i.test(l)).slice(-6).forEach(l => console.log(l.slice(0, 200)))
console.log('=== warnings ===')
lines.filter(l => /error|warn|unknown|ignoring/i.test(l)).slice(-8).forEach(l => console.log(l.slice(0, 180)))
