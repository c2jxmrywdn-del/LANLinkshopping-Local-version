<template>
  <a-config-provider :theme="{ algorithm: isDark ? theme.darkAlgorithm : theme.defaultAlgorithm, token: { colorPrimary: '#1E6EB8', colorLink: '#1E6EB8' } }">
    <router-view />
    <BusinessBusyLoading />
    <CookieConsent />
    <LanguageSwitcher />
    <SplashScreen v-if="showSplash" @done="showSplash = false" />
  </a-config-provider>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { theme } from 'ant-design-vue'
import SplashScreen from './components/SplashScreen.vue'
import BusinessBusyLoading from './components/BusinessBusyLoading.vue'
import CookieConsent from './components/CookieConsent.vue'
import LanguageSwitcher from './components/LanguageSwitcher.vue'
import { useUserStore } from './store/user'
import { useSettingsStore } from './store/settings'
import { fetchCsrfToken } from './api/request'

const showSplash = ref(false)
watch(showSplash, (v) => { document.body.style.overflow = v ? 'hidden' : '' }, { immediate: true })

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
body { margin: 0; font-family: -apple-system, "Microsoft YaHei", "PingFang SC", sans-serif; background: #f5f6f8; overflow-x: hidden; }
a { text-decoration: none; color: inherit; }
</style>