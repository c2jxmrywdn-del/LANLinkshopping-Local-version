<template>
  <div class="ptc">
    <div class="ptc-head">
      <div class="ptc-legend">
        <span class="dot price"></span><span>商品价</span>
        <span class="dot bench"></span><span>行业基准（归一）</span>
      </div>
      <div v-if="lastPrice != null" class="ptc-delta" :class="deltaClass">
        {{ delta >= 0 ? '+' : '' }}{{ delta.toFixed(2) }}%
        <small>区间</small>
      </div>
    </div>

    <div class="ptc-canvas" @mouseleave="hover = null">
      <svg :viewBox="`0 0 ${W} ${H}`" class="ptc-svg" preserveAspectRatio="none"
           @mousemove="onMove" @touchmove.prevent="onTouch">
        <defs>
          <linearGradient id="ptc-area" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="rgba(19,35,58,.22)" />
            <stop offset="100%" stop-color="rgba(19,35,58,0)" />
          </linearGradient>
          <linearGradient id="ptc-line" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stop-color="#13233A" />
            <stop offset="100%" stop-color="#1E6EB8" />
          </linearGradient>
        </defs>

        <g class="grid">
          <line v-for="t in ticks" :key="'g'+t.v" :x1="pad.l" :x2="W - pad.r" :y1="y(t.v)" :y2="y(t.v)" />
          <text v-for="t in ticks" :key="'l'+t.v" :x="pad.l - 8" :y="y(t.v) + 4" text-anchor="end">{{ t.label }}</text>
        </g>

        <path v-if="areaPath" :d="areaPath" fill="url(#ptc-area)" />
        <path v-if="benchPath" :d="benchPath" class="line bench" />
        <path v-if="pricePath" :d="pricePath" class="line price" />

        <g v-if="hover && points[hover]" class="focus">
          <line :x1="x(hover)" :x2="x(hover)" :y1="pad.t" :y2="H - pad.b" />
          <circle v-if="points[hover].price != null" :cx="x(hover)" :cy="y(points[hover].price)" r="4" class="dot-price" />
          <circle v-if="points[hover].bench != null" :cx="x(hover)" :cy="y(points[hover].benchPlot)" r="3.5" class="dot-bench" />
        </g>

        <g class="x-axis">
          <text v-for="i in xTicks" :key="'x'+i" :x="x(i)" :y="H - 8" text-anchor="middle">{{ shortDay(points[i]?.day) }}</text>
        </g>
      </svg>

      <div v-if="hover && points[hover]" class="ptc-tip"
           :style="{ left: tipLeft + '%', top: tipTop + '%' }">
        <b>{{ points[hover].day }}</b>
        <div v-if="points[hover].price != null"><span class="k price">商品价</span><span class="v">¥{{ fmt(points[hover].price) }}</span></div>
        <div v-if="points[hover].bench != null"><span class="k bench">行业均价</span><span class="v">¥{{ fmt(points[hover].bench) }}</span></div>
        <div v-if="points[hover].price != null && points[hover].benchPlot != null" class="diff">
          相对行业 <b :class="{ up: points[hover].price >= points[hover].benchPlot }">
            {{ (((points[hover].price - points[hover].benchPlot) / points[hover].benchPlot) * 100).toFixed(2) }}%
          </b>
        </div>
      </div>

      <div v-if="!points.length" class="ptc-empty">暂无价格历史数据</div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  series: { type: Array, default: () => [] },
  benchmark: { type: Array, default: () => [] },
  height: { type: Number, default: 300 }
})

const W = 900
const H = computed(() => props.height)
const pad = { l: 54, r: 18, t: 18, b: 30 }
const hover = ref(null)

const dayOf = (d) => {
  if (d == null) return ''
  if (Array.isArray(d)) return `${d[0]}-${String(d[1]).padStart(2, '0')}-${String(d[2]).padStart(2, '0')}`
  return String(d).slice(0, 10)
}

const points = computed(() => {
  const map = new Map()
  for (const r of props.series || []) {
    const day = dayOf(r.day)
    const v = Number(r.price)
    if (!day || !Number.isFinite(v)) continue
    const p = map.get(day) || { day, price: null, bench: null }
    p.price = v
    map.set(day, p)
  }
  for (const r of props.benchmark || []) {
    const day = dayOf(r.day)
    const v = Number(r.benchmark)
    if (!day || !Number.isFinite(v)) continue
    const p = map.get(day) || { day, price: null, bench: null }
    p.bench = v
    map.set(day, p)
  }
  const arr = [...map.values()].sort((a, b) => a.day.localeCompare(b.day))
  // 行业均价与单品价格量级差异大，按商品首个价格归一后再画，保证双线可比
  const anchor = arr.find(p => p.price != null && p.bench != null)
  const scale = anchor && anchor.bench ? anchor.price / anchor.bench : 1
  for (const p of arr) p.benchPlot = p.bench == null ? null : p.bench * scale
  return arr
})

const values = computed(() => points.value.flatMap(p => [p.price, p.benchPlot]).filter(v => v != null))
const min = computed(() => {
  if (!values.value.length) return 0
  const m = Math.min(...values.value)
  return m - (Math.max(...values.value) - m) * 0.08 || m * 0.98
})
const max = computed(() => {
  if (!values.value.length) return 1
  const m = Math.max(...values.value)
  return m + (m - Math.min(...values.value)) * 0.08 || m * 1.02
})

const x = (i) => points.value.length <= 1 ? pad.l : pad.l + i * (W - pad.l - pad.r) / (points.value.length - 1)
const y = (v) => {
  const span = max.value - min.value || 1
  return pad.t + (max.value - v) / span * (H.value - pad.t - pad.b)
}

const linePath = (key) => {
  let d = ''
  let open = false
  points.value.forEach((p, i) => {
    if (p[key] == null) { open = false; return }
    d += (open ? 'L' : 'M') + x(i).toFixed(1) + ' ' + y(p[key]).toFixed(1) + ' '
    open = true
  })
  return d.trim()
}
const pricePath = computed(() => linePath('price'))
const benchPath = computed(() => linePath('benchPlot'))

const areaPath = computed(() => {
  if (!pricePath.value) return ''
  let first = -1, last = -1
  points.value.forEach((p, i) => {
    if (p.price != null) { if (first < 0) first = i; last = i }
  })
  if (first < 0) return ''
  const base = H.value - pad.b
  return pricePath.value
    + ` L${x(last).toFixed(1)} ${base} L${x(first).toFixed(1)} ${base} Z`
})

const delta = computed(() => {
  const prices = points.value.map(p => p.price).filter(v => v != null)
  if (prices.length < 2) return 0
  const a = prices[0], b = prices[prices.length - 1]
  return a === 0 ? 0 : (b - a) / a * 100
})
const lastPrice = computed(() => {
  const prices = points.value.map(p => p.price).filter(v => v != null)
  return prices.length ? prices[prices.length - 1] : null
})
const deltaClass = computed(() => delta.value > 0 ? 'up' : delta.value < 0 ? 'down' : 'flat')

const ticks = computed(() => {
  const span = max.value - min.value || 1
  return [0, 1, 2, 3, 4].map(i => {
    const v = max.value - span * i / 4
    return { v, label: v >= 1000 ? Math.round(v / 100) / 10 + 'k' : v.toFixed(v < 20 ? 1 : 0) }
  })
})

const xTicks = computed(() => {
  const n = points.value.length
  if (n <= 1) return n ? [0] : []
  const step = Math.max(1, Math.floor((n - 1) / 5))
  const out = []
  for (let i = 0; i < n; i += step) out.push(i)
  if (out[out.length - 1] !== n - 1) out.push(n - 1)
  return out.slice(0, 7)
})

const shortDay = (d) => d ? d.slice(5) : ''
const fmt = (v) => Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 2 })

const tipLeft = computed(() => hover.value == null ? 0 : Math.min(88, Math.max(4, x(hover.value) / W * 100)))
const tipTop = computed(() => {
  if (hover.value == null) return 0
  const p = points.value[hover.value]
  const vy = p.price != null ? y(p.price) : p.benchPlot != null ? y(p.benchPlot) : pad.t
  return Math.min(72, Math.max(2, vy / H.value * 100 - 6))
})

function onMove(e) {
  const rect = e.currentTarget.getBoundingClientRect()
  if (!rect.width) return
  const ratio = (e.clientX - rect.left) / rect.width
  const i = Math.round(ratio * (points.value.length - 1))
  hover.value = Math.min(points.value.length - 1, Math.max(0, i))
}
function onTouch(e) {
  const t = e.touches?.[0]
  if (t) onMove({ currentTarget: e.currentTarget, clientX: t.clientX })
}
</script>

<style scoped>
.ptc { width: 100%; }
.ptc-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 6px; }
.ptc-legend { display: flex; align-items: center; gap: 7px; font-size: 11.5px; color: var(--ll-muted, #6B6F76); }
.ptc-legend .dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.ptc-legend .dot.price { background: #13233A; }
.ptc-legend .dot.bench { background: #1E6EB8; margin-left: 8px; }
.ptc-delta { font-size: 12.5px; font-weight: 700; color: #64748B; }
.ptc-delta small { margin-left: 4px; font-size: 10px; font-weight: 500; color: #94A3B8; }
.ptc-delta.up { color: #B45309; }
.ptc-delta.down { color: #047857; }
.ptc-canvas { position: relative; width: 100%; }
.ptc-svg { display: block; width: 100%; height: auto; overflow: visible; }
.grid line { stroke: rgba(19, 35, 58, .08); stroke-width: 1; }
.grid text { fill: #94A3B8; font-size: 10px; }
.x-axis text { fill: #94A3B8; font-size: 10px; }
.line { fill: none; stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; }
.line.price { stroke: url(#ptc-line); }
.line.bench { stroke: #1E6EB8; stroke-width: 1.6; stroke-dasharray: 5 4; opacity: .8; }
.focus line { stroke: rgba(19, 35, 58, .25); stroke-dasharray: 3 3; }
.dot-price { fill: #13233A; stroke: #fff; stroke-width: 1.5; }
.dot-bench { fill: #1E6EB8; stroke: #fff; stroke-width: 1.2; }
.ptc-tip {
  position: absolute; transform: translate(-50%, -100%);
  min-width: 132px; padding: 9px 11px; border-radius: 12px;
  background: rgba(19, 35, 58, .92); color: #fff;
  box-shadow: 0 10px 26px rgba(19, 35, 58, .25);
  font-size: 11.5px; pointer-events: none; z-index: 3;
}
.ptc-tip b { display: block; margin-bottom: 4px; font-size: 11px; color: rgba(255,255,255,.65); font-weight: 600; }
.ptc-tip > div { display: flex; justify-content: space-between; gap: 12px; }
.ptc-tip .k { color: rgba(255,255,255,.65); }
.ptc-tip .v { font-weight: 700; }
.ptc-tip .diff { margin-top: 3px; border-top: 1px solid rgba(255,255,255,.15); padding-top: 3px; color: rgba(255,255,255,.75); }
.ptc-tip .diff b.up { color: #F6D38A; }
.ptc-tip .diff b:not(.up) { color: #6EE7B7; }
.ptc-empty { position: absolute; inset: 0; display: grid; place-items: center; color: #94A3B8; font-size: 13px; }
</style>
