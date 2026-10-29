<template>
  <div class="pc-chart" :style="{ height: height + 'px' }">
    <svg :viewBox="`0 0 ${W} ${H}`" preserveAspectRatio="none" class="pc-svg">
      <defs>
        <linearGradient v-for="(s, i) in series" :key="'g' + i" :id="uid + '-grad-' + i" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" :stop-color="s.color" stop-opacity="0.22" />
          <stop offset="100%" :stop-color="s.color" stop-opacity="0" />
        </linearGradient>
      </defs>
      <!-- 横向网格 + y 轴刻度 -->
      <g v-for="(g, i) in gridLines" :key="'g' + i">
        <line :x1="PAD_L" :x2="W - PAD_R" :y1="g.y" :y2="g.y" stroke="currentColor" stroke-opacity=".08" stroke-dasharray="3 4" />
        <text :x="PAD_L - 6" :y="g.y + 3" text-anchor="end" class="pc-tick">{{ g.label }}</text>
      </g>
      <!-- 序列：面积 + 折线 + 数据点 -->
      <template v-for="(s, i) in visible" :key="'s' + i">
        <polygon v-if="s.fill !== false && s.points.length > 1"
                 :points="s.areaPoints" :fill="`url(#${uid}-grad-${i})`" />
        <polyline :points="s.points" fill="none" :stroke="s.color"
                  :stroke-width="s.width || 2" stroke-linejoin="round" stroke-linecap="round"
                  :stroke-dasharray="s.dashed ? '5 4' : undefined" />
        <circle v-for="(p, j) in s.circles" :key="'c' + j" :cx="p.x" :cy="p.y" :r="s.width === 1.6 ? 2 : 2.6"
                :fill="varCard" :stroke="s.color" :stroke-width="1.6">
          <title>{{ s.name }} {{ p.label }}：{{ p.value }}</title>
        </circle>
      </template>
    </svg>
    <!-- 图例 -->
    <div class="pc-legend">
      <span v-for="(s, i) in visible" :key="i" class="pc-legend-item">
        <i :style="{ background: s.color }"></i>{{ s.name }}
      </span>
    </div>
  </div>
</template>

<script setup>
/**
 * PriceChart — 价格走势 SVG 双折线图（商户中心价格监控/商品详情共用）。
 * 零依赖：viewBox 自适应（preserveAspectRatio:none 拉伸），面积渐变 + 原生 title 悬停数值。
 * series: [{ name, color, data: [{label, value}], dashed?, fill?, width? }]
 */
import { computed, useId } from 'vue'

const props = defineProps({
  series: { type: Array, default: () => [] },
  height: { type: Number, default: 240 }
})

const uid = String(useId()).replace(/[^a-zA-Z0-9]/g, '')
const W = 640
const H = 260
const PAD_L = 52
const PAD_R = 14
const PAD_T = 12
const PAD_B = 18

const varCard = computed(() => 'var(--mc-card, #fff)')

// 值域（全部序列统一归一化，保证可比）
const domain = computed(() => {
  const all = props.series.flatMap(s => s.data.map(d => Number(d.value) || 0)).filter(v => v > 0)
  if (!all.length) return { min: 0, max: 1 }
  const min = Math.min(...all)
  const max = Math.max(...all)
  const pad = (max - min) * 0.12 || max * 0.1 || 1
  return { min: Math.max(0, min - pad), max: max + pad }
})

const fmt = (v) => Number(v) >= 1000 ? Number(v).toLocaleString('zh-CN') : String(Math.round(Number(v) * 100) / 100)

const xAt = (idx, len) => PAD_L + (len <= 1 ? (W - PAD_L - PAD_R) / 2 : idx * (W - PAD_L - PAD_R) / (len - 1))
const yAt = (v) => {
  const { min, max } = domain.value
  return PAD_T + (1 - (v - min) / (max - min || 1)) * (H - PAD_T - PAD_B)
}

const visible = computed(() => props.series
  .filter(s => s.data && s.data.length)
  .map(s => {
    const pts = s.data.map((d, i) => ({ x: xAt(i, s.data.length), y: yAt(Number(d.value)), label: d.label, value: fmt(d.value) }))
    const first = pts[0], last = pts[pts.length - 1]
    return {
      ...s,
      points: pts.map(p => p.x + ',' + p.y).join(' '),
      areaPoints: `${first.x},${H - PAD_B} ${pts.map(p => p.x + ',' + p.y).join(' ')} ${last.x},${H - PAD_B}`,
      circles: pts
    }
  }))

const gridLines = computed(() => {
  const { min, max } = domain.value
  return [0, 0.25, 0.5, 0.75, 1].map(t => ({
    y: PAD_T + t * (H - PAD_T - PAD_B),
    label: fmt(max - t * (max - min))
  }))
})
</script>

<style scoped>
.pc-chart { position: relative; width: 100%; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }
.pc-svg { width: 100%; height: 100%; display: block; }
.pc-tick { font-size: 10px; fill: currentColor; opacity: .75; }
.pc-legend { display: flex; gap: 16px; flex-wrap: wrap; margin-top: 6px; padding-left: 8px; }
.pc-legend-item { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; }
.pc-legend-item i { width: 14px; height: 3px; border-radius: 2px; display: inline-block; }
</style>
