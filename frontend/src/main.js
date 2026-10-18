import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import './styles/theme.css'
import './styles/motion.css'
import App from './App.vue'
import router from './router'
import { initRipple } from './utils/ripple'
import { initScrollReveal } from './utils/scrollReveal'
import { initI18nDom } from './i18n'
import * as amplitude from '@amplitude/unified'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(Antd)

const amplitudeApiKey = import.meta.env.VITE_AMPLITUDE_API_KEY
if (!amplitudeApiKey) {
  console.warn('Amplitude API key missing — analytics disabled')
} else if (!window.__lanlinkAmplitudeInitialized) {
  // Keep the browser SDK singleton across app lifecycle and Vite HMR.
  window.__lanlinkAmplitudeInitialized = true
  amplitude.initAll(amplitudeApiKey, {
    analytics: { autocapture: true },
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

app.mount('#app')
initI18nDom()

// 全局按钮点击波纹（事件委托，任何 .ant-btn 生效）
initRipple()
// 滚动进场动效：启动 + 每次路由进入后自动扫描 [data-reveal] 元素
initScrollReveal()
