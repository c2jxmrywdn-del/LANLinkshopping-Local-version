// 五维因子快照覆盖状态（factor-auto 自动分析链展示端共用纯逻辑）。
// 供首页每日行情（HomeMarketPulse）与价格情报台（PriceIntelligence）共用：
// 快照索引行（GET /price-monitor/auto-profile/index，公开全量）→ prodId → { generatedAt, webInfoCount }。

/**
 * 快照索引行 → prodId 映射（"已分析"徽标 / N/M 覆盖计数数据源）。
 * 行缺失字段按空值兜底：prodId 非数值不收（Number() NaN 键会污染遍历）。
 */
export function buildAnalyzedMap(rows) {
  const map = {}
  for (const r of (rows || [])) {
    const prodId = Number(r?.prodId)
    if (!Number.isFinite(prodId)) continue
    map[prodId] = { generatedAt: r?.generatedAt || '', webInfoCount: Number(r?.webInfoCount || 0) }
  }
  return map
}

/**
 * generatedAt → 'YYYY-MM-DD HH:mm' 展示戳。
 * 兼容 ISO 字符串（"2026-11-04T16:50:02"）/ MySQL datetime（"2026-11-04 16:50:02"）/ 纪元毫秒；
 * 不可解析时回退原值截断（与旧版 slice(0,16).replace('T',' ') 行为一致），空值返回空串。
 */
export function analyzeStamp(generatedAt) {
  if (generatedAt == null || generatedAt === '') return ''
  if (typeof generatedAt === 'number' && Number.isFinite(generatedAt)) {
    const d = new Date(generatedAt)
    if (!Number.isNaN(d.getTime())) return padStamp(d)
  }
  const s = String(generatedAt)
  const d = new Date(s.replace(' ', 'T'))
  if (!Number.isNaN(d.getTime())) return padStamp(d)
  return s.slice(0, 16).replace('T', ' ')
}

function padStamp(d) {
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
