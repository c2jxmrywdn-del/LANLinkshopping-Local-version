<template>
  <button
    class="locale-switcher"
    :class="`is-${variant}`"
    type="button"
    :aria-label="isEn ? '切换为中文' : 'Switch to English'"
    :aria-pressed="isEn ? 'true' : 'false'"
    @click="toggle"
  >
    <span class="locale-switcher__track">
      <span class="locale-switcher__thumb" :class="{ 'is-en': isEn }" aria-hidden="true"></span>
      <span class="locale-switcher__opt" :class="{ active: !isEn }">中</span>
      <span class="locale-switcher__opt" :class="{ active: isEn }">EN</span>
    </span>
  </button>
</template>

<script setup>
import { computed } from 'vue'
import { locale } from '../i18n'
import { useSettingsStore } from '../store/settings'

// 两态适配（滑块式分段控件）：
//  dark  —— 深色顶栏（前台 MainLayout）内的行内控件，白字 + 青色滑块
//  light —— 浅色头部（后台 AdminLayout）内的行内控件，深字 + 品牌蓝滑块
// 说明：过渡动画页与登录/注册页不再提供手动切换入口（语言由多维特征自动识别），
//      故原 float 浮层形态已移除；仅保留登录进入主系统后的两态控件。
const props = defineProps({
  variant: { type: String, default: 'dark' } // dark | light
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
/* ============ 公共形态：胶囊外框 + 两格轨道 + 滑动指示块 ============ */
.locale-switcher {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 3px;
  border-radius: 999px;
  font: 800 11px/1 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  letter-spacing: .06em;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  transition: background-color .2s ease, border-color .2s ease, box-shadow .2s ease, transform .2s ease, color .2s ease;
}
.locale-switcher:focus-visible {
  outline: none;
  box-shadow: var(--ll-focus-ring, 0 0 0 3px rgba(30, 110, 184, .28));
}
.locale-switcher__track {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 1fr;
  align-items: center;
  min-width: 62px;
}
/* 滑块：默认停在"中"，is-en 时平移到"EN"，弹性缓动呼应全站动效 */
.locale-switcher__thumb {
  position: absolute;
  inset: 0 50% 0 0;
  border-radius: 999px;
  transition: transform .32s cubic-bezier(.34, 1.56, .64, 1), background-color .2s ease, box-shadow .2s ease;
}
.locale-switcher__thumb.is-en { transform: translateX(100%); }
.locale-switcher__opt {
  position: relative;
  z-index: 1;
  display: grid;
  place-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  transition: color .2s ease;
}

/* ============ dark：深色顶栏内的行内控件 ============ */
.locale-switcher.is-dark {
  border: 1px solid rgba(255, 255, 255, .22);
  background: rgba(255, 255, 255, .08);
  color: rgba(255, 255, 255, .74);
  white-space: nowrap;
}
.locale-switcher.is-dark:hover {
  background: rgba(255, 255, 255, .13);
  border-color: rgba(255, 255, 255, .42);
  color: #fff;
}
.locale-switcher.is-dark .locale-switcher__thumb {
  background: rgba(34, 211, 238, .22);
  box-shadow: inset 0 0 0 1px rgba(34, 211, 238, .5);
}
.locale-switcher.is-dark .locale-switcher__opt.active { color: #fff; }

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
.locale-switcher.is-light .locale-switcher__thumb {
  background: var(--ll-primary, #1E6EB8);
  box-shadow: 0 2px 8px rgba(30, 110, 184, .28);
}
.locale-switcher.is-light .locale-switcher__opt.active { color: #fff; }

@media (max-width: 767px) {
  .locale-switcher__track { min-width: 56px; }
  .locale-switcher__opt { padding: 0 6px; }
}
@media (prefers-reduced-motion: reduce) {
  .locale-switcher__thumb { transition: none; }
}
</style>
