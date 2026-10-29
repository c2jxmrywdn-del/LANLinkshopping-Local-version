import { readFileSync, writeFileSync } from 'node:fs'
import { execSync } from 'node:child_process'
const f = 'docker-compose.yml'
let s = readFileSync(f, 'utf8')
console.log('compose image line:', s.match(/image: \S*redis-stack-server\S*/)?.[0] || 'NOT-FOUND')
if (!s.includes('redis-stack-server')) {
  s = s.replace('image: redis:7-alpine', 'image: docker.m.daocloud.io/redis/redis-stack-server:7.4.0-v8')
  writeFileSync(f, s)
  console.log('re-swapped with mirror')
}
// 拉取镜像（带 mirror 前缀）
const img = s.match(/image: (\S*redis-stack-server\S*)/)?.[1]
console.log('pulling', img)
try {
  const out = execSync(`docker pull ${img}`, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 10 * 1024 * 1024 })
  console.log(out.split('\n').slice(-3).join('\n'))
} catch (e) {
  console.log('PULL-ERR:', (e.stdout || '').split('\n').slice(-2).join(' '), (e.stderr || '').slice(0, 200))
}
