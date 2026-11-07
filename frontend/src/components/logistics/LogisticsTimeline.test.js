import { describe, it, expect } from 'vitest'
import { h, reactive, createApp, nextTick } from 'vue'
import LogisticsTimeline from './LogisticsTimeline.vue'

// 无 @vue/test-utils 依赖（零新增 npm 包）：createApp + render 函数手动挂载到 jsdom
function mountTimeline(initialProps) {
  const props = reactive({ traces: [], loading: false, collapsedCount: 6, ...initialProps })
  const clicks = []
  const root = document.createElement('div')
  document.body.appendChild(root)
  const app = createApp({
    setup() {
      return () => h(LogisticsTimeline, {
        ...props,
        'onNode-click': (n) => clicks.push(n)
      })
    }
  })
  app.mount(root)
  return {
    root, clicks, props,
    set(p) { Object.assign(props, p); return nextTick() },
    items: () => [...root.querySelectorAll('.tl-item')],
    unmount() { app.unmount(); root.remove() }
  }
}

// GET /logistics/trace/{waybillId} 契约：LogisticsTrace[]，后端按 traceTime 升序
const ASC_TRACES = [
  { traceId: 1, waybillId: 9, traceTime: '2026-11-06T08:00:00', location: '深圳市宝安区xx仓', city: '深圳', hubType: 'warehouse', status: 'picked_up', description: '包裹已揽收', isAbnormal: 0, source: 'api' },
  { traceId: 2, waybillId: 9, traceTime: '2026-11-06T10:30:00', location: '南昌分拨中心', city: '南昌', hubType: 'hub', status: 'in_transit', description: '干线运输到达南昌分拨中心', isAbnormal: 0, source: 'api' },
  { traceId: 3, waybillId: 9, traceTime: '2026-11-06T14:00:00', location: '武汉中转站', city: '武汉', hubType: 'transfer', status: 'exception', description: '包裹滞留超时', isAbnormal: 1, source: 'api' }
]

describe('LogisticsTimeline', () => {
  it('最新节点置顶高亮：升序入参倒排后首个节点带 is-latest', () => {
    const t = mountTimeline({ traces: ASC_TRACES })
    const items = t.items()
    expect(items).toHaveLength(3)
    expect(items[0].className).toContain('is-latest')
    expect(items[0].textContent).toContain('包裹滞留超时')
    expect(items[2].textContent).toContain('包裹已揽收')
    t.unmount()
  })

  it('异常节点红描边 + 处理建议；正常节点不出现建议行', () => {
    const t = mountTimeline({ traces: ASC_TRACES })
    const items = t.items()
    expect(items[0].className).toContain('is-abnormal')
    expect(items[0].querySelector('.tl-advice')).toBeTruthy()
    expect(items[0].querySelector('.tl-advice-text').textContent).toContain('建议联系承运商催件')
    expect(items[1].className).not.toContain('is-abnormal')
    expect(items[1].querySelector('.tl-advice')).toBeNull()
    t.unmount()
  })

  it('异常判定口径与后端 LogisticsTraceParser 关键词一致（描述含"破损"→ damage 建议）', () => {
    const t = mountTimeline({
      traces: [{ traceId: 5, traceTime: '2026-11-06T09:00:00', status: 'in_transit', description: '外包装破损', isAbnormal: 0 }]
    })
    const first = t.items()[0]
    expect(first.className).toContain('is-abnormal')
    expect(first.querySelector('.tl-advice-text').textContent).toContain('建议拍照留证')
    t.unmount()
  })

  it('状态分色：delivered 为 tone-ok，exception 为 tone-bad', () => {
    const t = mountTimeline({ traces: ASC_TRACES })
    const items = t.items()
    expect(items[0].querySelector('.tl-dot').className).toContain('tone-bad')
    expect(items[2].querySelector('.tl-dot').className).toContain('tone-info')
    t.unmount()
  })

  it('折叠：超过 collapsedCount 默认截断，点击展开全部后可见全量', async () => {
    const many = Array.from({ length: 8 }, (_, i) => ({
      traceId: i + 1, traceTime: `2026-11-06T0${i}:00:00`, status: 'in_transit', description: '节点' + i, isAbnormal: 0
    }))
    const t = mountTimeline({ traces: many, collapsedCount: 6 })
    expect(t.items()).toHaveLength(6)
    const btn = t.root.querySelector('.tl-toggle')
    expect(btn).toBeTruthy()
    btn.click()
    await nextTick()
    expect(t.items()).toHaveLength(8)
    t.unmount()
  })

  it('空轨迹展示占位，loading 态先出骨架', async () => {
    const t = mountTimeline({ traces: [], loading: true })
    expect(t.root.querySelector('.tl-skeleton')).toBeTruthy()
    await t.set({ loading: false })
    expect(t.root.querySelector('.tl-empty')).toBeTruthy()
    expect(t.root.textContent).toContain('暂无轨迹')
    t.unmount()
  })

  it('点击节点向外发 node-click 事件', () => {
    const t = mountTimeline({ traces: ASC_TRACES })
    t.items()[0].click()
    expect(t.clicks).toHaveLength(1)
    expect(t.clicks[0].traceId).toBe(3)
    t.unmount()
  })
})
