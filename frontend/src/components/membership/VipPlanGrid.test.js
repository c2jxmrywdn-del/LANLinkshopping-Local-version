import { describe, it, expect } from 'vitest'
import { h, reactive, createApp, nextTick } from 'vue'
import VipPlanGrid from './VipPlanGrid.vue'

// 无 @vue/test-utils 依赖（零新增 npm 包）：createApp + render 函数手动挂载到 jsdom
function mountGrid(initialProps) {
  const props = reactive({ plans: [], modelValue: '', busy: '', billing: 'year', ...initialProps })
  const events = []
  const root = document.createElement('div')
  document.body.appendChild(root)
  const app = createApp({
    setup() {
      return () => h(VipPlanGrid, {
        ...props,
        'onUpdate:modelValue': (v) => { events.push(['model', v]); props.modelValue = String(v) },
        'onUpdate:billing': (v) => { events.push(['billing', v]); props.billing = v }
      })
    }
  })
  app.mount(root)
  return {
    root, events, props,
    set(p) { Object.assign(props, p); return nextTick() },
    radios: () => [...root.querySelectorAll('[role="radio"]')],
    grid: () => root.querySelector('[role="radiogroup"][aria-label="订阅档位"]'),
    checked: () => root.querySelector('[role="radio"][aria-checked="true"]'),
    unmount() { app.unmount(); root.remove() }
  }
}

const PLANS = [
  { planId: 1, planCode: 'MONTHLY', planName: '月度订阅', priceCents: 8888, durationDays: 30, autoRenew: false, discountLabel: '' },
  { planId: 2, planCode: 'AUTO_MONTHLY', planName: '连续包月', priceCents: 8299, durationDays: 30, autoRenew: true, discountLabel: '省7%' },
  { planId: 3, planCode: 'QUARTERLY', planName: '季度订阅', priceCents: 23999, durationDays: 90, autoRenew: false, discountLabel: '省10%' },
  { planId: 4, planCode: 'ANNUAL', planName: '年度订阅', priceCents: 88888, durationDays: 365, autoRenew: false, discountLabel: '省17%' }
]

describe('VipPlanGrid', () => {
  it('radio 语义：档位卡为 role=radio + aria-checked，卡组为 radiogroup，选中与提交分离（点击只发 update:modelValue）', async () => {
    const t = mountGrid({ plans: PLANS, modelValue: 4 })
    await t.set({})
    const grid = t.grid()
    expect(grid).toBeTruthy()
    expect(grid.getAttribute('aria-label')).toBe('订阅档位')
    const radios = t.radios()
    expect(radios.length).toBe(2) // 按年段：季度 + 年度
    // 恰好一张卡选中（modelValue=4 年度），aria-checked / roving tabindex 齐备
    const annual = t.checked()
    expect(annual).toBeTruthy()
    expect(annual.textContent).toContain('年度订阅')
    expect(annual.getAttribute('aria-checked')).toBe('true')
    expect(annual.getAttribute('tabindex')).toBe('0')
    radios.filter(r => r !== annual).forEach(r => {
      expect(r.getAttribute('aria-checked')).toBe('false')
      expect(r.getAttribute('tabindex')).toBe('-1')
    })
    // 点击未选中卡：只切换选中态，无提交事件
    t.events.length = 0
    radios[0].click()
    await nextTick()
    expect(t.events).toEqual([['model', 3]])
    expect(t.checked().textContent).toContain('季度订阅')
    // 卡内不渲染任何提交按钮（选择与提交分离）
    expect(grid.querySelectorAll('button[type="submit"], .plan-buy').length).toBe(0)
    t.unmount()
  })

  it('键盘换挡：方向键在档位间移动选中（radio 群组语义）', async () => {
    const t = mountGrid({ plans: PLANS, modelValue: 4 })
    await t.set({})
    const grid = t.grid()
    // ArrowRight：年度 → 季度（循环到段内下一档）
    grid.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }))
    await nextTick()
    expect(t.checked().textContent).toContain('季度订阅')
    // ArrowLeft：季度 → 年度
    grid.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }))
    await nextTick()
    expect(t.checked().textContent).toContain('年度订阅')
    // ArrowUp：年度 → 季度
    grid.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }))
    await nextTick()
    expect(t.checked().textContent).toContain('季度订阅')
    // ArrowDown：季度 → 年度
    grid.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    await nextTick()
    expect(t.checked().textContent).toContain('年度订阅')
    t.unmount()
  })

  it('推荐档徽标 + Apple 价格层级：年度卡挂推荐档，划线对比价 = 月度价×12，折合价 = 年价/12', async () => {
    const t = mountGrid({ plans: PLANS, modelValue: 4 })
    await t.set({})
    const annual = t.checked()
    expect(annual.querySelector('.plan-reco').textContent).toBe('推荐档')
    expect(annual.querySelector('.plan-strike').textContent).toBe('¥106,656') // 8888 × 12
    expect(annual.querySelector('.plan-equiv').textContent).toBe('折合 ¥7,407/月') // 88888/12 取整
    expect(annual.querySelector('.plan-price').textContent).toBe('¥88,888')
    // 季度卡无推荐档
    const quarterly = t.radios().find(r => r.textContent.includes('季度订阅'))
    expect(quarterly.querySelector('.plan-reco')).toBeNull()
    t.unmount()
  })

  it('分段切换：按月段展示月度+连续包月，连续包月按方案实际 auto_renew 挂「自动续费」标注', async () => {
    const t = mountGrid({ plans: PLANS, modelValue: 4, billing: 'month' })
    await t.set({})
    const names = t.radios().map(r => r.textContent)
    expect(names.some(n => n.includes('月度订阅'))).toBe(true)
    expect(names.some(n => n.includes('连续包月'))).toBe(true)
    const auto = t.radios().find(r => r.textContent.includes('连续包月'))
    // P1-4 扣款调度上线后恢复「自动续费」真实标注（连续包月方案 auto_renew=1）
    expect(auto.textContent).toContain('自动续费')
    // 非自动续费档（月度/季度/年度）不渲染续费标注
    const monthly = t.radios().find(r => r.textContent.includes('月度订阅'))
    expect(monthly.textContent).not.toContain('自动续费')
    // 分段切换事件走 native radio（v-model → update:billing）
    const segInput = t.root.querySelector('input[type="radio"][value="year"]')
    segInput.click()
    await nextTick()
    expect(t.events.some(e => e[0] === 'billing' && e[1] === 'year')).toBe(true)
    t.unmount()
  })

  it('P1 真实口径：仅 auto_renew 方案挂「自动续费」，其余档位不出现该标注', async () => {
    const t = mountGrid({ plans: PLANS, modelValue: 2, billing: 'month' })
    await t.set({})
    // 连续包月（autoRenew: true）有标注；月度/季度/年度（autoRenew: false）没有
    expect(t.radios().find(r => r.textContent.includes('连续包月')).textContent).toContain('自动续费')
    expect(t.radios().find(r => r.textContent.includes('月度订阅')).textContent).not.toContain('自动续费')
    t.unmount()
  })
})
