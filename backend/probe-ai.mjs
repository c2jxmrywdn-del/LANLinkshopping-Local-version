import { readFileSync } from 'node:fs'
const s = readFileSync('D:/桌面/LANLinkShopping/Shopping/backend/src/main/java/com/lanlink/shopping/module/pricemonitor/service/PriceMonitorAiService.java', 'utf8')
const L = s.split('\n')
console.log('total', L.length)
L.forEach((l, i) => {
  if (/factors|computeFactors|latest|MarketNews|source|url|marketNews/i.test(l)) {
    console.log((i + 1) + ': ' + l.trim().slice(0, 130))
  }
})
