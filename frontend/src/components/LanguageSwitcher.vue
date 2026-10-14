<template>
  <button class="locale-switcher" type="button" :aria-label="isEn ? '切换为中文' : 'Switch to English'" @click="toggle">
    <span :class="{ active: !isEn }">中</span>
    <i aria-hidden="true"></i>
    <span :class="{ active: isEn }">EN</span>
  </button>
</template>

<script setup>
import { computed } from 'vue'
import { locale, switchLocale } from '../i18n'

const isEn = computed(() => locale.value === 'en-US')
function toggle() {
  switchLocale(isEn.value ? 'zh-CN' : 'en-US')
}
</script>

<style scoped>
.locale-switcher {
  position: fixed;
  top: 76px;
  right: 16px;
  z-index: 2600;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-width: 68px;
  justify-content: center;
  padding: 7px 10px;
  border: 1px solid rgba(200,164,92,.45);
  border-radius: 999px;
  background: rgba(255,255,255,.9);
  color: #64748B;
  font: 700 11px/1 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  letter-spacing: .08em;
  box-shadow: 0 7px 22px rgba(15,23,42,.10);
  backdrop-filter: blur(14px) saturate(120%);
  cursor: pointer;
  transition: transform .2s ease, box-shadow .2s ease, border-color .2s ease;
}
.locale-switcher:hover {
  transform: translateY(-1px);
  border-color: rgba(200,164,92,.8);
  box-shadow: 0 10px 26px rgba(15,23,42,.14);
}
.locale-switcher span.active { color: #13233A; }
.locale-switcher i { width: 1px; height: 12px; background: #D9D3C7; }
@media (max-width: 767px) {
  .locale-switcher { top: 70px; right: 10px; min-width: 62px; }
}
</style>
