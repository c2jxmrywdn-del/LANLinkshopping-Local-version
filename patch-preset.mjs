import { readFileSync, writeFileSync } from 'node:fs'
const f = 'D:/桌面/LANLinkShopping/Shopping/scripts/local-ai/llama-container-preset.ini'
let s = readFileSync(f, 'utf8')
let n = 0
// 1) parallel 1 → 2（双槽连续批处理：并发 /kb/ask 不再串行；KV 多一槽 ≈ +500MB q8_0）
if (s.includes('parallel = 1')) { s = s.replace('parallel = 1', 'parallel = 2'); n++ }
// 2) ubatch-size 1024（预填吞吐加倍）
if (!s.includes('ubatch-size')) {
  s = s.replace('threads-batch = 8', 'threads-batch = 8\nubatch-size = 1024')
  n++
}
// 注释补充
s = s.replace('# Single 8GB GPU: router child defaults to 4 slots x ctx (~1.8GB extra KV).',
  '# Single 8GB GPU: parallel=2 enables continuous batching for 2 concurrent /kb/ask\n# (extra q8_0 KV slot ~500MB @16384 -> VRAM ~6.7GB of 8188).')
writeFileSync(f, s)
console.log('preset patched', n)
console.log(s.slice(0, 700))
