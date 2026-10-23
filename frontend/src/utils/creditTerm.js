// 企业账期页面共用的金额、日期与账单状态口径。
// 日期比较一律使用浏览器本地日期，避免 toISOString() 按 UTC 截断导致逾期误判。

export const money = (value) => Number(value || 0).toFixed(2)

export const prettyTime = (value) => (value ? String(value).replace('T', ' ') : '—')

function pad(n) {
  return String(n).padStart(2, '0')
}

export function todayStr() {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export function dayGap(dueDate) {
  if (!dueDate) return null
  const [y, m, d] = String(dueDate).slice(0, 10).split('-').map(Number)
  const [ty, tm, td] = todayStr().split('-').map(Number)
  return Math.round((Date.UTC(y, m - 1, d) - Date.UTC(ty, tm - 1, td)) / 86400000)
}

export function billLabel(bill) {
  if (!bill) return ''
  if (bill.status === 'PAID') return '已结清'
  if (bill.status === 'CANCELLED') return '已取消'
  if (bill.status === 'OVERDUE' || (bill.dueDate && dayGap(bill.dueDate) < 0)) return '已逾期'
  return '待还'
}

export function billColor(bill) {
  const label = billLabel(bill)
  return label === '已结清' ? 'green' : label === '已逾期' ? 'red' : 'orange'
}

export function remainingText(bill) {
  if (!bill || ['PAID', 'CANCELLED'].includes(bill.status)) return '—'
  const gap = dayGap(bill.dueDate)
  if (gap === null) return '—'
  if (gap < 0) return `逾期 ${Math.abs(gap)} 天`
  if (gap === 0) return '今天到期'
  return `剩余 ${gap} 天`
}

export function billCategory(bill) {
  if (bill.status === 'PAID') return 'paid'
  if (bill.status === 'CANCELLED') return 'cancelled'
  if (bill.status === 'OVERDUE' || (bill.dueDate && dayGap(bill.dueDate) < 0)) return 'overdue'
  return 'unpaid'
}

export function repayMethodLabel(method) {
  return method === 'transfer' ? '对公转账' : '企业钱包'
}

export function repaymentStatusLabel(status) {
  return status === 'PENDING' ? '待确认' : status === 'REJECTED' ? '已驳回' : '已入账'
}

export function repaymentStatusColor(status) {
  return status === 'PENDING' ? 'orange' : status === 'REJECTED' ? 'red' : 'green'
}
