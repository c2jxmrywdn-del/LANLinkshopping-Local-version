/* 外部 API 终兜底 E2E：假 OpenAI 端点 → 注册 custom 渠道 → tools 失败路径 → routed=external
 * 用法：node verify-external.mjs
 */
import http from 'node:http'
const API = 'http://127.0.0.1:8080/api'
const FAKE_PORT = 9377
let failed = 0
const ok = (c, label, extra) => {
  if (!c) failed++
  console.log(`[${c ? 'PASS' : 'FAIL'}] ${label}${extra ? ' | ' + extra : ''}`)
}

// 1. 假 OpenAI 兼容端点（带请求计数：验证 60s 去重）
let hits = 0
const fake = http.createServer((q, s) => {
  let b = ''
  q.on('data', c => b += c)
  q.on('end', () => {
    hits++
    s.writeHead(200, { 'Content-Type': 'application/json' })
    s.end(JSON.stringify({
      id: 'x', object: 'chat.completion', created: 0, model: 'fake-model',
      choices: [{ index: 0, message: { role: 'assistant', content: '[E2E-EXTERNAL] 这是外部兜底假端点的回答，用于验证链路。' }, finish_reason: 'stop' }],
      usage: { prompt_tokens: 10, completion_tokens: 10, total_tokens: 20 }
    }))
  })
})
await new Promise(r => fake.listen(FAKE_PORT, r))
console.log(`fake OpenAI endpoint on :${FAKE_PORT}`)

async function login() {
  const r = await fetch(API + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone: '13800000000', password: '123456' })
  })
  return (r.headers.getSetCookie ? r.headers.getSetCookie() : []).map(c => c.split(';')[0]).join('; ')
}

try {
  const cookie = await login()
  const api = async (method, path, body) => fetch(API + path, {
    method, headers: { 'Content-Type': 'application/json', Cookie: cookie },
    body: body ? JSON.stringify(body) : undefined
  }).then(r => r.json())

  // 2. 注册 custom 渠道（管理接口走 AES-GCM 加密落库）
  const up = await api('POST', '/admin/outbound-model-config', {
    channel: 'custom', baseUrl: `http://host.docker.internal:${FAKE_PORT}/v1`,
    apiKey: 'dummy-e2e-key', modelName: 'fake-model', enabled: true
  })
  ok(up?.code === 200 || up?.code === undefined, '注册 custom 渠道', JSON.stringify(up).slice(0, 120))

  // 3. 制造 tools 失败路径：停网关（chatViaGatewayTools 不可达 → 落外部兜底）；
  //    并临时停用真实 deepseek 渠道（白名单第一位），让 custom 假端点接手；测完恢复
  const { execSync } = await import('node:child_process')
  const fs = await import('node:fs')
  const envSql = fs.readFileSync('D:/桌面/LANLinkShopping/Shopping/.env', 'utf8')
  const pwd = /MYSQL_ROOT_PASSWORD=(\S+)/.exec(envSql)?.[1] || ''
  const sql = (stmt) => execSync(
    `docker exec shopping-mysql-1 mysql -uroot -p${pwd} lanlink_shopping -e "${stmt}"`, { stdio: 'pipe' })
  execSync('docker stop lanlink-ai-gateway')
  sql("UPDATE t_outbound_model_config SET enabled=0 WHERE channel='deepseek'")
  console.log('gateway stopped (tools path disabled); deepseek disabled temporarily')

  const q = 'zzq wvkt pbmq e2e-external-probe ' + Date.now() // 纯 ASCII 乱串：词面/向量都不会命中 KB
  const t0 = Date.now()
  const ask1 = await api('POST', '/kb/ask', { question: q, enableTools: true })
  const d1 = ask1?.data || {}
  ok(d1.routed === 'external', 'ask#1 routed=external',
    `notice=${d1.notice} channel=${d1.externalChannel} model=${d1.externalModel} ${Math.round((Date.now() - t0) / 100) / 10}s`)
  ok(String(d1.answer || '').includes('[E2E-EXTERNAL]'), '回答来自假端点（grounding 透传成功）',
    String(d1.answer || '').slice(0, 50))
  ok(d1.externalChannel === 'custom' && d1.externalModel === 'fake-model', '渠道/模型标记正确',
    `${d1.externalChannel}/${d1.externalModel}`)
  const hitsAfter1 = hits
  ok(hitsAfter1 >= 1, '假端点收到请求', `hits=${hits}`)

  // 4. 60s 去重：同问题立刻重发 → 不再打假端点（hits 不增长），落 NO_CONTEXT
  const ask2 = await api('POST', '/kb/ask', { question: q, enableTools: true })
  const d2 = ask2?.data || {}
  ok(hits === hitsAfter1, '同问 60s 去重生效（假端点零新增调用）', `hits ${hitsAfter1} -> ${hits}`)
  ok(d2.routed !== 'external' && d2.notice === 'NO_CONTEXT', '去重后落回友好兜底',
    `routed=${d2.routed} notice=${d2.notice}`)

  // 5. 清理：删渠道 + 恢复 deepseek + 起回网关
  sql("DELETE FROM t_outbound_model_config WHERE channel='custom'")
  sql("UPDATE t_outbound_model_config SET enabled=1 WHERE channel='deepseek'")
  execSync('docker start lanlink-ai-gateway')
  console.log('cleanup: custom deleted, deepseek re-enabled, gateway started')
} finally {
  fake.close()
}

console.log(failed === 0 ? '\n=== ALL EXTERNAL-FALLBACK CHECKS PASSED ===' : `\n=== ${failed} CHECK(S) FAILED ===`)
process.exit(failed === 0 ? 0 : 1)
