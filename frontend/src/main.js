import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import './styles/theme.css'
import './styles/motion.css'
import './styles/liquidGlass.css'
import './styles/mobile.css'
import './styles/techservice.css'
import App from './App.vue'
import router from './router'
import { initRipple } from './utils/ripple'
import { initScrollReveal } from './utils/scrollReveal'
import { initPerfGovernor } from './utils/perfGovernor'
import { installMobileAdapter } from './utils/mobileAdapter'
import { applyAutoLocale, applySavedLocaleSync, initI18nDom } from './i18n'
import { initAnalytics } from './utils/analytics'
import { installErrorReport } from './utils/errorReport'
import prefetchDirective from './directives/prefetch'
import { warmUpPublicConfigs } from './utils/bootPreload'

// ResizeObserver 循环通知根治：三方（高德 JSAPI / antd 组件）的 RO 回调若在通知派发
// 窗口内改动布局，浏览器会抛 "ResizeObserver loop completed with undelivered
// notifications." 的 window.onerror。统一把回调推迟到下一帧执行，派发窗口内零布局
// 变更，该报错从结构上不再产生；回调内读到的元素尺寸推迟后仍是实时值。
if (typeof window !== 'undefined' && typeof window.ResizeObserver === 'function' && !window.__llRoRafPatched) {
  window.__llRoRafPatched = true
  const NativeResizeObserver = window.ResizeObserver
  const schedule = typeof requestAnimationFrame === 'function'
    ? (cb) => requestAnimationFrame(() => cb())
    : (cb) => setTimeout(cb, 0)
  window.ResizeObserver = class extends NativeResizeObserver {
    constructor(callback) {
      super((entries, observer) => {
        schedule(() => callback(entries, observer))
      })
    }
  }
}

// 移动端适配器：在挂载前给 <html> 打上 mobile/desktop 标记，
// 所有移动端样式只在 mobile 标记下生效，PC 端保持原版。
installMobileAdapter()

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(Antd)
app.directive('prefetch', prefetchDirective)

// 运行时错误上报（P0-7）先于 analytics 安装：analytics 初始化期的异常也要被捕获。
// 收口在 utils/errorReport.js（去重 + 会话上限 + traffic events 静默通道）。
installErrorReport(app)

// Amplitude 双门控（API key + analytics Cookie 授权）与漏斗事件全部收口在 utils/analytics.js；
// 授权撤回经 ll-cookie-consent-changed 事件在该模块内 setOptOut，main.js 不再感知。
initAnalytics()

/*
 * 语言识别不再阻塞挂载。
 *
 * 原实现是 `applyAutoLocale().finally(() => app.mount())`：首次访客的离线信号置信度不足时
 * 会发 `GET /i18n/locale-hint`（超时 1200ms + 100ms），整页首屏被这个网络往返卡住，
 * 叠加启动 Splash 后 LCP 被推到 3s 以上。
 *
 * 现在的策略：
 *   1) 老用户（localStorage 里已有语言）→ 同步应用，零等待，直接挂载；
 *   2) 首次访客 → 先用默认语言挂载（中文兜底，避免空白屏），识别完成后再切换。
 * 识别结果仍会落到同一个响应式 locale 上，文案随之更新；因为首屏本来就是默认语言，
 * 不存在"先中文再英文"的闪切放大，最坏情况只是首次访客多一次文案更新。
 */
const savedLocale = applySavedLocaleSync()
app.mount('#app')
initScrollReveal()
initI18nDom()
warmUpPublicConfigs()

if (!savedLocale) {
  // 首次访客：异步识别，不阻塞挂载；识别完成后补一次 DOM 翻译（幂等）
  applyAutoLocale()
    .then(() => initI18nDom())
    .catch((e) => {
      console.warn('[i18n] auto locale detection failed:', e)
    })
}

// 全局按钮点击波纹（事件委托，任何 .ant-btn 生效）
initRipple()
// 性能治理：帧率自适应/渲染节流/软硬解码切换/熔断降级（保护渲染线程）
initPerfGovernor()

// 发版检测：长开标签页/旧缓存页可能一直运行旧 bundle（旧接口、旧错误提示），
// 启动 4s 后立即比对一次服务端 index.html 引用资源；不一致时自动刷新（每会话一次），
// 避免用户停留在旧页面看到"后端服务异常"却始终无法恢复。
if (import.meta.env.PROD && typeof window !== 'undefined' && !window.__lanlinkUpdateWatch) {
  window.__lanlinkUpdateWatch = true
  const RELOAD_FLAG = 'll_build_auto_reloaded'
  const currentAsset = (Array.from(document.querySelectorAll('script[type="module"]'))
    .map(s => s.src || '')
    .find(src => /\/assets\/index-[\w-]+\.js/.test(src)) || '').split('/').pop()
  if (currentAsset) {
    let dismissed = false
    const checkUpdate = async () => {
      if (dismissed || document.visibilityState === 'hidden') return
      try {
        const html = await (await fetch(`/index.html?t=${Date.now()}`, { cache: 'no-store' })).text()
        const m = html.match(/\/assets\/(index-[\w-]+\.js)/)
        if (!m || m[1] === currentAsset) return
        // 自动刷新只做一次，避免与开发热更新/灰度发布互相触发刷新循环
        if (!sessionStorage.getItem(RELOAD_FLAG)) {
          sessionStorage.setItem(RELOAD_FLAG, '1')
          window.location.reload()
          return
        }
        if (window.confirm('系统已更新，是否刷新页面加载最新版本？')) {
          window.location.reload()
        } else {
          dismissed = true
        }
      } catch {
        // 离线/后端重启窗口：静默，下次轮询再试
      }
    }
    window.setTimeout(checkUpdate, 4000)
    window.setInterval(checkUpdate, 45_000)
  }
}
