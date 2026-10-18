<template>
  <div
    ref="rootRef"
    class="mb-badge"
    :class="[
      `mb-${tier}`,
      `mb-state-${state}`,
      `mb-size-${size}`,
      { 'mb-paused': overlayPaused, 'mb-bursting': bursting }
    ]"
    :style="{ '--mb-delay': delay + 'ms' }"
    :role="link ? 'link' : 'button'"
    :tabindex="0"
    :aria-label="`${eyebrow} ${title}（${stateText}），点击查看详情`"
    @pointerenter="onEnter"
    @pointermove="onMove"
    @pointerleave="onLeave"
    @pointercancel="onLeave"
    @click="activate"
    @keydown.enter="activate"
    @keydown.space.prevent="activate"
  >
    <!-- 3D 透视层：matrix3d 随指针位置实时更新（源码：perspective(700px) matrix3d(...)） -->
    <div class="mb-3d" :style="{ transform: `perspective(700px) matrix3d(${matrix})` }">
      <svg class="mb-svg" viewBox="0 0 260 54" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
        <defs>
          <!-- 彩虹层高斯模糊（源码 stdDeviation=3） -->
          <filter :id="`${uid}-blur`"><feGaussianBlur in="SourceGraphic" stdDeviation="3" /></filter>
          <!-- 全息层裁切到胶囊圆角内（源码 badgeMask，与源码同构） -->
          <mask :id="`${uid}-clip`"><rect width="260" height="54" fill="#fff" rx="10" /></mask>
          <!-- 等级徽记渐变 -->
          <linearGradient :id="`${uid}-mark`" x1="6" y1="4" x2="42" y2="44" gradientUnits="userSpaceOnUse">
            <stop class="mb-mark-0" />
            <stop class="mb-mark-1" offset="1" />
          </linearGradient>
        </defs>

        <!-- 1. 等级底色（源码 place 三色 → 会员五档配色） -->
        <rect class="mb-base" width="260" height="54" rx="10" />
        <!-- 2. 内嵌描边（源码 x=4 y=4 252×46 rx=8） -->
        <rect x="4" y="4" width="252" height="46" rx="8" fill="none" stroke="rgba(122,128,138,.5)" stroke-width="1" />

        <!-- 3. 眉题 + 标题（源码 y=20 / y=40，x 右移至 48 让位徽记） -->
        <text class="mb-eyebrow-text" x="48" y="20">{{ eyebrow }}</text>
        <text class="mb-title-text" x="48" y="41">{{ title }}</text>

        <!-- 4. 等级徽记（源码月桂花环 → 五档专属徽记） -->
        <g transform="translate(8,9) scale(0.72)">
          <circle cx="24" cy="24" r="21" :stroke="`url(#${uid}-mark)`" stroke-width="2.2" fill="rgba(255,255,255,.5)" />
          <circle class="mb-mark-line" cx="24" cy="24" r="17" fill="none" stroke-width=".9" />
          <path v-if="tier === 'regular'" :fill="markFill" fill-rule="evenodd" d="M15 30c-3-4-3-10 1-14 1 5 3 8 6 10-4-1-6 1-7 4Zm18 0c3-4 3-10-1-14-1 5-3 8-6 10 4-1 6 1 7 4Zm-9 3c-2 0-3.4-1.4-3.4-3.2 0-2.4 1.6-5.8 3.4-8.8 1.8 3 3.4 6.4 3.4 8.8 0 1.8-1.4 3.2-3.4 3.2Z" />
          <path v-else-if="tier === 'silver'" :fill="markFill" fill-rule="evenodd" d="M24 12l9 4v8c0 6-4 10.5-9 12-5-1.5-9-6-9-12v-8l9-4Zm0 6.5l-2.2 4.6-5 .7 3.6 3.5-.9 5 4.5-2.4 4.5 2.4-.9-5 3.6-3.5-5-.7L24 18.5Z" />
          <path v-else-if="tier === 'gold'" :fill="markFill" d="M12 30l2-12 7 6 3-9 3 9 7-6 2 12H12Zm1 3h22v3H13v-3Z" />
          <path v-else-if="tier === 'diamond'" :fill="markFill" fill-rule="evenodd" d="M16 14h16l6 8-14 14L10 22l6-8Zm1.8 3l-3.4 4.6h6l-2.6-4.6Zm5 0l2.6 4.6h-5.2l2.6-4.6Zm7.4 0l3.4 4.6h-6l2.6-4.6Z" />
          <path v-else :fill="markFill" d="M24 10l3.6 8.6 9.4.8-7.1 6.2 2.1 9.2-8-4.9-8 4.9 2.1-9.2-7.1-6.2 9.4-.8L24 10Z" />
        </g>

        <!-- 5. 全息彩虹层（源码核心：overlay 混合 + 模糊对角多边形，逐层错开 10°） -->
        <g class="mb-holo" :mask="`url(#${uid}-clip)`">
          <g
            v-for="(band, i) in BANDS"
            :key="band.angle"
            class="mb-band"
            :class="`mb-band-${i + 1}`"
            :style="bandStyle(band.angle)"
          >
            <polygon points="0,0 260,54 260,0 0,54" :fill="band.color" :filter="`url(#${uid}-blur)`" opacity="0.5" />
          </g>
        </g>
      </svg>
    </div>

    <span v-if="state === 'current'" class="mb-chip">当前</span>
    <span v-else-if="state === 'locked'" class="mb-chip mb-chip--lock" aria-hidden="true">🔒</span>
  </div>
</template>

<script setup>
// 会员勋章徽章 v3：对照 21st.dev award-badge（@shugar，MPL-2.0）原始源码忠实移植。
// 核心技术：260×54 SVG 胶囊 + 等级底色 + 10° 错位分布的模糊对角多边形彩虹层
//（mix-blend-mode: overlay + 圆角裁剪），静止时各层以 5s 周期 ±10° 连续摆动，
// 指针到徽章中心的距离驱动整组彩虹旋转；配合 matrix3d 多轴旋转与距离衰减缩放，
// 以及进/离场的「反向矩阵」弹簧编排。tier/state/size/delay/burst/link 为会员体系适配层。
import { useId, ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'

const props = defineProps({
  // 等级维度：regular/silver/gold/diamond；成就维度通用色：azure
  tier: { type: String, default: 'gold' },
  eyebrow: { type: String, default: 'LANLINK MEMBERSHIP' },
  title: { type: String, required: true },
  // locked 未解锁 / earned 已获得 / current 当前等级
  state: { type: String, default: 'earned' },
  size: { type: String, default: 'md' }, // sm | md | lg
  // 进场错峰延迟 ms（勋章墙逐枚入场）
  delay: { type: Number, default: 0 },
  // 获取/升级：挂载时额外播放一次彩虹旋绕
  burst: { type: Boolean, default: false },
  link: { type: String, default: '' }
})
const emit = defineEmits(['detail'])

const uid = useId().replace(/[:]/g, '')
const markFill = computed(() => `url(#${uid}-mark)`)
const router = useRouter()
const rootRef = ref(null)
const stateText = computed(() =>
  props.state === 'locked' ? '未解锁' : props.state === 'current' ? '当前等级' : '已获得'
)

/* ---------- 彩虹层：源码 10 层中去掉 2 层透明层（视觉等价，省 20% 模糊开销） ---------- */
const BANDS = [
  { angle: 0, color: 'hsl(358, 100%, 62%)' },
  { angle: 10, color: 'hsl(30, 100%, 50%)' },
  { angle: 20, color: 'hsl(60, 100%, 50%)' },
  { angle: 30, color: 'hsl(96, 100%, 50%)' },
  { angle: 40, color: 'hsl(233, 85%, 47%)' },
  { angle: 50, color: 'hsl(271, 85%, 47%)' },
  { angle: 60, color: 'hsl(300, 20%, 35%)' },
  { angle: 90, color: '#ffffff' }
]

/* ---------- matrix3d 弹簧编排（源码常量与公式保持一致） ---------- */
const IDENTITY = '1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1'
const MAX_ROTATE = 0.25
const MIN_ROTATE = -0.25
const MAX_SCALE = 1
const MIN_SCALE = 0.97

const matrix = ref(IDENTITY) // 当前展示矩阵
const overlayPos = ref(0) // 彩虹组旋转角（源码 firstOverlayPosition）
const overlayPaused = ref(false) // 暂停静止摆动（源码 disableOverlayAnimation）
const animateRotate = ref(false) // 允许彩虹旋转过渡（源码 disableInOutOverlayAnimation 的反义）
const bursting = ref(false)
let live = false // 3D 跟随已激活（源码 isTimeoutFinished）

let tEnter = null
let tLive = null
let tReset = null
let tMove = null
let tLeave1 = null
let tLeave2 = null
let tLeave3 = null

function reducedMotion() {
  return typeof window !== 'undefined' &&
    window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
}

function getDims() {
  const el = rootRef.value
  if (!el) return null
  const r = el.getBoundingClientRect()
  if (!r.width || !r.height) return null
  return { left: r.left, right: r.right, top: r.top, bottom: r.bottom }
}

/** 源码 getMatrix：距离衰减缩放 + 多轴旋转，拼装 matrix3d */
function getMatrix(cx, cy) {
  const d = getDims()
  if (!d) return IDENTITY
  const { left, right, top, bottom } = d
  const xCenter = (left + right) / 2
  const yCenter = (top + bottom) / 2

  const scale = [
    MAX_SCALE - (MAX_SCALE - MIN_SCALE) * Math.abs(xCenter - cx) / (xCenter - left),
    MAX_SCALE - (MAX_SCALE - MIN_SCALE) * Math.abs(yCenter - cy) / (yCenter - top),
    MAX_SCALE - (MAX_SCALE - MIN_SCALE) * (Math.abs(xCenter - cx) + Math.abs(yCenter - cy)) / (xCenter - left + yCenter - top)
  ]
  const sx = MAX_ROTATE - (MAX_ROTATE - MIN_ROTATE) * Math.abs(right - cx) / (right - left)
  const sy = MAX_ROTATE - (MAX_ROTATE - MIN_ROTATE) * (top - cy) / (top - bottom)
  const r = {
    x1: 0.25 * ((yCenter - cy) / yCenter - (xCenter - cx) / xCenter),
    x2: sx,
    y2: sy,
    z0: -sx,
    z1: 0.2 - 0.8 * (top - cy) / (top - bottom)
  }
  return `${scale[0]}, 0, ${r.z0}, 0, ` +
    `${r.x1}, ${scale[1]}, ${r.z1}, 0, ` +
    `${r.x2}, ${r.y2}, ${scale[2]}, 0, ` +
    `0, 0, 0, 1`
}

/** 源码 getOppositeMatrix：进场 0.7 衰减反向、离场 1/4 衰减反向，形成弹簧回弹 */
function getOpposite(m, cy, entering) {
  const d = getDims()
  if (!d) return IDENTITY
  const { top, bottom } = d
  const oppositeY = bottom - cy + top
  const weakening = entering ? 0.7 : 4
  const multiplier = entering ? -1 : 1
  const rot = MAX_ROTATE - (MAX_ROTATE - MIN_ROTATE) * (top - oppositeY) / (top - bottom)
  return m.split(', ').map((item, i) => {
    // 索引 2/4/8 = matrix3d 的 z0/x1/z1 旋转项；0/5/10 = 缩放主对角
    if (i === 2 || i === 4 || i === 8) return String(-parseFloat(item) * multiplier / weakening)
    if (i === 0 || i === 5 || i === 10) return '1'
    if (i === 6) return String(multiplier * rot / weakening)
    if (i === 9) return String(rot / weakening)
    return item
  }).join(', ')
}

/** 指针到徽章中心的曼哈顿距离 / 1.5（源码公式） */
function updateOverlayPos(cx, cy) {
  const d = getDims()
  if (!d) return
  const xCenter = (d.left + d.right) / 2
  const yCenter = (d.top + d.bottom) / 2
  overlayPos.value = (Math.abs(xCenter - cx) + Math.abs(yCenter - cy)) / 1.5
}

function clearLeaveTimers() {
  clearTimeout(tLeave1); clearTimeout(tLeave2); clearTimeout(tLeave3)
}

function onEnter(e) {
  if (reducedMotion()) return
  clearLeaveTimers()
  clearTimeout(tReset)
  overlayPaused.value = true // 暂停静止摆动，交给指针驱动
  if (e.pointerType === 'touch') return

  animateRotate.value = true
  clearTimeout(tEnter)
  tEnter = setTimeout(() => { animateRotate.value = false }, 350)

  requestAnimationFrame(() => requestAnimationFrame(() => updateOverlayPos(e.clientX, e.clientY)))

  const m = getMatrix(e.clientX, e.clientY)
  matrix.value = getOpposite(m, e.clientY, true) // 先反向偏移，再回正形成弹簧
  live = false
  clearTimeout(tLive)
  tLive = setTimeout(() => { live = true }, 200)
}

function onMove(e) {
  if (reducedMotion() || e.pointerType === 'touch') return
  const { clientX, clientY } = e
  // 150ms 延迟跟随：彩虹滞后于指针，产生「液体全息」手感（源码 setTimeout 150）
  clearTimeout(tMove)
  tMove = setTimeout(() => updateOverlayPos(clientX, clientY), 150)
  if (live) matrix.value = getMatrix(clientX, clientY)
}

function onLeave(e) {
  if (reducedMotion() || e.pointerType === 'touch') return
  clearTimeout(tEnter)
  matrix.value = getOpposite(matrix.value, e.clientY, false)
  clearTimeout(tReset)
  tReset = setTimeout(() => { matrix.value = IDENTITY }, 200)

  requestAnimationFrame(() => requestAnimationFrame(() => {
    animateRotate.value = true
    clearLeaveTimers()
    tLeave1 = setTimeout(() => { overlayPos.value = -overlayPos.value / 4 }, 150)
    tLeave2 = setTimeout(() => { overlayPos.value = 0 }, 300)
    tLeave3 = setTimeout(() => {
      overlayPaused.value = false // 恢复静止摆动
      animateRotate.value = false
    }, 500)
  }))
}

/** 每层彩虹的旋转角 = 基础角 + 指针驱动角，并控制过渡开关 */
function bandStyle(baseAngle) {
  return {
    transform: `rotate(${baseAngle + overlayPos.value}deg)`,
    transformOrigin: 'center center',
    transition: animateRotate.value ? 'transform 200ms ease-out' : 'none'
  }
}

/* ---------- 获取/升级：解锁瞬间彩虹额外旋绕一周（900ms 缓出） ---------- */
onMounted(() => {
  if (!props.burst || props.state === 'locked' || reducedMotion()) return
  bursting.value = true
  overlayPaused.value = true
  animateRotate.value = true
  overlayPos.value = -150
  requestAnimationFrame(() => requestAnimationFrame(() => { overlayPos.value = 0 }))
  setTimeout(() => {
    animateRotate.value = false
    overlayPaused.value = false
    bursting.value = false
  }, 950)
})

onBeforeUnmount(() => {
  [tEnter, tLive, tReset, tMove, tLeave1, tLeave2, tLeave3].forEach(clearTimeout)
})

function activate() {
  if (props.link) { router.push(props.link); return }
  emit('detail')
}
</script>

<style scoped>
/* ============ 徽章容器（尺寸 / 状态 / 点击语义） ============ */
.mb-badge {
  /* 等级配色令牌：底色 + 徽记渐变端点，深色模式在下方覆盖 */
  --mb-base: #f3e3ac;
  --mb-mark-0: #f2d489;
  --mb-mark-1: #b98a3c;
  position: relative;
  width: 100%;
  border-radius: 10px;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  contain: layout paint;
  transition: box-shadow .25s ease;
}
.mb-badge.mb-regular { --mb-base: #e7ebef; --mb-mark-0: #a8bccd; --mb-mark-1: #6f8ba3; }
.mb-badge.mb-silver { --mb-base: #dddddd; --mb-mark-0: #c3ccd6; --mb-mark-1: #8c99a8; }
.mb-badge.mb-gold { --mb-base: #f3e3ac; --mb-mark-0: #f2d489; --mb-mark-1: #b98a3c; }
.mb-badge.mb-diamond { --mb-base: #d7e6f7; --mb-mark-0: #bfe0ff; --mb-mark-1: #5f9ada; }
.mb-badge.mb-azure { --mb-base: #d9e8fb; --mb-mark-0: #b6d8f5; --mb-mark-1: #3f86c9; }
.mb-size-sm { max-width: 190px; }
.mb-size-md { max-width: 300px; }
.mb-size-lg { max-width: 360px; }
.mb-badge:focus-visible { box-shadow: var(--ll-focus-ring, 0 0 0 3px rgba(30, 110, 184, .28)); outline: none; }

.mb-base { fill: var(--mb-base); }
.mb-mark-0 { stop-color: var(--mb-mark-0); }
.mb-mark-1 { stop-color: var(--mb-mark-1); }
.mb-mark-line { stroke: var(--mb-mark-0); stroke-opacity: .45; }

/* 3D 层：源码 perspective(700px) matrix3d(...)，transformOrigin center center + 200ms ease-out */
.mb-3d {
  transform-origin: center center;
  transition: transform 200ms ease-out;
  will-change: transform;
}
.mb-svg { display: block; width: 100%; height: auto; isolation: isolate; }

/* ============ 文字（源码 Helvetica #666，中文补 CJK 字体） ============ */
.mb-eyebrow-text {
  font-family: Helvetica, "PingFang SC", "Microsoft YaHei", Arial, sans-serif;
  font-size: 9px; font-weight: 700; letter-spacing: .08em; fill: #8f959d;
}
.mb-title-text {
  font-family: Helvetica, "PingFang SC", "Microsoft YaHei", Arial, sans-serif;
  font-size: 16px; font-weight: 800; fill: #4e545c;
}

/* ============ 全息彩虹层：overlay 混合 + 圆角裁剪（源码 mask 的等价实现） ============ */
.mb-holo { mix-blend-mode: overlay; }
.mb-band { transform-origin: center center; }
/* 静止摆动：各层以 10° 步进错开，5s 周期往复 ±10° */
.mb-band-1 { animation: mbOvA1 5s infinite; }
.mb-band-2 { animation: mbOvA2 5s infinite; }
.mb-band-3 { animation: mbOvA3 5s infinite; }
.mb-band-4 { animation: mbOvA4 5s infinite; }
.mb-band-5 { animation: mbOvA5 5s infinite; }
.mb-band-6 { animation: mbOvA6 5s infinite; }
.mb-band-7 { animation: mbOvA7 5s infinite; }
.mb-band-8 { animation: mbOvA8 5s infinite; }
@keyframes mbOvA1 { 0%,100% { transform: rotate(0deg); } 50% { transform: rotate(10deg); } }
@keyframes mbOvA2 { 0%,100% { transform: rotate(10deg); } 50% { transform: rotate(20deg); } }
@keyframes mbOvA3 { 0%,100% { transform: rotate(20deg); } 50% { transform: rotate(30deg); } }
@keyframes mbOvA4 { 0%,100% { transform: rotate(30deg); } 50% { transform: rotate(40deg); } }
@keyframes mbOvA5 { 0%,100% { transform: rotate(40deg); } 50% { transform: rotate(50deg); } }
@keyframes mbOvA6 { 0%,100% { transform: rotate(50deg); } 50% { transform: rotate(60deg); } }
@keyframes mbOvA7 { 0%,100% { transform: rotate(60deg); } 50% { transform: rotate(70deg); } }
@keyframes mbOvA8 { 0%,100% { transform: rotate(90deg); } 50% { transform: rotate(100deg); } }

/* 指针进入：暂停摆动，改由内联 transform 驱动（源码同一机制） */
.mb-paused .mb-band { animation: none; }
/* 未解锁：彩虹静止（灰度由外层 filter 处理） */
.mb-state-locked .mb-band { animation: none; }

/* ============ 状态：解锁入场弹跳 / 当前等级辉光 ============ */
.mb-state-earned, .mb-state-current {
  animation: mbPop .55s var(--ll-ease-spring, cubic-bezier(.34, 1.56, .64, 1)) backwards;
  animation-delay: var(--mb-delay, 0ms);
}
@keyframes mbPop {
  0% { transform: scale(.88); opacity: 0; }
  60% { transform: scale(1.02); opacity: 1; }
  100% { transform: scale(1); opacity: 1; }
}
.mb-state-locked { filter: grayscale(.92); opacity: .55; }
.mb-state-current { animation: mbPop .55s var(--ll-ease-spring, cubic-bezier(.34, 1.56, .64, 1)) backwards, mbGlow 3.2s ease-in-out 1.2s infinite; }
@keyframes mbGlow {
  0%, 100% { box-shadow: 0 0 0 0 rgba(200, 164, 92, 0); }
  50% { box-shadow: 0 0 18px 3px rgba(200, 164, 92, .34); }
}
/* 获取瞬间：彩虹旋绕提速 */
.mb-bursting .mb-band { transition-duration: 900ms !important; transition-timing-function: cubic-bezier(.22, 1, .36, 1) !important; }

/* ============ 角标 ============ */
.mb-chip {
  position: absolute; top: 5px; right: 7px;
  padding: 1px 6px; border-radius: 99px;
  font-size: 10px; font-weight: 800; letter-spacing: .06em; line-height: 1.5;
  color: #fff; background: #b98a3c;
  box-shadow: 0 1px 4px rgba(19, 35, 58, .2);
}
.mb-chip--lock { background: rgba(100, 116, 139, .78); }

/* ============ 深色模式：粉彩底压暗、徽记提亮，彩虹层维持 overlay 混合 ============ */
[data-theme='dark'] .mb-eyebrow-text { fill: #a7aeb8; }
[data-theme='dark'] .mb-title-text { fill: #eef1f5; }
[data-theme='dark'] .mb-badge.mb-regular { --mb-base: #333b45; --mb-mark-0: #b9cada; --mb-mark-1: #7d97ae; }
[data-theme='dark'] .mb-badge.mb-silver { --mb-base: #3a4048; --mb-mark-0: #d8e0e8; --mb-mark-1: #9daab9; }
[data-theme='dark'] .mb-badge.mb-gold { --mb-base: #4a4230; --mb-mark-0: #f6d38a; --mb-mark-1: #c8a45c; }
[data-theme='dark'] .mb-badge.mb-diamond { --mb-base: #2f3f52; --mb-mark-0: #bfe0ff; --mb-mark-1: #6ba6e4; }
[data-theme='dark'] .mb-badge.mb-azure { --mb-base: #2c3f55; --mb-mark-0: #b6d8f5; --mb-mark-1: #4f92d3; }
[data-theme='dark'] .mb-chip { background: #c8a45c; }

/* ============ 可达性 / 性能：减少动效全量降级 ============ */
@media (prefers-reduced-motion: reduce) {
  .mb-band, .mb-state-earned, .mb-state-current { animation: none !important; }
  .mb-3d { transition: none; }
  .mb-badge { transition: none; }
}
</style>
