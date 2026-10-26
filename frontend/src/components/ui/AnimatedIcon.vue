<template>
  <span class="animated-icon" :class="{ 'anim-on': alwaysOn }" :style="{ width: size + 'px', height: size + 'px' }">
    <!-- 券票：左右两半撕开（上游 pqoqubbw/icons ticket，spring 300/20） -->
    <svg v-if="name === 'ticket'" v-bind="svgAttrs">
      <g class="ai-ticket-left">
        <path d="M13 5H4a2 2 0 0 0-2 2v2a3 3 0 0 1 0 6v2a2 2 0 0 0 2 2h9" />
        <path d="M13 5v2" /><path d="M13 11v2" /><path d="M13 17v2" />
      </g>
      <path class="ai-ticket-right" d="M13 5h7a2 2 0 0 1 2 2v2a3 3 0 0 0 0 6v2a2 2 0 0 1-2 2h-7" />
    </svg>

    <!-- 闪电：pathLength 重绘（上游 zap） -->
    <svg v-else-if="name === 'zap'" v-bind="svgAttrs">
      <path class="ai-zap" d="M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z" />
    </svg>

    <!-- 购物车：颠簸行进 -->
    <svg v-else-if="name === 'cart'" v-bind="svgAttrs">
      <g class="ai-cart">
        <circle cx="8" cy="21" r="1" /><circle cx="19" cy="21" r="1" />
        <path d="M2.05 2.05h2l2.66 12.42a2 2 0 0 0 2 1.58h9.78a2 2 0 0 0 1.95-1.57l1.65-7.43H5.12" />
      </g>
    </svg>

    <!-- 银行卡：翻面 -->
    <svg v-else-if="name === 'credit-card'" v-bind="svgAttrs">
      <g class="ai-card"><rect width="20" height="14" x="2" y="5" rx="2" /><line x1="2" x2="22" y1="10" y2="10" /></g>
    </svg>

    <!-- 火焰：摇曳 -->
    <svg v-else-if="name === 'flame'" v-bind="svgAttrs">
      <path class="ai-flame" d="M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.153.433-2.294 1-3a2.5 2.5 0 0 0 2.5 2.5z" />
    </svg>

    <!-- 上箭头：上浮 -->
    <svg v-else-if="name === 'arrow-up'" v-bind="svgAttrs">
      <g class="ai-arrow-up"><path d="m5 12 7-7 7 7" /><path d="M12 19V5" /></g>
    </svg>

    <!-- 礼盒：盖子弹起 -->
    <svg v-else-if="name === 'gift'" v-bind="svgAttrs">
      <g class="ai-gift-lid"><rect x="3" y="8" width="18" height="4" rx="1" /><path d="M12 8v13" /></g>
      <path d="M19 12v7a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-7" />
      <path d="M7.5 8a2.5 2.5 0 0 1 0-5A4.8 8 0 0 1 12 8a4.8 8 0 0 1 4.5-5 2.5 2.5 0 0 1 0 5" />
    </svg>

    <!-- 靶心：同心脉冲 -->
    <svg v-else-if="name === 'target'" v-bind="svgAttrs">
      <circle class="ai-target-outer" cx="12" cy="12" r="10" />
      <circle class="ai-target-mid" cx="12" cy="12" r="6" />
      <circle class="ai-target-core" cx="12" cy="12" r="2" />
    </svg>

    <!-- 商店：弹跳 -->
    <svg v-else-if="name === 'store'" v-bind="svgAttrs">
      <g class="ai-store">
        <path d="m2 7 4.41-4.41A2 2 0 0 1 7.83 2h8.34a2 2 0 0 1 1.42.59L22 7" />
        <path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8" />
        <path d="M15 22v-4a2 2 0 0 0-2-2h-2a2 2 0 0 0-2 2v4" />
        <path d="M2 7h20" />
        <path d="M22 7v3a2 2 0 0 1-2 2a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 16 12a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 12 12a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 8 12a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 4 12a2 2 0 0 1-2-2V7" />
      </g>
    </svg>

    <!-- 包裹：开箱 -->
    <svg v-else-if="name === 'package'" v-bind="svgAttrs">
      <path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z" />
      <path d="M12 22V12" />
      <path class="ai-package-lid" d="m3.3 7 8.7 5 8.7-5" />
      <path class="ai-package-tape" d="m7.5 4.27 9 5.15" />
    </svg>

    <!-- 购物袋：提手提拉 -->
    <svg v-else-if="name === 'shopping-bag'" v-bind="svgAttrs">
      <path d="M6 2 3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4Z" />
      <path d="M3 6h18" />
      <path class="ai-bag-handle" d="M16 10a4 4 0 0 1-8 0" />
    </svg>
  </span>
</template>

<script setup>
/**
 * AnimatedIcon — 21st.dev 动画图标（上游 pqoqubbw/icons "lucide-animated"，MIT；
 * 源码存档 docs/ui-reference/pqoqubbw-icons/：ticket 左右撕开、zap pathLength 重绘等）。
 * Vue 移植：上游用 motion 变体（default→hover），此处以等价 CSS 动画实现（hover 触发），
 * spring 手感用 cubic-bezier(.34,1.56,.64,1) 近似；svgAttrs 展开 lucide 标准属性。
 */
import { computed } from 'vue'

const props = defineProps({
  /** 业务图标名：ticket/zap/cart/credit-card/flame/arrow-up/gift/target/store/package/shopping-bag */
  name: { type: String, required: true },
  size: { type: Number, default: 20 },
  strokeWidth: { type: Number, default: 2 },
  /** 常驻动画（不依赖 hover，用于 Dock 等无人交互场景可关） */
  alwaysOn: { type: Boolean, default: false }
})

const svgAttrs = computed(() => ({
  fill: 'none', stroke: 'currentColor', 'stroke-width': props.strokeWidth,
  'stroke-linecap': 'round', 'stroke-linejoin': 'round',
  viewBox: '0 0 24 24', width: props.size, height: props.size,
  xmlns: 'http://www.w3.org/2000/svg', class: 'ai-svg'
}))
</script>

<style scoped>
.animated-icon { display: inline-flex; align-items: center; justify-content: center; overflow: visible; }
.ai-svg { overflow: visible; display: block; }
.animated-icon *, .ai-svg * { transform-box: fill-box; }
.ai-ticket-right, .ai-package-tape { transform-origin: center; }

/* spring 手感（上游 spring stiffness 300 / damping 20 的 CSS 近似） */
.animated-icon .ai-ticket-left, .animated-icon .ai-ticket-right, .animated-icon .ai-cart,
.animated-icon .ai-card, .animated-icon .ai-arrow-up, .animated-icon .ai-gift-lid,
.animated-icon .ai-store, .animated-icon .ai-bag-handle, .animated-icon .ai-target-outer,
.animated-icon .ai-target-mid, .animated-icon .ai-flame, .animated-icon .ai-package-lid {
  transition: transform .45s cubic-bezier(.34, 1.56, .64, 1);
}
.animated-icon:hover .ai-ticket-left { transform: translateX(-3px); }
.animated-icon:hover .ai-ticket-right { transform: translateX(3px) rotate(4deg); transform-origin: 13px 12px; }
.animated-icon:hover .ai-cart { animation: ai-wobble-x .55s cubic-bezier(.34, 1.56, .64, 1); }
.animated-icon:hover .ai-card { transform: rotateY(.5turn); transition: transform .6s cubic-bezier(.34, 1.56, .64, 1); }
.animated-icon:hover .ai-flame { transform-origin: 50% 90%; animation: ai-flicker .7s ease-in-out; }
.animated-icon:hover .ai-arrow-up { transform: translateY(-3px); }
.animated-icon:hover .ai-gift-lid { transform: translateY(-2px) rotate(-3deg); transform-origin: center; }
.animated-icon:hover .ai-target-outer { transform: scale(1.08); }
.animated-icon:hover .ai-target-mid { transform: scale(.9); }
.animated-icon:hover .ai-target-core { fill: currentColor; }
.animated-icon:hover .ai-store { animation: ai-bounce .55s cubic-bezier(.34, 1.56, .64, 1); }
.animated-icon:hover .ai-package-lid { transform: translateY(-1.5px); transform-origin: center; }
.animated-icon:hover .ai-bag-handle { transform: translateY(-1.5px); }
/* 闪电重绘：dashoffset 0→满→0 */
.ai-zap { stroke-dasharray: 52; }
.animated-icon:hover .ai-zap { animation: ai-zap-draw .6s ease forwards; }
.anim-on .ai-zap { animation: ai-zap-draw 1.6s ease infinite; }

@keyframes ai-wobble-x { 0% { transform: translateX(0); } 30% { transform: translateX(-2px); } 60% { transform: translateX(2px); } 100% { transform: translateX(0); } }
@keyframes ai-flicker { 0%, 100% { transform: rotate(0) scaleY(1); } 35% { transform: rotate(-5deg) scaleY(1.06); } 70% { transform: rotate(4deg) scaleY(.96); } }
@keyframes ai-bounce { 0%, 100% { transform: translateY(0); } 45% { transform: translateY(-2.5px); } }
@keyframes ai-zap-draw { 0% { stroke-dashoffset: 52; opacity: 0; } 15% { opacity: 1; } 100% { stroke-dashoffset: 0; opacity: 1; } }
@media (prefers-reduced-motion: reduce) {
  .animated-icon * { transition: none !important; animation: none !important; }
}
</style>
