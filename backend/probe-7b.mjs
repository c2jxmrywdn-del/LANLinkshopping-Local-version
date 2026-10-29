import { readFileSync } from 'node:fs'
import { execSync } from 'node:child_process'
const run = (c) => { try { return execSync(c, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 }) } catch (e) { return 'ERR ' + String(e.stdout || e.message).slice(0, 200) } }
console.log('=== ollama list ===')
console.log(run('docker exec lanlink-ollama ollama list 2>&1'))
console.log('=== 直接调 7B（/api/chat 非流式，短 prompt 计载入+推理） ===')
const t = Date.now()
try {
  const r = JSON.parse(run(`docker exec lanlink-ollama ollama api chat 2>&1`, { encoding: 'utf8', input: '' }))
} catch (e) {}
// 用 node fetch 走宿主机端口
const resp = await fetch('http://127.0.0.1:11434/api/chat', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ model: 'qwen2.5:7b', messages: [{ role: 'user', content: '用一句话介绍 B2B 平台。' }], stream: false, options: { num_ctx: 16384, temperature: 0.2 } })
})
const j = await resp.json().catch(() => null)
console.log('HTTP', resp.status, '总耗时', Date.now() - t, 'ms')
if (j) {
  console.log('eval_count:', j.eval_count, 'eval_duration:', j.eval_duration ? (j.eval_duration / 1e9).toFixed(2) + 's' : '-', 'prompt_eval_count:', j.prompt_eval_count)
  if (j.eval_count && j.eval_duration) console.log('生成速度:', (j.eval_count / (j.eval_duration / 1e9)).toFixed(1), 'tok/s')
  console.log('内容:', (j.message?.content || '').slice(0, 100))
}
