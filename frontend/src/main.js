import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import './styles/theme.css'
import './styles/motion.css'
import './styles/liquidGlass.css'
import './styles/mobile.css'
import App from './App.vue'
import router from './router'
import { initRipple } from './utils/ripple'
import { initScrollReveal } from './utils/scrollReveal'
import { initPerfGovernor } from './utils/perfGovernor'
import { installMobileAdapter } from './utils/mobileAdapter'
import { applyAutoLocale, initI18nDom } from './i18n'
import * as amplitude from '@amplitude/unified'

// 移动端适配器：在挂载前给 <html> 打上 mobile/desktop 标记，
// 所有移动端样式只在 mobile 标记下生效，PC 端保持原版。
installMobileAdapter()

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(Antd)

const amplitudeApiKey = import.meta.env.VITE_AMPLITUDE_API_KEY
if (!amplitudeApiKey) {
  // 未配置埋点密钥属于预期状态：用 debug 级别记录，避免污染默认控制台
  console.debug('[analytics] Amplitude API key missing — analytics disabled')
} else if (!window.__lanlinkAmplitudeInitialized) {
  // Keep the browser SDK singleton across app lifecycle and Vite HMR.
  window.__lanlinkAmplitudeInitialized = true
  amplitude.initAll(amplitudeApiKey, {
    analytics: {
      autocapture: true,
      // CSP 合规：关闭自定义 enrichment，避免远程配置下发函数字符串、
      // SDK 用 new Function 求值而被 script-src 拦截（显式 false 可阻止远程配置重新开启）
      customEnrichment: false
    },
    sessionReplay: { sampleRate: 1 }
  })

  // Emit exactly one explicit setup event, only when the initial route is home.
  router.isReady().then(() => {
    if (router.currentRoute.value.name === 'home') {
      amplitude.track('Viewed Home Page', {
        prompt_version: 'BA400.4'
      }) // helps improve this setup flow — safe to remove once you've verified the event lands
    }
  })
}

// 语言自动识别先于挂载：识别结果落地后再渲染，避免"先中文再英文"的可见闪切与文字错乱。
// 已有手动选择时零延迟；离线信号足够明确时不会发起 IP 请求。
async function bootstrapLocale() {
  try {
    await applyAutoLocale()
  } catch (e) {
    console.warn('[i18n] auto locale detection failed:', e)
  }
}
bootstrapLocale().finally(() => {
  app.mount('#app')
  initI18nDom()
})

// 全局按钮点击波纹（事件委托，任何 .ant-btn 生效）
initRipple()
// 滚动进场动效：启动 + 每次路由进入后自动扫描 [data-reveal] 元素
initScrollReveal()
// 性能治理：帧率自适应/渲染节流/软硬解码切换/熔断降级（保护渲染线程）
initPerfGovernor()

// 发版检测：长开标签页可能一直运行旧 bundle（旧 15s 超时、旧"系统繁忙"遮罩），
// 发现服务端 index.html 引用的新构建时提示刷新，避免"明明已修复但页面仍报错"。
if (import.meta.env.PROD && typeof window !== 'undefined' && !window.__lanlinkUpdateWatch) {
  window.__lanlinkUpdateWatch = true
  const currentAsset = (Array.from(document.querySelectorAll('script[type="module"]'))
    .map(s => s.src || '')
    .find(src => /\/assets\/index-[\w-]+\.js/.test(src)) || '').split('/').pop()
  if (currentAsset) {
    let dismissed = false
    window.setInterval(async () => {
      if (dismissed || document.visibilityState === 'hidden') return
      try {
        const html = await (await fetch(`/index.html?t=${Date.now()}`, { cache: 'no-store' })).text()
        const m = html.match(/\/assets\/(index-[\w-]+\.js)/)
        if (!m || m[1] === currentAsset) return
        if (window.confirm('系统已更新，是否刷新页面加载最新版本？')) {
          window.location.reload()
        } else {
          dismissed = true // 用户选择稍后刷新，本次会话不再打扰
        }
      } catch (e) {
        // 离线/后端重启窗口：静默，下次轮询再试
      }
    }, 60_000)
  }
}
