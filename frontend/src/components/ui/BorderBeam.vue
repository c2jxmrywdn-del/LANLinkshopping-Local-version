<template>
  <div ref="root" class="bb-wrap" :data-beam="uid" :data-active="state === 'active' || null"
       :data-fading="state === 'fading' || null" :data-paused="paused || null">
    <i data-beam-bloom aria-hidden="true"></i>
    <slot />
  </div>
  <component :is="'style'" v-if="css">{{ css }}</component>
</template>

<script setup>
/**
 * BorderBeam（完整移植 npm 包 `border-beam`@1.4.1 / 21st.dev @larsen66，MIT）
 * md 模式：全边框旋转光束，三层渲染——
 *   ::after  描边层（sheen 锥形渐变 + 彩色沿边 stops，conic mask 只留光束窗口，1px 边环）
 *   ::before 内辉光层（彩色柔光 + inset 阴影，边缘 28px 渐隐）
 *   [data-beam-bloom] 泛光层（窄光束 blur 8px 描边环）
 * 浅色主题（light）光束为黑色渐变（白底可见），透明度 stroke .12 / inner .26 / bloom .34；
 * 深色主题（dark）为白色渐变（.26/.42/.24）。12s ±30° 色相漂移 + 0.6s 淡入 / 0.5s 淡出生命周期。
 */
import { computed, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'

const props = defineProps({
  /** 配色：colorful 彩虹 / ocean 海洋 / sunset 落日 / forest 森林 / candy 糖果 / ice 冰蓝 / gold 鎏金 / mono 灰度 */
  colorVariant: { type: String, default: 'colorful' },
  /** light 适配白底（光束深色），dark 适配深底（光束亮色），auto 跟随系统 */
  theme: { type: String, default: 'light' },
  /** 旋转一圈时长（秒），包默认 1.96 */
  duration: { type: Number, default: 1.96 },
  /** 圆角（px），需与内容卡一致 */
  borderRadius: { type: Number, default: 18 },
  /** 整体强度 0-1（写入 --beam-strength，可在任意层级覆盖） */
  strength: { type: Number, default: 1 },
  /** 是否激活动画（置 false 时 0.5s 淡出） */
  active: { type: Boolean, default: true },
  paused: { type: Boolean, default: false },
  /** 固定色彩（关闭色相漂移动画） */
  staticColors: { type: Boolean, default: false }
})

const uid = 'bb' + String(useId()).replace(/[^a-zA-Z0-9]/g, '')
const root = ref(null)
const isDark = ref(props.theme === 'dark')

// ===== 沿边彩色 stops（位置/尺寸取自包内 G.*.border，各配色仅颜色不同） =====
const STOPS = [
  { pos: '33% -7.4%', size: '70px 40px' },
  { pos: '12% -5%', size: '60px 35px' },
  { pos: '2.1% 68.3%', size: '40px 70px' },
  { pos: '2.1% 68.3%', size: '20px 35px' },
  { pos: '74.4% 100%', size: '180px 32px' },
  { pos: '55% 100%', size: '85px 26px' },
  { pos: '93.9% 0%', size: '74px 32px' },
  { pos: '100% 27.1%', size: '26px 42px' },
  { pos: '100% 27.1%', size: '52px 48px' }
]
const VARIANT_COLORS = {
  colorful: [[255,50,100],[40,140,255],[50,200,80],[30,185,170],[100,70,255],[40,140,255],[255,120,40],[240,50,180],[180,40,240]],
  ocean: [[100,80,220],[60,120,255],[80,100,200],[50,140,220],[120,80,255],[70,130,255],[140,100,240],[90,110,230],[130,70,255]],
  sunset: [[255,80,50],[255,160,40],[255,120,60],[255,200,50],[255,100,80],[255,180,60],[255,60,60],[255,140,50],[255,90,70]],
  forest: [[46,160,90],[30,190,120],[70,180,70],[20,150,130],[90,200,80],[40,170,110],[120,210,70],[35,145,100],[60,195,140]],
  candy: [[240,70,170],[255,90,140],[215,60,200],[255,110,180],[200,80,240],[250,60,150],[230,120,220],[245,85,165],[210,70,230]],
  ice: [[90,200,240],[60,175,230],[130,220,250],[70,190,215],[110,210,255],[50,165,220],[150,230,250],[85,195,235],[65,180,245]],
  gold: [[240,190,60],[255,210,90],[225,165,40],[250,200,70],[255,225,120],[230,175,50],[245,205,85],[215,155,35],[255,215,100]],
  mono: [[180,180,180],[140,140,140],[160,160,160],[130,130,130],[170,170,170],[150,150,150],[190,190,190],[145,145,145],[165,165,165]]
}
// ===== 主题参数（包内 pe.md + props 默认） =====
const THEME = {
  dark: { stroke: 0.26, inner: 0.42, bloom: 0.24, innerShadow: 'rgba(255,255,255,.27)', saturation: 1.2, brightness: 1.3 },
  light: { stroke: 0.12, inner: 0.26, bloom: 0.34, innerShadow: 'rgba(0,0,0,.14)', saturation: 1.5, brightness: 1.3 }
}

function stopGradients(variant, alpha) {
  const colors = VARIANT_COLORS[variant] || VARIANT_COLORS.colorful
  return STOPS.map((s, i) => {
    const [r, g, b] = colors[i]
    const size = alpha != null ? s.size.replace(/(\d+)px/g, m => Math.round(parseInt(m) * 0.9) + 'px') : s.size
    const color = alpha != null ? `rgba(${r}, ${g}, ${b}, ${alpha})` : `rgb(${r}, ${g}, ${b})`
    return `radial-gradient(ellipse ${size} at ${s.pos}, ${color}, transparent)`
  }).join(',\n    ')
}

const css = computed(() => {
  const e = uid
  const variant = props.colorVariant
  const dark = props.theme === 'auto' ? isDark.value : props.theme === 'dark'
  const t = dark ? THEME.dark : THEME.light
  const mono = variant === 'mono' ? 0.5 : 1
  const stroke = (t.stroke * mono).toFixed(2)
  const inner = (t.inner * mono).toFixed(2)
  const bloom = (t.bloom * mono).toFixed(2)
  const innerAlpha = variant === 'mono' ? 0.225 : 0.45
  const dur = props.duration
  const o = props.borderRadius
  const r = 1
  const d = Math.max(0, o - r)
  const strengthVar = `var(--beam-strength, ${props.strength})`

  const sheen = dark
    ? `conic-gradient(from var(--beam-angle-${e}), transparent 0%, transparent 54%, rgba(255,255,255,.1) 57%, rgba(255,255,255,.3) 60%, rgba(255,255,255,.6) 63%, rgba(255,255,255,.75) 66%, rgba(255,255,255,.6) 69%, rgba(255,255,255,.3) 72%, rgba(255,255,255,.1) 75%, transparent 78%, transparent 100%)`
    : `conic-gradient(from var(--beam-angle-${e}), transparent 0%, transparent 54%, rgba(0,0,0,.08) 57%, rgba(0,0,0,.2) 60%, rgba(0,0,0,.4) 63%, rgba(0,0,0,.55) 66%, rgba(0,0,0,.4) 69%, rgba(0,0,0,.2) 72%, rgba(0,0,0,.08) 75%, transparent 78%, transparent 100%)`
  const bloomConic = dark
    ? `conic-gradient(from var(--beam-angle-${e}), transparent 0%, transparent 58%, rgba(255,255,255,.03) 62%, rgba(255,255,255,.08) 65%, rgba(255,255,255,.2) 67%, rgba(255,255,255,.45) 69%, rgba(255,255,255,.85) 70%, rgba(255,255,255,.85) 70.5%, rgba(255,255,255,.45) 71.5%, rgba(255,255,255,.2) 73%, rgba(255,255,255,.08) 75%, rgba(255,255,255,.03) 78%, transparent 82%)`
    : `conic-gradient(from var(--beam-angle-${e}), transparent 0%, transparent 58%, rgba(0,0,0,.02) 62%, rgba(0,0,0,.08) 65%, rgba(0,0,0,.2) 67%, rgba(0,0,0,.4) 69%, rgba(0,0,0,.6) 70%, rgba(0,0,0,.6) 70.5%, rgba(0,0,0,.4) 71.5%, rgba(0,0,0,.2) 73%, rgba(0,0,0,.08) 75%, rgba(0,0,0,.02) 78%, transparent 82%)`
  const conicMask = `conic-gradient(from var(--beam-angle-${e}), transparent 0%, transparent 30%, rgba(255,255,255,.1) 36%, rgba(255,255,255,.35) 44%, white 52%, white 80%, rgba(255,255,255,.35) 86%, rgba(255,255,255,.1) 92%, transparent 95%, transparent 100%)`
  const hueFilter = `brightness(${t.brightness}) saturate(${t.saturation})`
  const hueAnim = props.staticColors ? `filter: ${hueFilter};` : `animation: beam-hue-shift-${e} 12s ease-in-out infinite;`
  const hueKeyframes = props.staticColors ? '' : `
@keyframes beam-hue-shift-${e} {
  0% { filter: hue-rotate(-30deg) ${hueFilter}; }
  50% { filter: hue-rotate(30deg) ${hueFilter}; }
  100% { filter: hue-rotate(-30deg) ${hueFilter}; }
}`

  return `
@property --beam-angle-${e} { syntax: "<angle>"; initial-value: 0deg; inherits: true; }
@property --beam-opacity-${e} { syntax: "<number>"; initial-value: 0; inherits: true; }

[data-beam="${e}"] { position: relative; border-radius: ${o}px; overflow: hidden; }
[data-beam="${e}"] [data-beam-bloom] { display: none; }
[data-beam="${e}"][data-active] { animation: beam-spin-${e} ${dur}s linear infinite, beam-fade-in-${e} .6s ease forwards; }
[data-beam="${e}"][data-fading] { animation: beam-spin-${e} ${dur}s linear infinite, beam-fade-out-${e} .5s ease forwards; }

/* 描边层：sheen + 彩色沿边 stops，conic 窗口 ∩ 1px 边环 */
[data-beam="${e}"][data-active]::after,
[data-beam="${e}"][data-fading]::after {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: ${d}px;
  padding: ${r}px;
  clip-path: inset(0 round ${o}px);
  background: ${sheen},\n    ${stopGradients(variant, null)};
  -webkit-mask: ${conicMask}, linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
  -webkit-mask-composite: source-in, xor;
  mask: ${conicMask}, linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
  mask-composite: intersect, exclude;
  pointer-events: none;
  z-index: 2;
  opacity: calc(var(--beam-opacity-${e}) * ${stroke} * ${strengthVar});
  ${hueAnim}
}

/* 内辉光层：彩色柔光 + inset 阴影，边缘 28px 渐隐 */
[data-beam="${e}"][data-active]::before,
[data-beam="${e}"][data-fading]::before {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: ${o}px;
  clip-path: inset(0 round ${o}px);
  background: ${stopGradients(variant, innerAlpha)};
  box-shadow: inset 0 0 9px 1px ${t.innerShadow};
  -webkit-mask-image: ${conicMask},
    linear-gradient(white, transparent 28px, transparent calc(100% - 28px), white),
    linear-gradient(to right, white, transparent 28px, transparent calc(100% - 28px), white);
  -webkit-mask-composite: source-in, source-over;
  mask-image: ${conicMask},
    linear-gradient(white, transparent 28px, transparent calc(100% - 28px), white),
    linear-gradient(to right, white, transparent 28px, transparent calc(100% - 28px), white);
  mask-composite: intersect, add;
  pointer-events: none;
  z-index: 1;
  opacity: calc(var(--beam-opacity-${e}) * ${inner} * ${strengthVar});
  ${hueAnim}
}

/* 泛光层：窄光束 blur(8px) 边环 */
[data-beam="${e}"][data-active] [data-beam-bloom],
[data-beam="${e}"][data-fading] [data-beam-bloom] {
  display: block;
  position: absolute;
  inset: 0;
  border-radius: ${d}px;
  clip-path: inset(0 round ${o}px);
  background: ${bloomConic};
  -webkit-mask: linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
  -webkit-mask-composite: xor;
  mask: linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
  mask-composite: exclude;
  padding: ${r}px;
  filter: blur(8px) ${hueFilter};
  pointer-events: none;
  z-index: 3;
  opacity: calc(var(--beam-opacity-${e}) * ${bloom} * ${strengthVar});
}

[data-beam="${e}"][data-paused],
[data-beam="${e}"][data-paused]::after,
[data-beam="${e}"][data-paused]::before,
[data-beam="${e}"][data-paused] [data-beam-bloom] { animation-play-state: paused !important; }

@keyframes beam-spin-${e} { to { --beam-angle-${e}: 360deg; } }
@keyframes beam-fade-in-${e} { to { --beam-opacity-${e}: 1; } }
@keyframes beam-fade-out-${e} { from { --beam-opacity-${e}: 1; } to { --beam-opacity-${e}: 0; } }
${hueKeyframes}

@media (prefers-reduced-motion: reduce) {
  [data-beam="${e}"][data-active],
  [data-beam="${e}"][data-fading],
  [data-beam="${e}"][data-active]::after,
  [data-beam="${e}"][data-fading]::after,
  [data-beam="${e}"][data-active]::before,
  [data-beam="${e}"][data-fading]::before,
  [data-beam="${e}"][data-active] [data-beam-bloom],
  [data-beam="${e}"][data-fading] [data-beam-bloom] { animation: none !important; }
}`
})

// ===== 生命周期：active → data-active；false → data-fading 0.5s 淡出后关闭 =====
const state = ref(props.active ? 'active' : 'off')
let fadeTimer = null
watch(() => props.active, on => {
  clearTimeout(fadeTimer)
  if (on) state.value = 'active'
  else if (state.value === 'active') {
    state.value = 'fading'
    fadeTimer = setTimeout(() => { state.value = 'off' }, 500)
  }
})

let media = null
onMounted(() => {
  if (props.theme === 'auto') {
    media = window.matchMedia('(prefers-color-scheme: dark)')
    isDark.value = media.matches
    media.addEventListener?.('change', onScheme)
  }
})
function onScheme(ev) { isDark.value = ev.matches }
onBeforeUnmount(() => {
  media?.removeEventListener?.('change', onScheme)
  clearTimeout(fadeTimer)
})
</script>

<style scoped>
.bb-wrap { box-sizing: border-box; }
.bb-wrap > *:not([data-beam-bloom]) { position: relative; z-index: 4; }
</style>
