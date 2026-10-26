<template>
  <div class="mg" :style="{ '--mg-size': size + 'px' }">
    <svg class="mg-arc" :viewBox="`-150 -150 300 168`" aria-hidden="true">
      <line
        v-for="t in ticks"
        :key="t.i"
        :x1="t.x1" :y1="t.y1" :x2="t.x2" :y2="t.y2"
        :stroke="t.color"
        :stroke-opacity="t.opacity"
        stroke-width="3.2"
        stroke-linecap="round"
        class="mg-tick"
        :style="{ animationDelay: (t.i * 12) + 'ms' }"
      />
    </svg>
    <div class="mg-center">
      <span class="mg-value">{{ display }}<i v-if="unit">{{ unit }}</i></span>
      <span class="mg-label">{{ label }}</span>
    </div>
    <ul v-if="rows.length" class="mg-rows">
      <li v-for="(r, i) in rows" :key="i">
        <i class="mg-dot" :style="{ background: r.color }"></i>
        <span class="mg-name">{{ r.label }}</span>
        <span class="mg-share">{{ r.share }}</span>
        <span class="mg-extra">{{ r.extra }}</span>
      </li>
    </ul>
  </div>
</template>

<script setup>
/**
 * MarketGauge — 移植自 21st.dev「Finance Bento」(romanvasnev) 的 Allocation 半环仪表。
 * 上游实现：56 根刻度线从 180° 扫到 360°（上半环），每 7 根加长；按资产占比依次着色，
 * 末段（Fiat）以 0.35 透明区分；逐根 opacity 0 → 1，延迟 i*12ms 的「仪表点亮」动画；
 * 圆心为 52px 细体大数字 + % 上标 + 12px 说明文字，下方三行占比列表。
 * 本 Vue 版保留全部几何/动画参数，中心与列表改为 DOM 渲染以适配 i18n 与响应式，
 * 配色接入站内暖象牙 + 海军蓝 + 金色令牌。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  /** 0-100，中心大数字（进入视口后从 0 弹到该值） */
  value: { type: Number, default: 0 },
  label: { type: String, default: '' },
  /** 中心数字后缀，默认 %；指数类可传 /100 */
  unit: { type: String, default: '%' },
  /** [{ label, share: 0-1, color, fade?: boolean }]，share 累计决定刻度着色 */
  segments: { type: Array, default: () => [] },
  /** [{ label, share: '47%', extra: '强度 82', color }] */
  rows: { type: Array, default: () => [] },
  size: { type: Number, default: 300 }
})

const TICKS = 56
const display = ref(0)

const ticks = computed(() => {
  const segs = props.segments.length
    ? props.segments
    : [{ share: 1, color: 'var(--ll-brand-gold, #C8A45C)' }]
  const out = []
  let acc = 0
  for (let t = 0; t <= TICKS; t++) {
    const ratio = t / TICKS
    acc = 0
    let seg = segs[segs.length - 1]
    for (const s of segs) {
      acc += s.share || 0
      if (ratio <= acc) { seg = s; break }
    }
    const deg = 180 + ratio * 180
    const rad = deg * Math.PI / 180
    const rOut = 136
    const rIn = t % 7 === 0 ? 112 : 118
    out.push({
      i: t,
      x1: +(rOut * Math.cos(rad)).toFixed(1),
      y1: +(rOut * Math.sin(rad)).toFixed(1),
      x2: +(rIn * Math.cos(rad)).toFixed(1),
      y2: +(rIn * Math.sin(rad)).toFixed(1),
      color: seg.color || 'rgba(19,35,58,.35)',
      opacity: seg.fade ? 0.35 : 1
    })
  }
  return out
})

const reduceMotion = () => typeof window !== 'undefined'
  && window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches
let raf = 0
function animate(from = 0) {
  cancelAnimationFrame(raf)
  if (reduceMotion()) { display.value = Math.round(props.value); return }
  const target = Math.max(0, Math.min(100, Number(props.value) || 0))
  const startValue = Number(from) || 0
  const dur = 1100
  const start = performance.now()
  const step = (now) => {
    const t = Math.min(1, (now - start) / dur)
    const eased = 1 - Math.pow(1 - t, 3)
    display.value = Math.round(startValue + (target - startValue) * eased)
    if (t < 1) raf = requestAnimationFrame(step)
  }
  raf = requestAnimationFrame(step)
}

onMounted(() => animate(0))
watch(() => props.value, () => animate(display.value))
onBeforeUnmount(() => cancelAnimationFrame(raf))
</script>

<style scoped>
.mg {
  position: relative;
  width: 100%;
  max-width: var(--mg-size);
  margin: 0 auto;
  text-align: center;
}
.mg-arc { display: block; width: 100%; height: auto; overflow: visible; }
.mg-tick { opacity: 0; animation: mg-tk .4s cubic-bezier(.22, 1, .36, 1) forwards; }
@keyframes mg-tk { to { opacity: 1; } }

.mg-center {
  position: absolute;
  left: 50%;
  top: 72%;
  transform: translate(-50%, -50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  pointer-events: none;
}
.mg-value {
  display: inline-flex;
  align-items: flex-start;
  font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Segoe UI', sans-serif;
  font-size: clamp(38px, 4.4vw, 54px);
  font-weight: 300;
  letter-spacing: -.05em;
  line-height: 1;
  color: var(--ll-brand-base, #13233A);
  font-variant-numeric: tabular-nums;
}
.mg-value i { font-style: normal; font-size: .38em; font-weight: 600; margin-left: 2px; color: var(--ll-muted, #64748B); }
.mg-label { font-size: 12px; font-weight: 600; letter-spacing: .02em; color: var(--ll-muted, #64748B); }

.mg-rows { list-style: none; margin: 6px 0 0; padding: 0; }
.mg-rows li { display: flex; align-items: center; gap: 9px; height: 34px; border-top: 1px solid rgba(19, 35, 58, .07); }
.mg-rows li:first-child { border-top: 0; }
.mg-dot { width: 8px; height: 8px; border-radius: 50%; flex: none; }
.mg-name { flex: 1; text-align: left; font-size: 13px; font-weight: 600; color: var(--ll-brand-base, #13233A); }
.mg-share { width: 44px; text-align: right; font-size: 12.5px; color: var(--ll-muted, #64748B); font-variant-numeric: tabular-nums; }
.mg-extra { width: 78px; text-align: right; font-size: 12.5px; color: var(--ll-muted, #64748B); font-variant-numeric: tabular-nums; }

@media (prefers-reduced-motion: reduce) { .mg-tick { animation: none; opacity: 1; } }
</style>