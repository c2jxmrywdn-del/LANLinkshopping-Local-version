<template>
  <div class="pkc">
    <div class="pkc-head">
      <div class="pkc-legend">
        <span class="lg"><i class="sw up"></i>涨</span>
        <span class="lg"><i class="sw down"></i>跌</span>
        <span class="lg"><i class="ln ma5"></i>MA5</span>
        <span class="lg"><i class="ln ma10"></i>MA10</span>
        <span class="lg"><i class="bar"></i>成交量（估算）</span>
      </div>
      <span v-if="candles.length" class="pkc-count">{{ candles.length }} 个交易日</span>
    </div>

    <div v-if="active" class="pkc-strip">
      <span class="pkc-date">{{ active.day }}</span>
      <span><em>开</em><b>{{ fmt(active.open) }}</b></span>
      <span><em>高</em><b class="hi">{{ fmt(active.high) }}</b></span>
      <span><em>低</em><b class="lo">{{ fmt(active.low) }}</b></span>
      <span><em>收</em><b>{{ fmt(active.close) }}</b></span>
      <span class="pkc-chg" :class="tone(active)">
        {{ signed(active.changePct) }}%
      </span>
      <span class="pkc-vol"><em>量</em><b>{{ volumeText(active.volume) }}</b><i>估算</i></span>
    </div>

    <div ref="host" class="pkc-canvas" @mouseleave="hover = null">
      <svg :viewBox="`0 0 ${W} ${H}`" class="pkc-svg"
           role="img" aria-label="商品价格 K 线与成交量"
           @mousemove="onMove" @touchmove.prevent="onTouch">
        <defs>
          <linearGradient id="pkc-volume-up" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#9B2D20" stop-opacity=".78" />
            <stop offset="100%" stop-color="#9B2D20" stop-opacity=".3" />
          </linearGradient>
          <linearGradient id="pkc-volume-down" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#2E7D5B" stop-opacity=".78" />
            <stop offset="100%" stop-color="#2E7D5B" stop-opacity=".3" />
          </linearGradient>
        </defs>

        <!-- 价格网格 -->
        <g class="grid">
          <line v-for="t in ticks" :key="'g' + t.v" :x1="pad.l" :x2="W - pad.r" :y1="yPrice(t.v)" :y2="yPrice(t.v)" />
          <text v-for="t in ticks" :key="'l' + t.v" :x="pad.l - 8" :y="yPrice(t.v) + 3.5" text-anchor="end">{{ t.label }}</text>
        </g>

        <!-- 区间最高 / 最低参考线 -->
        <g v-if="hi !== lo" class="extremes">
          <line :x1="pad.l" :x2="W - pad.r" :y1="yPrice(hi)" :y2="yPrice(hi)" class="hi" />
          <text :x="W - pad.r + 6" :y="yPrice(hi) + 3.5">高 ¥{{ fmt(hi) }}</text>
          <line :x1="pad.l" :x2="W - pad.r" :y1="yPrice(lo)" :y2="yPrice(lo)" class="lo" />
          <text :x="W - pad.r + 6" :y="yPrice(lo) + 3.5">低 ¥{{ fmt(lo) }}</text>
        </g>

        <!-- 蜡烛（进入视口逐根落下） -->
        <g class="candles">
          <g v-for="(c, i) in candles" :key="c.day" :class="['candle', tone(c), { on: hover === i }]" :style="{ '--ci': Math.min(i, 40) }">
            <line :x1="cx(i)" :x2="cx(i)" :y1="yPrice(c.high)" :y2="yPrice(c.low)" class="wick" />
            <rect :x="cx(i) - bodyW / 2"
                  :y="Math.min(yPrice(c.open), yPrice(c.close))"
                  :width="bodyW"
                  :height="Math.max(1, Math.abs(yPrice(c.open) - yPrice(c.close)))"
                  class="body" rx="0.5" />
          </g>
        </g>

        <!-- 均线（路径描线展开） -->
        <path v-if="ma5Path" :d="ma5Path" class="ma ma5" pathLength="1" />
        <path v-if="ma10Path" :d="ma10Path" class="ma ma10" pathLength="1" />

        <!-- 成交量 -->
        <g class="volume">
          <line :x1="pad.l" :x2="W - pad.r" :y1="volBottom" :y2="volBottom" class="base" />
          <text :x="pad.l - 8" :y="volTop + 9" text-anchor="end" class="vol-label">量</text>
          <rect v-for="(c, i) in candles" :key="'v' + c.day"
                :x="cx(i) - bodyW / 2"
                :y="yVol(c.volume)"
                :width="bodyW"
                :height="Math.max(0.8, volBottom - yVol(c.volume))"
                :class="['vol-bar', tone(c)]" rx="0.5"
                :style="{ '--vi': Math.min(i, 40) }" />
        </g>

        <!-- 悬停十字线 -->
        <line v-if="hover != null && candles[hover]" :x1="cx(hover)" :x2="cx(hover)" :y1="pad.t" :y2="volBottom" class="crosshair" />
        <circle v-if="hover != null && candles[hover]" :cx="cx(hover)" :cy="yPrice(candles[hover].close)" r="3.2" class="focus-dot" />

        <!-- 日期轴 -->
        <g class="x-axis">
          <text v-for="i in xTicks" :key="'x' + i" :x="cx(i)" :y="H - 6" text-anchor="middle">{{ shortDay(candles[i]?.day) }}</text>
        </g>
      </svg>

      <div v-if="!candles.length" class="pkc-empty">暂无 K 线数据</div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  ohlc: { type: Array, default: () => [] },
  height: { type: Number, default: 360 }
})

// 以容器实际像素宽度作为 SVG 坐标系宽度：图内文字/蜡烛不会在窄屏被等比压扁。
const W = ref(900)
const H = computed(() => props.height)
const pad = { l: 52, r: 66, t: 14, b: 20 }
const hover = ref(null)
const host = ref(null)
let ro = null

onMounted(() => {
  if (host.value && typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect?.width
      if (width && width > 120) W.value = Math.round(width)
    })
    ro.observe(host.value)
  }
})
onBeforeUnmount(() => {
  if (ro) ro.disconnect()
})

const num = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

watch(() => props.ohlc, () => { hover.value = null })

const candles = computed(() => (props.ohlc || []).map((r) => ({
  day: String(r.day || '').slice(0, 10),
  open: num(r.open),
  high: num(r.high),
  low: num(r.low),
  close: num(r.close),
  volume: Math.max(0, Number(r.volume) || 0),
  changePct: Number(r.changePct) || 0
})).filter((c) => c.day && [c.open, c.high, c.low, c.close].every((v) => v != null)))

const plotW = computed(() => W.value - pad.l - pad.r)
const plotH = computed(() => H.value - pad.t - pad.b)
const gap = computed(() => Math.max(10, plotH.value * 0.055))
const volH = computed(() => plotH.value * 0.22)
const priceH = computed(() => plotH.value - volH.value - gap.value)
const priceBottom = computed(() => pad.t + priceH.value)
const volTop = computed(() => priceBottom.value + gap.value)
const volBottom = computed(() => pad.t + plotH.value)
const slot = computed(() => plotW.value / Math.max(candles.value.length, 1))
const bodyW = computed(() => Math.min(14, Math.max(2, slot.value * 0.62)))

const hi = computed(() => candles.value.length ? Math.max(...candles.value.map(c => c.high)) : 0)
const lo = computed(() => candles.value.length ? Math.min(...candles.value.map(c => c.low)) : 0)
const minY = computed(() => {
  if (!candles.value.length) return 0
  const span = hi.value - lo.value || Math.max(hi.value * 0.02, 1)
  return lo.value - span * 0.06
})
const maxY = computed(() => {
  if (!candles.value.length) return 1
  const span = hi.value - lo.value || Math.max(hi.value * 0.02, 1)
  return hi.value + span * 0.06
})
const maxVol = computed(() => candles.value.length ? Math.max(...candles.value.map(c => c.volume), 1) : 1)

const cx = (i) => pad.l + (i + 0.5) * slot.value
const yPrice = (v) => pad.t + (maxY.value - v) / (maxY.value - minY.value || 1) * priceH.value
const yVol = (v) => volBottom.value - (v / maxVol.value) * volH.value

const tone = (c) => c.close > c.open ? 'up' : c.close < c.open ? 'down' : c.changePct >= 0 ? 'up' : 'down'
const signed = (v) => (Number(v) > 0 ? '+' : '') + Number(v || 0).toFixed(2)
const fmt = (v) => Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })
const shortDay = (d) => d ? d.slice(5) : ''
const volumeText = (v) => v >= 10000 ? (v / 10000).toFixed(1) + '万' : Math.round(v).toLocaleString('zh-CN')

const activeIndex = computed(() => hover.value != null ? hover.value : candles.value.length - 1)
const active = computed(() => candles.value[activeIndex.value] || null)

const maOf = (n) => candles.value.map((c, i) => {
  if (i < n - 1) return null
  let sum = 0
  for (let j = i - n + 1; j <= i; j++) sum += candles.value[j].close
  return sum / n
})
const pathOf = (arr) => {
  let d = ''
  let started = false
  arr.forEach((v, i) => {
    if (v == null) return
    d += (started ? 'L' : 'M') + cx(i).toFixed(1) + ' ' + yPrice(v).toFixed(1) + ' '
    started = true
  })
  return d.trim()
}
const ma5Path = computed(() => pathOf(maOf(5)))
const ma10Path = computed(() => pathOf(maOf(10)))

const ticks = computed(() => {
  const span = maxY.value - minY.value || 1
  return [0, 1, 2, 3, 4].map((i) => {
    const v = maxY.value - span * i / 4
    return { v, label: v >= 10000 ? (v / 1000).toFixed(1) + 'k' : v.toFixed(v < 20 ? 1 : 0) }
  })
})

const xTicks = computed(() => {
  const n = candles.value.length
  if (n <= 1) return n ? [0] : []
  const intervals = W.value < 560 ? 2 : 5
  const step = Math.max(1, Math.floor((n - 1) / intervals))
  const out = []
  for (let i = 0; i < n; i += step) out.push(i)
  if (out[out.length - 1] !== n - 1) {
    // 末尾两天挨得太近会导致日期标签重叠：用最后一天替换掉倒数第二个刻度。
    if (n - 1 - out[out.length - 1] < Math.max(2, step / 2)) out[out.length - 1] = n - 1
    else out.push(n - 1)
  }
  return out.slice(0, intervals + 2)
})

function onMove(e) {
  const n = candles.value.length
  if (!n) return
  const rect = e.currentTarget.getBoundingClientRect()
  if (!rect.width) return
  const svgX = (e.clientX - rect.left) / rect.width * W.value
  const i = Math.floor((svgX - pad.l) / slot.value)
  hover.value = Math.max(0, Math.min(n - 1, i))
}
function onTouch(e) {
  const t = e.touches?.[0]
  if (t) onMove({ currentTarget: e.currentTarget, clientX: t.clientX })
}
</script>

<style scoped>
.pkc { width: 100%; }
.pkc-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; flex-wrap: wrap; }
.pkc-legend { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; font-size: 10.5px; letter-spacing: .02em; color: var(--ll-muted, #64748B); }
.pkc-legend .lg { display: inline-flex; align-items: center; gap: 5px; }
.pkc-legend .sw { width: 8px; height: 8px; border-radius: 2px; display: inline-block; }
.pkc-legend .sw.up { background: #A33A2B; }
.pkc-legend .sw.down { background: #2F7A68; }
.pkc-legend .ln { width: 14px; height: 1.5px; border-radius: 2px; display: inline-block; }
.pkc-legend .ln.ma5 { background: #C8A45C; }
.pkc-legend .ln.ma10 { background: #1E6EB8; }
.pkc-legend .bar { width: 8px; height: 9px; border-radius: 2px; display: inline-block; background: linear-gradient(180deg, rgba(155,45,32,.6), rgba(155,45,32,.2)); }
.pkc-count { font-size: 10.5px; letter-spacing: .04em; color: var(--ll-muted, #94A3B8); }
.pkc-strip {
  display: flex; align-items: baseline; gap: 15px; flex-wrap: wrap;
  padding: 9px 13px; margin-bottom: 8px; border-radius: 12px;
  background: rgba(255, 255, 255, .72);
  border: 1px solid rgba(19, 35, 58, .065);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .9), 0 6px 18px -16px rgba(19, 35, 58, .4);
  backdrop-filter: blur(12px) saturate(1.2);
  font-size: 11px; color: var(--ll-muted, #64748B);
  font-variant-numeric: tabular-nums;
}
.pkc-strip em { font-style: normal; margin-right: 4px; opacity: .58; }
.pkc-strip b { color: var(--ll-ink, #0F172A); font-weight: 620; }
.pkc-strip b.hi { color: #A33A2B; }
.pkc-strip b.lo { color: #2F7A68; }
.pkc-date { color: var(--ll-brand-base, #13233A); font-weight: 700; letter-spacing: .02em; }
.pkc-chg { font-weight: 700; }
.pkc-chg.up { color: #A33A2B; }
.pkc-chg.down { color: #2F7A68; }
.pkc-vol i { font-style: normal; margin-left: 3px; font-size: 8.5px; color: var(--ll-muted, #94A3B8); }
.pkc-canvas { position: relative; width: 100%; }
.pkc-svg { display: block; width: 100%; height: auto; overflow: visible; }
.grid line { stroke: rgba(19, 35, 58, .05); stroke-width: 1; }
.grid text { fill: #98A2B3; font-size: 9.5px; letter-spacing: .02em; }
.x-axis text { fill: #98A2B3; font-size: 9.5px; letter-spacing: .02em; }
.extremes line { stroke-width: 1; stroke-dasharray: 3 5; opacity: .75; }
.extremes line.hi { stroke: rgba(163, 58, 43, .4); }
.extremes line.lo { stroke: rgba(47, 122, 104, .4); }
.extremes text { font-size: 9px; fill: #80601F; letter-spacing: .02em; }
.candle .wick { stroke-width: 1; }
.candle.up .wick, .candle.up .body { stroke: #A33A2B; fill: #A33A2B; }
.candle.down .wick, .candle.down .body { stroke: #2F7A68; fill: #2F7A68; }
.candle .body { stroke-width: .6; opacity: .94; }
.candle { animation: pkc-candle .55s cubic-bezier(.22, 1, .36, 1) both; animation-delay: calc(var(--ci, 0) * 12ms); }
.candle.on .body { filter: drop-shadow(0 0 5px rgba(19, 35, 58, .28)); }
.ma { fill: none; stroke-width: 1.4; stroke-linejoin: round; stroke-linecap: round; stroke-dasharray: 1; stroke-dashoffset: 1; animation: pkc-draw 1.15s .3s cubic-bezier(.22, 1, .36, 1) forwards; }
.ma5 { stroke: #C8A45C; }
.ma10 { stroke: #1E6EB8; opacity: .9; animation-delay: .42s; }
.volume .base { stroke: rgba(19, 35, 58, .08); }
.volume .vol-label { fill: #98A2B3; font-size: 9px; letter-spacing: .04em; }
.volume rect.up { fill: url(#pkc-volume-up); }
.volume rect.down { fill: url(#pkc-volume-down); }
.vol-bar { transform-box: fill-box; transform-origin: bottom; animation: pkc-vol .55s cubic-bezier(.22, 1, .36, 1) both; animation-delay: calc(120ms + var(--vi, 0) * 10ms); }
.crosshair { stroke: rgba(19, 35, 58, .2); stroke-width: 1; stroke-dasharray: 3 4; }
.focus-dot { fill: #13233A; stroke: #fff; stroke-width: 1.4; }
.pkc-empty { position: absolute; inset: 0; display: grid; place-items: center; color: #94A3B8; font-size: 13px; }

@keyframes pkc-candle { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
@keyframes pkc-draw { to { stroke-dashoffset: 0; } }
@keyframes pkc-vol { from { transform: scaleY(0); opacity: .25; } to { transform: scaleY(1); opacity: 1; } }
@media (max-width: 640px) {
  .pkc-strip { gap: 9px; padding: 8px 11px; }
  .pkc-legend { gap: 9px; }
}
@media (prefers-reduced-motion: reduce) {
  .candle, .vol-bar, .ma { animation: none; }
  .ma { stroke-dashoffset: 0; }
}
</style>
