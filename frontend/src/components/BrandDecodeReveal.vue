<template>
  <div
    class="brand-decode"
    :class="[`brand-decode--${mode}`, { 'is-ready': ready }]"
    :style="{ '--decode-delay': delay }"
    role="img"
    :aria-label="ariaLabel"
  >
    <div class="brand-decode__scan" aria-hidden="true"></div>
    <div class="brand-decode__noise" aria-hidden="true">{{ noise }}</div>
    <BrandWordmark class="brand-decode__logo" :size="size" />
    <div class="brand-decode__cursor" aria-hidden="true"></div>
    <span class="brand-decode__status" aria-hidden="true">{{ ready ? 'LINK ESTABLISHED' : 'DECODING BRAND MARK' }}</span>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import BrandWordmark from './BrandWordmark.vue'

const props = defineProps({
  size: { type: String, default: 'inherit' },
  mode: { type: String, default: 'soft' },
  delay: { type: String, default: '0s' },
  ariaLabel: { type: String, default: 'LANLinkshopping' }
})

const ready = ref(false)
const noise = ref('LΛN // L1NK // SH0PP1NG')
let timer = null

const glyphs = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789·/<>[]{}+-='

function scramble() {
  const frames = 9
  let frame = 0
  const run = () => {
    if (frame >= frames) {
      noise.value = 'LAN // LINK // SHOPPING'
      ready.value = true
      return
    }
    const chars = Array.from({ length: 25 }, () => glyphs[Math.floor(Math.random() * glyphs.length)])
    noise.value = chars.join('')
    frame += 1
    timer = setTimeout(run, 58)
  }
  run()
}

onMounted(() => {
  timer = setTimeout(scramble, 520)
})

onBeforeUnmount(() => clearTimeout(timer))
</script>

<style scoped>
.brand-decode {
  position: relative;
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  min-width: min(82vw, 520px);
  isolation: isolate;
}
.brand-decode__logo {
  position: relative;
  z-index: 3;
  opacity: 0;
  filter: blur(8px);
  transform: translateY(7px) scale(.985);
  animation: decodeLogoIn .78s var(--decode-delay) cubic-bezier(.2,.8,.2,1) forwards;
}
.brand-decode__noise {
  position: absolute;
  z-index: 2;
  top: 50%;
  transform: translateY(-50%);
  width: 100%;
  overflow: hidden;
  color: rgba(246,211,138,.72);
  font: 600 11px/1.2 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  letter-spacing: 3px;
  white-space: nowrap;
  text-align: center;
  text-shadow: 0 0 18px rgba(200,164,92,.25);
  opacity: .82;
  filter: blur(.15px);
  transition: opacity .35s ease, filter .45s ease, transform .55s ease;
}
.brand-decode.is-ready .brand-decode__noise {
  opacity: 0;
  filter: blur(8px);
  transform: translateY(-50%) scaleX(1.06);
}
.brand-decode__scan {
  position: absolute;
  z-index: 4;
  left: 5%;
  right: 5%;
  top: 48%;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(246,211,138,.72), rgba(255,255,255,.9), rgba(30,110,184,.55), transparent);
  box-shadow: 0 0 18px rgba(246,211,138,.32);
  opacity: 0;
  animation: decodeScan 1.25s .45s cubic-bezier(.2,.8,.2,1) forwards;
  pointer-events: none;
}
.brand-decode__cursor {
  position: absolute;
  z-index: 5;
  width: 2px;
  height: 1.35em;
  left: 50%;
  top: 50%;
  background: rgba(255,255,255,.9);
  box-shadow: 0 0 12px rgba(246,211,138,.8);
  opacity: 0;
  animation: decodeCursor .9s .7s ease forwards;
}
.brand-decode__status {
  margin-top: 12px;
  color: rgba(174,183,196,.72);
  font: 500 9px/1.2 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  letter-spacing: 2.8px;
  opacity: 0;
  transform: translateY(5px);
  animation: decodeStatus .5s 1.2s ease forwards;
}
.brand-decode--hard .brand-decode__logo {
  animation-duration: .48s;
}
.brand-decode--hard .brand-decode__scan {
  animation-duration: .72s;
}
.brand-decode--hard .brand-decode__noise {
  color: rgba(255,255,255,.82);
  text-shadow: 0 0 10px rgba(30,110,184,.48);
}

@keyframes decodeLogoIn {
  0% { opacity: 0; filter: blur(8px); transform: translateY(7px) scale(.985); }
  55% { opacity: .55; filter: blur(2.2px); }
  100% { opacity: 1; filter: blur(0); transform: translateY(0) scale(1); }
}
@keyframes decodeScan {
  0% { opacity: 0; transform: translateY(-18px) scaleX(.4); }
  18% { opacity: 1; }
  72% { opacity: .7; }
  100% { opacity: 0; transform: translateY(18px) scaleX(1); }
}
@keyframes decodeCursor {
  0% { opacity: 0; transform: translateX(-52px) scaleY(.6); }
  18%, 72% { opacity: .9; }
  100% { opacity: 0; transform: translateX(52px) scaleY(1); }
}
@keyframes decodeStatus {
  from { opacity: 0; transform: translateY(5px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (prefers-reduced-motion: reduce) {
  .brand-decode__logo,
  .brand-decode__scan,
  .brand-decode__cursor,
  .brand-decode__status { animation: none !important; }
  .brand-decode__logo { opacity: 1; filter: none; transform: none; }
  .brand-decode__noise,
  .brand-decode__scan,
  .brand-decode__cursor { display: none; }
  .brand-decode__status { opacity: .72; transform: none; }
}
</style>
