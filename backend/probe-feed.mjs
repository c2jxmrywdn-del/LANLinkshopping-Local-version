import { readFileSync } from 'node:fs'
const s = readFileSync('D:/桌面/LANLinkShopping/Shopping/backend/src/main/java/com/lanlink/shopping/module/pricemonitor/service/MarketNewsService.java', 'utf8')
const i = s.indexOf('feed')
console.log(s.slice(Math.max(0, i - 300), i + 1500))
console.log('===LINKS===')
console.log(s.includes('sourceUrl') ? 'HAS sourceUrl' : 'NO sourceUrl')
console.log(s.includes('linkType') ? 'HAS linkType' : 'NO linkType')
console.log(s.includes('NewsLinkResolver') ? 'USES resolver' : 'no resolver')
