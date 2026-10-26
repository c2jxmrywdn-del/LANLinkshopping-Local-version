<template>
  <span class="agt" :class="$attrs.class"
        :style="{ '--agt-size': (speed * 300) + '%', '--agt-from': colorFrom, '--agt-to': colorTo }"><slot /></span>
</template>

<script setup>
/**
 * AnimatedGradientText — Magic UI animated-gradient-text 的 Vue 移植
 * （上游 magicuidesign/magicui，MIT；原始源码 docs/ui-reference/magicui/animated-gradient-text.tsx）。
 * 纯 CSS：from→to→from 渐变，background-size 随 speed 放大，8s 线性循环流动。
 * 注意：slot 内容走 bg-clip:text（emoji 位图字形不受 color:transparent 影响，可正常显示）。
 */
defineProps({
  /** 流动速度系数（越大渐变带越长、流动越快） */
  speed: { type: Number, default: 1 },
  colorFrom: { type: String, default: '#ffaa40' },
  colorTo: { type: String, default: '#9c40ff' }
})
</script>

<style scoped>
.agt { display: inline;
  background-image: linear-gradient(90deg, var(--agt-from), var(--agt-to), var(--agt-from));
  background-size: var(--agt-size) 100%;
  background-clip: text; -webkit-background-clip: text; color: transparent;
  animation: agt-flow 8s linear infinite; }
@keyframes agt-flow { to { background-position: var(--agt-size) center; } }
@media (prefers-reduced-motion: reduce) { .agt { animation: none; } }
</style>
