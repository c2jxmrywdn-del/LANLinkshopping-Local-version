<template>
  <div class="touch-term-slider-root" ref="trackContainerRef">
    <!-- 顶部状态栏：当前天数与免息提示 -->
    <div class="slider-meta-header">
      <div class="current-term-indicator">
        <Calendar :size="15" class="text-sky-600" aria-hidden="true" />
        <span class="meta-label">账期天数：</span>
        <strong class="meta-value">{{ modelValue }} 天</strong>
        <span class="tier-tag" :class="tierClass">{{ currentTierLabel }}</span>
      </div>
      <div class="grace-badge">
        <Clock :size="13" class="text-indigo-600" aria-hidden="true" />
        <span>享 3 天免息宽限</span>
      </div>
    </div>

    <!-- 交互轨道容器 -->
    <div
      class="slider-track-wrap"
      @mousedown="onTrackMouseDown"
      @touchstart.passive="onTrackTouchStart"
      role="slider"
      :aria-valuemin="7"
      :aria-valuemax="15"
      :aria-valuenow="modelValue"
      aria-label="账期期限滑块（7至15天）"
      tabindex="0"
      @keydown="onKeydown"
    >
      <!-- 轨道背景 -->
      <div class="slider-rail"></div>

      <!-- 进度高亮条 (21st.dev 渐变光效) -->
      <div class="slider-fill" :style="{ width: percent + '%' }"></div>

      <!-- 7天、10天、15天 关键锚点脉冲点 -->
      <div
        v-for="anchor in anchors"
        :key="anchor.val"
        class="milestone-anchor"
        :style="{ left: calcPercent(anchor.val) + '%' }"
        :class="{ 'is-active': modelValue >= anchor.val, 'is-exact': modelValue === anchor.val }"
        @click.stop="selectDay(anchor.val)"
      >
        <div class="anchor-dot"></div>
        <div class="anchor-label">{{ anchor.val }}天</div>
      </div>

      <!-- 动态悬浮数字气泡与可拖拽触控滑块 (Touch Thumb) -->
      <div
        class="slider-thumb-wrapper"
        :style="{ left: percent + '%' }"
        :class="{ 'is-dragging': isDragging }"
        @mousedown.stop="startDrag"
        @touchstart.stop="onTouchStart"
      >
        <!-- 悬浮指示气泡 -->
        <div class="floating-bubble">
          <span>{{ modelValue }}天</span>
        </div>
        <!-- 触控热区 (>= 44px 满足移动端人机工效规范) -->
        <div class="slider-thumb">
          <div class="thumb-core"></div>
        </div>
      </div>
    </div>

    <!-- 刻度与底部指引 -->
    <div class="slider-scale-footer">
      <span class="scale-min">7天 (极速短融)</span>
      <span class="scale-mid">10天 (周转备货)</span>
      <span class="scale-max">15天 (赊销上限)</span>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { Calendar, Clock } from 'lucide-vue-next'
import { clampTermDays } from '../../utils/creditCalculator'

const props = defineProps({
  modelValue: {
    type: Number,
    default: 7
  },
  min: {
    type: Number,
    default: 7
  },
  max: {
    type: Number,
    default: 15
  }
})

const emit = defineEmits(['update:modelValue', 'change'])

const trackContainerRef = ref(null)
const isDragging = ref(false)

const anchors = [
  { val: 7, label: '7天' },
  { val: 10, label: '10天' },
  { val: 15, label: '15天' }
]

function calcPercent(val) {
  const min = props.min
  const max = props.max
  if (max === min) return 0
  return Math.max(0, Math.min(100, ((val - min) / (max - min)) * 100))
}

const percent = computed(() => {
  return calcPercent(props.modelValue)
})

const currentTierLabel = computed(() => {
  if (props.modelValue === 7) return '敏捷短融'
  if (props.modelValue === 10) return '周转备货'
  if (props.modelValue === 15) return '赊销上限'
  if (props.modelValue < 10) return '短期周转'
  return '中线赊销'
})

const tierClass = computed(() => {
  if (props.modelValue <= 8) return 'tier-fast'
  if (props.modelValue <= 12) return 'tier-mid'
  return 'tier-standard'
})

function selectDay(val) {
  const clamped = clampTermDays(val)
  emit('update:modelValue', clamped)
  emit('change', clamped)
  triggerHaptic()
}

function triggerHaptic() {
  if (typeof navigator !== 'undefined' && typeof navigator.vibrate === 'function') {
    try {
      navigator.vibrate(10)
    } catch {
      // ignore
    }
  }
}

function updateValueFromPointer(clientX) {
  if (!trackContainerRef.value) return
  const trackEl = trackContainerRef.value.querySelector('.slider-track-wrap')
  if (!trackEl) return
  const rect = trackEl.getBoundingClientRect()
  const offsetX = clientX - rect.left
  const ratio = Math.max(0, Math.min(1, offsetX / rect.width))
  const rawValue = props.min + ratio * (props.max - props.min)
  const clamped = clampTermDays(Math.round(rawValue))

  if (clamped !== props.modelValue) {
    emit('update:modelValue', clamped)
    emit('change', clamped)
    triggerHaptic()
  }
}

function onTrackMouseDown(e) {
  if (e.button !== 0) return
  updateValueFromPointer(e.clientX)
  startDrag(e)
}

function startDrag(e) {
  isDragging.value = true
  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('mouseup', onMouseUp)
}

function onMouseMove(e) {
  if (!isDragging.value) return
  updateValueFromPointer(e.clientX)
}

function onMouseUp() {
  isDragging.value = false
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('mouseup', onMouseUp)
}

function onTrackTouchStart(e) {
  if (e.touches.length > 0) {
    updateValueFromPointer(e.touches[0].clientX)
  }
}

function onTouchStart(e) {
  isDragging.value = true
  window.addEventListener('touchmove', onTouchMove, { passive: false })
  window.addEventListener('touchend', onTouchEnd)
  window.addEventListener('touchcancel', onTouchEnd)
}

function onTouchMove(e) {
  if (!isDragging.value || e.touches.length === 0) return
  if (e.cancelable) {
    e.preventDefault() // 阻止拖拽滑块时的页面上下滚动抖动
  }
  updateValueFromPointer(e.touches[0].clientX)
}

function onTouchEnd() {
  isDragging.value = false
  window.removeEventListener('touchmove', onTouchMove)
  window.removeEventListener('touchend', onTouchEnd)
  window.removeEventListener('touchcancel', onTouchEnd)
}

function onKeydown(e) {
  if (e.key === 'ArrowRight' || e.key === 'ArrowUp') {
    e.preventDefault()
    selectDay(props.modelValue + 1)
  } else if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') {
    e.preventDefault()
    selectDay(props.modelValue - 1)
  }
}

onBeforeUnmount(() => {
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('mouseup', onMouseUp)
  window.removeEventListener('touchmove', onTouchMove)
  window.removeEventListener('touchend', onTouchEnd)
  window.removeEventListener('touchcancel', onTouchEnd)
})
</script>

<style scoped>
.touch-term-slider-root {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  user-select: none;
  -webkit-user-select: none;
  touch-action: pan-y;
}

.slider-meta-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}

.current-term-indicator {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.meta-label {
  color: #475569;
}

.meta-value {
  font-size: 15px;
  color: #0f172a;
  font-weight: 700;
}

.tier-tag {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 9999px;
  border: 1px solid transparent;
}

.tier-fast {
  background: rgba(16, 185, 129, 0.1);
  color: #059669;
  border-color: rgba(16, 185, 129, 0.2);
}

.tier-mid {
  background: rgba(2, 132, 199, 0.1);
  color: #0284c7;
  border-color: rgba(2, 132, 199, 0.2);
}

.tier-standard {
  background: rgba(99, 102, 241, 0.1);
  color: #4f46e5;
  border-color: rgba(99, 102, 241, 0.2);
}

.grace-badge {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #4f46e5;
  background: rgba(99, 102, 241, 0.08);
  padding: 2px 8px;
  border-radius: 6px;
  font-weight: 500;
}

/* 交互轨道 */
.slider-track-wrap {
  position: relative;
  height: 48px;
  margin: 0 16px; /* 预留 16px 边距，防止 44px Thumb 与悬浮气泡在 0% 和 100% 时溢出外层容器 */
  display: flex;
  align-items: center;
  cursor: pointer;
  outline: none;
  touch-action: none;
}

.slider-track-wrap:focus-visible .slider-rail {
  box-shadow: 0 0 0 2px #38bdf8;
}

.slider-rail {
  position: absolute;
  left: 0;
  right: 0;
  height: 8px;
  border-radius: 9999px;
  background: #e2e8f0;
  transition: background 200ms ease;
}

.slider-fill {
  position: absolute;
  left: 0;
  height: 8px;
  border-radius: 9999px;
  background: linear-gradient(90deg, #0284c7 0%, #6366f1 100%);
  box-shadow: 0 0 10px rgba(2, 132, 199, 0.35);
  pointer-events: none;
}

/* 关键里程碑锚点 (44px 移动端人机工效触控热区) */
.milestone-anchor {
  position: absolute;
  top: 50%;
  transform: translate(-50%, -50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  z-index: 2;
  min-width: 44px;
  min-height: 44px;
  touch-action: manipulation;
}

.milestone-anchor::after {
  content: '';
  position: absolute;
  inset: 0;
}

.anchor-dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #ffffff;
  border: 2px solid #94a3b8;
  transition: all 200ms ease;
}

.milestone-anchor.is-active .anchor-dot {
  border-color: #0284c7;
  background: #0284c7;
}

.milestone-anchor.is-exact .anchor-dot {
  border-color: #6366f1;
  background: #ffffff;
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.3);
}

.anchor-label {
  position: absolute;
  top: 24px;
  font-size: 11px;
  color: #64748b;
  font-weight: 500;
  white-space: nowrap;
}

.milestone-anchor.is-exact .anchor-label {
  color: #0f172a;
  font-weight: 700;
}

/* 触控滑块 Thumb */
.slider-thumb-wrapper {
  position: absolute;
  top: 50%;
  transform: translate(-50%, -50%);
  z-index: 5;
  cursor: grab;
}

.slider-thumb-wrapper.is-dragging {
  cursor: grabbing;
}

/* 悬浮数字气泡 */
.floating-bubble {
  position: absolute;
  bottom: 28px;
  left: 50%;
  transform: translateX(-50%);
  background: #0f172a;
  color: #ffffff;
  font-size: 11px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 6px;
  white-space: nowrap;
  box-shadow: 0 4px 8px rgba(15, 23, 42, 0.25);
  pointer-events: none;
  animation: bubblePop 200ms ease;
}

.floating-bubble::after {
  content: '';
  position: absolute;
  top: 100%;
  left: 50%;
  transform: translateX(-50%);
  border-width: 4px;
  border-style: solid;
  border-color: #0f172a transparent transparent transparent;
}

@keyframes bubblePop {
  from {
    opacity: 0;
    transform: translate(-50%, 4px) scale(0.9);
  }
  to {
    opacity: 1;
    transform: translate(-50%, 0) scale(1);
  }
}

/* 44px 人机工效触控目标 */
.slider-thumb {
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  touch-action: none;
}

.thumb-core {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #ffffff;
  border: 3px solid #0284c7;
  box-shadow: 0 2px 8px rgba(2, 132, 199, 0.35);
  transition: transform 160ms ease, border-color 160ms ease, box-shadow 160ms ease;
}

.slider-thumb-wrapper:hover .thumb-core,
.slider-thumb-wrapper.is-dragging .thumb-core {
  transform: scale(1.15);
  border-color: #6366f1;
  box-shadow: 0 0 12px rgba(99, 102, 241, 0.45);
}

/* 刻度底栏 */
.slider-scale-footer {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #94a3b8;
  margin-top: 4px;
  padding: 0 16px;
}
</style>
