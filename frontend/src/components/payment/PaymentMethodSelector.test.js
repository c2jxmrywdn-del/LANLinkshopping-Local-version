import { describe, it, expect } from 'vitest'
import { h, createApp, nextTick } from 'vue'

// 被测组件为纯 props 组件（无 api/antd 依赖），直接挂载即可。
// 覆盖 PaymentMethodSelector 钱包渠道（apple_pay/google_pay）的两种能力前提态：
// walletReady=false（未启用环境）呈禁用灰度态；walletReady=true（域名验证完成的
// 环境）呈启用态。验收断言 7 的前提依赖环境数据，这里把两态固化为可重复断言。
import PaymentMethodSelector from './PaymentMethodSelector.vue'

async function flush(times = 3) {
  for (let i = 0; i < times; i++) {
    await nextTick()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

function mountSelector(props = {}) {
  const emitted = []
  const root = document.createElement('div')
  document.body.appendChild(root)
  const app = createApp({
    setup() {
      return () => h(PaymentMethodSelector, {
        modelValue: 'alipay',
        ...props,
        'onUpdate:modelValue': (v) => emitted.push(['update:modelValue', v]),
        onChange: (v) => emitted.push(['change', v])
      })
    }
  })
  app.mount(root)
  return {
    root,
    emitted,
    tiles: () => [...root.querySelectorAll('.bento-tile')],
    tileByTitle: (kw) =>
      [...root.querySelectorAll('.bento-tile')].find((t) =>
        t.querySelector('.tile-title')?.textContent.includes(kw)
      ),
    unmount() { app.unmount(); root.remove() }
  }
}

describe('PaymentMethodSelector 钱包渠道能力两态', () => {
  const KEYS = ['stripe', 'apple_pay', 'google_pay', 'alipay', 'wechat', 'balance', 'term', 'corporate']

  it('渲染 8 张渠道卡（默认不排除任何渠道）', () => {
    const t = mountSelector()
    expect(t.tiles()).toHaveLength(KEYS.length)
    for (const key of KEYS) {
      expect(t.tiles().some((el) => el.textContent.includes(
        { stripe: 'Stripe 国际卡', apple_pay: 'Apple Pay', google_pay: 'Google Pay', alipay: '支付宝',
          wechat: '微信支付', balance: '企业钱包', term: '企业账期', corporate: '对公转账' }[key]
      ))).toBe(true)
    }
    t.unmount()
  })

  it('walletReady=false（未启用环境）：钱包格呈禁用态——disabled 类、徽章「未启用」、LOGO 灰度类，点击不切换', async () => {
    const t = mountSelector({ walletReady: false })
    await flush()

    for (const kw of ['Apple Pay', 'Google Pay']) {
      const tile = t.tileByTitle(kw)
      expect(tile, `${kw} 渠道卡应存在`).toBeTruthy()
      expect(tile.classList.contains('disabled')).toBe(true)
      expect(tile.querySelector('.tile-badge')?.textContent).toBe('未启用')
      // 官方黑 glyph LOGO 带灰度弱化类（深色主题反白类恒在）
      const logo = tile.querySelector('img.brand-logo')
      expect(logo).toBeTruthy()
      expect(logo.className).toContain('logo-muted')
      // 禁用态点击：不发出任何选中事件
      tile.click()
      await flush()
      expect(t.emitted).toEqual([])
    }
    t.unmount()
  })

  it('walletReady=true（本环境实测：域名验证完成）：钱包格启用——无 disabled、徽章 iOS/Mac·Android，点击发出选中事件', async () => {
    const t = mountSelector({ walletReady: true })
    await flush()

    const expectEnabled = (kw, badgeText, key) => {
      const tile = t.tileByTitle(kw)
      expect(tile, `${kw} 渠道卡应存在`).toBeTruthy()
      expect(tile.classList.contains('disabled')).toBe(false)
      expect(tile.querySelector('.tile-badge')?.textContent).toBe(badgeText)
      const logo = tile.querySelector('img.brand-logo')
      expect(logo).toBeTruthy()
      expect(logo.className).not.toContain('logo-muted')
      return tile
    }

    const apple = expectEnabled('Apple Pay', 'iOS/Mac', 'apple_pay')
    apple.click()
    await flush()
    expect(t.emitted).toContainEqual(['update:modelValue', 'apple_pay'])
    expect(t.emitted).toContainEqual(['change', 'apple_pay'])

    const google = expectEnabled('Google Pay', 'Android', 'google_pay')
    google.click()
    await flush()
    expect(t.emitted).toContainEqual(['update:modelValue', 'google_pay'])
    expect(t.emitted).toContainEqual(['change', 'google_pay'])
    t.unmount()
  })

  it('walletReady=false 的文案为「需域名验证」说明（用户可读懂为何不可用）', async () => {
    const t = mountSelector({ walletReady: false })
    await flush()
    expect(t.tileByTitle('Apple Pay').querySelector('.tile-desc')?.textContent)
      .toContain('域名验证')
    expect(t.tileByTitle('Google Pay').querySelector('.tile-desc')?.textContent)
      .toContain('Stripe 后台')
    t.unmount()
  })
})
