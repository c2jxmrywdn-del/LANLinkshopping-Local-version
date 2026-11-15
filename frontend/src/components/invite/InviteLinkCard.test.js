import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { h, defineComponent, createApp, nextTick } from 'vue'

// 组件依赖全部 mock：二维码渲染与邀请接口都不触网/不碰真实 canvas。
vi.mock('qrcode', () => ({ default: { toCanvas: vi.fn() } }))
vi.mock('../../api/invite', () => ({
  getInviteLink: vi.fn(),
  rotateInviteLink: vi.fn(),
  getInviteStats: vi.fn()
}))
// message 用 mock 替代真实 toast，避免测试往 document.body 追加节点，并可断言提示文案
vi.mock('ant-design-vue', () => ({
  message: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }
}))

import QRCode from 'qrcode'
import { message } from 'ant-design-vue'
import { getInviteLink, rotateInviteLink } from '../../api/invite'
import InviteLinkCard from './InviteLinkCard.vue'
import cardSource from './InviteLinkCard.vue?raw'

// 仓库未安装 @vue/test-utils（package.json 约束不可改），沿用现有 *.test.js 的
// createApp + render 函数手挂载方式，断言真实编译后的组件 DOM。
const AButtonStub = defineComponent({
  emits: ['click'],
  setup(_, { slots, attrs, emit }) {
    return () => h('button', { ...attrs, onClick: () => emit('click') }, slots.default?.())
  }
})

const AInputStub = defineComponent({
  props: ['value'],
  setup(props, { attrs }) {
    return () => h('input', { ...attrs, value: props.value })
  }
})

// a-input-number 桩：承载 v-model:value，暴露 input 事件供用例改值
const AInputNumberStub = defineComponent({
  props: ['value'],
  emits: ['update:value'],
  setup(props, { attrs, emit }) {
    return () => h('input', {
      ...attrs,
      value: props.value ?? '',
      onInput: (e) => emit('update:value', e.target.value === '' ? undefined : Number(e.target.value))
    })
  }
})

const DAY = 86400000

// jsdom 对 canvas.toBlob / URL.createObjectURL / a.click 的支持不完整，
// 用例内按需打桩，结束后还原原始描述符，避免污染同文件其他用例。
const ORIGINALS = {
  toBlob: Object.getOwnPropertyDescriptor(HTMLCanvasElement.prototype, 'toBlob'),
  anchorClick: Object.getOwnPropertyDescriptor(HTMLAnchorElement.prototype, 'click'),
  createObjectURL: Object.getOwnPropertyDescriptor(URL, 'createObjectURL'),
  revokeObjectURL: Object.getOwnPropertyDescriptor(URL, 'revokeObjectURL'),
  clipboard: Object.getOwnPropertyDescriptor(navigator, 'clipboard'),
  execCommand: Object.getOwnPropertyDescriptor(document, 'execCommand')
}

function restoreDescriptor(target, key, descriptor) {
  if (descriptor) Object.defineProperty(target, key, descriptor)
  else delete target[key]
}

// 后端 LocalDateTime 不带时区（"2026-12-14T10:00:00"），JS 按本地时间解析；
// 夹具也按本地时间格式化，避免 toISOString 的 UTC 偏移把"已过期"变成"未来"。
function localIso(ms) {
  const d = new Date(ms)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function linkPayload(overrides = {}) {
  const expiresAt = localIso(Date.now() + 30 * DAY)
  return {
    code: 'INV8F3A2C9D1B4E5F6',
    url: 'http://localhost:5173/register?invite=INV8F3A2C9D1B4E5F6',
    inviteType: 'buyer',
    grantedRoleCode: 'buyer',
    expiresAt,
    expiresInSeconds: 2592000,
    maxUses: 0,
    usedCount: 0,
    remainingUses: -1,
    channel: 'link',
    ...overrides
  }
}

async function flush(times = 5) {
  for (let i = 0; i < times; i++) {
    await nextTick()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

function mountCard(props = {}) {
  const events = []
  const root = document.createElement('div')
  document.body.appendChild(root)
  const app = createApp({
    setup() {
      return () => h(InviteLinkCard, {
        ...props,
        onCopySuccess: () => events.push('copy-success'),
        onCopyError: () => events.push('copy-error'),
        onQrError: () => events.push('qr-error'),
        onRotated: (d) => events.push(['rotated', d]),
        onRotateError: () => events.push('rotate-error'),
        onSaveSuccess: () => events.push('save-success'),
        onSaveError: () => events.push('save-error')
      })
    }
  })
  app.component('a-button', AButtonStub)
  app.component('a-input', AInputStub)
  app.component('a-input-number', AInputNumberStub)
  app.mount(root)
  return {
    root,
    events,
    text: () => root.textContent,
    copyBtn: () => root.querySelector('.invite-copy-btn'),
    rotateBtn: () => [...root.querySelectorAll('button')].find((b) => /重新生成|Generate new link/.test(b.textContent)),
    saveBtn: () => root.querySelector('.invite-save-btn'),
    input: () => root.querySelector('.invite-link-input'),
    usesInput: () => root.querySelector('.invite-uses-input'),
    async setUses(value) {
      const el = root.querySelector('.invite-uses-input')
      el.value = String(value)
      el.dispatchEvent(new Event('input'))
      await nextTick()
    },
    unmount() { app.unmount(); root.remove() }
  }
}

describe('InviteLinkCard', () => {

  /**
   * 视觉材质契约：邀请卡必须是「平面浅底」。
   *
   * <p>回归缺陷：卡片曾用 `ll-glass--day`，该材质用 140deg 四段渐变模拟玻璃斜面，
   * 在大面积浅底上会呈现明显的斜向明暗分界（看起来像一道光栅），
   * 且 ::before 还有 6s 循环扫光，导致「一直在动」。
   */
  it('不使用玻璃/光栅材质：根节点无 ll-glass 类，样式无斜向渐变与扫光动画', () => {
    const src = cardSource.replace(/\r\n/g, '\n')
    // 根节点 <section> 标签本身不能再挂 ll-glass（注释里提到是允许的，用于说明历史原因）
    const sectionStart = src.indexOf('<section')
    const rootTag = src.slice(sectionStart, src.indexOf('>', sectionStart))
    expect(rootTag).not.toContain('ll-glass')
    // 样式区不得出现斜向渐变与扫光 keyframes
    const styleBlock = src.slice(src.indexOf('<style'))
    expect(styleBlock).not.toContain('140deg')
    expect(styleBlock).not.toContain('llDaySheen')
    expect(styleBlock).not.toContain('backdrop-filter')
  })

  it('卡片使用纯色底 + 细边（信息型卡片的稳定可读基线）', () => {
    const styleBlock = cardSource.slice(cardSource.indexOf('<style'))
    expect(styleBlock).toMatch(/\.invite-card\{[\s\S]{0,400}background:var\(--ll-card/)
    expect(styleBlock).toMatch(/\.invite-card\{[\s\S]{0,400}border:1px solid/)
  })

  /**
   * 邀请码明文的可见性边界。
   *
   * <p>需求：普通用户与商户在前台「不能出现邀请码」，运营在**前台**也不能出现，
   * 只有运营**后台**才展示。默认 showCode=false 保证任何前台调用点（个人中心、
   * 活动中心弹窗）都拿不到明文。
   */
  it('默认不展示邀请码明文（前台隐藏）', async () => {
    const t = mountCard()
    await flush()

    expect(t.root.querySelector('.invite-code')).toBeNull()
    // 明文不得出现在任何文本节点里
    expect(t.text()).not.toContain('INV8F3A2C9D1B4E5F6')
    // 但链接本身仍然可用（含同一个码）
    expect(t.input().value).toContain('INV8F3A2C9D1B4E5F6')
    t.unmount()
  })

  it('show-code 时才展示邀请码明文（运营后台）', async () => {
    const t = mountCard({ showCode: true })
    await flush()

    const codeEl = t.root.querySelector('.invite-code')
    expect(codeEl).not.toBeNull()
    expect(codeEl.textContent).toContain('INV8F3A2C9D1B4E5F6')
    t.unmount()
  })

  beforeEach(() => {
    vi.clearAllMocks()
    QRCode.toCanvas.mockResolvedValue(undefined)
    getInviteLink.mockResolvedValue(linkPayload())
    rotateInviteLink.mockResolvedValue(linkPayload({
      code: 'INVNEW0000000001',
      url: 'http://localhost:5173/register?invite=INVNEW0000000001'
    }))
  })

  afterEach(() => {
    restoreDescriptor(navigator, 'clipboard', ORIGINALS.clipboard)
    restoreDescriptor(document, 'execCommand', ORIGINALS.execCommand)
    restoreDescriptor(HTMLCanvasElement.prototype, 'toBlob', ORIGINALS.toBlob)
    restoreDescriptor(HTMLAnchorElement.prototype, 'click', ORIGINALS.anchorClick)
    restoreDescriptor(URL, 'createObjectURL', ORIGINALS.createObjectURL)
    restoreDescriptor(URL, 'revokeObjectURL', ORIGINALS.revokeObjectURL)
  })

  it('复制成功：调用 clipboard.writeText，链接原样写入', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })

    const t = mountCard()
    await flush()
    expect(t.input().value).toContain('INV8F3A2C9D1B4E5F6')

    t.copyBtn().click()
    await flush(3)

    expect(writeText).toHaveBeenCalledWith('http://localhost:5173/register?invite=INV8F3A2C9D1B4E5F6')
    expect(t.events).toContain('copy-success')
    expect(message.success).toHaveBeenCalled()
    t.unmount()
  })

  it('剪贴板不可用：回退 document.execCommand("copy") 并成功提示', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) },
      configurable: true
    })
    const execCommand = vi.fn(() => true)
    document.execCommand = execCommand

    const t = mountCard()
    await flush()
    t.copyBtn().click()
    await flush(3)

    expect(execCommand).toHaveBeenCalledWith('copy')
    expect(t.events).toContain('copy-success')
    expect(t.events).not.toContain('copy-error')
    t.unmount()
  })

  it('复制彻底失败：clipboard 与 execCommand 均不可用时提示手动复制', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) },
      configurable: true
    })
    document.execCommand = vi.fn(() => false)

    const t = mountCard()
    await flush()
    t.copyBtn().click()
    await flush(3)

    expect(t.events).toContain('copy-error')
    expect(message.error).toHaveBeenCalledWith(expect.stringContaining('手动'))
    t.unmount()
  })

  it('二维码生成成功：toCanvas 收到链接与 size/配色参数', async () => {
    const t = mountCard({ size: 220, dark: '#000000', light: '#FFFFFF' })
    await flush()

    expect(QRCode.toCanvas).toHaveBeenCalledTimes(1)
    const [canvas, text, opts] = QRCode.toCanvas.mock.calls[0]
    expect(canvas).toBe(t.root.querySelector('.invite-qr-canvas'))
    expect(text).toBe('http://localhost:5173/register?invite=INV8F3A2C9D1B4E5F6')
    expect(opts).toMatchObject({ width: 220, color: { dark: '#000000', light: '#FFFFFF' } })
    expect(t.text()).not.toContain('二维码生成失败')
    t.unmount()
  })

  it('有效期展示：30 天有效码显示「剩余 30 天」，不因毫秒误差进位成 31 天', async () => {
    const t = mountCard()
    await flush()
    expect(t.text()).toContain('剩余 30 天')
    expect(t.text()).not.toContain('剩余 31 天')
    t.unmount()
  })

  it('边界：expiresAt 缺失且 expiresInSeconds=-1 视为永久有效，复制可用', async () => {
    getInviteLink.mockResolvedValue(linkPayload({ expiresAt: null, expiresInSeconds: -1 }))

    const t = mountCard()
    await flush()

    expect(t.text()).toContain('永久有效')
    expect(t.text()).not.toContain('已过期')
    expect(t.copyBtn().disabled).toBe(false)
    t.unmount()
  })

  it('二维码抛错：降级文案展示，复制能力保留', async () => {
    QRCode.toCanvas.mockRejectedValue(new Error('canvas unsupported'))
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })

    const t = mountCard()
    await flush()

    expect(t.text()).toContain('二维码生成失败，可复制链接分享')
    expect(t.events).toContain('qr-error')
    expect(t.copyBtn().disabled).toBe(false)

    t.copyBtn().click()
    await flush(3)
    expect(writeText).toHaveBeenCalled()
    t.unmount()
  })

  it('过期状态：禁用复制与保存，展示过期文案', async () => {
    const expiredAt = localIso(Date.now() - 60 * 1000)
    getInviteLink.mockResolvedValue(linkPayload({ expiresAt: expiredAt, expiresInSeconds: 0 }))

    const t = mountCard()
    await flush()

    expect(t.text()).toContain('邀请链接已过期，请重新生成')
    expect(t.copyBtn().disabled).toBe(true)
    expect(t.saveBtn().disabled).toBe(true)
    expect(t.rotateBtn()).toBeTruthy()
    t.unmount()
  })

  it('重新生成成功：链接与二维码刷新为新邀请码', async () => {
    const t = mountCard()
    await flush()
    expect(t.input().value).toContain('INV8F3A2C9D1B4E5F6')

    t.rotateBtn().click()
    await flush(6)

    expect(rotateInviteLink).toHaveBeenCalledTimes(1)
    expect(t.input().value).toContain('INVNEW0000000001')
    // 前台默认不展示邀请码明文，只能通过链接输入框/二维码内容体现新码
    expect(t.root.querySelector('.invite-code')).toBeNull()
    expect(QRCode.toCanvas).toHaveBeenCalledTimes(2)
    expect(QRCode.toCanvas.mock.calls[1][1]).toBe('http://localhost:5173/register?invite=INVNEW0000000001')
    expect(t.events.some((e) => Array.isArray(e) && e[0] === 'rotated')).toBe(true)
    t.unmount()
  })

  it('保存二维码成功：canvas.toBlob → 下载 PNG，文件名含邀请码', async () => {
    const blob = new Blob(['png'], { type: 'image/png' })
    HTMLCanvasElement.prototype.toBlob = function (cb) { cb(blob) }
    URL.createObjectURL = vi.fn(() => 'blob:invite')
    URL.revokeObjectURL = vi.fn()
    const clicked = []
    HTMLAnchorElement.prototype.click = function () { clicked.push(this.download) }

    const t = mountCard()
    await flush()
    t.saveBtn().click()
    await flush(3)

    expect(clicked).toEqual(['invite-INV8F3A2C9D1B4E5F6.png'])
    expect(t.events).toContain('save-success')
    expect(message.success).toHaveBeenCalled()
    await flush(2)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:invite')
    t.unmount()
  })

  it('保存二维码失败：canvas.toBlob 不可用时提示错误且不触发下载', async () => {
    // jsdom 的 toBlob 只打 "Not implemented" 且不回调；显式给出 null blob 模拟"无法导出"
    HTMLCanvasElement.prototype.toBlob = function (cb) { cb(null) }
    const clicked = []
    HTMLAnchorElement.prototype.click = function () { clicked.push(this.download) }

    const t = mountCard()
    await flush()
    t.saveBtn().click()
    await flush(3)

    expect(clicked).toEqual([])
    expect(t.events).toContain('save-error')
    expect(message.error).toHaveBeenCalled()
    t.unmount()
  })

  it('重新生成失败：不更新链接、上抛 rotate-error，且不重复弹 toast（交给拦截器）', async () => {
    rotateInviteLink.mockRejectedValue(new Error('邀请链接生成过于频繁，请稍后再试'))

    const t = mountCard()
    await flush()
    t.rotateBtn().click()
    await flush(6)

    expect(message.error).not.toHaveBeenCalled()
    expect(t.input().value).toContain('INV8F3A2C9D1B4E5F6')
    expect(t.events).toContain('rotate-error')
    expect(t.text()).toContain('邀请链接生成过于频繁，请稍后再试')
    t.unmount()
  })

  it('重新生成成功后清除上一次失败提示', async () => {
    rotateInviteLink.mockRejectedValueOnce(new Error('临时故障'))

    const t = mountCard()
    await flush()
    t.rotateBtn().click()
    await flush(6)
    expect(t.text()).toContain('临时故障')

    t.rotateBtn().click()
    await flush(6)
    expect(t.text()).not.toContain('临时故障')
    expect(t.input().value).toContain('INVNEW0000000001')
    t.unmount()
  })

  it('merchant 邀请：顶部展示「邀请后成为：商户」徽标', async () => {
    getInviteLink.mockResolvedValue(linkPayload({
      inviteType: 'merchant',
      grantedRoleCode: 'merchant'
    }))

    const t = mountCard()
    await flush()

    expect(t.root.querySelector('.invite-role-badge').textContent).toContain('邀请后成为：商户')
    expect(t.text()).not.toContain('邀请后成为：普通用户')
    t.unmount()
  })

  it('buyer 邀请：徽标显示「邀请后成为：普通用户」，且无身份切换控件', async () => {
    const t = mountCard()
    await flush()

    expect(t.root.querySelector('.invite-role-badge').textContent).toContain('邀请后成为：普通用户')
    // 身份完全由服务端决定，前端不提供任何下拉/开关
    expect(t.root.querySelector('select')).toBeNull()
    expect(t.root.querySelector('.ant-select')).toBeNull()
    expect(t.root.querySelector('input[type="radio"]')).toBeNull()
    t.unmount()
  })

  /**
   * 商家入驻邀约（merchant_apply）：文案必须写清「审核通过后」才成为商户。
   *
   * <p>与注册邀请区分：注册邀请一律给买家；入驻邀约不能直接注册成商户。
   */
  it('merchant_apply 入驻邀约：徽标注明需审核通过，且不承诺注册即商户', async () => {
    getInviteLink.mockResolvedValue(linkPayload({
      inviteType: 'merchant_apply',
      scene: 'admin_console',
      grantedRoleCode: 'merchant',
      requiresMerchantReview: true
    }))

    const t = mountCard()
    await flush()

    const badge = t.root.querySelector('.invite-role-badge').textContent
    expect(badge).toContain('商家入驻邀约')
    expect(badge).toContain('审核通过后成为商户')
    // 不得出现"邀请后成为：商户"这种让人误以为注册即成商户的文案
    expect(badge).not.toContain('邀请后成为：商户')
    t.unmount()
  })

  it('不限次数：maxUses=0 显示「不限次数」，不显示已用/剩余', async () => {
    getInviteLink.mockResolvedValue(linkPayload({ maxUses: 0, usedCount: 2, remainingUses: -1 }))

    const t = mountCard()
    await flush()

    expect(t.text()).toContain('使用次数：不限次数')
    expect(t.text()).not.toContain('剩余 0')
    expect(t.copyBtn().disabled).toBe(false)
    t.unmount()
  })

  it('有限次数：显示「已用 usedCount / maxUses，剩余 remainingUses」', async () => {
    getInviteLink.mockResolvedValue(linkPayload({ maxUses: 5, usedCount: 2, remainingUses: 3 }))

    const t = mountCard()
    await flush()

    expect(t.text()).toContain('使用次数：已用 2 / 5，剩余 3')
    expect(t.copyBtn().disabled).toBe(false)
    t.unmount()
  })

  it('次数用尽：remainingUses=0 时禁用复制/保存并提示重新生成', async () => {
    getInviteLink.mockResolvedValue(linkPayload({ maxUses: 2, usedCount: 2, remainingUses: 0 }))

    const t = mountCard()
    await flush()

    expect(t.text()).toContain('邀请链接已用尽，请重新生成')
    expect(t.copyBtn().disabled).toBe(true)
    expect(t.saveBtn().disabled).toBe(true)
    expect(t.rotateBtn()).toBeTruthy()
    t.unmount()
  })

  it('次数边界：maxUses=1、remainingUses=-1（脏数据）按不限处理，不禁用复制', async () => {
    getInviteLink.mockResolvedValue(linkPayload({ maxUses: 1, usedCount: 0, remainingUses: -1 }))

    const t = mountCard()
    await flush()

    // maxUses>0 时 remainingUses=-1 无意义；组件按 max-used 兜底为 1，视为仍可用
    expect(t.copyBtn().disabled).toBe(false)
    t.unmount()
  })

  it('重新生成带 maxUses：输入次数上限后按数字透传给 rotateInviteLink', async () => {
    const t = mountCard()
    await flush()

    await t.setUses(5)
    t.rotateBtn().click()
    await flush(6)

    expect(rotateInviteLink).toHaveBeenCalledWith(5)
    t.unmount()
  })

  it('重新生成 maxUses 边界：输入 0 时传 0（后端按配置默认/不限），清空则不传参', async () => {
    const t = mountCard()
    await flush()

    await t.setUses(0)
    t.rotateBtn().click()
    await flush(6)
    expect(rotateInviteLink).toHaveBeenLastCalledWith(0)

    await t.setUses('')
    t.rotateBtn().click()
    await flush(6)
    expect(rotateInviteLink).toHaveBeenLastCalledWith(undefined)
    t.unmount()
  })

  it('重新生成 maxUses 超上限：输入 99999 时夹到 10000', async () => {
    const t = mountCard()
    await flush()

    await t.setUses(99999)
    t.rotateBtn().click()
    await flush(6)

    expect(rotateInviteLink).toHaveBeenCalledWith(10000)
    t.unmount()
  })

  it('重新生成后次数展示刷新为新链接的上限', async () => {
    rotateInviteLink.mockResolvedValue(linkPayload({
      code: 'INVNEW0000000001',
      url: 'http://localhost:5173/register?invite=INVNEW0000000001',
      maxUses: 3,
      usedCount: 0,
      remainingUses: 3
    }))

    const t = mountCard()
    await flush()
    await t.setUses(3)
    t.rotateBtn().click()
    await flush(6)

    expect(t.text()).toContain('使用次数：已用 0 / 3，剩余 3')
    expect(t.usesInput().value).toBe('3')
    t.unmount()
  })
})
