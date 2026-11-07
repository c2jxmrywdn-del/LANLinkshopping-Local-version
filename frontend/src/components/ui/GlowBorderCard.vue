<template>
  <div
    ref="cardRef"
    class="glow-border-card"
    :class="[customClass, { 'is-hovered': isHovered }]"
    @mousemove="onMouseMove"
    @mouseenter="isHovered = true"
    @mouseleave="isHovered = false"
  >
    <!-- 21st.dev 旋转 Conic 流光背景层 -->
    <div
      v-if="glowEnabled"
      class="glow-effect-layer"
      :style="{
        '--glow-color-1': glowColor1,
        '--glow-color-2': glowColor2,
        '--glow-speed': `${glowDuration}s`
      }"
      aria-hidden="true"
    />

    <!-- 鼠标聚光追踪层 (Spotlight Follower) -->
    <div
      class="spotlight-tracker"
      :style="{
        transform: `translate3d(${mouseX}px, ${mouseY}px, 0)`,
        opacity: isHovered ? 1 : 0
      }"
      aria-hidden="true"
    />

    <!-- 卡片内容实体 -->
    <div class="card-inner-surface">
      <slot />
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'

const props = defineProps({
  glowEnabled: {
    type: Boolean,
    default: true
  },
  glowColor1: {
    type: String,
    default: '#0284c7' // 品牌科技蓝
  },
  glowColor2: {
    type: String,
    default: '#6366f1' // AI 曜紫
  },
  glowDuration: {
    type: Number,
    default: 6
  },
  customClass: {
    type: String,
    default: ''
  }
})

const cardRef = ref(null)
const mouseX = ref(-100)
const mouseY = ref(-100)
const isHovered = ref(false)

function onMouseMove(e) {
  if (!cardRef.value) return
  const rect = cardRef.value.getBoundingClientRect()
  mouseX.value = e.clientX - rect.left
  mouseY.value = e.clientY - rect.top
}
</script>

<style scoped>
.glow-border-card {
  position: relative;
  border-radius: 16px;
  padding: 1px;
  overflow: hidden;
  background: rgba(226, 232, 240, 0.6);
  box-shadow: 0 4px 20px -2px rgba(15, 23, 42, 0.05);
  transition: transform 200ms cubic-bezier(0.16, 1, 0.3, 1), box-shadow 200ms ease;
  box-sizing: border-box;
}

.glow-border-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 32px -4px rgba(2, 132, 199, 0.12);
}

/* 21st.dev 标杆 Conic 渐变流光转圈 */
.glow-effect-layer {
  position: absolute;
  inset: -150%;
  background: conic-gradient(
    from 0deg,
    transparent 0deg,
    var(--glow-color-1) 60deg,
    transparent 120deg,
    transparent 180deg,
    var(--glow-color-2) 240deg,
    transparent 300deg
  );
  animation: rotateGlow var(--glow-speed, 6s) linear infinite;
  pointer-events: none;
  opacity: 0.75;
}

@keyframes rotateGlow {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

/* Spotlight 鼠标追踪聚焦光圈 */
.spotlight-tracker {
  position: absolute;
  width: 240px;
  height: 240px;
  margin-left: -120px;
  margin-top: -120px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(2, 132, 199, 0.22) 0%, transparent 70%);
  pointer-events: none;
  mix-blend-mode: multiply;
  transition: opacity 250ms ease;
  will-change: transform;
}

.card-inner-surface {
  position: relative;
  z-index: 2;
  border-radius: 15px;
  background: #ffffff;
  padding: 20px;
  height: 100%;
  box-sizing: border-box;
}

/* 深色模式 / HUD 适配 */
:global(.dark-gis-theme) .glow-border-card {
  background: rgba(30, 41, 59, 0.8);
}
:global(.dark-gis-theme) .card-inner-surface {
  background: #0f172a;
  color: #f8fafc;
}
</style>
