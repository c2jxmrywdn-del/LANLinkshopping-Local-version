import { readFileSync } from 'node:fs'
console.log('===== utils/newsLink.js =====')
console.log(readFileSync('D:/桌面/LANLinkShopping/Shopping/frontend/src/utils/newsLink.js', 'utf8'))
// 后端 MarketNews 数据生成处（url 字段是否被填充）
const { execSync } = await import('node:child_process')
console.log('===== backend MarketNews 写入点 =====')
console.log(execSync('findstr /s /n /c:"MarketNews" /c:"setUrl" D:\\桌面\\LANLinkShopping\\Shopping\\backend\\src\\main\\java\\com\\lanlink\\shopping\\module\\pricemonitor\\service\\*.java 2>&1 | findstr /i "insert save url new MarketNews"', { encoding: 'utf8' }).slice(0, 800))
