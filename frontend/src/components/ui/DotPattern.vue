<template>
  <svg ref="svg" aria-hidden="true" class="dot-pattern" :class="$attrs.class" :style="$attrs.style">
    <defs>
      <radialGradient v-if="glow" :id="uid + '-gradient'">
        <stop offset="0%" stop-color="currentColor" stop-opacity="1" />
        <stop offset="100%" stop-color="currentColor" stop-opacity="0" />
      </radialGradient>
    </defs>
    <circle v-for="(d, i) in dots" :key="i" :cx="d.x" :cy="d.y" :r="cr"
            :fill="glow ? `url(#${uid}-gradient)` : 'currentColor'"
            :style="glow ? { animationDelay: d.delay + 's', animationDuration: d.duration + 's' } : null"
            :class="{ pulse: glow }" />
  </svg>
</template>

<script setup>
/**
 * DotPattern — Magic UI dot-pattern 的 Vue 移植（上游 magicuidesign/magicui，MIT；
 * 原始源码 docs/ui-reference/magicui/dot-pattern.tsx）。
 * SVG pattern 随容器尺寸铺点；glow=true 时逐点随机相位呼吸（CSS 动画，
 * transform-box: fill-box 保证 scale 以自身为中心）。颜色跟随 currentColor。
 */
import { computed, onBeforeUnmount, onMounted, ref, useId } from 'vue'

const props = defineProps({
  width: { type: Number, default: 16 },
  height: { type: Number, default: 16 },
  x: { type: Number, default: 0 },
  y: { type: Number, default: 0 },
  cx: { type: Number, default: 1 },
  cy: { type: Number, default: 1 },
  cr: { type: Number, default: 1 },
  glow: { type: Boolean, default: false }
})

const uid = String(useId()).replace(/[^a-zA-Z0-9]/g, '')
const svg = ref(null)
const box = ref({ w: 0, h: 0 })
let ro = null

const dots = computed(() => {
  const cols = Math.ceil(box.value.w / props.width)
  const rows = Math.ceil(box.value.h / props.height)
  if (cols * rows > 2500) return [] // 超大容器保护
  const out = []
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) {
      out.push({
        x: c * props.width + props.cx + props.x,
        y: r * props.height + props.cy + props.y,
        delay: Math.random() * 5,
        duration: Math.random() * 3 + 2
      })
    }
  }
  return out
})

onMounted(() => {
  const update = () => {
    if (svg.value) {
      const r = svg.value.getBoundingClientRect()
      box.value = { w: r.width, h: r.height }
    }
  }
  update()
  ro = new ResizeObserver(update)
  ro.observe(svg.value)
})
onBeforeUnmount(() => ro?.disconnect())
</script>

<style scoped>
.dot-pattern { position: absolute; inset: 0; width: 100%; height: 100%;
  pointer-events: none; color: rgba(100, 116, 139, .4); }
.dot-pattern .pulse { opacity: .4; transform-box: fill-box; transform-origin: center;
  animation: dot-pulse ease-in-out infinite; }
@keyframes dot-pulse {
  0%, 100% { opacity: .4; transform: scale(1); }
  50% { opacity: 1; transform: scale(1.5); }
}
@media (prefers-reduced-motion: reduce) { .dot-pattern .pulse { animation: none; } }
</style>
