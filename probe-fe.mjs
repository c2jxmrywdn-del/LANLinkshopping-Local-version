import { readFileSync } from 'node:fs'
// 1) utils/newsLink.js 全文
console.log('===== utils/newsLink.js =====')
console.log(readFileSync('D:/桌面/LANLinkShopping/Shopping/frontend/src/utils/newsLink.js', 'utf8'))
// 2) 后端 factors 当前版本（factors 方法 + computeFactors 签名与商品上下文）
const s = readFileSync('D:/桌面/LANLinkShopping/Shopping/backend/src/main/java/com/lanlink/shopping/module/pricemonitor/service/PriceMonitorAiService.java', 'utf8')
const i = s.indexOf('public Map<String, Object> factors')
console.log('===== factors 当前实现 =====')
console.log(s.slice(i, i + 1200))
const j = s.indexOf('private List<Map<String, Object>> computeFactors')
console.log('===== computeFactors 签名与开头 =====')
console.log(s.slice(j, j + 800))
