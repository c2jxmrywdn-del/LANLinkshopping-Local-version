<template>
  <span ref="root" class="nt" :class="$attrs.class" :style="$attrs.style">{{ startValue }}</span>
</template>

<script setup>
/**
 * NumberTicker — Magic UI number-ticker 的 Vue 移植（上游 magicuidesign/magicui，MIT；
 * 原始源码 docs/ui-reference/magicui/number-ticker.tsx）。
 * 上游用 motion useSpring(damping 60 / stiffness 100) + useInView(once)；
 * 此处用等价的 rAF 弹簧（手写 delta-time），进入视口后经 delay 延迟把值弹到目标，
 * 每帧直写 textContent（不走 Vue 响应式，无重渲染），Intl.NumberFormat 千分位格式化。
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  value: { type: Number, required: true },
  startValue: { type: Number, default: 0 },
  /** up: 从 startValue 滚到 value；down: 从 value 滚到 startValue */
  direction: { type: String, default: 'up' },
  delay: { type: Number, default: 0 },
  decimalPlaces: { type: Number, default: 0 },
  /** 弹簧刚度：默认沿用上游 100；大金额建议 200-260，约 0.6-1.2s 收敛 */
  stiffness: { type: Number, default: 100 },
  /** 弹簧阻尼：默认沿用上游 60；与 stiffness 220 搭配建议 28-32 */
  damping: { type: Number, default: 60 }
})

const root = ref(null)
// 弹簧参数与上游一致：damping 60 / stiffness 100（近临界阻尼，平滑收敛无超调）
const spring = { x: props.direction === 'down' ? props.value : props.startValue, v: 0, target: springTarget() }
let raf = 0
let last = 0
let io = null
let timer = null

function springTarget() {
  return props.direction === 'down' ? props.startValue : props.value
}
function fmt(n) {
  return new Intl.NumberFormat('en-US', {
    minimumFractionDigits: props.decimalPlaces,
    maximumFractionDigits: props.decimalPlaces
  }).format(Number(n.toFixed(props.decimalPlaces)))
}
function paint() {
  if (root.value) root.value.textContent = fmt(spring.x)
}
function frame(now) {
  if (!last) last = now
  const dt = Math.min(0.05, Math.max(0, (now - last) / 1000))
  last = now
  const a = props.stiffness * (spring.target - spring.x) - props.damping * spring.v
  spring.v += a * dt
  spring.x += spring.v * dt
  paint()
  raf = requestAnimationFrame(frame)
}
function activate() {
  timer = setTimeout(() => {
    spring.target = springTarget()
    spring.x = props.direction === 'down' ? props.value : props.startValue
    cancelAnimationFrame(raf)
    last = 0
    raf = requestAnimationFrame(frame)
  }, props.delay * 1000)
}

watch(() => props.value, () => {
  // 目标值变化（切商品/轮询刷新）：弹簧持续追新值；
  // 不能只在 IntersectionObserver 仍存活时更新，否则首次进场后目标会被锁死。
  spring.target = springTarget()
})

onMounted(() => {
  paint()
  io = new IntersectionObserver(([entry]) => {
    if (entry.isIntersecting) {
      activate()
      io.disconnect()
      io = null
    }
  }, { threshold: 0 })
  if (root.value) io.observe(root.value)
})
onBeforeUnmount(() => {
  cancelAnimationFrame(raf)
  clearTimeout(timer)
  io?.disconnect()
})
</script>

<style scoped>
.nt { display: inline-block; letter-spacing: .02em; font-variant-numeric: tabular-nums; }
</style>
