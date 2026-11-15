import { describe, it, expect, vi, beforeEach } from 'vitest'
import { h, defineComponent, createApp, nextTick } from 'vue'

// 只测「运营后台入驻邀约页」的接线：接口、二维码、组件全 mock，不触网不碰 canvas。
vi.mock('qrcode', () => ({ default: { toCanvas: vi.fn() } }))
vi.mock('../../api/invite', () => ({
  inviteApi: {
    getLink: vi.fn(),
    rotateLink: vi.fn(),
    getStats: vi.fn(),
    issueMerchantApplyCode: vi.fn()
  }
}))
// 组件直接 import 进来，必须模块级 mock 才能替换（app.component 只影响模板解析）
vi.mock('../../components/invite/InviteLinkCard.vue', () => ({
  default: defineComponent({
    // 与真实组件一致：showCode 声明为 Boolean，Vue 会把无值的 `show-code` 规范化为 true
    props: {
      initialLink: Object,
      showRotate: Boolean,
      autoLoad: Boolean,
      showCode: Boolean
    },
    setup: (p) => () => h('div', {
      class: 'invite-card-stub',
      'data-show-code': String(p.showCode === true)
    }, p.initialLink?.code || '')
  })
}))
vi.mock('ant-design-vue', () => ({
  message: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
  // 页面用到的展示型组件做轻量桩，避免引入完整 antd 运行时
  Alert: defineComponent({ props: ['message'], setup: (p) => () => h('div', { class: 'alert' }, p.message) }),
  Empty: defineComponent({ props: ['description'], setup: (p) => () => h('div', { class: 'empty' }, p.description) }),
  Spin: defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) }),
  Button: defineComponent({
    emits: ['click'],
    setup: (_, { slots, attrs, emit }) => () => h('button', { ...attrs, onClick: () => emit('click') }, slots.default?.())
  }),
  InputNumber: defineComponent({
    props: ['value'],
    emits: ['update:value'],
    setup: (props, { attrs, emit }) => () => h('input', {
      ...attrs,
      value: props.value ?? '',
      onInput: (e) => emit('update:value', e.target.value === '' ? undefined : Number(e.target.value))
    })
  })
}))

import { inviteApi } from '../../api/invite'
import AdminMerchantInvite from './AdminMerchantInvite.vue'

const AButtonStub = defineComponent({
  emits: ['click'],
  setup: (_, { slots, attrs, emit }) => () => h('button', { ...attrs, onClick: () => emit('click') }, slots.default?.())
})
const AInputNumberStub = defineComponent({
  props: ['value'],
  emits: ['update:value'],
  setup: (props, { attrs, emit }) => () => h('input', {
    ...attrs,
    value: props.value ?? '',
    onInput: (e) => emit('update:value', e.target.value === '' ? undefined : Number(e.target.value))
  })
})
const AAlertStub = defineComponent({ props: ['message'], setup: (p) => () => h('div', { class: 'alert' }, p.message) })
const AEmptyStub = defineComponent({ props: ['description'], setup: (p) => () => h('div', { class: 'empty' }, p.description) })
const ASpinStub = defineComponent({ setup: (_, { slots }) => () => h('div', { class: 'spin' }, slots.default?.()) })
function mount() {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp({
    render: () => h(AdminMerchantInvite)
  })
  app.component('AButton', AButtonStub)
  app.component('AInputNumber', AInputNumberStub)
  app.component('AAlert', AAlertStub)
  app.component('AEmpty', AEmptyStub)
  app.component('ASpin', ASpinStub)
  app.mount(host)
  return { app, host }
}

const flush = async () => { await nextTick(); await Promise.resolve(); await nextTick() }

describe('AdminMerchantInvite 运营后台入驻邀约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('挂载即请求 merchant_apply 码（不是前台 buyer 邀请）', async () => {
    inviteApi.issueMerchantApplyCode.mockResolvedValue({
      code: 'INVAPPLY000001', inviteType: 'merchant_apply', scene: 'admin_console',
      grantedRoleCode: 'merchant', requiresMerchantReview: true, maxUses: 0, usedCount: 0, remainingUses: -1
    })

    const { app, host } = mount()
    await flush()

    expect(inviteApi.issueMerchantApplyCode).toHaveBeenCalled()
    expect(inviteApi.getLink).not.toHaveBeenCalled()
    expect(host.querySelector('.invite-card-stub').textContent).toContain('INVAPPLY000001')
    app.unmount(); host.remove()
  })

  it('页面明确说明「审核通过后才成为商户」', async () => {
    inviteApi.issueMerchantApplyCode.mockResolvedValue({ code: 'INVA', inviteType: 'merchant_apply' })
    const { app, host } = mount()
    await flush()

    const text = host.textContent
    expect(text).toContain('审核通过后')
    expect(text).toContain('商户身份')
    app.unmount(); host.remove()
  })

  /**
   * 运营后台是唯一允许看到邀请码明文的入口。
   *
   * <p>前台（个人中心、活动中心弹窗）必须隐藏明文，因此组件默认 showCode=false；
   * 本页必须显式开启，否则运营无法把码口述/录入线下渠道。
   */
  it('显式开启 showCode（运营后台才展示邀请码明文）', async () => {
    inviteApi.issueMerchantApplyCode.mockResolvedValue({
      code: 'INVAPPLY000001', inviteType: 'merchant_apply'
    })
    const { app, host } = mount()
    await flush()

    const card = host.querySelector('.invite-card-stub')
    expect(card).not.toBeNull()
    expect(card.getAttribute('data-show-code')).toBe('true')
    app.unmount(); host.remove()
  })

  it('生成失败：展示错误文案且不渲染卡片', async () => {
    inviteApi.issueMerchantApplyCode.mockRejectedValue(new Error('无权限：仅平台运营可生成商家入驻邀请码'))
    const { app, host } = mount()
    await flush()

    expect(host.querySelector('.alert').textContent).toContain('无权限')
    expect(host.querySelector('.invite-card-stub')).toBeNull()
    app.unmount(); host.remove()
  })
})
