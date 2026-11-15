<template>
  <!--
    locale 必须显式绑定：antd 组件内置文案（分页「10 / page」「共 x 条」、空态、日期选择器等）
    不受项目 i18n 字典控制，只认 ConfigProvider 的 locale。
    历史缺陷：此前只绑 theme 未绑 locale，切到中文后分页仍显示英文「10 / page」。
  -->
  <a-config-provider
    :locale="antdLocale"
    :theme="{ algorithm: isDark ? theme.darkAlgorithm : theme.defaultAlgorithm, token: themeTokens }"
  >
    <!-- 页面切换过渡：out-in + 上浮淡入，路由跳转更顺滑（尊重 prefers-reduced-motion） -->
    <router-view v-slot="{ Component, route }">
      <Transition name="page" mode="out-in" @after-enter="onPageEntered">
        <component :is="Component" :key="route.path" />
      </Transition>
    </router-view>
    <CookieConsent />
    <!-- 语言切换：过渡动画页与登录/注册页不提供手动入口，由多维特征自动识别（见 main.js → applyAutoLocale）；
         登录进入主系统后，切换控件在各布局头部内保持正常可用 -->
    <SplashScreen v-if="showSplash" @done="showSplash = false" />
  </a-config-provider>
</template>

<script setup>
import { computed, ref, watch, onMounted } from 'vue'
import { theme } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import enUS from 'ant-design-vue/es/locale/en_US'
import { locale } from './i18n'
import SplashScreen from './components/SplashScreen.vue'
import CookieConsent from './components/CookieConsent.vue'
import { useUserStore } from './store/user'
import { useSettingsStore } from './store/settings'
// 豁免封装收口：fetchCsrfToken 是 request.js 显式导出的引导期接口（封装层自身公共面），非绕行直连
import { fetchCsrfToken } from './api/request'

const showSplash = ref(false)
watch(showSplash, (v) => { document.body.style.overflow = v ? 'hidden' : '' }, { immediate: true })

/**
 * antd 组件语言包：跟随项目 locale 实时切换。
 *
 * <p>项目 i18n 只管自有文案（`t()` 字典 + DOM 扫描），antd 的内置文案走自己的
 * ConfigProvider locale，两者必须显式同步，否则会出现「页面中文、分页英文」的混排。
 */
const antdLocale = computed(() => (String(locale.value || '').toLowerCase().startsWith('en') ? enUS : zhCN))

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

// 页面切换：滚动复位统一交给 router.scrollBehavior（前进回顶、后退恢复原位置），
// 这里只在新页面入场后触发滚动进场扫描
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
