<template>
  <span class="shiny" :class="$attrs.class" :style="{ '--shiny-width': shimmerWidth + 'px', '--shiny-duration': duration }">
    <slot />
  </span>
</template>

<script setup>
/**
 * AnimatedShinyText — Magic UI animated-shiny-text 的 Vue 移植（上游 magicuidesign/magicui，
 * MIT；原始源码 docs/ui-reference/magicui/animated-shiny-text.tsx）。纯 CSS：
 * 文字裁切渐变（transparent → 高亮 → transparent），background-position 8s 循环——
 * 前 30% 扫过、之后停留（比匀速循环更有节奏感）。深色模式中间高亮自动切换白色。
 */
defineProps({
  /** 扫光带宽度 px */
  shimmerWidth: { type: Number, default: 100 },
  duration: { type: String, default: '8s' }
})
</script>

<style scoped>
.shiny {
  --shiny-color: rgba(0, 0, 0, .8);
  background-image: linear-gradient(110deg, transparent 20%, var(--shiny-color) 50%, transparent 80%);
  background-size: var(--shiny-width) 100%;
  background-clip: text;
  -webkit-background-clip: text;
  background-repeat: no-repeat;
  color: var(--ll-muted, #6e6e73);
  animation: shiny-text var(--shiny-duration) linear infinite;
}
[data-theme='dark'] .shiny, .shiny.on-dark { --shiny-color: rgba(255, 255, 255, .85); }
@keyframes shiny-text {
  0%, 90%, 100% { background-position: calc(-100% - var(--shiny-width)) center; }
  30%, 60% { background-position: calc(100% + var(--shiny-width)) center; }
}
@media (prefers-reduced-motion: reduce) { .shiny { animation: none; } }
</style>
