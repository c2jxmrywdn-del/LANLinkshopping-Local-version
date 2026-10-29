import { readFileSync, writeFileSync } from 'node:fs'
import { execSync } from 'node:child_process'
const f = 'docker-compose.yml'
let s = readFileSync(f, 'utf8')
if (s.includes('image: redis/redis-stack-server')) {
  s = s.replace('image: redis/redis-stack-server:7.4.0-v8', 'image: docker.m.daocloud.io/redis/redis-stack-server:7.4.0-v8')
  writeFileSync(f, s)
  console.log('mirror prefix applied')
} else if (s.includes('docker.m.daocloud.io/redis/redis-stack-server')) {
  console.log('mirror already applied')
} else console.log('UNEXPECTED compose state')
const img = 'docker.m.daocloud.io/redis/redis-stack-server:7.4.0-v8'
console.log('pulling', img)
try {
  const out = execSync(`docker pull ${img}`, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 10 * 1024 * 1024 })
  console.log(out.split('\n').filter(Boolean).slice(-2).join('\n'))
} catch (e) {
  console.log('PULL-ERR:', (e.stdout || '').split('\n').filter(Boolean).slice(-1).join(' '), (e.stderr || '').slice(0, 150))
}
