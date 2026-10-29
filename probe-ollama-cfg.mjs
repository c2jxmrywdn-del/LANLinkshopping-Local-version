import { readFileSync, writeFileSync } from 'node:fs'
const c = readFileSync('D:/桌面/LANLinkShopping/Shopping/docker-compose.yml', 'utf8')
const i = c.indexOf('lanlink-ollama')
writeFileSync('D:/桌面/LANLinkShopping/Shopping/probe-out.txt', c.slice(Math.max(0, i - 150), i + 900))
