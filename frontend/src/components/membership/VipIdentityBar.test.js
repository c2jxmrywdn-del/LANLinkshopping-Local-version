import { describe, it, expect } from 'vitest'
import { h, createApp, defineComponent, nextTick } from 'vue'
import VipIdentityBar from './VipIdentityBar.vue'

// a-button 桩：render 函数实现（vitest 环境 vue 为 runtime-only 构建，不能用字符串模板）
const AButtonStub = defineComponent({
  emits: ['click'],
  setup(_, { slots, emit }) {
    return () => h('button', { class: 'a-button-stub', onClick: () => emit('click') }, slots.default?.())
  }
})

function mountBar(props) {
  const events = []
  const root = document.createElement('div')
  document.body.appendChild(root)
  const app = createApp({
    setup() {
      return () => h(VipIdentityBar, {
        ...props,
        onRenew: () => events.push('renew'),
        onCancel: () => events.push('cancel')
      })
    }
  })
  app.component('a-button', AButtonStub)
  app.mount(root)
  return {
    root, events,
    text: () => root.querySelector('.vip-idbar').textContent,
    buttons: () => [...root.querySelectorAll('.a-button-stub')].map(b => b.textContent.trim()),
    unmount() { app.unmount(); root.remove() }
  }
}

describe('VipIdentityBar（身份态三态）', () => {
  it('未订阅态：当前等级+等级折扣单行 + 升级提示，无续费/取消按钮', () => {
    const t = mountBar({ state: 'none', levelName: '黄金会员', rateText: '9.7 折' })
    expect(t.text()).toContain('当前等级：黄金会员 · 等级折扣 9.7 折')
    expect(t.text()).toContain('需有效订阅方可升级')
    expect(t.buttons()).toEqual([])
    t.unmount()
  })

  it('生效中态（auto_renew=false）：当前方案+到期时间+「到期可一键续费」，无取消入口，续费按钮齐备', () => {
    const t = mountBar({ state: 'active', planName: '年度订阅', endDate: '2027-01-01', multiplier: 2 })
    expect(t.text()).toContain('当前方案')
    expect(t.text()).toContain('年度订阅')
    expect(t.text()).toContain('到期时间')
    expect(t.text()).toContain('2027-01-01')
    expect(t.text()).toContain('到期可一键续费')
    // D3：auto_renew 已关（未开通自动续费/已撤销）→ chip 展示一键续费引导，取消入口隐藏
    expect(t.text()).not.toContain('自动续费')
    expect(t.buttons()).toEqual(['续费'])
    // 双轨口径并置：等级折扣轨 + VIP 消费身份轨
    expect(t.text()).toContain('等级折扣')
    expect(t.text()).toContain('VIP 消费享 ×2 积分（按累计消费身份判定）')
    t.unmount()
  })

  it('生效中态（auto_renew=true）：挂「自动续费」真实标注（P1-4 调度上线后按实际状态渲染），续费/取消按钮齐备', () => {
    const t = mountBar({ state: 'active', planName: '连续包月', endDate: '2027-01-01', autoRenew: true })
    expect(t.text()).toContain('自动续费')
    expect(t.buttons()).toEqual(['续费', '取消订阅'])
    t.unmount()
  })

  it('生效中态：续费/取消按钮事件上抛（cancel 走父级确认弹窗；auto_renew=1 时取消入口可见）', async () => {
    const t = mountBar({ state: 'active', planName: '年度订阅', endDate: '2027-01-01', autoRenew: true })
    const [, cancelBtn] = t.root.querySelectorAll('.a-button-stub')
    cancelBtn.click()
    await nextTick()
    expect(t.events).toEqual(['cancel'])
    const [renewBtn] = t.root.querySelectorAll('.a-button-stub')
    renewBtn.click()
    await nextTick()
    expect(t.events).toEqual(['cancel', 'renew'])
    t.unmount()
  })

  it('已取消/过期态：状态文案 + 到期时间 + 恢复入口（续费同链路），无取消按钮', () => {
    const t = mountBar({ state: 'ended', statusText: '已取消', endDate: '2026-12-31' })
    expect(t.text()).toContain('已取消')
    expect(t.text()).toContain('到期时间')
    expect(t.text()).toContain('2026-12-31')
    expect(t.buttons()).toEqual(['续费'])
    t.unmount()
  })

  it('回退口径：等级/折扣缺省时展示「普通会员 · 无折扣」', () => {
    const t = mountBar({ state: 'none' })
    expect(t.text()).toContain('当前等级：普通会员 · 等级折扣 无折扣')
    t.unmount()
  })
})
