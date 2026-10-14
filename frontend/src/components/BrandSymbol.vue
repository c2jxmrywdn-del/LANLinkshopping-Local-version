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
  border: 1px solid rgba(255,255,255,.16);
}
.symbol-orbit-a {
  inset: 4.88%;
  transform: rotate(22deg) skewX(-8deg);
}
.symbol-orbit-b {
  inset: 19.51% 0.98%;
  transform: rotate(-25deg) skewY(-7deg);
  border-color: rgba(200,164,92,.28);
}
.symbol-core {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%,-50%);
  width: 32.2%;
  height: 32.2%;
  border-radius: 9.27%;
  display: grid;
  place-content: center;
  text-align: center;
  background: linear-gradient(145deg,rgba(255,255,255,.16),rgba(255,255,255,.035));
  border: 1px solid rgba(255,255,255,.24);
  box-shadow: inset 0 1px rgba(255,255,255,.35), 0 24px 50px rgba(0,0,0,.2);
  backdrop-filter: blur(18px) saturate(125%);
  -webkit-backdrop-filter: blur(18px) saturate(125%);
}
.core-lan,
.core-link {
  font-size: 22px;
  font-weight: 800;
  letter-spacing: -0.06em;
}
.core-lan { color: #fff; }
.core-link { color: var(--ll-brand-gold-soft); }
.symbol-core small {
  margin-top: 8px;
  color: rgba(255,255,255,.5);
  font-size: 7px;
  letter-spacing: .17em;
}
.symbol-dot {
  position: absolute;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--ll-brand-gold-soft);
  box-shadow: 0 0 22px rgba(245,158,11,.75);
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
  .symbol-core small { font-size: calc(var(--brand-symbol-size) * .0171); }
  .symbol-dot { width: calc(var(--brand-symbol-size) * .0244); height: calc(var(--brand-symbol-size) * .0244); }
}
@media (prefers-reduced-motion: reduce) {
  .brand-symbol:not(.brand-symbol--static) .symbol-dot,
  .brand-symbol:not(.brand-symbol--static) .symbol-core { animation: none; }
}
</style>
