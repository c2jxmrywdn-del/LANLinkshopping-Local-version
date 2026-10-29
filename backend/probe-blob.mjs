import { readFileSync } from 'node:fs'
import { execSync } from 'node:child_process'
const run = (c) => { try { return execSync(c, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 }) } catch (e) { return 'ERR ' + String(e.stdout || e.message).slice(0, 300) } }
console.log('=== ollama mounts ===')
console.log(run('docker inspect lanlink-ollama --format "{{range .Mounts}}{{.Source}} -> {{.Destination}}\\n{{end}}"'))
console.log('=== blobs 目录 ===')
console.log(run('docker exec lanlink-ollama sh -c "ls -la /root/.ollama/models/blobs 2>&1 | head -10"'))
console.log('=== 0.5b blob 查找 ===')
console.log(run('docker exec lanlink-ollama sh -c "find /root/.ollama -name \'*a8b0c5157701*\' 2>/dev/null | head -3"'))
