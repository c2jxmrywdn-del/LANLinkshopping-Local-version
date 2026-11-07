<template>
  <div
    class="brand-symbol"
    :class="{ 'brand-symbol--static': !motion }"
    :style="{ '--brand-symbol-size': size }"
    aria-hidden="true"
  >
    <div class="symbol-orbit symbol-orbit-a"></div>
    <div class="symbol-orbit symbol-orbit-b"></div>
    <div class="symbol-core">
      <i class="core-tint"></i>
      <span class="core-lan">LAN</span>
      <span class="core-link">Link</span>
      <small>SHOPPING NETWORK</small>
    </div>
    <i class="symbol-dot symbol-dot-1"></i>
    <i class="symbol-dot symbol-dot-2"></i>
    <i class="symbol-dot symbol-dot-3"></i>
  </div>
</template>

<script setup>
defineProps({
  size: { type: String, default: '410px' },
  motion: { type: Boolean, default: true }
})
</script>

<style scoped>
.brand-symbol {
  --brand-symbol-size: 410px;
  position: relative;
  width: var(--brand-symbol-size);
  height: var(--brand-symbol-size);
  flex: 0 0 var(--brand-symbol-size);
  overflow: visible;
}
.symbol-orbit {
  position: absolute;
  border-radius: 50%;
  border: 1.5px solid rgba(30,110,184,.32);
}
.symbol-orbit-a {
  inset: 4.88%;
  transform: rotate(22deg) skewX(-8deg);
}
.symbol-orbit-b {
  inset: 19.51% 0.98%;
  transform: rotate(-25deg) skewY(-7deg);
  border-color: rgba(180,140,60,.5);
}
.symbol-core {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%,-50%);
  width: 32.2%;
  height: 32.2%;
  border-radius: 9.27%;
  overflow: hidden;
  display: grid;
  place-content: center;
  text-align: center;
  background: linear-gradient(150deg,rgba(255,255,255,.46) 0%,rgba(255,255,255,.08) 38%,rgba(214,236,250,.10) 68%,rgba(255,255,255,.20) 100%);
  border: 1px solid rgba(255,255,255,.66);
  box-shadow: inset 0 1px rgba(255,255,255,.85), inset 0 -8px 18px rgba(255,255,255,.16), 0 20px 44px rgba(30,110,184,.2);
  backdrop-filter: blur(14px) saturate(180%) brightness(1.04);
  -webkit-backdrop-filter: blur(14px) saturate(180%) brightness(1.04);
}
/* 卡片自身的高光带：核心卡只有 32% 边长，整块 hero 的扫光掠过时它太小、看不出来，必须自带一层 */
.symbol-core::after {
  content: "";
  position: absolute;
  inset: -40% -70%;
  background: linear-gradient(115deg,transparent 38%,rgba(255,255,255,.52) 50%,rgba(186,230,253,.18) 56%,transparent 64%);
  transform: translateX(-70%);
  animation: symbolSheen 7s ease-in-out 1.2s infinite;
  mix-blend-mode: screen;
  pointer-events: none;
}
.symbol-core > * { position: relative; z-index: 1; }
/* 核心卡内动态底色：与 Home 的 pane-tint 同一动效语言（21st.dev liquid-effect 的色团漂移，
   仅借鉴思路未复制源码）；用 conic 慢转，让 LAN/Link 卡片自身有底色流动 */
.core-tint {
  position: absolute;
  inset: -30%;
  z-index: 0;
  pointer-events: none;
  opacity: .8;
  background: conic-gradient(from 0deg,
    rgba(125,211,252,.55),
    rgba(200,164,92,.26) 26%,
    rgba(186,230,253,.5) 52%,
    rgba(56,189,248,.34) 74%,
    rgba(125,211,252,.55));
  filter: blur(11px) saturate(140%);
  animation: coreTintSpin 20s linear infinite;
}
@keyframes coreTintSpin {
  to { transform: rotate(1turn); }
}
/* 与全局 motion.css 同一降级口径：低性能档停掉卡内色层动画 */
[data-perf-tier="reduced"] .core-tint,
[data-perf-tier="suspended"] .core-tint { animation: none; }
@keyframes symbolSheen {
  0% { transform: translateX(-70%); }
  55%, 100% { transform: translateX(70%); }
}
.core-lan,
.core-link {
  font-size: 22px;
  font-weight: 800;
  letter-spacing: -0.06em;
}
.core-lan { color: var(--ll-ink-on-glass, #13233A); }
.core-link { color: var(--ll-brand-gold-strong, #80601F); }
.symbol-core small {
  margin-top: 8px;
  color: rgba(19,35,58,.62);
  font-size: 9px;
  letter-spacing: .17em;
}
.symbol-dot {
  position: absolute;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--ll-brand-gold, #C8A45C);
  box-shadow: 0 0 14px rgba(245,158,11,.45);
}
.symbol-dot-1 { left: 13.17%; top: 25.61%; }
.symbol-dot-2 { right: 6.83%; top: 48.29%; }
.symbol-dot-3 { left: 25.61%; bottom: 13.41%; }
.brand-symbol:not(.brand-symbol--static) .symbol-dot {
  animation: brandSymbolPulse 2.4s ease-in-out infinite;
}
.brand-symbol:not(.brand-symbol--static) .symbol-dot-2 { animation-delay: .7s; }
.brand-symbol:not(.brand-symbol--static) .symbol-dot-3 { animation-delay: 1.3s; }
.brand-symbol:not(.brand-symbol--static) .symbol-core {
  animation: brandSymbolFloat 5.5s ease-in-out infinite;
}
@keyframes brandSymbolPulse {
  0%,100% { transform: scale(.85); opacity: .65; }
  50% { transform: scale(1.2); opacity: 1; }
}
@keyframes brandSymbolFloat {
  0%,100% { translate: 0 0; }
  50% { translate: 0 -3px; }
}
@media (max-width: 640px) {
  .core-lan,
  .core-link { font-size: calc(var(--brand-symbol-size) * .0537); }
  .symbol-core small { font-size: calc(var(--brand-symbol-size) * .022); }
  .symbol-dot { width: calc(var(--brand-symbol-size) * .0244); height: calc(var(--brand-symbol-size) * .0244); }
}
@media (prefers-reduced-motion: reduce) {
  .brand-symbol:not(.brand-symbol--static) .symbol-dot,
  .brand-symbol:not(.brand-symbol--static) .symbol-core { animation: none; }
  .brand-symbol:not(.brand-symbol--static) .symbol-core::after { animation: none; }
  .brand-symbol:not(.brand-symbol--static) .core-tint { animation: none; }
}
</style>
