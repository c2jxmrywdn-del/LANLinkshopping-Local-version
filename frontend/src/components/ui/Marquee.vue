<template>
  <div class="mq" :class="{ 'is-vertical': vertical }" :style="{ '--mq-duration': duration, '--mq-gap': gap }">
    <div v-for="i in repeat" :key="i" class="mq-group"
         :class="{ reverse, 'pause-on-hover': pauseOnHover, 'is-vertical': vertical }">
      <slot />
    </div>
  </div>
</template>

<script setup>
/**
 * Marquee — Magic UI marquee 的 Vue 移植（上游 magicuidesign/magicui，MIT；
 * 原始源码 docs/ui-reference/magicui/marquee.tsx）。纯 CSS 实现：
 * 内容重复 repeat 份，每组位移恰好 `自身宽度 + gap`（translateX(calc(-100% - var(--gap)))）
 * 实现无缝循环；pauseOnHover 冻结动画，reverse 反向，vertical 纵向。
 */
defineProps({
  reverse: { type: Boolean, default: false },
  pauseOnHover: { type: Boolean, default: false },
  vertical: { type: Boolean, default: false },
  /** 重复份数——必须足以铺满容器（内容不足时会露出空隙） */
  repeat: { type: Number, default: 4 },
  duration: { type: String, default: '40s' },
  gap: { type: String, default: '1rem' }
})
</script>

<style scoped>
.mq { display: flex; flex-direction: row; gap: var(--mq-gap); overflow: hidden; padding: 2px; }
.mq.is-vertical { flex-direction: column; }
.mq-group { display: flex; flex-shrink: 0; justify-content: space-around; gap: var(--mq-gap);
  animation: mq-scroll var(--mq-duration) linear infinite; }
.mq-group.is-vertical { flex-direction: column; animation-name: mq-scroll-vertical; }
.mq-group.reverse { animation-direction: reverse; }
.mq-group.pause-on-hover:hover { animation-play-state: paused; }
@keyframes mq-scroll { to { transform: translateX(calc(-100% - var(--mq-gap))); } }
@keyframes mq-scroll-vertical { to { transform: translateY(calc(-100% - var(--mq-gap))); } }
@media (prefers-reduced-motion: reduce) { .mq-group { animation: none; } }
</style>
