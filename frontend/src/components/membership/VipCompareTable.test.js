import { describe, it, expect } from 'vitest'
import { h, createApp, nextTick } from 'vue'
import VipCompareTable from './VipCompareTable.vue'

function mountTable() {
  const root = document.createElement('div')
  document.body.appendChild(root)
  const app = createApp({ render: () => h(VipCompareTable) })
  app.mount(root)
  return {
    root,
    table: () => root.querySelector('table'),
    rows: () => [...root.querySelectorAll('tbody tr')],
    toggle: () => root.querySelector('.cmp-toggle'),
    unmount() { app.unmount(); root.remove() }
  }
}

describe('VipCompareTable（两列权益对比）', () => {
  it('真 table 结构：两列表头（未订阅免费身份/已订阅会员档）+ 五行真实权益', () => {
    const t = mountTable()
    const table = t.table()
    expect(table).toBeTruthy()
    const headers = [...table.querySelectorAll('thead th')].map(th => th.textContent.trim())
    expect(headers).toContain('未订阅（免费身份）')
    expect(headers).toContain('已订阅（会员档）')
    // 行头取 .cmp-label 的首个文本节点（积分抵现行附 1000 积分=1 元小注）
    const labels = t.rows().map(tr => tr.querySelector('.cmp-label').childNodes[0].textContent.trim())
    expect(labels).toEqual(['等级折扣', '消费积分', '积分抵现', '成长值累积', '在线客服支持'])
    t.unmount()
  })

  it('勾叉 aria：勾配 aria-label=已包含，叉配 aria-label=未包含（不只靠图形与颜色区分）', () => {
    const t = mountTable()
    const checks = [...t.root.querySelectorAll('.cmp-check[aria-label="已包含"]')]
    const crosses = [...t.root.querySelectorAll('.cmp-x[aria-label="未包含"]')]
    expect(checks.length).toBeGreaterThan(0)
    expect(crosses.length).toBe(1) // 仅「等级折扣」未订阅列为叉
    // 叉所在行的文案如实：普通会员 1.000 无折扣
    const crossRow = crosses[0].closest('tr')
    expect(crossRow.querySelector('tbody th').textContent).toContain('等级折扣')
    expect(crossRow.querySelector('.cmp-cell-text').textContent).toContain('普通会员 1.000，无折扣')
    t.unmount()
  })

  it('已订阅列整列高亮：所有行 vip 列挂 .cmp-vip-col（顶部渐变条 + 底色 token）', () => {
    const t = mountTable()
    const vipCols = [...t.root.querySelectorAll('.cmp-vip-col')]
    // 表头 1 + 每行 1
    expect(vipCols.length).toBe(t.rows().length + 1)
    // 双通道：高亮列表头文字「已订阅（会员档）」存在，不只靠颜色
    expect(t.root.querySelector('thead .cmp-vip-col').textContent).toContain('已订阅（会员档）')
    t.unmount()
  })

  it('口径注随表输出（可被读屏读取）：×2 归身份轨与人工客服全员开放的诚实说明', () => {
    const t = mountTable()
    const noteEls = [...t.root.querySelectorAll('.cmp-note')]
    const notes = noteEls.map(n => n.textContent)
    expect(notes.some(n => n.includes('消费积分 ×2 为累计消费身份权益'))).toBe(true)
    expect(notes.some(n => n.includes('订阅解锁的是等级折扣资格'))).toBe(true)
    expect(notes.some(n => n.includes('人工客服工单对所有登录用户开放'))).toBe(true)
    // 口径注不 aria-hidden
    noteEls.forEach(n => expect(n.hasAttribute('aria-hidden')).toBe(false))
    t.unmount()
  })

  it('可折叠：默认展开，点击切换 aria-expanded 与表格显隐', async () => {
    const t = mountTable()
    const toggle = t.toggle()
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    expect(t.table().style.display).not.toBe('none')
    toggle.click()
    await nextTick()
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    expect(t.table().closest('.cmp-body').style.display).toBe('none')
    toggle.click()
    await nextTick()
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    t.unmount()
  })
})
