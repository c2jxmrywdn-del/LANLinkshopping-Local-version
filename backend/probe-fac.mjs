import { readFileSync } from 'node:fs'
const s = readFileSync('D:/桌面/LANLinkShopping/Shopping/backend/src/main/java/com/lanlink/shopping/module/pricemonitor/service/PriceMonitorAiService.java', 'utf8')
// factors 方法与 computeFactors 调用点
const i = s.indexOf('public Map<String, Object> factors')
console.log(s.slice(i, i + 900))
console.log('=====')
// 是否有商品上下文参数
console.log('has productCtx:', s.includes('productCtx') || s.includes('ProductCtx'))
console.log('computeFactors(news) 调用:', (s.match(/computeFactors\(news\)/g) || []).length, '处')
console.log('computeFactors(news, other):', (s.match(/computeFactors\(news,[^)]*\)/g) || []).length, '处')
// PriceHistoryMapper 是否已注入
console.log('has PriceHistoryMapper:', s.includes('PriceHistoryMapper'))
// 构造器
const c = s.indexOf('public PriceMonitorAiService')
console.log(s.slice(c, c + 700))
