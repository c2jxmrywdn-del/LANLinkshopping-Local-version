import { describe, it, expect } from 'vitest'
import {
  GRACE_DAYS, parseMonthKey, monthKey, addMonths, monthLabel,
  lastCompletedMonth, completedMonths, reportingDeadline,
  parseUploadedAt, parseUploadPeriod, splitTaxProofUrls, uploadForPeriod, monthlyStatus,
  recentTaxStatuses, deadlineText
} from './taxCompliance'

/** 构造与后端落盘规则一致的上传 URL（userId_毫秒时间戳.ext） */
function uploadedAt(date) {
  return `/api/uploads/tax-proofs/1_${date.getTime()}.png`
}

describe('taxCompliance 基础月份工具', () => {
  it('月份键与标签在跨年时正确', () => {
    expect(monthKey(new Date(2026, 0, 15))).toBe('2026-01')
    expect(addMonths('2026-01', -1)).toBe('2025-12')
    expect(addMonths('2025-12', 1)).toBe('2026-01')
    expect(monthLabel('2026-09')).toBe('2026年9月')
    expect(parseMonthKey('2026-09').getDate()).toBe(1)
  })

  it('已结束月份的集合升序且不含当月', () => {
    const now = new Date(2026, 9, 20) // 2026-10-20
    expect(lastCompletedMonth(now)).toBe('2026-09')
    expect(completedMonths(now, 3)).toEqual(['2026-07', '2026-08', '2026-09'])
  })

  it('截止时间为次月 15 日 23:59:59（最多延期 15 天）', () => {
    expect(GRACE_DAYS).toBe(15)
    const d = reportingDeadline('2026-09')
    expect(d.getFullYear()).toBe(2026)
    expect(d.getMonth()).toBe(9) // 10 月
    expect(d.getDate()).toBe(15)
    expect(deadlineText(d)).toContain('2026年10月15日')
  })
})

describe('taxCompliance 上传解析', () => {
  it('从文件名解析上传时刻', () => {
    const at = new Date(2026, 9, 10, 9, 30)
    const parsed = parseUploadedAt(uploadedAt(at))
    expect(parsed && parsed.getTime()).toBe(at.getTime())
    expect(parseUploadedAt('/uploads/a.png')).toBeNull()
    expect(parseUploadedAt('')).toBeNull()
  })

  it('兼容逗号与 JSON 数组两种存储写法', () => {
    expect(splitTaxProofUrls('a,b,,c')).toEqual(['a', 'b', 'c'])
    expect(splitTaxProofUrls('["a","b"]')).toEqual(['a', 'b'])
    expect(splitTaxProofUrls('')).toEqual([])
  })

  it('按上报窗口归属到对应所属期', () => {
    const urls = [
      uploadedAt(new Date(2026, 9, 10)), // 10 月上传 → 归属 2026-09
      uploadedAt(new Date(2026, 10, 3))  // 11 月上传 → 归属 2026-10
    ]
    expect(uploadForPeriod('2026-09', urls)?.url).toBe(urls[0])
    expect(uploadForPeriod('2026-10', urls)?.url).toBe(urls[1])
    expect(uploadForPeriod('2026-08', urls)).toBeNull()
  })

  it('显式所属期优先于上传时间推断（本月早报不应误归属上月）', () => {
    const at = new Date(2026, 9, 20, 10, 0) // 10 月 20 日上传
    const url = `/api/uploads/tax-proofs/1_${at.getTime()}_2026-10.png`
    expect(parseUploadPeriod(url)).toBe('2026-10')
    const now = new Date(2026, 9, 21)
    expect(monthlyStatus('2026-10', [url], now).status).toBe('on_time')
    expect(monthlyStatus('2026-09', [url], now).status).toBe('overdue')
  })
})

describe('taxCompliance 月度状态', () => {
  it('截止日前上传 → 按时', () => {
    const s = monthlyStatus('2026-09', [uploadedAt(new Date(2026, 9, 10))], new Date(2026, 9, 20))
    expect(s.status).toBe('on_time')
    expect(s.short).toBe('已上报')
  })

  it('15 日之后上传 → 延期完成', () => {
    const s = monthlyStatus('2026-09', [uploadedAt(new Date(2026, 9, 18))], new Date(2026, 9, 25))
    expect(s.status).toBe('late')
    expect(s.hint).toContain('延期 3 天')
  })

  it('截止日之前未上传 → 待上报且给出剩余天数', () => {
    const s = monthlyStatus('2026-09', [], new Date(2026, 9, 12, 9, 0))
    expect(s.status).toBe('pending')
    expect(s.hint).toContain('剩余 4 天')
  })

  it('截止日之后仍未上传 → 逾期', () => {
    const s = monthlyStatus('2026-09', [], new Date(2026, 9, 20, 12, 0))
    expect(s.status).toBe('overdue')
    expect(s.color).toBe('red')
    expect(s.hint).toContain('已逾期')
  })

  it('最近 3 期按最新在前的顺序返回', () => {
    const list = recentTaxStatuses([], new Date(2026, 9, 20))
    expect(list.map((x) => x.period)).toEqual(['2026-09', '2026-08', '2026-07'])
  })
})
