import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { h, createApp, nextTick, defineComponent } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import SupportChat from './SupportChat.vue'
import { supportApi } from '../api'

// antd message 桩（jsdom 下 ant-design-vue 组件按需可用，message 为对象导出，原样保留）
// lucide 图标为函数组件，无需桩。

const RouterLinkStub = defineComponent({
  props: ['to'],
  setup(props, { slots }) {
    return () => h('a', { href: props.to || '#' }, slots.default?.())
  }
})

/**
 * 挂载 SupportChat（真组件、真状态机，仅访客令牌与网络层可控）。
 * supportApi.ask / askStream / history 用 vi.spyOn 打桩，
 * askStream 桩默认走 onDone（流式链路不在本测试范围）。
 */
function mountChat() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div/>' } }]
  })
  const root = document.createElement('div')
  document.body.appendChild(root)
  const app = createApp({ render: () => h(SupportChat) })
  app.use(router)
  app.component('router-link', RouterLinkStub)
  app.mount(root)
  return { root, app }
}

function openPanel(root) {
  root.querySelector('.support-launcher').click()
  return nextTick()
}

function markerText() {
  // 与 SupportChat.vue / i18n zh 词典保持一致
  return '转接请求：需要人工客服协助'
}

async function clickTransfer(root) {
  root.querySelector('.support-human-btn').click()
  await nextTick()
  await nextTick()
}

describe('SupportChat 转人工（P1-20）', () => {
  let askSpy
  let askStreamSpy
  let historySpy

  // jsdom 未内置 storage 实现（Node 25 实验性 storage 在本仓 vitest 下无 clear 方法）：
  // 注入内存桩，语义同 i18n.test.js 的 installStorage
  function installStorage() {
    const make = () => {
      const mem = new Map()
      return {
        getItem: (k) => (mem.has(k) ? mem.get(k) : null),
        setItem: (k, v) => { mem.set(k, String(v)) },
        removeItem: (k) => { mem.delete(k) },
        clear: () => { mem.clear() }
      }
    }
    globalThis.localStorage = make()
    globalThis.sessionStorage = make()
  }

  beforeEach(() => {
    installStorage()
    document.body.innerHTML = ''
    askSpy = vi.spyOn(supportApi, 'ask').mockResolvedValue({
      answer: '已记录您的转接请求。', visitorToken: 'aaaaaaaa-1111-4111-8111-aaaaaaaaaaa1', lastMessageId: 3
    })
    askStreamSpy = vi.spyOn(supportApi, 'askStream').mockImplementation(async ({ onMeta, onDone }) => {
      if (onMeta) onMeta({ cacheStatus: '', similarity: 0 })
      if (onContent) onContent('知识库回答')
      if (onDone) onDone()
    })
    historySpy = vi.spyOn(supportApi, 'history').mockResolvedValue({ messages: [], visitorToken: '', lastMessageId: 0 })
  })

  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('面板打开后显示「转接人工客服」按钮（Headphones 图标，禁 emoji）', async () => {
    const t = mountChat()
    await openPanel(t.root)
    const btn = t.root.querySelector('.support-human-btn')
    expect(btn).toBeTruthy()
    expect(btn.textContent).toContain('转接人工客服')
    // 禁 emoji：按钮内不允许出现 emoji 码位（U+1F000-U+1FAFF / U+2600-U+27BF）
    expect(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(btn.textContent)).toBe(false)
    expect(btn.querySelector('svg')).toBeTruthy() // lucide 图标以 svg 渲染
    t.app.unmount()
  })

  it('点击转人工：调 supportApi.ask 落一条普通客户消息（persist=true），进入人工会话态', async () => {
    const t = mountChat()
    await openPanel(t.root)
    await clickTransfer(t.root)

    expect(askSpy).toHaveBeenCalledTimes(1)
    const [question, , persist] = askSpy.mock.calls[0]
    expect(question).toBe(markerText())

    // 人工会话态 UI：横幅出现，占位文案如实写值班/留言队列
    const banner = t.root.querySelector('.support-human-banner')
    expect(banner).toBeTruthy()
    expect(banner.textContent).toContain('已转接人工客服')
    expect(banner.textContent).toContain('工作日 9:00 - 18:00')
    expect(banner.textContent).toContain('当前时段无值班客服将进入留言队列')
    // 输入区按钮切换为「返回智能客服」（横幅文案含「已转接人工客服」子串，不能按全文断言）
    expect(t.root.querySelector('.support-human-btn.is-back')).toBeTruthy()
    expect(t.root.querySelector('.support-human-btn:not(.is-back)')).toBeNull()
    t.app.unmount()
  })

  it('转人工请求标记消息进入会话（用户气泡）且 sessionStorage 持久化会话态', async () => {
    const t = mountChat()
    await openPanel(t.root)
    await clickTransfer(t.root)
    expect(t.root.textContent).toContain(markerText())
    expect(sessionStorage.getItem('ll_support_human_mode')).toBe('1')
    // 重新挂载（模拟关面板重开）：会话态恢复
    t.app.unmount()
    document.body.innerHTML = ''
    const t2 = mountChat()
    await openPanel(t2.root)
    expect(t2.root.querySelector('.support-human-banner')).toBeTruthy()
    t2.app.unmount()
  })

  it('人工会话态内发送消息：走 ask 持久化（消息中心可见），AI 回答不上屏', async () => {
    const t = mountChat()
    await openPanel(t.root)
    await clickTransfer(t.root)
    askSpy.mockClear()
    askStreamSpy.mockClear()

    const ta = t.root.querySelector('#support-question')
    ta.value = '订单被扣款了但没发货'
    ta.dispatchEvent(new Event('input'))
    await nextTick()
    t.root.querySelector('.support-compose').dispatchEvent(new Event('submit'))
    await nextTick()
    await nextTick()
    await Promise.resolve()

    // ask 被调（买家消息落库、管理端可见）；askStream（AI 流式）不被调
    expect(askSpy).toHaveBeenCalledTimes(1)
    expect(askSpy.mock.calls[0][0]).toBe('订单被扣款了但没发货')
    expect(askStreamSpy).not.toHaveBeenCalled()
    // 买家消息上屏，mock 的 bot 回答「已记录您的转接请求。」不上屏
    expect(t.root.textContent).toContain('订单被扣款了但没发货')
    t.app.unmount()
  })

  it('人工会话态轮询拉到 agent 回复：标记为「人工客服」气泡；customer 自发消息去重', async () => {
    const t = mountChat()
    await openPanel(t.root)
    await clickTransfer(t.root)
    // 提前装好轮询返回（send 内 humanSession 分支会触发 void syncHistory）：
    // 自己的 customer 消息（游标重放）+ 一条 agent 回复
    historySpy.mockResolvedValue({
      messages: [
        { id: 4, senderType: 'customer', senderName: '网站访客', content: '订单被扣款了但没发货', createdAt: '2026-11-10 10:00:00' },
        { id: 5, senderType: 'agent', senderName: '客服小林', content: '您好，人工已接手，正在核实订单状态。', createdAt: '2026-11-10 10:01:00' }
      ],
      visitorToken: '',
      lastMessageId: 5
    })
    // 手动发送一条买家消息（本地即时上屏，无 messageId）
    const ta = t.root.querySelector('#support-question')
    ta.value = '订单被扣款了但没发货'
    ta.dispatchEvent(new Event('input'))
    await nextTick()
    t.root.querySelector('.support-compose').dispatchEvent(new Event('submit'))
    await nextTick()
    await nextTick()
    // 等 syncHistory 的 fetch 桩 resolve 并渲染
    await new Promise(r => setTimeout(r, 0))
    await nextTick()
    await new Promise(r => setTimeout(r, 0))
    await nextTick()

    // customer 自发消息去重：气泡只出现一次
    const bubbles = [...t.root.querySelectorAll('.support-message')].map(b => b.textContent)
    expect(bubbles.filter(b => b.includes('订单被扣款了但没发货')).length).toBe(1)
    // agent 回复渲染为「人工客服」标签气泡
    const agentBubble = bubbles.find(b => b.includes('人工已接手'))
    expect(agentBubble).toBeTruthy()
    expect(agentBubble).toContain('人工客服')
    t.app.unmount()
  })

  it('返回智能客服：退出人工会话态、横幅消失、问候语回归', async () => {
    const t = mountChat()
    await openPanel(t.root)
    await clickTransfer(t.root)
    expect(t.root.querySelector('.support-human-banner')).toBeTruthy()

    t.root.querySelector('.support-human-btn.is-back').click()
    await nextTick()
    await nextTick()

    expect(t.root.querySelector('.support-human-banner')).toBeNull()
    expect(sessionStorage.getItem('ll_support_human_mode')).toBe('0')
    expect(t.root.textContent).toContain('转接人工客服')
    t.app.unmount()
  })

  it('ask 持久化失败：本地转人工仍生效（会话态不依赖后端落库）', async () => {
    askSpy.mockRejectedValueOnce(new Error('network down'))
    const t = mountChat()
    await openPanel(t.root)
    await clickTransfer(t.root)
    expect(t.root.querySelector('.support-human-banner')).toBeTruthy()
    t.app.unmount()
  })
})
