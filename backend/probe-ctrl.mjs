import { readFileSync } from 'node:fs'
const s = readFileSync('D:/桌面/LANLinkShopping/Shopping/backend/src/main/java/com/lanlink/shopping/module/pricemonitor/controller/PriceMonitorController.java', 'utf8')
const L = s.split('\n')
// 构造器与 news 端点
L.forEach((l, i) => {
  if (/public PriceMonitorController|@GetMapping\("\/news"\)|public R.*news\(|private final/.test(l)) {
    console.log((i + 1) + ': ' + l.trim().slice(0, 130))
  }
})
console.log('---news block---')
const i = L.findIndex(l => l.includes('@GetMapping("/news")'))
console.log(L.slice(i, i + 30).map((l, k) => (i + k + 1) + ': ' + l).join('\n'))
