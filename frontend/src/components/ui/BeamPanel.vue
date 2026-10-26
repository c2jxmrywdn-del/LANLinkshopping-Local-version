<template>
  <div ref="root" v-bind="$attrs" class="mk-panel" :class="cls" :data-motion="staticMode ? 'static' : 'animated'"
       :data-paused="paused ? 'true' : 'false'" :style="panelStyle"
       @pointerenter="surge" @pointerleave="settle" @focusin="surge" @focusout="settle">
    <component :is="'style'" v-if="css">{{ css }}</component>
    <div v-if="glow" aria-hidden="true" class="mk-beam-glow"></div>
    <div aria-hidden="true" class="mk-beam-ring"></div>
    <slot />
  </div>
</template>

<script setup>
/**
 * BorderBeamPanel —— 21st.dev @rmahammad/border-beam-panel 的忠实 Vue 移植
 * （上游 rmahammad/motiq，MIT；原始源码存档于 docs/ui-reference/border-beam-panel/）
 *
 * 双彗星绕 2px 边框环：边环是旋转 conic-gradient，两层 CSS alpha mask（mask-composite:
 * exclude）裁切；角速度本身由弹簧驱动（k=30, d=11），hover 升至 240°/s、离开惯性滑回
 * 42°/s；每帧只写一个自定义属性 --mk-beam-a，内容永不重绘。滚出视口/标签页隐藏暂停；
 * reduced-motion 静态停靠 40°；forced-colors 降级为纯边框。
 */
import { computed, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'

const props = defineProps({
  /** 一颗或两颗对冲彗星（180°） */
  beams: { type: Number, default: 2 },
  /** 彗星颜色 [主色, 第二颗]；默认 azure→cyan + 珊瑚签名色 */
  colors: { type: Array, default: null },
  /** 边环厚度 px */
  thickness: { type: Number, default: 2 },
  /** 静息角速度 deg/s（42 ≈ 8.5s/圈） */
  idleSpeed: { type: Number, default: 42 },
  /** hover/focus 角速度 deg/s */
  hoverSpeed: { type: Number, default: 240 },
  /** 面板后方的模糊光晕（投射光） */
  glow: { type: Boolean, default: true },
  /** 圆角 px */
  radius: { type: Number, default: 16 },
  /** 速度弹簧 { stiffness, damping } */
  spring: { type: Object, default: null },
  /** 确定性起始角（seed×137.508 黄金角） */
  seed: { type: Number, default: 1 },
  /** 滚出视口/页面隐藏时暂停 */
  pauseWhenHidden: { type: Boolean, default: true },
  /** 强制静态模式 */
  reducedMotion: { type: Boolean, default: false }
})

/* ===== 手写 delta-time 弹簧（保持零依赖，速度被弹簧驱动 → 惯性滑行） ===== */
class Spring {
  constructor(value, k, d) {
    this.x = value; this.v = 0; this.target = value; this.k = k; this.d = d
  }
  step(dt) {
    const a = this.k * (this.target - this.x) - this.d * this.v
    this.v += a * dt
    this.x += this.v * dt
    return this.x
  }
}
const clamp = (n, lo, hi) => Math.min(hi, Math.max(lo, n))
/** 静态模式停靠角：两颗彗星都停在可见边缘 */
const PARKED_ANGLE = 40

/**
 * 单颗彗星：~45° 尾巴渐亮至 4° 亮头。尾部用 color-mix 4% 过渡而非 transparent
 * （transparent 是 rgba(0,0,0,0)，conic 经过它会明显发灰）。
 */
function comet(tail, head, tip, midAlpha, start) {
  return [
    `color-mix(in srgb, ${tail} 4%, transparent) ${start + 18}deg`,
    `color-mix(in srgb, ${tail} ${midAlpha}%, transparent) ${start + 46}deg`,
    `${head} ${start + 56}deg`,
    `${tip} ${start + 60}deg`,
    `transparent ${start + 63}deg`
  ].join(', ')
}
function ringGradient(beams, colors) {
  const tail0 = colors?.[0] ?? 'var(--color-accent, #4f7cff)'
  // 默认主彗星沿身体 azure → cyan；自定义色则保持本色
  const head0 = colors?.[0] ?? 'var(--color-secondary-accent, #22c7d9)'
  const stops = [
    'transparent 0deg',
    comet(tail0, head0, `color-mix(in srgb, ${head0} 22%, #ffffff)`, 55, 0)
  ]
  // 第二颗彗星 —— 表面上的唯一珊瑚色时刻
  if (beams === 2) {
    const c1 = colors?.[1] ?? 'var(--color-signature, #ff6b5e)'
    stops.push('transparent 198deg', comet(c1, c1, `color-mix(in srgb, ${c1} 26%, #ffffff)`, 50, 198))
  }
  stops.push('transparent 360deg')
  return `conic-gradient(from var(--mk-beam-a, 0deg), ${stops.join(', ')})`
}

const uid = String(useId()).replace(/[^a-zA-Z0-9]/g, '')
const cls = `mk-beam-${uid}`
const root = ref(null)

const stiffness = computed(() => props.spring?.stiffness ?? 30)
const damping = computed(() => props.spring?.damping ?? 11)
const startAngle = (((props.seed * 137.508) % 360) + 360) % 360

const speed = new Spring(props.idleSpeed, stiffness.value, damping.value)
let angle = startAngle
let raf = 0
let last = 0

const systemReduced = ref(false)
const onScreen = ref(true)
const staticMode = computed(() => props.reducedMotion || systemReduced.value)
const paused = computed(() => props.pauseWhenHidden && !onScreen.value)
const animate = computed(() => !staticMode.value && !paused.value)

const gradient = computed(() => ringGradient(props.beams, props.colors))

const css = computed(() => `
.${cls} .mk-beam-ring, .${cls} .mk-beam-glow {
  position: absolute;
  inset: -1px;
  border-radius: ${props.radius}px;
  pointer-events: none;
  background: ${gradient.value};
}
.${cls} .mk-beam-ring {
  padding: ${Math.max(1, props.thickness)}px;
  -webkit-mask: linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
  -webkit-mask-composite: xor;
  mask: linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
  mask-composite: exclude;
}
.${cls} .mk-beam-glow { filter: blur(14px); opacity: .35; z-index: -1; }
@media (forced-colors: active) {
  .${cls} .mk-beam-ring, .${cls} .mk-beam-glow { display: none; }
  .${cls} { border-color: CanvasText; }
}`.trim())

const panelStyle = computed(() => ({
  borderRadius: props.radius + 'px',
  isolation: 'isolate',
  '--mk-beam-a': startAngle.toFixed(2) + 'deg'
}))

function paint(a) {
  root.value?.style.setProperty('--mk-beam-a', (((a % 360) + 360) % 360).toFixed(2) + 'deg')
}

function frame(now) {
  if (!last) last = now
  const dt = clamp((now - last) / 1000, 0, 0.05)
  last = now
  angle += speed.step(dt) * dt
  paint(angle)
  raf = requestAnimationFrame(frame)
}

function surge() { speed.target = props.hoverSpeed }
function settle() { speed.target = props.idleSpeed }

// 弹簧参数热更
watch([stiffness, damping], ([k, d]) => { speed.k = k; speed.d = d })
watch(animate, on => {
  cancelAnimationFrame(raf)
  last = 0
  if (on) raf = requestAnimationFrame(frame)
})
watch(staticMode, sm => {
  if (sm) {
    angle = PARKED_ANGLE
    speed.x = speed.target = props.idleSpeed
    speed.v = 0
    paint(PARKED_ANGLE)
  }
}, { immediate: true }) // 挂载即静态（系统减少动效）时也停靠——对齐原版 React useEffect 挂载必跑的语义

let motionMedia = null
let io = null
let visibilityHandler = null
onMounted(() => {
  motionMedia = window.matchMedia('(prefers-reduced-motion: reduce)')
  systemReduced.value = motionMedia.matches
  motionMedia.addEventListener?.('change', onMotion)
  // 滚出视口 / 标签页隐藏 → 暂停循环
  io = new IntersectionObserver(([entry]) => { onScreen.value = entry.isIntersecting }, { threshold: 0.05 })
  io.observe(root.value)
  visibilityHandler = () => { onScreen.value = !document.hidden && onScreen.value }
  document.addEventListener('visibilitychange', visibilityHandler)
  if (animate.value) raf = requestAnimationFrame(frame)
})
function onMotion(ev) { systemReduced.value = ev.matches }
onBeforeUnmount(() => {
  cancelAnimationFrame(raf)
  motionMedia?.removeEventListener?.('change', onMotion)
  io?.disconnect()
  document.removeEventListener('visibilitychange', visibilityHandler)
})
</script>

<script>
// inheritAttrs:false —— class/style 已在模板里显式合并（class 追加在默认面板类之后，调用方可覆盖）
export default { inheritAttrs: false }
</script>

<style scoped>
/* 面板默认外观（原版：relative + 1px 边框 + 深色面板底 + p-7）。
   全部包 :where() 归零特异性 —— 调用方传入的任意 class 都能直接覆盖。 */
:where(.mk-panel) {
  position: relative;
  width: 100%;
  border: 1px solid var(--color-border, #263449);
  background: var(--color-surface, #111827);
  padding: 28px;
}
.mk-panel > *:not(.mk-beam-ring):not(.mk-beam-glow):not(style) { position: relative; }
</style>
