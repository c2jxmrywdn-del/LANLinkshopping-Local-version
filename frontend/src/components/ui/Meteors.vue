<template>
  <div class="meteors" aria-hidden="true">
    <span v-for="(m, i) in list" :key="i" class="meteor" :style="m">
      <i class="tail"></i>
    </span>
  </div>
</template>

<script setup>
/**
 * Meteors — Magic UI meteors 的 Vue 移植（上游 magicuidesign/magicui，MIT；
 * 原始源码 docs/ui-reference/magicui/meteors.tsx）。
 * 差异：left 取容器宽百分比（上游取 window.innerWidth，仅适合全屏宿主）；
 * 新增 color/tailLength props 以适配深色卡（上游固定 zinc 灰）。
 * 容器自身 overflow:hidden + 圆角继承，流星不会溢出宿主卡。
 */
import { onMounted, ref } from 'vue'

const props = defineProps({
  number: { type: Number, default: 20 },
  minDelay: { type: Number, default: 0.2 },
  maxDelay: { type: Number, default: 1.2 },
  minDuration: { type: Number, default: 2 },
  maxDuration: { type: Number, default: 10 },
  /** 划过方向角（215 = 右上向左下），CSS 里取负值旋转 */
  angle: { type: Number, default: 215 },
  color: { type: String, default: '#71717a' },
  tailLength: { type: String, default: '50px' }
})

const list = ref([])
onMounted(() => {
  list.value = [...new Array(props.number)].map(() => ({
    '--angle': -props.angle + 'deg',
    '--mc-color': props.color,
    '--tail-w': props.tailLength,
    top: '-5%',
    left: Math.floor(Math.random() * 100) + '%',
    animationDelay: (Math.random() * (props.maxDelay - props.minDelay) + props.minDelay) + 's',
    animationDuration: Math.floor(Math.random() * (props.maxDuration - props.minDuration) + props.minDuration) + 's'
  }))
})
</script>

<style scoped>
.meteors { position: absolute; inset: 0; overflow: hidden; border-radius: inherit; pointer-events: none; }
.meteor { position: absolute; width: 2px; height: 2px; border-radius: 999px;
  background: var(--mc-color, #71717a); box-shadow: 0 0 0 1px rgba(255, 255, 255, .06);
  transform: rotate(var(--angle));
  animation: meteor-fall linear infinite; }
/* 尾巴：从头向运动反方向（右侧）延伸渐隐 */
.tail { position: absolute; top: 50%; left: 100%; width: var(--tail-w, 50px); height: 1px;
  transform: translateY(-50%);
  background: linear-gradient(to right, var(--mc-color, #71717a), transparent); }
@keyframes meteor-fall {
  0% { transform: rotate(var(--angle)) translateX(0); opacity: 1; }
  70% { opacity: 1; }
  100% { transform: rotate(var(--angle)) translateX(-500px); opacity: 0; }
}
@media (prefers-reduced-motion: reduce) { .meteor { animation: none; opacity: 0; } }
</style>
