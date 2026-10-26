<template>
  <button class="shimmer-btn" :class="$attrs.class" :style="vars" v-bind="buttonAttrs">
    <!-- 流光层（上游 spark）：容器查询尺寸 + conic 旋转 -->
    <i class="sparks" aria-hidden="true"><i class="slide"><i class="spin"></i></i></i>
    <span class="content"><slot /></span>
    <!-- 高光层（上游 highlight）：inset 阴影 hover/active 增强 -->
    <i class="highlight" aria-hidden="true"></i>
    <!-- 背景层（上游 backdrop）：盖住流光溢出，只留 --cut 边缘缝 -->
    <i class="backdrop" aria-hidden="true"></i>
  </button>
</template>

<script setup>
/**
 * ShimmerButton — Magic UI shimmer-button 的 Vue 移植（上游 magicuidesign/magicui，MIT；
 * 原始源码 docs/ui-reference/magicui/shimmer-button.tsx）。纯 CSS 驱动：
 * 旋转 conic-gradient（--spread 90°）先平移后自旋，blur(2px) 后透过 --cut 边缘缝
 * 露出流光；backdrop 层（inset var(--cut)）盖住中部只留边缘光。容器查询
 * container-type: size 提供 cqw/cqh 单位。
 */
import { computed, useAttrs } from 'vue'

const props = defineProps({
  shimmerColor: { type: String, default: '#ffffff' },
  shimmerSize: { type: String, default: '0.05em' },
  borderRadius: { type: String, default: '100px' },
  shimmerDuration: { type: String, default: '3s' },
  background: { type: String, default: 'rgba(0, 0, 0, 1)' },
  disabled: { type: Boolean, default: false },
  type: { type: String, default: 'button' }
})

const attrs = useAttrs()
// 非 class/style 的一般属性（type/disabled/aria-* 等）直传 button
const buttonAttrs = computed(() => {
  const { class: _c, style: _s, ...rest } = attrs
  return { ...rest, type: props.type, disabled: props.disabled }
})

const vars = computed(() => ({
  '--spread': '90deg',
  '--shimmer-color': props.shimmerColor,
  '--radius': props.borderRadius,
  '--speed': props.shimmerDuration,
  '--cut': props.shimmerSize,
  '--bg': props.background
}))
</script>

<style scoped>
.shimmer-btn {
  position: relative; z-index: 0; display: flex; align-items: center; justify-content: center;
  overflow: hidden; white-space: nowrap; cursor: pointer;
  border: 1px solid rgba(255, 255, 255, .1); border-radius: var(--radius);
  padding: 12px 24px; color: #fff; background: var(--bg); font: inherit; font-weight: 600;
  transform: translateZ(0);
  transition: transform .3s ease-in-out;
}
.shimmer-btn:active { transform: translateY(1px); }
.shimmer-btn:disabled { cursor: not-allowed; opacity: .5; }

/* 流光容器：container-type size 让子层用 cqh/cqw */
.sparks { position: absolute; inset: 0; z-index: -3; overflow: visible;
  filter: blur(2px); container-type: size; pointer-events: none; }
.slide { position: absolute; inset: 0; aspect-ratio: 1; height: 100cqh; border-radius: 0;
  animation: shimmer-slide var(--speed) ease-in-out infinite alternate; }
.spin { position: absolute; inset: -100%; width: auto;
  background: conic-gradient(from calc(270deg - (var(--spread) * .5)),
    transparent 0, var(--shimmer-color) var(--spread), transparent var(--spread));
  animation: spin-around calc(var(--speed) * 5) linear infinite; }

.content { position: relative; z-index: 1; }

/* 高光层 */
.highlight { position: absolute; inset: 0; border-radius: calc(var(--radius) - 4px);
  box-shadow: inset 0 -8px 10px rgba(255, 255, 255, .12);
  transition: box-shadow .3s ease-in-out; pointer-events: none; }
.shimmer-btn:hover .highlight { box-shadow: inset 0 -6px 10px rgba(255, 255, 255, .25); }
.shimmer-btn:active .highlight { box-shadow: inset 0 -10px 10px rgba(255, 255, 255, .25); }

/* 背景层：留出 --cut 边缘缝透出流光 */
.backdrop { position: absolute; inset: var(--cut); z-index: -2; border-radius: var(--radius);
  background: var(--bg); pointer-events: none; }

@keyframes shimmer-slide { to { transform: translate(calc(100cqw - 100%), 0); } }
@keyframes spin-around { 0% { transform: translateZ(0) rotate(0deg); } 100% { transform: translateZ(0) rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .slide, .spin { animation: none; } }
</style>
