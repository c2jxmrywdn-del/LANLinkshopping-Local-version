<template>
  <transition name="busy-leave">
    <div v-if="visible" class="busy-overlay" role="status" aria-live="polite">
      <div class="busy-backdrop" aria-hidden="true">
        <span class="busy-orb busy-orb-a"></span>
        <span class="busy-orb busy-orb-b"></span>
        <span class="busy-grid"></span>
      </div>

      <div class="busy-panel">
        <div class="busy-network" aria-hidden="true">
          <span class="node node-a"></span>
          <span class="node node-b"></span>
          <span class="node node-c"></span>
          <span class="node node-d"></span>
          <i class="link link-a"></i>
          <i class="link link-b"></i>
          <i class="link link-c"></i>
          <i class="pulse"></i>
        </div>

        <div class="busy-brand ll-wordmark">
          <span class="ll-lan">LAN</span><span class="ll-link">Link</span><span class="ll-shopping">shopping</span>
        </div>

        <h2>系统正在繁忙处理</h2>
        <p>当前请求较多，正在为你保持连接。请稍候，无需重复操作。</p>

        <div class="busy-status">
          <span class="status-dot"></span>
          <span>正在优化请求队列</span>
          <strong>{{ elapsed }}s</strong>
        </div>

        <div class="busy-progress" aria-hidden="true">
          <span></span>
        </div>

        <button class="busy-cancel" type="button" @click="continueWaiting">
          继续等待
        </button>
      </div>
    </div>
  </transition>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { busy, startedAt, hideBusyLoading } from '../utils/busy'

const visible = computed(() => busy.value)
const elapsed = ref(0)
let timer = null

function tick() {
  elapsed.value = Math.max(0, Math.floor((Date.now() - startedAt.value) / 1000))
}
function continueWaiting() {
  tick()
}
onMounted(() => {
  tick()
  timer = window.setInterval(tick, 500)
})
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<style scoped>
.busy-overlay {
  position: fixed; inset: 0; z-index: 9998;
  display: grid; place-items: center; overflow: hidden;
  isolation: isolate; background: rgba(10,18,32,.46);
  backdrop-filter: blur(12px) saturate(115%);
  -webkit-backdrop-filter: blur(12px) saturate(115%);
}
.busy-backdrop { position:absolute; inset:0; overflow:hidden; }
.busy-grid {
  position:absolute; inset:-20%;
  opacity:.12;
  background-image: linear-gradient(rgba(255,255,255,.09) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255,255,255,.09) 1px, transparent 1px);
  background-size:48px 48px;
  transform: perspective(700px) rotateX(58deg) translateY(18%);
  animation:gridDrift 7s linear infinite;
}
.busy-orb {
  position:absolute; width:460px; height:460px; border-radius:50%;
  filter:blur(70px); opacity:.18;
}
.busy-orb-a { left:-170px; top:8%; background:#1E6EB8; animation:orbA 8s ease-in-out infinite alternate; }
.busy-orb-b { right:-180px; bottom:2%; background:#C8A45C; animation:orbB 9s ease-in-out infinite alternate; }

.busy-panel {
  position:relative; width:min(430px, calc(100vw - 36px));
  padding:34px 34px 28px; border:1px solid rgba(255,255,255,.18);
  border-radius:28px; text-align:center; color:#F7F4EC;
  background:linear-gradient(145deg, rgba(255,255,255,.105), rgba(255,255,255,.035));
  box-shadow:0 30px 90px rgba(0,0,0,.32), inset 0 1px 0 rgba(255,255,255,.18);
  backdrop-filter:blur(28px) saturate(125%);
  -webkit-backdrop-filter:blur(28px) saturate(125%);
  animation:panelIn .45s cubic-bezier(.2,.8,.2,1);
}
.busy-network { position:relative; width:116px; height:78px; margin:0 auto 20px; }
.node { position:absolute; width:9px; height:9px; border-radius:50%; background:#DCEBFF; box-shadow:0 0 0 5px rgba(220,235,255,.08),0 0 18px rgba(220,235,255,.45); }
.node-a { left:5px; top:34px; } .node-b { left:38px; top:10px; }
.node-c { left:38px; top:58px; } .node-d { right:5px; top:34px; }
.link { position:absolute; height:1px; transform-origin:left center; background:linear-gradient(90deg,rgba(255,255,255,.12),rgba(255,255,255,.55)); }
.link-a { width:39px; left:13px; top:39px; transform:rotate(-32deg); }
.link-b { width:39px; left:13px; top:39px; transform:rotate(32deg); }
.link-c { width:39px; left:47px; top:39px; }
.pulse { position:absolute; left:51px; top:34px; width:8px; height:8px; border-radius:50%; background:#C8A45C; animation:pulse 1.8s ease-out infinite; }
.busy-brand { font-size:20px; line-height:1; margin-bottom:16px; }
.busy-brand .ll-lan { color:#fff; } .busy-brand .ll-link { color:#F2C96B; } .busy-brand .ll-shopping { color:#F4F1E9; }
h2 { margin:0; font-size:22px; letter-spacing:.2px; font-weight:700; }
p { margin:10px auto 0; max-width:330px; color:rgba(247,244,236,.68); font-size:13px; line-height:1.8; }
.busy-status { display:flex; align-items:center; justify-content:center; gap:8px; margin-top:22px; font-size:12px; color:rgba(247,244,236,.68); }
.busy-status strong { min-width:26px; color:#F4F1E9; font-variant-numeric:tabular-nums; font-weight:600; }
.status-dot { width:6px; height:6px; border-radius:50%; background:#C8A45C; box-shadow:0 0 10px rgba(200,164,92,.65); animation:dotBlink 1.2s ease-in-out infinite; }
.busy-progress { height:3px; margin:15px auto 20px; width:100%; overflow:hidden; border:1px solid rgba(255,255,255,.15); border-radius:99px; background:rgba(255,255,255,.045); }
.busy-progress span { display:block; height:100%; width:35%; border-radius:inherit; background:rgba(255,255,255,.76); box-shadow:0 0 14px rgba(255,255,255,.16); animation:queueSweep 1.65s cubic-bezier(.4,0,.2,1) infinite; }
.busy-cancel { border:1px solid rgba(255,255,255,.18); border-radius:999px; padding:8px 18px; background:rgba(255,255,255,.06); color:#F4F1E9; cursor:pointer; font-size:12px; }
.busy-cancel:hover { background:rgba(255,255,255,.11); }
.busy-leave-leave-active { transition:opacity .25s ease; } .busy-leave-leave-to { opacity:0; }

@keyframes panelIn { from { opacity:0; transform:translateY(12px) scale(.985); } to { opacity:1; transform:none; } }
@keyframes gridDrift { to { transform:perspective(700px) rotateX(58deg) translateY(12%) translateX(48px); } }
@keyframes orbA { to { transform:translate(120px,60px) scale(1.1); } }
@keyframes orbB { to { transform:translate(-100px,-70px) scale(.9); } }
@keyframes pulse { 0% { transform:scale(.7); opacity:.9; } 70%,100% { transform:scale(4); opacity:0; } }
@keyframes dotBlink { 50% { opacity:.35; } }
@keyframes queueSweep { from { transform:translateX(-120%); } to { transform:translateX(320%); } }

@media (prefers-reduced-motion:reduce) {
  .busy-grid,.busy-orb,.pulse,.status-dot,.busy-progress span { animation:none !important; }
}
@media (max-width:560px) {
  .busy-panel { padding:28px 22px 24px; border-radius:24px; }
}
</style>
