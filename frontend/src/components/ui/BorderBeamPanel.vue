<template>
  <div
    ref="root"
    class="bbp"
    :data-motion="animate ? 'animated' : 'static'"
    :data-paused="paused ? 'true' : 'false'"
    :style="rootStyle"
    @pointerenter="boost"
    @pointerleave="relax"
    @focusin="boost"
    @focusout="relax"
  >
    <div v-if="glow" class="bbp-glow" aria-hidden="true" :style="{ opacity: glowOpacity }"></div>
    <div class="bbp-ring" aria-hidden="true"></div>
    <slot />
  </div>
</template>

<script setup>
/**
 * BorderBeamPanel — 移植自 21st.dev @rmahammad 的 Border Beam Panel（MIT）。
 * 源码逻辑来自其预览 bundle：
 *  - conic-gradient 双 comet（蓝/青 + 珊瑚），`from var(--beam-a)` 旋转；
 *  - 2px 遮罩光环 + blur(14px)/opacity .35 光晕；
 *  - 角度推进用弹簧：默认 stiffness 30 / damping 11，idle 42°/s、hover 240°/s；
 *  - 不可见时暂停（IntersectionObserver + visibilitychange）；
 *  - reduced-motion 静止在 seed * 137.508°。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  beams: { type: Number, default: 2 },
  colors: { type: Array, default: () => [] },
  thickness: { type: Number, default: 2 },
  idleSpeed: { type: Number, default: 42 },
  hoverSpeed: { type: Number, default: 240 },
  glow: { type: Boolean, default: true },
  glowOpacity: { type: Number, default: 0.35 },
  radius: { type: Number, default: 16 },
  spring: { type: Object, default: () => ({ stiffness: 30, damping: 11 }) },
  seed: { type: Number, default: 1 },
  pauseWhenHidden: { type: Boolean, default: true },
  reducedMotion: { type: Boolean, default: false }
})

const root = ref(null)
const initialAngle = ((props.seed * 137.508) % 360 + 360) % 360
const angle = ref(initialAngle)
const hovered = ref(false)
const inView = ref(true)
const pageVisible = ref(true)
const prefersReduced = ref(false)

const reduced = computed(() => props.reducedMotion || prefersReduced.value)
const paused = computed(() => props.pauseWhenHidden && (!inView.value || !pageVisible.value))
const animate = computed(() => !reduced.value && !paused.value)

const spring = { x: props.idleSpeed, v: 0, target: props.idleSpeed, k: props.spring?.stiffness ?? 30, d: props.spring?.damping ?? 11 }

let raf = 0
let last = 0

function stepSpring(dt) {
  const acc = spring.k * (spring.target - spring.x) - spring.d * spring.v
  spring.v += acc * dt
  spring.x += spring.v * dt
  return spring.x
}

function frame(now) {
  if (!last) last = now
  const dt = Math.min(0.05, Math.max(0, (now - last) / 1000))
  last = now
  angle.value = (angle.value + stepSpring(dt) * dt + 360) % 360
  raf = requestAnimationFrame(frame)
}

function start() {
  cancelAnimationFrame(raf)
  last = 0
  raf = requestAnimationFrame(frame)
}
function stop() {
  cancelAnimationFrame(raf)
  raf = 0
  last = 0
}

watch(animate, (on) => {
  if (on) {
    spring.x = spring.target = hovered.value ? props.hoverSpeed : props.idleSpeed
    spring.v = 0
    start()
  } else {
    stop()
    angle.value = initialAngle
  }
}, { immediate: true })

function boost() {
  hovered.value = true
  spring.target = props.hoverSpeed
}
function relax() {
  hovered.value = false
  spring.target = props.idleSpeed
}

let observer = null
function onVisibility() {
  pageVisible.value = document.visibilityState !== 'hidden'
}

onMounted(() => {
  try {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)')
    prefersReduced.value = mq.matches
    mq.addEventListener?.('change', (e) => { prefersReduced.value = e.matches })
  } catch (e) { /* ignore */ }

  if (typeof IntersectionObserver !== 'undefined' && root.value) {
    observer = new IntersectionObserver((entries) => {
      inView.value = entries.some((e) => e.isIntersecting)
    }, { threshold: 0.05 })
    observer.observe(root.value)
  }
  document.addEventListener('visibilitychange', onVisibility)
})

onBeforeUnmount(() => {
  stop()
  observer?.disconnect()
  document.removeEventListener('visibilitychange', onVisibility)
})

/** 与源码一致的 comet 段构造 */
function comet(primary, secondary, tail, opacityPct, start) {
  return [
    `color-mix(in srgb, ${primary} 4%, transparent) ${start + 18}deg`,
    `color-mix(in srgb, ${primary} ${opacityPct}%, transparent) ${start + 46}deg`,
    `${secondary} ${start + 56}deg`,
    `${tail} ${start + 60}deg`,
    `transparent ${start + 63}deg`
  ].join(', ')
}

const gradient = computed(() => {
  const c = props.colors || []
  const primary = c[0] || 'var(--motiq-accent, #4f7cff)'
  const secondary = c[0] || 'var(--motiq-secondary-accent, #22c7d9)'
  const parts = ['transparent 0deg', comet(primary, secondary, `color-mix(in srgb, ${secondary} 22%, #ffffff)`, 55, 0)]
  if (props.beams === 2) {
    const signature = c[1] || 'var(--motiq-signature, #ff6b5e)'
    parts.push('transparent 198deg', comet(signature, signature, `color-mix(in srgb, ${signature} 26%, #ffffff)`, 50, 198))
  }
  parts.push('transparent 360deg')
  return `conic-gradient(from var(--beam-a, 0deg), ${parts.join(', ')})`
})

const rootStyle = computed(() => ({
  borderRadius: `${props.radius}px`,
  isolation: 'isolate',
  '--beam-a': `${angle.value.toFixed(2)}deg`,
  '--beam-gradient': gradient.value,
  '--beam-thickness': `${Math.max(1, props.thickness)}px`
}))
</script>

<style scoped>
.bbp {
  position: relative;
}
.bbp-glow,
.bbp-ring {
  position: absolute;
  inset: -1px;
  border-radius: inherit;
  pointer-events: none;
  background: var(--beam-gradient);
}
.bbp-ring {
  padding: var(--beam-thickness, 2px);
  -webkit-mask: linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
  -webkit-mask-composite: xor;
  mask: linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
  mask-composite: exclude;
}
.bbp-glow {
  filter: blur(14px);
  opacity: .35;
  z-index: -1;
}
@media (forced-colors: active) {
  .bbp-glow,
  .bbp-ring { display: none; }
}
</style>
