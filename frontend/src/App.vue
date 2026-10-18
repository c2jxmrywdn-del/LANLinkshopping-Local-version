<template>
  <a-config-provider :theme="{ algorithm: isDark ? theme.darkAlgorithm : theme.defaultAlgorithm, token: themeTokens }">
    <!-- 页面切换过渡：out-in + 上浮淡入，路由跳转更顺滑（尊重 prefers-reduced-motion） -->
    <router-view v-slot="{ Component, route }">
      <Transition name="page" mode="out-in" @before-enter="onPageEnter" @after-enter="onPageEntered">
        <component :is="Component" :key="route.path" />
      </Transition>
    </router-view>
    <BusinessBusyLoading />
    <CookieConsent />
    <!-- 语言切换：前台/后台已各自把控件并入头部；仅无头部的独立页（登录/注册）用浮层形态 -->
    <LanguageSwitcher v-if="standaloneRoute" variant="float" />
    <SplashScreen v-if="showSplash" @done="showSplash = false" />
  </a-config-provider>
</template>

<script setup>
import { ref, watch, computed, onMounted } from 'vue'
import { theme } from 'ant-design-vue'
import { useRoute } from 'vue-router'
import SplashScreen from './components/SplashScreen.vue'
import BusinessBusyLoading from './components/BusinessBusyLoading.vue'
import CookieConsent from './components/CookieConsent.vue'
import LanguageSwitcher from './components/LanguageSwitcher.vue'
import { useUserStore } from './store/user'
import { useSettingsStore } from './store/settings'
import { fetchCsrfToken } from './api/request'

// 无头部布局的独立路由（MainLayout 与 AdminLayout 之外的页面）
const route = useRoute()
const standaloneRoute = computed(() => route.name === 'login' || route.name === 'register')

const showSplash = ref(false)
watch(showSplash, (v) => { document.body.style.overflow = v ? 'hidden' : '' }, { immediate: true })

// AntD 全局设计令牌：统一圆角/字号/控件高度/动效曲线（21st.dev 式克制精致）
// colorBgLayout 透明：让 body 的 --ll-page 令牌成为唯一页面底色，深色模式自动一致
const themeTokens = {
  colorPrimary: '#1E6EB8',
  colorLink: '#1E6EB8',
  colorInfo: '#1E6EB8',
  colorSuccess: '#10B981',
  colorWarning: '#F59E0B',
  colorError: '#E4393C',
  colorBgLayout: 'transparent',
  borderRadius: 10,
  borderRadiusLG: 14,
  borderRadiusSM: 8,
  fontFamily: '-apple-system, "PingFang SC", "Microsoft YaHei", "Segoe UI", sans-serif',
  fontSize: 14,
  controlHeight: 38,
  controlHeightLG: 46,
  controlHeightSM: 30,
  boxShadow: '0 8px 28px rgba(19, 35, 58, 0.10)',
  boxShadowSecondary: '0 16px 48px rgba(19, 35, 58, 0.14)',
  motionDurationMid: '0.22s',
  motionDurationSlow: '0.34s',
  wireframe: false
}

// 页面切换：进入前滚回顶部（浏览器后退时不打断原滚动位置），进入后触发滚动进场扫描
function onPageEnter() {
  if (!history.state?.back) window.scrollTo({ top: 0, behavior: 'auto' })
}
function onPageEntered() {
  document.dispatchEvent(new CustomEvent('ll:page-entered'))
}

const user = useUserStore()
const settings = useSettingsStore()
const isDark = ref(false)
watch(() => settings.isDark, (v) => { isDark.value = !!v }, { immediate: true })

function bootstrapLogged() {
  if (!user.logged) return
  fetchCsrfToken().catch(() => {})
  settings.load().catch(() => {})
}

watch(() => user.logged, (v) => { if (v) bootstrapLogged() })
onMounted(() => {
  // 首次进入及每次刷新均播放完整启动过渡。
  // 启动动画自身控制约 5s 的节奏，完成后再进入工作台。
  showSplash.value = true
  if (user.logged) bootstrapLogged()
})
</script>

<style>
* { box-sizing: border-box; }
body {
  margin: 0;
  font-family: -apple-system, "PingFang SC", "Microsoft YaHei", "Segoe UI", sans-serif;
  background: var(--ll-page, #f5f6f8);
  overflow-x: hidden;
  /* 全局文字渲染优化：更锐利的字形 */
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  text-rendering: optimizeLegibility;
}
a { text-decoration: none; color: inherit; }

/* ============ 页面切换过渡（21st.dev 式：轻微上浮 + 淡入，250ms） ============ */
.page-enter-active {
  transition: opacity 0.26s ease-out, transform 0.26s cubic-bezier(0.22, 1, 0.36, 1);
}
.page-leave-active {
  transition: opacity 0.16s ease-in, transform 0.16s ease-in;
}
.page-enter-from {
  opacity: 0;
  transform: translateY(14px);
}
.page-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

/* 无障碍：尊重系统"减少动态效果"偏好 */
@media (prefers-reduced-motion: reduce) {
  .page-enter-active, .page-leave-active { transition: none; }
  .page-enter-from, .page-leave-to { opacity: 1; transform: none; }
  * { animation-duration: 0.01ms !important; transition-duration: 0.01ms !important; }
}
</style>
