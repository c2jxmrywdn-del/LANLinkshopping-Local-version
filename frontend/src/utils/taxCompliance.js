/**
 * 完税记录上报规则（纯函数，便于单测与前后端口径统一）。
 *
 * 业务口径：
 *  - 入驻首季：以季度为单位提交近 3 个月（3 份）完税记录；
 *  - 入驻之后：每月上报上一自然月的完税记录；
 *  - 上报截止：次月 15 日 23:59:59（即最多延期 15 天）；
 *  - 逾期仍未上报即视为违约（状态 overdue）。
 *
 * 上传时间去哪儿取：后端落盘文件名为 `{userId}_{毫秒时间戳}.{ext}`，
 * 因此可直接从 URL 解析出上传时刻，无需额外字段即可判定按时/延期/逾期。
 */

export const GRACE_DAYS = 15

const DAY_MS = 24 * 60 * 60 * 1000

/** 解析 'YYYY-MM' → 当月 1 日 00:00（本地时区） */
export function parseMonthKey(key) {
  const [y, m] = String(key || '').split('-').map(Number)
  return new Date(y, (m || 1) - 1, 1)
}

/** Date → 'YYYY-MM' */
export function monthKey(date = new Date()) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}

/** 平移月份，返回 'YYYY-MM' */
export function addMonths(key, delta) {
  const d = parseMonthKey(key)
  d.setMonth(d.getMonth() + delta)
  return monthKey(d)
}

/** 中文月份标签：'2026-09' → '2026年9月' */
export function monthLabel(key) {
  const d = parseMonthKey(key)
  return `${d.getFullYear()}年${d.getMonth() + 1}月`
}

/** 上一个已结束的自然月（即当前应上报的所属月份） */
export function lastCompletedMonth(now = new Date()) {
  return monthKey(new Date(now.getFullYear(), now.getMonth() - 1, 1))
}

/** 近 count 个已结束的自然月，升序（最早 → 最近） */
export function completedMonths(now = new Date(), count = 3) {
  const out = []
  for (let i = count; i >= 1; i--) {
    out.push(monthKey(new Date(now.getFullYear(), now.getMonth() - i, 1)))
  }
  return out
}

/** 所属期 period 的上报截止时刻：次月 15 日 23:59:59.999（最多延期 15 天） */
export function reportingDeadline(period) {
  const d = parseMonthKey(addMonths(period, 1))
  d.setDate(GRACE_DAYS)
  d.setHours(23, 59, 59, 999)
  return d
}

/** 从上传 URL 解析上传时刻（文件名格式 userId_timestamp[_period].ext），解析不到返回 null */
export function parseUploadedAt(url) {
  const m = /_(\d{12,14})(?:_\d{4}-\d{2})?\.(?:jpe?g|png|pdf)$/i.exec(String(url || ''))
  if (!m) return null
  const ts = Number(m[1])
  if (!Number.isFinite(ts)) return null
  const d = new Date(ts)
  return Number.isNaN(d.getTime()) ? null : d
}

/** 从上传 URL 解析显式所属期（月度上报时由后端写入文件名），没有则返回 null */
export function parseUploadPeriod(url) {
  const m = /_\d{12,14}_(\d{4}-\d{2})\.(?:jpe?g|png|pdf)$/i.exec(String(url || ''))
  return m ? m[1] : null
}

/** 兼容逗号分隔与 JSON 数组两种历史写法 */
export function splitTaxProofUrls(raw) {
  if (!raw) return []
  if (Array.isArray(raw)) return raw.filter(Boolean)
  const s = String(raw).trim()
  if (s.startsWith('[')) {
    try {
      const arr = JSON.parse(s)
      return Array.isArray(arr) ? arr.filter(Boolean) : []
    } catch (e) {
      return []
    }
  }
  return s.split(',').map((x) => x.trim()).filter(Boolean)
}

/**
 * 某所属期的有效上传：
 *  1) 优先按文件名中显式写入的所属期匹配（月度上报新数据，最准确）；
 *  2) 兼容历史数据（文件名无所属期）：上传时刻落在「次月 1 日 ~ 再次月 1 日」区间内，
 *     取最早一次作为该期的上报记录。
 */
export function uploadForPeriod(period, urls = []) {
  const explicit = urls
    .filter((url) => parseUploadPeriod(url) === period)
    .map((url) => ({ url, at: parseUploadedAt(url) }))
    .filter((x) => x.at)
    .sort((a, b) => a.at - b.at)
  if (explicit.length) return explicit[0]

  const start = parseMonthKey(addMonths(period, 1)).getTime()
  const end = parseMonthKey(addMonths(period, 2)).getTime()
  const candidates = urls
    .filter((url) => !parseUploadPeriod(url))
    .map((url) => ({ url, at: parseUploadedAt(url) }))
    .filter((x) => x.at && x.at.getTime() >= start && x.at.getTime() < end)
    .sort((a, b) => a.at - b.at)
  return candidates[0] || null
}

/**
 * 单期上报状态。
 * status: on_time（按时） | late（延期但已交） | pending（待上报） | overdue（逾期未交）
 */
export function monthlyStatus(period, urls = [], now = new Date()) {
  const label = monthLabel(period)
  const deadline = reportingDeadline(period)
  const upload = uploadForPeriod(period, urls)
  const base = { period, periodLabel: label, deadline }

  if (upload) {
    if (upload.at.getTime() <= deadline.getTime()) {
      return { ...base, uploadedAt: upload.at, status: 'on_time', label: '已按时上报', short: '已上报', color: 'green', hint: '' }
    }
    const lateDays = Math.ceil((upload.at.getTime() - deadline.getTime()) / DAY_MS)
    return {
      ...base, uploadedAt: upload.at, status: 'late',
      label: `延期 ${lateDays} 天完成`, short: '已延期', color: 'orange', hint: `延期 ${lateDays} 天`
    }
  }

  if (now.getTime() <= deadline.getTime()) {
    const daysLeft = Math.max(0, Math.ceil((deadline.getTime() - now.getTime()) / DAY_MS))
    return {
      ...base, uploadedAt: null, status: 'pending',
      label: `待上报 · 剩余 ${daysLeft} 天`, short: '待上报', color: 'blue', hint: `剩余 ${daysLeft} 天`
    }
  }

  const daysLate = Math.ceil((now.getTime() - deadline.getTime()) / DAY_MS)
  return {
    ...base, uploadedAt: null, status: 'overdue',
    label: `已逾期 ${daysLate} 天`, short: '已逾期', color: 'red', hint: `已逾期 ${daysLate} 天`
  }
}

/** 最近 count 期的状态，最新在前（用于时间轴展示） */
export function recentTaxStatuses(urls = [], now = new Date(), count = 3) {
  return completedMonths(now, count).reverse().map((p) => monthlyStatus(p, urls, now))
}

/** 截止日期文案：2026年10月15日 23:59 前 */
export function deadlineText(deadline) {
  const d = deadline
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')} 前`
}
