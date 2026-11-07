<template>
  <teleport to="body">
    <transition name="drawer-fade">
      <div
        v-if="open"
        class="smooth-drawer-backdrop"
        @click="onBackdropClick"
      >
        <transition name="drawer-slide">
          <div
            v-if="open"
            ref="sheetRef"
            class="smooth-drawer-surface"
            :style="{
              transform: `translate3d(0, ${currentY}px, 0)`,
              maxHeight: maxHeight
            }"
            @click.stop
            @touchstart="onTouchStart"
            @touchmove="onTouchMove"
            @touchend="onTouchEnd"
          >
            <!-- 顶部拖拽手柄 (Drag Handle) -->
            <div class="drawer-handle-bar">
              <span class="handle-pill" />
            </div>

            <!-- 头部标题区 -->
            <div v-if="title || $slots.header" class="drawer-header">
              <slot name="header">
                <h3 class="drawer-title">{{ title }}</h3>
                <button
                  type="button"
                  class="drawer-close-btn"
                  aria-label="关闭抽屉"
                  @click="close"
                >
                  <X :size="18" />
                </button>
              </slot>
            </div>

            <!-- 抽屉主体滚动区 -->
            <div class="drawer-body">
              <slot />
            </div>

            <!-- 底部安全区垫片与操作区 -->
            <div v-if="$slots.footer" class="drawer-footer">
              <slot name="footer" />
            </div>
          </div>
        </transition>
      </div>
    </transition>
  </teleport>
</template>

<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { X } from 'lucide-vue-next'

const props = defineProps({
  open: {
    type: Boolean,
    default: false
  },
  title: {
    type: String,
    default: ''
  },
  maxHeight: {
    type: String,
    default: '88vh'
  },
  dismissible: {
    type: Boolean,
    default: true
  }
})

const emit = defineEmits(['update:open', 'close'])

const sheetRef = ref(null)
const currentY = ref(0)
let startY = 0
let lastY = 0
let lastTime = 0
let velocity = 0

function close() {
  currentY.value = 0
  emit('update:open', false)
  emit('close')
}

function onBackdropClick() {
  if (props.dismissible) {
    close()
  }
}

function onTouchStart(e) {
  if (e.touches.length !== 1) return
  startY = e.touches[0].clientY
  lastY = startY
  lastTime = Date.now()
  velocity = 0
}

function onTouchMove(e) {
  if (e.touches.length !== 1) return
  const touchY = e.touches[0].clientY
  const delta = touchY - startY
  const now = Date.now()
  const dt = Math.max(1, now - lastTime)
  velocity = (touchY - lastY) / dt
  lastY = touchY
  lastTime = now

  if (delta > 0) {
    // 向下拉动：线性移动
    currentY.value = delta
  } else {
    // 向上拉动：施加橡皮筋阻尼 (0.25)
    currentY.value = delta * 0.25
  }
}

function onTouchEnd() {
  // 阈值：下拉超过 90px 或向下甩动速度超过 0.45px/ms 自动关闭
  if (currentY.value > 90 || velocity > 0.45) {
    close()
  } else {
    // 弹性复位
    currentY.value = 0
  }
}

watch(
  () => props.open,
  val => {
    if (val) {
      currentY.value = 0
      if (typeof document !== 'undefined') {
        document.body.style.overflow = 'hidden'
      }
    } else {
      if (typeof document !== 'undefined') {
        document.body.style.overflow = ''
      }
    }
  }
)

onBeforeUnmount(() => {
  if (typeof document !== 'undefined') {
    document.body.style.overflow = ''
  }
})
</script>

<style scoped>
.smooth-drawer-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(15, 23, 42, 0.45);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.smooth-drawer-surface {
  position: relative;
  width: 100%;
  max-width: 640px;
  background: #ffffff;
  border-top-left-radius: 20px;
  border-top-right-radius: 20px;
  box-shadow: 0 -8px 30px rgba(0, 0, 0, 0.12);
  display: flex;
  flex-direction: column;
  transition: transform 180ms cubic-bezier(0.16, 1, 0.3, 1);
  will-change: transform;
  /* 适配 iOS Safari 安全区 */
  padding-bottom: calc(16px + env(safe-area-inset-bottom, 0px));
}

.drawer-handle-bar {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 10px 0 6px;
  touch-action: none;
  cursor: grab;
}

.handle-pill {
  width: 36px;
  height: 4px;
  border-radius: 9999px;
  background: #cbd5e1;
}

.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 20px 12px;
  border-bottom: 1px solid #f1f5f9;
}

.drawer-title {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
  margin: 0;
}

.drawer-close-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: none;
  background: #f1f5f9;
  color: #64748b;
  cursor: pointer;
  position: relative;
  touch-action: manipulation;
}

/* WCAG 2.2 AA / Apple HIG 44x44px 隐式触摸热区扩展 */
.drawer-close-btn::after {
  content: '';
  position: absolute;
  inset: -6px;
}

.drawer-body {
  flex: 1;
  padding: 16px 20px;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}

.drawer-footer {
  padding: 12px 20px;
  border-top: 1px solid #f1f5f9;
}

/* 过渡动画 */
.drawer-fade-enter-active,
.drawer-fade-leave-active {
  transition: opacity 220ms ease;
}
.drawer-fade-enter-from,
.drawer-fade-leave-to {
  opacity: 0;
}

.drawer-slide-enter-active,
.drawer-slide-leave-active {
  transition: transform 260ms cubic-bezier(0.25, 1, 0.5, 1);
}
.drawer-slide-enter-from,
.drawer-slide-leave-to {
  transform: translate3d(0, 100%, 0) !important;
}
</style>
