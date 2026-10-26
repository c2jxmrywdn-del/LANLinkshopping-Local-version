<template>
  <section
    ref="root"
    class="sb"
    :data-in-view="inView ? '' : undefined"
    :data-layout="layout"
    :style="{ '--count': items.length, '--draw': Math.round(duration * 420) + 'ms' }"
    :aria-label="ariaLabel || undefined"
  >
    <header v-if="title || description" class="sb-header">
      <h2 v-if="title" class="sb-title">{{ title }}</h2>
      <p v-if="description" class="sb-description">{{ description }}</p>
    </header>

    <dl class="sb-stats" :data-count="Math.min(items.length, 4)" :data-visuals="hasVisuals ? '' : undefined">
      <div
        v-for="s in items"
        :key="s.i"
        class="sb-stat"
        :class="s.tone ? 'tone-' + s.tone : null"
        :style="{ '--i': s.i }"
        :tabindex="s.context ? 0 : undefined"
        :data-context="s.context && s.detail ? '' : undefined"
      >
        <dt class="sb-label">{{ s.label }}</dt>
        <dd class="sb-figure">
          <span class="sb-sr">{{ s.prefix }}{{ formatted(s) }}{{ s.suffix }}</span>
          <span class="sb-number" aria-hidden="true">
            <span v-if="s.prefix" class="sb-prefix">{{ s.prefix }}</span>
            <span class="sb-digits">
              <span class="sb-sizer">{{ formatted(s) }}</span>
              <span :ref="(el) => setLive(el, s.i)" class="sb-live">{{ formatted(s) }}</span>
            </span>
            <span v-if="s.suffix" class="sb-suffix">{{ s.suffix }}</span>
          </span>
        </dd>
        <dd v-if="s.detail || s.context" class="sb-caption">
          <span v-if="s.detail" class="sb-detail">{{ s.detail }}</span>
          <span v-if="s.context" class="sb-context">{{ s.context }}</span>
        </dd>
        <dd v-if="hasVisuals" class="sb-visual" aria-hidden="true">
          <div v-if="s.visual && s.visual.kind === 'trend' && s.trend" class="sb-trend">
            <svg class="sb-draw" viewBox="0 0 100 40" preserveAspectRatio="none">
              <path class="sb-area" :d="s.trend.area" />
              <path class="sb-line" :d="s.trend.line" vector-effect="non-scaling-stroke" />
            </svg>
            <span class="sb-end-dot" :style="{ left: s.trend.dotLeft + '%', top: s.trend.dotTop + '%' }"></span>
          </div>
          <div v-else-if="s.visual && s.visual.kind === 'uptime'" class="sb-bars is-dense">
            <span
              v-for="(b, bi) in s.bars"
              :key="bi"
              class="sb-bar"
              :class="{ 'is-ink': b.ink }"
              :style="{ '--j': b.j, '--h': b.h }"
            ></span>
          </div>
          <div v-else-if="s.visual && s.visual.kind === 'distribution'" class="sb-bars">
            <span
              v-for="(b, bi) in s.bars"
              :key="bi"
              class="sb-bar"
              :class="{ 'is-ink': b.ink }"
              :style="{ '--j': b.j, '--h': b.h }"
            ></span>
          </div>
        </dd>
      </div>
    </dl>
  </section>
</template>

<script setup>
/**
 * StatsBand — 移植自 21st.dev「Stats Band」(kuratlielia) 的完整实现（MIT）。
 * 上游用 motion 的 useInView + animate(0, value) 做进入视口计数，并内置四类迷你图表：
 * trend（面积趋势线 + 末端光点）、uptime（密集条，<100 记为缺口）、distribution（直方图 + 目标刻度）。
 * 本 Vue 版：
 *  - rAF + easeOutCubic 等价替代 motion 的 [0.16,1,0.3,1] 缓动，逐帧直写 textContent；
 *  - sizer 占位 + live 叠加，数字变宽时不抖动（与上游一致）；
 *  - 进入视口沿用户设计：柱子 scaleY(0→1)、趋势线 clip-path 展开、末点 1s 后落点；
 *  - divided 布局保留发丝分隔线与逐条描线动画；
 *  - 主题接入站内暖象牙 + 海军蓝 + 金色令牌，不再使用上游的黑底白字。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  /** [{ value, decimals, prefix, suffix, tone, label, detail, context, visual:{kind,values|days|bins,max,marker} }] */
  stats: { type: Array, default: () => [] },
  layout: { type: String, default: 'plain' },
  duration: { type: Number, default: 1.6 },
  locale: { type: String, default: undefined },
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  ariaLabel: { type: String, default: '' }
})

const root = ref(null)
const liveEls = ref({})
const animValues = []
const inView = ref(false)
let io = null
let rafIds = []

const setLive = (el, i) => { if (el) liveEls.value[i] = el }

const fmtInstance = computed(() => {
  const loc = props.locale || (typeof navigator !== 'undefined' && navigator.language) || 'zh-CN'
  return (decimals, compact) => new Intl.NumberFormat(loc, {
    minimumFractionDigits: decimals || 0,
    maximumFractionDigits: decimals || 0,
    notation: compact ? 'compact' : 'standard'
  })
})

function formatted(s, value) {
  const v = value === undefined ? s.value : value
  return fmtInstance.value(s.decimals || 0, s.notation === 'compact').format(v)
}

const items = computed(() => props.stats.map((s, i) => {
  const out = { ...s, i }
  if (s.visual && s.visual.kind === 'trend') out.trend = trendGeometry(s.visual.values || [])
  if (s.visual && s.visual.kind === 'uptime') {
    const days = s.visual.days || []
    out.bars = days.map((d, j) => ({
      j: +(j / Math.max(days.length, 1)).toFixed(4),
      h: Math.min(1, Math.max(.3, 1 - (100 - d) * .7)).toFixed(3),
      ink: d < 100
    }))
  }
  if (s.visual && s.visual.kind === 'distribution') {
    const bins = s.visual.bins || []
    const max = Math.max(...bins) || 1
    const binWidth = (s.visual.max || bins.length) / Math.max(bins.length, 1)
    out.bars = bins.map((b, j) => ({
      j: +(j / Math.max(bins.length, 1)).toFixed(4),
      h: Math.max(.04, b / max).toFixed(3),
      ink: j * binWidth < (s.visual.marker || 0)
    }))
  }
  return out
}))
const hasVisuals = computed(() => props.stats.some(s => s.visual))

function trendGeometry(values) {
  if (!values.length) return { line: '', area: '', dotLeft: 100, dotTop: 50 }
  const min = Math.min(...values)
  const span = (Math.max(...values) - min) || 1
  const pts = values.map((v, i) => [
    values.length > 1 ? i / (values.length - 1) * 100 : 100,
    36 - (v - min) / span * 32
  ])
  const line = pts.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(2)} ${y.toFixed(2)}`).join(' ')
  const [lx, ly] = pts[pts.length - 1]
  return { line, area: `${line} L100 40 L0 40 Z`, dotLeft: +lx.toFixed(2), dotTop: +(ly / 40 * 100).toFixed(2) }
}

const reduceMotion = () => typeof window !== 'undefined'
  && window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches

function paintFinal() {
  props.stats.forEach((s, i) => {
    const el = liveEls.value[i]
    if (el) el.textContent = formatted(s)
    animValues[i] = s.value || 0
  })
}

function startCount(fromZero = false) {
  rafIds.forEach(id => cancelAnimationFrame(id))
  rafIds = []
  props.stats.forEach((s, i) => {
    const el = liveEls.value[i]
    if (!el) return
    const target = s.value || 0
    if (reduceMotion()) { el.textContent = formatted(s); animValues[i] = target; return }
    const from = fromZero ? 0 : (Number.isFinite(animValues[i]) ? animValues[i] : target)
    const delay = i * 90
    const dur = Math.max(0.3, props.duration) * 1000
    const startAt = performance.now() + delay
    const step = (now) => {
      const t = Math.min(1, Math.max(0, (now - startAt) / dur))
      const eased = 1 - Math.pow(1 - t, 3)
      const v = from + (target - from) * eased
      animValues[i] = v
      el.textContent = formatted(s, v)
      if (t < 1) rafIds.push(requestAnimationFrame(step))
      else { animValues[i] = target; el.textContent = formatted(s) }
    }
    rafIds.push(requestAnimationFrame(step))
  })
}

// 切换商品/周期：从当前显示值平滑追到新值（不回到 0，避免闪动）
watch(() => props.stats, () => {
  if (inView.value) startCount(false)
  else paintFinal()
})
watch(() => props.locale, () => {
  if (inView.value) paintFinal()
})

onMounted(() => {
  io = new IntersectionObserver(([entry]) => {
    if (entry.isIntersecting) {
      inView.value = true
      startCount(true)
      io.disconnect()
      io = null
    }
  }, { threshold: 0.15 })
  if (root.value) io.observe(root.value)
})
onBeforeUnmount(() => {
  rafIds.forEach(id => cancelAnimationFrame(id))
  io?.disconnect()
})
</script>

<style scoped>
.sb { container: stats / inline-size; }
.sb * { box-sizing: border-box; }

.sb-header { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); align-items: last baseline; gap: 8px 32px; margin-bottom: 40px; }
.sb-title { max-width: 16ch; margin: 0; font-size: clamp(24px, 1.4rem + 2.4cqi, 34px); font-weight: 700; letter-spacing: -.035em; line-height: 1.15; color: var(--ll-brand-base, #13233A); }
.sb-description { max-width: 34ch; margin: 0; justify-self: end; color: var(--ll-muted, #64748B); font-size: 13px; line-height: 1.6; }

.sb-stats {
  position: relative;
  display: grid;
  grid-template-columns: repeat(var(--count), minmax(0, 1fr));
  column-gap: 40px;
  row-gap: 56px;
  margin: 0;
}
.sb-stat {
  position: relative;
  display: flex;
  min-width: 0;
  flex-direction: column;
  cursor: default;
  opacity: 0;
  transform: translateY(8px);
  transition: opacity .48s cubic-bezier(.22, 1, .36, 1), transform .64s cubic-bezier(.22, 1, .36, 1);
  transition-delay: calc(var(--i) * 90ms);
  --ink: var(--ll-muted, #64748B);
  --soft: rgba(19, 35, 58, .16);
}
.sb[data-in-view] .sb-stat { opacity: 1; transform: none; }
.sb-stat.tone-high { --ink: #9B2D20; --soft: rgba(155, 45, 32, .26); }
.sb-stat.tone-low { --ink: #2E7D5B; --soft: rgba(46, 125, 91, .26); }
.sb-stat.tone-range { --ink: #80601F; --soft: rgba(128, 96, 31, .24); }
.sb-stat.tone-changes { --ink: #13233A; --soft: rgba(19, 35, 58, .18); }

.sb-figure { order: -1; margin: 0 0 10px; }
.sb-label { color: var(--ll-ink, #0F172A); font-size: 13px; font-weight: 500; line-height: 1.5; }
.sb-caption { display: grid; margin: 2px 0 0; color: var(--ll-muted, #64748B); font-size: 12.5px; line-height: 1.6; }
.sb-detail, .sb-context { transition: opacity .28s ease, transform .28s ease; }
.sb-stat[data-context] .sb-context { opacity: 0; transform: translateY(4px); }
.sb-stat[data-context]:hover .sb-detail,
.sb-stat[data-context]:focus .sb-detail { opacity: 0; transform: translateY(-4px); }
.sb-stat[data-context]:hover .sb-context,
.sb-stat[data-context]:focus .sb-context { opacity: 1; transform: none; }
.sb-stat:focus { outline: none; }
.sb-stat:focus-visible { outline: 2px solid var(--ll-brand-blue, #1E6EB8); outline-offset: 6px; border-radius: 8px; }

.sb-number {
  display: inline-flex;
  align-items: baseline;
  font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Segoe UI', sans-serif;
  font-size: 28px;
  font-weight: 600;
  letter-spacing: -.045em;
  line-height: 1;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  color: var(--ll-brand-base, #13233A);
}
.sb-prefix { font-size: .56em; font-weight: 600; margin-right: 1px; color: var(--ll-muted, #64748B); }
.sb-suffix { font-size: .5em; font-weight: 600; margin-left: 2px; color: var(--ll-muted, #64748B); }
.sb-digits { display: inline-grid; justify-items: start; }
.sb-sizer, .sb-live { grid-area: 1 / 1; }
.sb-sizer { visibility: hidden; }
.sb-sr { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }

.sb-visual { position: relative; display: flex; flex: none; flex-direction: column; justify-content: flex-end; height: 70px; margin: auto 0 0; padding-top: 18px; }
.sb-visual > * { position: relative; height: 100%; }
.sb-visual > .sb-bars.is-dense { height: 56%; }
.sb-trend { margin-right: 3px; }
.sb-draw { display: block; width: 100%; height: 100%; overflow: visible; }
.sb-area { fill: var(--soft); opacity: .24; transition: fill .3s ease; }
.sb-line { fill: none; stroke: var(--ink); stroke-width: 1.5; stroke-linejoin: round; stroke-linecap: round; transition: stroke .3s ease; }
.sb-end-dot {
  position: absolute; width: 7px; height: 7px; margin: -3.5px 0 0 -3.5px; border-radius: 50%;
  background: var(--ink); box-shadow: 0 0 0 2px rgba(255, 255, 255, .9);
  transition: background-color .3s ease, opacity .32s ease, transform .5s cubic-bezier(.34, 1.56, .64, 1);
}
.sb-bars { display: flex; align-items: flex-end; gap: 3px; }
.sb-bars.is-dense { gap: 0; }
.sb-bars.is-dense .sb-bar { border-radius: 0; clip-path: inset(0 22% 0 22%); }
.sb-bar {
  flex: 1 1 0; height: 100%; border-radius: 1px;
  background: var(--soft);
  transform: scaleY(var(--h));
  transform-origin: bottom;
  transition: background-color .3s ease, transform .56s cubic-bezier(.22, 1, .36, 1);
}
.sb-bar.is-ink { background: var(--ink); }

/* 未进入视口：图表收拢，进入后按序展开（与上游一致） */
.sb:not([data-in-view]) .sb-draw { clip-path: inset(0 100% 0 0); }
.sb:not([data-in-view]) .sb-bar { transform: scaleY(0); }
.sb:not([data-in-view]) .sb-end-dot { opacity: 0; transform: scale(.3); }
.sb[data-in-view] .sb-bar { transition-delay: 0ms, calc(var(--j, 0) * 420ms); }
.sb[data-in-view] .sb-end-dot { transition-delay: 0ms, 1s, 1s; }

/* divided：发丝分隔线 + 逐条描线 */
.sb[data-layout='divided'] .sb-stats { column-gap: 0; row-gap: 0; }
.sb[data-layout='divided'] .sb-stat { padding: 26px 22px 20px; }
.sb[data-layout='divided'] .sb-stat:first-child { padding-left: 0; }
.sb[data-layout='divided'] .sb-stat:last-child { padding-right: 0; }
.sb[data-layout='divided'] .sb-stat:not(:first-child)::before {
  content: ''; position: absolute; top: 0; bottom: 0; left: 0; width: 1px;
  background: rgba(19, 35, 58, .08);
  transform: scaleY(0); transform-origin: top;
  transition: transform .7s cubic-bezier(.22, 1, .36, 1);
  transition-delay: calc(var(--i) * 90ms + .24s);
}
.sb[data-layout='divided'][data-in-view] .sb-stat::before { transform: scaleY(1); }

@media (prefers-reduced-motion: reduce) {
  .sb-stat, .sb-bar, .sb-end-dot { transition: none; }
  .sb:not([data-in-view]) .sb-draw { clip-path: none; }
}

@container stats (max-width: 620px) {
  .sb-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 22px; row-gap: 30px; }
  .sb[data-layout='divided'] .sb-stat { padding: 20px 0 16px; }
  .sb[data-layout='divided'] .sb-stat:not(:first-child)::before { display: none; }
  .sb-number { font-size: 26px; }
  .sb-visual { height: 64px; padding-top: 12px; }
}
@container stats (max-width: 300px) {
  .sb-stats { grid-template-columns: 1fr; }
}
</style>