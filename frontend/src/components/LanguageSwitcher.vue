<template>
  <button
    class="locale-switcher"
    :class="`is-${variant}`"
    type="button"
    :aria-label="isEn ? '切换为中文' : 'Switch to English'"
    :aria-pressed="isEn ? 'true' : 'false'"
    @click="toggle"
  >
    <span :class="{ active: !isEn }">中</span>
    <i aria-hidden="true"></i>
    <span :class="{ active: isEn }">EN</span>
  </button>
</template>

<script setup>
import { computed } from 'vue'
import { locale } from '../i18n'
import { useSettingsStore } from '../store/settings'

// 三态适配：
//  dark  —— 深色顶栏（前台 MainLayout）内的行内控件，白字
//  light —— 浅色头部（后台 AdminLayout）内的行内控件，深字
//  float —— 无头部的独立页（登录/注册）浮层，浮于右上角
const props = defineProps({
  variant: { type: String, default: 'float' } // dark | light | float
})

const settings = useSettingsStore()
const isEn = computed(() => locale.value === 'en-US')
void props

function toggle() {
  const next = isEn.value ? 'zh-CN' : 'en-US'
  try {
    // setLanguage：本地立即生效 + 登录态持久化（失败不回滚），
    // 彻底解决"切换后被 settings.applyAppearance() 弹回"的状态源冲突
    settings.setLanguage(next)
  } catch (e) {
    console.warn('[i18n] switch locale failed:', e)
  }
}
</script>

<style scoped>
/* ============ 公共形态 ============ */
.locale-switcher {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  justify-content: center;
  padding: 6px 10px;
  border-radius: 999px;
  font: 700 11px/1 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  letter-spacing: .08em;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  transition: transform .2s ease, box-shadow .2s ease, border-color .2s ease, background-color .2s ease, color .2s ease;
}
.locale-switcher i { width: 1px; height: 12px; }

/* ============ float：无头部页面（登录/注册）浮层 ============ */
.locale-switcher.is-float {
  position: fixed;
  top: 76px;
  right: 16px;
  /* 高于 SplashScreen 蒙层(9999)，保证启动动画期间按钮也可点击 */
  z-index: 10001;
  min-width: 68px;
  padding: 7px 10px;
  border: 1px solid rgba(200, 164, 92, .45);
  background: rgba(255, 255, 255, .9);
  color: #64748B;
  box-shadow: 0 7px 22px rgba(15, 23, 42, .10);
  backdrop-filter: blur(14px) saturate(120%);
}
.locale-switcher.is-float:hover {
  transform: translateY(-1px);
  border-color: rgba(200, 164, 92, .8);
  box-shadow: 0 10px 26px rgba(15, 23, 42, .14);
}
.locale-switcher.is-float span.active { color: #13233A; }
.locale-switcher.is-float i { background: #D9D3C7; }

/* ============ dark：深色顶栏内的行内控件 ============ */
.locale-switcher.is-dark {
  border: 1px solid rgba(255, 255, 255, .26);
  background: rgba(255, 255, 255, .08);
  color: rgba(255, 255, 255, .72);
  white-space: nowrap;
}
.locale-switcher.is-dark:hover {
  background: rgba(155, 192, 217, .20);
  border-color: rgba(255, 255, 255, .45);
  color: #fff;
}
.locale-switcher.is-dark:active { background: rgba(155, 192, 217, .42); }
.locale-switcher.is-dark span.active { color: var(--ll-cyan, #22D3EE); }
.locale-switcher.is-dark i { background: rgba(255, 255, 255, .28); }

/* ============ light：浅色头部（后台）内的行内控件 ============ */
.locale-switcher.is-light {
  border: 1px solid var(--ll-border, #e5e7eb);
  background: var(--ll-card, #fff);
  color: var(--ll-muted, #64748b);
}
.locale-switcher.is-light:hover {
  border-color: rgba(30, 110, 184, .45);
  color: var(--ll-ink, #0f172a);
}
.locale-switcher.is-light span.active { color: var(--ll-primary, #1E6EB8); }
.locale-switcher.is-light i { background: var(--ll-border, #e5e7eb); }

@media (max-width: 767px) {
  .locale-switcher.is-float { top: 70px; right: 10px; min-width: 62px; }
  .locale-switcher.is-dark { padding: 5px 9px; gap: 6px; }
}
</style>
