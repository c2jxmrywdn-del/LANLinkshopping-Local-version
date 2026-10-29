import { readFileSync, writeFileSync } from 'node:fs'
import { execSync } from 'node:child_process'
const ini = 'D:/桌面/LANLinkShopping/Shopping/scripts/local-ai/llama-container-preset.ini'
// 1) 恢复 ubatch 1024
let s = readFileSync(ini, 'utf8')
if (!s.includes('ubatch-size = 1024')) {
  s = s.replace('threads-batch = 8', 'threads-batch = 8\nubatch-size = 1024')
  writeFileSync(ini, s)
  console.log('ubatch 1024 restored')
} else console.log('ubatch already')
// 2) 找 0.5b GGUF blob 并放入 llama-models 卷
const blobs = execSync('docker exec lanlink-ollama sh -c "ls /root/.ollama/models/blobs | grep a8b0c5157701"', { encoding: 'utf8' }).replace(/Warning[^\n]*\n/g, '').trim()
console.log('0.5b blob:', blobs)
const blobName = blobs.split('\n')[0].trim()
execSync(`docker cp lanlink-ollama:/root/.ollama/models/blobs/${blobName} D:/桌面/LANLinkShopping/Shopping/Qwen2.5-0.5B-draft.gguf`, { stdio: 'ignore' })
// 放入 llama-models 卷（借 model-init 同款 alpine + 卷挂载）
console.log(execSync(`docker run --rm -v llama-models:/models -v "D:/桌面/LANLinkShopping/Shopping:/src" docker.m.daocloud.io/library/alpine:3.20 sh -c "cp /src/Qwen2.5-0.5B-draft.gguf /models/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf && ls -lh /models/"`, { encoding: 'utf8' }))
