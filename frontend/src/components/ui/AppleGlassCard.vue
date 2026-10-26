<template>
  <div
    ref="containerRef"
    class="apple-glass-card-perspective"
    :style="{ perspective: `${perspective}px` }"
  >
    <div
      ref="cardRef"
      class="apple-glass-card"
      :class="[
        `variant-${variant}`,
        { 'is-hovered': isHovered, 'no-tilt': disabledTilt }
      ]"
      :style="cardTransformStyle"
      @mousemove="handleMouseMove"
      @mouseenter="handleMouseEnter"
      @mouseleave="handleMouseLeave"
      @click="handleClick"
    >
      <!-- 动态高光光斑层 (跟随鼠标坐标的径向透光) -->
      <div
        v-if="spotlight"
        class="glass-spotlight-layer"
        :style="spotlightStyle"
        aria-hidden="true"
      ></div>

      <!-- 液态边缘折射内光圈 (Refractive Inner Rim) -->
      <div class="glass-refraction-border" aria-hidden="true"></div>

      <!-- 卡片真实内容 -->
      <div class="glass-content-wrap">
        <slot />
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * AppleGlassCard.vue — Apple VisionOS 空间液态透光微光卡片 (全端触控与桌面适配)
 * 灵感源: 21st.dev Liquid Glass Card (DesignAli / 21st.dev)
 *
 * 特性:
 * - 纯 Vue 3 实现，无大型外部动画依赖
 * - 移动端友好: 触屏环境下自动平滑降级，避免触控上下滑动时出现 3D 剧烈跳闪，保留通透液态毛玻璃与微光质感
 * - 3D 空间视差倾斜 (Perspective Tilt, rAF 物理弹簧阻尼平滑插值)
 * - 镜面高光光斑追踪 (Specular Radial Spotlight)
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'

const props = defineProps({
  /** 最大倾斜角度 (度) */
  maxTilt: {
    type: Number,
    default: 8
  },
  /** 3D 视距 (px) */
  perspective: {
    type: Number,
    default: 1000
  },
  /** 光斑半径 (px) */
  spotlightSize: {
    type: Number,
    default: 320
  },
  /** 光斑颜色 */
  spotlightColor: {
    type: String,
    default: 'rgba(255, 255, 255, 0.22)'
  },
  /** 是否开启光斑追踪 */
  spotlight: {
    type: Boolean,
    default: true
  },
  /** 是否禁用 3D 倾斜 */
  disabledTilt: {
    type: Boolean,
    default: false
  },
  /** 风格变体: 'standard' | 'frosted' | 'subtle' | 'dark' */
  variant: {
    type: String,
    default: 'standard'
  }
})

const emit = defineEmits(['click'])

const containerRef = ref(null)
const cardRef = ref(null)
const isHovered = ref(false)

const mouseX = ref(0)
const mouseY = ref(0)

// 3D 旋转角度（插值平滑）
const currentRotateX = ref(0)
const currentRotateY = ref(0)
let targetRotateX = 0
let targetRotateY = 0
let animationFrameId = null

function updateTilt() {
  if (props.disabledTilt) return
  
  // 阻尼平滑插值 (Spring Lerp)
  const ease = 0.12
  currentRotateX.value += (targetRotateX - currentRotateX.value) * ease
  currentRotateY.value += (targetRotateY - currentRotateY.value) * ease

  // 如果误差很小且未悬停，则停止循环节约性能
  if (!isHovered.value && Math.abs(currentRotateX.value) < 0.05 && Math.abs(currentRotateY.value) < 0.05) {
    currentRotateX.value = 0
    currentRotateY.value = 0
    animationFrameId = null
    return
  }

  animationFrameId = requestAnimationFrame(updateTilt)
}

function handleMouseMove(e) {
  if (!cardRef.value) return
  
  // 移动端触屏滚动时防止与页面纵向手势冲突
  const isTouch = e.pointerType === 'touch' || (window.matchMedia && !window.matchMedia('(hover: hover)').matches)
  if (isTouch) {
    return
  }

  const rect = cardRef.value.getBoundingClientRect()
  const x = e.clientX - rect.left
  const y = e.clientY - rect.top

  mouseX.value = x
  mouseY.value = y

  if (!props.disabledTilt) {
    const centerX = rect.width / 2
    const centerY = rect.height / 2
    targetRotateX = ((y - centerY) / centerY) * -props.maxTilt
    targetRotateY = ((x - centerX) / centerX) * props.maxTilt

    if (!animationFrameId) {
      animationFrameId = requestAnimationFrame(updateTilt)
    }
  }
}

function handleMouseEnter() {
  isHovered.value = true
  if (!props.disabledTilt && !animationFrameId) {
    animationFrameId = requestAnimationFrame(updateTilt)
  }
}

function handleMouseLeave() {
  isHovered.value = false
  targetRotateX = 0
  targetRotateY = 0
  if (!props.disabledTilt && !animationFrameId) {
    animationFrameId = requestAnimationFrame(updateTilt)
  }
}

function handleClick(e) {
  emit('click', e)
}

const cardTransformStyle = computed(() => {
  if (props.disabledTilt) return {}
  return {
    transform: `rotateX(${currentRotateX.value.toFixed(2)}deg) rotateY(${currentRotateY.value.toFixed(2)}deg)`
  }
})

const spotlightStyle = computed(() => {
  return {
    background: `radial-gradient(${props.spotlightSize}px circle at ${mouseX.value}px ${mouseY.value}px, ${props.spotlightColor}, transparent 80%)`,
    opacity: isHovered.value ? 1 : 0
  }
})

onBeforeUnmount(() => {
  if (animationFrameId) {
    cancelAnimationFrame(animationFrameId)
  }
})
</script>

<style scoped>
.apple-glass-card-perspective {
  width: 100%;
  height: 100%;
  display: block;
}

/* 核心毛玻璃卡片主体 */
.apple-glass-card {
  position: relative;
  width: 100%;
  height: 100%;
  border-radius: 24px;
  overflow: hidden;
  transform-style: preserve-3d;
  will-change: transform;
  
  /* 拟真 Apple 液态毛玻璃效果 */
  background: rgba(255, 255, 255, 0.55);
  backdrop-filter: blur(24px) saturate(190%);
  -webkit-backdrop-filter: blur(24px) saturate(190%);
  
  /* 优雅边框与阴影 */
  border: 1px solid rgba(255, 255, 255, 0.45);
  box-shadow:
    0 12px 32px -4px rgba(0, 0, 0, 0.08),
    0 4px 12px 0 rgba(0, 0, 0, 0.03),
    inset 0 1px 1.5px 0 rgba(255, 255, 255, 0.6);

  transition:
    box-shadow 0.3s cubic-bezier(0.16, 1, 0.3, 1),
    border-color 0.3s ease;
}

/* 深色模式 / Dark Theme */
:global(.dark) .apple-glass-card,
:global([data-theme="dark"]) .apple-glass-card {
  background: rgba(26, 34, 48, 0.55);
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow:
    0 16px 40px -4px rgba(0, 0, 0, 0.4),
    0 4px 16px 0 rgba(0, 0, 0, 0.25),
    inset 0 1px 1.5px 0 rgba(255, 255, 255, 0.15);
}

.apple-glass-card.is-hovered {
  border-color: rgba(255, 255, 255, 0.7);
  box-shadow:
    0 24px 50px -8px rgba(0, 0, 0, 0.14),
    0 8px 24px 0 rgba(0, 0, 0, 0.06),
    inset 0 1px 2px 0 rgba(255, 255, 255, 0.8);
}

:global(.dark) .apple-glass-card.is-hovered,
:global([data-theme="dark"]) .apple-glass-card.is-hovered {
  border-color: rgba(255, 255, 255, 0.2);
  box-shadow:
    0 24px 55px -8px rgba(0, 0, 0, 0.6),
    0 8px 24px 0 rgba(0, 0, 0, 0.35),
    inset 0 1px 2px 0 rgba(255, 255, 255, 0.25);
}

/* 风格变体 */
.variant-frosted {
  background: rgba(255, 255, 255, 0.75);
}
:global(.dark) .variant-frosted {
  background: rgba(30, 41, 59, 0.75);
}

.variant-subtle {
  background: rgba(255, 255, 255, 0.3);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.05);
}

/* 动态光斑追踪层 */
.glass-spotlight-layer {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 2;
  transition: opacity 0.25s ease;
}

/* 边缘折射环 */
.glass-refraction-border {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  pointer-events: none;
  z-index: 3;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.25);
}
:global(.dark) .glass-refraction-border {
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.08);
}

/* 内容插槽包装层 */
.glass-content-wrap {
  position: relative;
  z-index: 4;
  height: 100%;
}

@media (max-width: 640px) {
  .apple-glass-card {
    border-radius: 20px;
  }
}
</style>
