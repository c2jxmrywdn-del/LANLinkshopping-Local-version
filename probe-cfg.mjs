import { readFileSync } from 'node:fs'
// 1) preset ini 全文
console.log('===== llama-container-preset.ini =====')
console.log(readFileSync('D:/桌面/LANLinkShopping/Shopping/scripts/local-ai/llama-container-preset.ini', 'utf8'))
// 2) compose llama-cpp 服务块
const c = readFileSync('D:/桌面/LANLinkShopping/Shopping/docker-compose.yml', 'utf8')
const i = c.indexOf('llama-cpp')
console.log('===== compose llama-cpp =====')
console.log(c.slice(Math.max(0, i - 200), i + 1500))
