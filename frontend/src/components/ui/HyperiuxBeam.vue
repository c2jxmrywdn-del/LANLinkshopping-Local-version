<template>
  <div
    ref="root"
    class="hx"
    :data-theme="resolvedTheme"
    :data-active="active && !reduced ? '' : null"
    :style="rootStyle"
  >
    <div class="hx-core" aria-hidden="true"></div>
    <div class="hx-bloom" aria-hidden="true"></div>
    <div class="hx-stroke" aria-hidden="true"></div>
    <slot />
  </div>
</template>

<script setup>
/**
 * Hyperiux Border Beam（移植自 21st.dev @hyperiux 的 border-beam，MIT）。
 * 源码逻辑取自其公开预览 bundle：
 *  - 角向扫光：conic-gradient 亮带 + 三层 mask（角向窗 ∩ 边框环）；
 *  - 彩色边缘：多枚 radial-gradient 椭圆光斑叠加，12s hue-rotate 循环；
 *  - 光晕层：blur 后置于卡片背后，默认 0.35；
 *  - 参数：size(md) / colorVariant(colorful|sunset|mono) / theme(light|dark|auto)
 *          / duration / active / borderRadius / beamWidth / strength。
 *  说明：原组件含 line/sm/pulse-inner/pulse-outside 等尺寸变体，
 *        此处移植截图使用的默认 md 变体，其余变体本页未使用。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  colorVariant: { type: String, default: 'colorful' },
  theme: { type: String, default: 'auto' },
  duration: { type: Number, default: 3.3 },
  active: { type: Boolean, default: true },
  borderRadius: { type: Number, default: 16 },
  beamWidth: { type: Number, default: 2 },
  strength: { type: Number, default: 1 },
  reducedMotion: { type: Boolean, default: false }
})

const root = ref(null)
const pageDark = ref(false)
const prefersReduced = ref(false)

const resolvedTheme = computed(() => {
  if (props.theme === 'light' || props.theme === 'dark') return props.theme
  return pageDark.value ? 'dark' : 'light'
})
const reduced = computed(() => props.reducedMotion || prefersReduced.value)

let observer = null
function readPageTheme() {
  pageDark.value = typeof document !== 'undefined'
    && document.documentElement.getAttribute('data-theme') === 'dark'
}

onMounted(() => {
  readPageTheme()
  observer = new MutationObserver(readPageTheme)
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'class'] })

  try {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)')
    prefersReduced.value = mq.matches
    mq.addEventListener?.('change', (e) => { prefersReduced.value = e.matches })
  } catch (e) { /* ignore */ }
})

onBeforeUnmount(() => observer?.disconnect())

const EDGE = {
  colorful: [
    'radial-gradient(ellipse 38px 34px at 0% 4%, rgba(255, 70, 120, .42), transparent)',
    'radial-gradient(ellipse 34px 30px at 100% 18%, rgba(40, 180, 220, .42), transparent)',
    'radial-gradient(ellipse 36px 30px at 100% 92%, rgba(60, 200, 90, .38), transparent)',
    'radial-gradient(ellipse 30px 34px at 0% 88%, rgba(180, 60, 240, .38), transparent)',
    'radial-gradient(ellipse 26px 22px at 50% 0%, rgba(255, 170, 40, .34), transparent)',
    'radial-gradient(ellipse 30px 24px at 70% 100%, rgba(255, 100, 80, .32), transparent)'
  ],
  sunset: [
    'radial-gradient(ellipse 40px 30px at 0% 0%, rgba(255, 140, 80, .45), transparent)',
    'radial-gradient(ellipse 34px 28px at 100% 20%, rgba(255, 90, 110, .4), transparent)',
    'radial-gradient(ellipse 36px 30px at 100% 95%, rgba(255, 180, 70, .38), transparent)',
    'radial-gradient(ellipse 30px 26px at 0% 85%, rgba(230, 70, 120, .34), transparent)'
  ],
  mono: [
    'radial-gradient(ellipse 42px 32px at 0% 10%, rgba(100, 150, 255, .4), transparent)',
    'radial-gradient(ellipse 36px 30px at 100% 80%, rgba(80, 130, 240, .36), transparent)',
    'radial-gradient(ellipse 28px 26px at 60% 0%, rgba(120, 170, 255, .3), transparent)'
  ],
  brand: [
    'radial-gradient(ellipse 40px 32px at 0% 6%, rgba(200, 164, 92, .38), transparent)',
    'radial-gradient(ellipse 34px 30px at 100% 22%, rgba(30, 110, 184, .3), transparent)',
    'radial-gradient(ellipse 36px 30px at 100% 94%, rgba(155, 45, 32, .26), transparent)',
    'radial-gradient(ellipse 30px 30px at 0% 86%, rgba(34, 211, 238, .22), transparent)',
    'radial-gradient(ellipse 28px 22px at 52% 0%, rgba(246, 211, 138, .3), transparent)'
  ]
}

const edgeGradient = computed(() => (EDGE[props.colorVariant] || EDGE.colorful).join(', '))

const rootStyle = computed(() => ({
  borderRadius: `${props.borderRadius}px`,
  '--hx-duration': `${Math.max(0.8, props.duration)}s`,
  '--hx-width': `${Math.max(1, props.beamWidth)}px`,
  '--hx-edge': edgeGradient.value,
  '--hx-strength': String(props.strength)
}))
</script>

<style scoped>
@property --hx-angle {
  syntax: '<angle>';
  initial-value: 0deg;
  inherits: false;
}
.hx {
  position: relative;
  overflow: hidden;
  isolation: isolate;
}
.hx[data-theme='light'] { --hx-band-color: rgba(0, 0, 0, .36); }
.hx[data-theme='dark'] { --hx-band-color: rgba(255, 255, 255, .62); }

.hx-core,
.hx-bloom,
.hx-stroke {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  pointer-events: none;
}

/* 彩色边缘内晕 */
.hx-core {
  background: var(--hx-edge);
  filter: blur(10px) saturate(1.05);
  opacity: 0;
  transition: opacity .4s ease;
  z-index: 0;
  -webkit-mask-image: radial-gradient(ellipse at center, transparent 42%, #000 78%);
  mask-image: radial-gradient(ellipse at center, transparent 42%, #000 78%);
}

/* 外发光 + 锐利扫光 */
.hx-bloom,
.hx-stroke {
  background:
    conic-gradient(from var(--hx-angle),
      transparent 0% 54%,
      color-mix(in srgb, var(--hx-band-color) 22%, transparent) 57%,
      var(--hx-band-color) 66%,
      color-mix(in srgb, var(--hx-band-color) 22%, transparent) 75%,
      transparent 78% 100%),
    var(--hx-edge);
  -webkit-mask:
    conic-gradient(from var(--hx-angle),
      transparent 0% 30%,
      rgba(255, 255, 255, .35) 44%,
      #fff 52% 80%,
      rgba(255, 255, 255, .35) 86%,
      transparent 95% 100%),
    linear-gradient(#fff 0 0) content-box,
    linear-gradient(#fff 0 0);
  -webkit-mask-composite: source-in, xor;
  mask:
    conic-gradient(from var(--hx-angle),
      transparent 0% 30%,
      rgba(255, 255, 255, .35) 44%,
      #fff 52% 80%,
      rgba(255, 255, 255, .35) 86%,
      transparent 95% 100%),
    linear-gradient(#fff 0 0) content-box,
    linear-gradient(#fff 0 0);
  mask-composite: intersect, exclude;
  opacity: 0;
  transition: opacity .4s ease;
}
.hx-stroke {
  padding: var(--hx-width);
  z-index: 2;
}
.hx-bloom {
  padding: calc(var(--hx-width) + 1px);
  filter: blur(14px);
  z-index: -1;
}

.hx[data-active] .hx-stroke { opacity: calc(.8 * var(--hx-strength)); animation: hx-spin var(--hx-duration) linear infinite; }
.hx[data-active] .hx-bloom { opacity: calc(.3 * var(--hx-strength)); animation: hx-spin var(--hx-duration) linear infinite; }
.hx[data-active] .hx-core { opacity: calc(.36 * var(--hx-strength)); animation: hx-hue 12s ease-in-out infinite; }

@keyframes hx-spin { to { --hx-angle: 360deg; } }
@keyframes hx-hue {
  0%, 100% { filter: blur(10px) hue-rotate(-38deg) saturate(1.05); }
  50% { filter: blur(10px) hue-rotate(38deg) saturate(1.05); }
}

@media (prefers-reduced-motion: reduce) {
  .hx-stroke, .hx-bloom, .hx-core {
    animation: none !important;
    opacity: 0 !important;
  }
}
@media (forced-colors: active) {
  .hx-core, .hx-stroke, .hx-bloom { display: none; }
}
</style>
