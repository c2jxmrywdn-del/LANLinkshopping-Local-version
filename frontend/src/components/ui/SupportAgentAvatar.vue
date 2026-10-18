<template>
  <span
    class="ll-agent-avatar"
    :class="[`ll-agent-avatar--${variant}`, `ll-agent-avatar--${sizeToken}`]"
    :style="wrapStyle"
    role="img"
    :aria-label="resolvedLabel"
    :title="resolvedLabel"
  >
    <svg class="ll-agent-avatar__glyph" viewBox="0 0 32 32" focusable="false" aria-hidden="true">
      <!-- 客服耳机头梁 -->
      <path d="M8.4 16.6v-2.2a7.6 7.6 0 0 1 15.2 0v2.2"
            fill="none" stroke="#1F3864" stroke-width="1.9" stroke-linecap="round" />
      <!-- 左右耳罩 -->
      <rect x="6.2" y="15.4" width="4.4" height="6.8" rx="2.2" fill="#1F3864" />
      <rect x="21.4" y="15.4" width="4.4" height="6.8" rx="2.2" fill="#1F3864" />
      <!-- 面部（深墨色，保证浅底上高对比） -->
      <rect x="10.4" y="15.4" width="11.2" height="8.8" rx="4.4" fill="#1F3864" />
      <!-- 眼睛（浅色，深脸上高对比） -->
      <circle cx="14" cy="19.8" r="1.3" fill="#EAF2FD" />
      <circle cx="18" cy="19.8" r="1.3" fill="#EAF2FD" />
      <!-- 智能火花（加深金，浅底可读；纯装饰） -->
      <path d="M26.2 3.6l.9 1.7 1.7.9-1.7.9-.9 1.7-.9-1.7-1.7-.9 1.7-.9Z" fill="#80601F" />
    </svg>

    <span v-if="status" class="ll-agent-avatar__dot" :class="`is-${status}`" aria-hidden="true" />
  </span>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 智能客服头像（第二版设计）：
 *  - 与首版「深色圆盘 + 气泡」区分：改为浅色品牌底 + 深墨色客服图形，明暗关系反转
 *  - 浅底承托深色图形，白色/浅色眼睛落在深色面部，双向都满足对比度要求
 *  - 圆角方形轮廓，在深色顶栏/客服面板头部上更醒目
 */
const props = defineProps({
  /** 像素尺寸或任意 CSS 长度 */
  size: { type: [Number, String], default: 36 },
  /** 在线状态：online | busy | offline | ''（不显示状态点） */
  status: { type: String, default: '' },
  /** 变体：solid（默认，完全着色）/ plain（无描边，浮于已有底色） */
  variant: { type: String, default: 'solid' },
  /** 无障碍标签覆盖 */
  label: { type: String, default: '' }
})

const sizeToken = computed(() => (typeof props.size === 'number' ? 'num' : 'css'))
const wrapStyle = computed(() => ({
  '--ll-agent-size': typeof props.size === 'number' ? `${props.size}px` : props.size
}))
const resolvedLabel = computed(() => props.label || '智能客服头像')
</script>

<style scoped>
.ll-agent-avatar {
  position: relative;
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  width: var(--ll-agent-size, 36px);
  height: var(--ll-agent-size, 36px);
  border-radius: 32%;
  background: linear-gradient(150deg, #F8FAFF 0%, #DCE9FB 100%);
  border: 1px solid rgba(30, 110, 184, .30);
  box-shadow: 0 3px 10px rgba(19, 35, 58, .14);
  isolation: isolate;
}
.ll-agent-avatar--plain { border-color: transparent; box-shadow: none; }
.ll-agent-avatar__glyph { display: block; width: 82%; height: 82%; }

/* 在线状态点：白描边保证在任意底上可辨识 */
.ll-agent-avatar__dot {
  position: absolute; right: -1px; bottom: -1px;
  width: 30%; height: 30%; min-width: 9px; min-height: 9px;
  border-radius: 50%;
  border: 1.5px solid #fff;
}
.ll-agent-avatar__dot.is-online { background: #15803D; }
.ll-agent-avatar__dot.is-busy { background: #B45309; }
.ll-agent-avatar__dot.is-offline { background: #64748B; }
</style>
