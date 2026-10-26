<template>
  <div ref="root" class="magic-card" v-bind="$attrs"
       @pointermove="onMove" @pointerenter="onEnter" @pointerleave="onLeave">
    <i class="mc-border" aria-hidden="true"></i>
    <i class="mc-spot" aria-hidden="true"></i>
    <slot />
  </div>
</template>

<script setup>
/**
 * MagicCard — Magic UI magic-card 的 Vue 移植（上游 magicuidesign/magicui，MIT；
 * 原始源码 docs/ui-reference/magicui/magic-card.tsx，gradient 模式）。
 *
 * 与上游的差异（均为改进）：
 *  - 坐标直写 CSS 变量（--mc-x/--mc-y），不做组件级响应式更新（上游 motion 每帧 re-render）；
 *  - "零侵入宿主"：边框光用 mask 法叠加（上游是 border-box 双层背景，要求组件自持背景），
 *    宿主自带背景/边框/圆角时直接套 class 即可。
 * 全局 pointerout/blur/visibilitychange 复位（对齐上游 reset("global")）。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  /** 光斑半径 px */
  gradientSize: { type: Number, default: 200 },
  gradientFrom: { type: String, default: '#9E7AFF' },
  gradientTo: { type: String, default: '#FE8BBB' },
  /** 卡内光斑颜色（深色卡用亮色） */
  gradientColor: { type: String, default: 'rgba(30, 110, 184, .10)' },
  /** 卡内光斑不透明度 0-1 */
  gradientOpacity: { type: Number, default: 0.8 }
})

const root = ref(null)

function onMove(e) {
  const rect = root.value.getBoundingClientRect()
  root.value.style.setProperty('--mc-x', (e.clientX - rect.left).toFixed(1) + 'px')
  root.value.style.setProperty('--mc-y', (e.clientY - rect.top).toFixed(1) + 'px')
}
function onEnter() { root.value.style.setProperty('--mc-on', '1') }
function onLeave() {
  root.value.style.setProperty('--mc-on', '0')
  const off = -props.gradientSize + 'px'
  root.value.style.setProperty('--mc-x', off)
  root.value.style.setProperty('--mc-y', off)
}
function resetGlobal() { if (root.value) onLeave() }

onMounted(() => {
  onLeave()
  window.addEventListener('pointerout', onGlobalOut)
  window.addEventListener('blur', resetGlobal)
  document.addEventListener('visibilitychange', resetGlobal)
})
function onGlobalOut(e) { if (!e.relatedTarget) resetGlobal() }
onBeforeUnmount(() => {
  window.removeEventListener('pointerout', onGlobalOut)
  window.removeEventListener('blur', resetGlobal)
  document.removeEventListener('visibilitychange', resetGlobal)
})
</script>

<style scoped>
.magic-card { position: relative; }
/* 内容置于光效层之上（slot 直接子元素） */
.magic-card > :not(.mc-border):not(.mc-spot) { position: relative; z-index: 3; }

/* 边框光：鼠标径向渐变 ∩ 1.5px 边环，hover 淡入 */
.mc-border { position: absolute; inset: 0; border-radius: inherit; padding: 1.5px;
  pointer-events: none; z-index: 1;
  background: radial-gradient(var(--mc-size, 200px) circle at var(--mc-x, -999px) var(--mc-y, -999px),
    var(--mc-from, #9E7AFF), var(--mc-to, #FE8BBB), transparent 100%);
  -webkit-mask: linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
  -webkit-mask-composite: xor;
  mask: linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
  mask-composite: exclude;
  opacity: var(--mc-on, 0); transition: opacity .3s ease; }

/* 卡内光斑：hover 淡入 */
.mc-spot { position: absolute; inset: 0; border-radius: inherit; pointer-events: none; z-index: 2;
  background: radial-gradient(var(--mc-size, 200px) circle at var(--mc-x, -999px) var(--mc-y, -999px),
    var(--mc-color, rgba(30, 110, 184, .10)), transparent 100%);
  opacity: calc(var(--mc-on, 0) * var(--mc-spot-opacity, .8)); transition: opacity .3s ease; }

@media (prefers-reduced-motion: reduce) {
  .mc-border, .mc-spot { transition: none; }
}
</style>
